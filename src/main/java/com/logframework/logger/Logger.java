package com.logframework.logger;

import com.logframework.config.LoggerConfig;
import com.logframework.handler.LogHandler;
import com.logframework.model.LogLevel;
import com.logframework.model.LogRecord;
import com.logframework.output.LogOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logger is the primary public API of the logging framework.
 *
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║  SINGLETON PATTERN (enum-based — Josh Bloch, Effective Java)    ║
 * ║                                                                  ║
 * ║  Why enum?                                                       ║
 * ║  • JVM guarantees exactly ONE instance across classloaders       ║
 * ║  • Serialisation-safe (no readResolve() needed)                  ║
 * ║  • Immune to reflection attacks                                  ║
 * ║  • No double-checked locking boilerplate                         ║
 * ╚══════════════════════════════════════════════════════════════════╝
 *
 * BRIDGE PATTERN:
 *   Logger (abstraction) delegates actual I/O to LogOutput impls
 *   via the LogHandler chain (Behavioural layer).
 *
 *   Logger ──► LogHandler chain ──► LogOutput (Console / File / Cloud)
 *
 * SOLID - SRP: Logger orchestrates; it does NOT format or write.
 * SOLID - OCP: New outputs/handlers plug in via configure() — no Logger change.
 * SOLID - DIP: Logger depends on LogOutput interface, not concrete classes.
 *
 * Advanced features:
 *   • Asynchronous logging via a bounded BlockingQueue + dedicated worker thread
 *   • Configurable minimum log level (records below level are dropped early)
 *   • Graceful shutdown with drain of remaining records
 *   • Per-level convenience methods: debug(), info(), warn(), error(), fatal()
 */
public enum Logger {
    INSTANCE;  // ← Singleton — entire enum body is the one Logger object

    // ── State ─────────────────────────────────────────────────────────────────

    private volatile LoggerConfig    config;
    private volatile LogHandler      handlerChainHead;
    private volatile List<LogOutput> outputs = new ArrayList<>();

    // Async infrastructure
    private volatile ExecutorService asyncExecutor;
    private final BlockingQueue<LogRecord> asyncQueue =
            new LinkedBlockingQueue<>(10_000);   // bounded: prevents OOM under load

    // Metrics
    private final AtomicLong totalLogged  = new AtomicLong(0);
    private final AtomicLong totalDropped = new AtomicLong(0);

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Configures the logger with the given config and output list.
     * Must be called once at application startup before any log() calls.
     *
     * Thread Safety: volatile writes + happens-before guarantee that any
     * thread calling log() after configure() sees the updated state.
     */
    public synchronized Logger configure(LoggerConfig cfg, List<LogOutput> logOutputs) {
        this.config  = cfg;
        this.outputs = List.copyOf(logOutputs);

        // Build handler chain (Chain of Responsibility)
        this.handlerChainHead = LogHandler.buildDefaultChain(this.outputs);

        // Start async worker
        startAsyncWorker(cfg.getAsyncThreadPoolSize());

        System.out.printf("[LogFramework] Logger configured. Level=%s, Outputs=%d, Threads=%d%n",
                cfg.getMinimumLevel(), logOutputs.size(), cfg.getAsyncThreadPoolSize());
        return this;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Core log method — the entry point for all logging.
     *
     * @param level   severity of the message
     * @param message human-readable log text
     */
    public void log(LogLevel level, String message) {
        submitAsync(LogRecord.of(level, message));
    }

    /**
     * Log with a throwable (for exceptions).
     */
    public void log(LogLevel level, String message, Throwable t) {
        submitAsync(LogRecord.of(level, message, t));
    }

    // ── Convenience methods ───────────────────────────────────────────────────

    public void debug(String message)                   { log(LogLevel.DEBUG, message); }
    public void info(String message)                    { log(LogLevel.INFO,  message); }
    public void warn(String message)                    { log(LogLevel.WARN,  message); }
    public void error(String message)                   { log(LogLevel.ERROR, message); }
    public void error(String message, Throwable t)      { log(LogLevel.ERROR, message, t); }
    public void fatal(String message)                   { log(LogLevel.FATAL, message); }
    public void fatal(String message, Throwable t)      { log(LogLevel.FATAL, message, t); }

    // ── Metrics ───────────────────────────────────────────────────────────────

    public long totalLogged()  { return totalLogged.get(); }
    public long totalDropped() { return totalDropped.get(); }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    /**
     * Gracefully shuts down async workers, drains the queue, and closes outputs.
     * Call this in a JVM shutdown hook or @PreDestroy method.
     */
    public synchronized void shutdown() {
        System.out.println("[LogFramework] Shutting down — draining queue...");
        if (asyncExecutor != null) {
            asyncExecutor.shutdown();
            try {
                // Wait up to 15 s for the worker to drain remaining records
                if (!asyncExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
                    asyncExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                asyncExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Close all outputs (flushes files, ships final cloud batch, etc.)
        outputs.forEach(out -> {
            try {
                out.close();
            } catch (Exception e) {
                System.err.println("[LogFramework] Error closing " + out.name() + ": " + e.getMessage());
            }
        });

        System.out.printf("[LogFramework] Shutdown complete. Logged=%d, Dropped=%d%n",
                totalLogged.get(), totalDropped.get());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Submits a record for asynchronous processing.
     * Non-blocking offer — drops under extreme back-pressure rather than blocking callers.
     */
    private void submitAsync(LogRecord record) {
        ensureConfigured();

        // Early level filter — avoids queue pressure for suppressed levels
        if (!record.level().isAtLeast(config.getMinimumLevel())) {
            return;
        }

        boolean enqueued = asyncQueue.offer(record);
        if (enqueued) {
            totalLogged.incrementAndGet();
        } else {
            totalDropped.incrementAndGet();
            System.err.printf("[LogFramework] Async queue full — record dropped (total: %d)%n",
                    totalDropped.get());
        }
    }

    /**
     * Starts the async worker thread(s) that drain the queue and pass
     * records through the handler chain.
     */
    private void startAsyncWorker(int threadCount) {
        asyncExecutor = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "log-async-worker");
            t.setDaemon(true);
            return t;
        });

        // Use a single-consumer pattern for handler chain (it IS thread-safe internally)
        // but spin up multiple threads for I/O throughput
        for (int i = 0; i < threadCount; i++) {
            asyncExecutor.submit(this::drainLoop);
        }
    }

    /**
     * Worker loop — runs on the async thread pool.
     * Blocks waiting for records; exits when interrupted (shutdown signal).
     */
    private void drainLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Block for up to 1s so the thread can check interruption
                LogRecord record = asyncQueue.poll(1, TimeUnit.SECONDS);
                if (record != null) {
                    dispatchToChain(record);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // restore interrupt flag → loop exits
            }
        }

        // Drain any records still in the queue after interrupt
        List<LogRecord> remaining = new ArrayList<>();
        asyncQueue.drainTo(remaining);
        remaining.forEach(this::dispatchToChain);
    }

    /**
     * Sends a record through the handler chain.
     * Wrapped in try-catch so one bad output doesn't kill the worker thread.
     */
    private void dispatchToChain(LogRecord record) {
        try {
            handlerChainHead.handle(record);
        } catch (Exception e) {
            System.err.println("[LogFramework] Dispatch error: " + e.getMessage());
        }
    }

    private void ensureConfigured() {
        if (config == null || handlerChainHead == null) {
            throw new IllegalStateException(
                    "Logger not configured. Call Logger.INSTANCE.configure(...) at startup.");
        }
    }
}
