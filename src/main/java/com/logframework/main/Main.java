package com.logframework.main;

import com.logframework.config.LoggerConfig;
import com.logframework.logger.Logger;
import com.logframework.logger.LoggerBuilder;
import com.logframework.model.LogLevel;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * Main demonstrates the complete LogFramework in action.
 *
 * Execution flow:
 *   1. Build Logger singleton via LoggerBuilder (fluent DSL)
 *   2. Log at each level via convenience methods
 *   3. Log an exception with stack-trace context
 *   4. Stress-test async logging from 10 concurrent threads
 *   5. Graceful shutdown (drains queue, flushes files)
 */
public class Main {

    public static void main(String[] args) throws InterruptedException {

        // ── 1. Build the Logger ───────────────────────────────────────────────
        //
        // LoggerBuilder wires:
        //   • ConsoleOutput  (coloured ANSI)
        //   • FileOutput     (daily-rotating file in ./logs/)
        //   • CloudOutput    (mock HTTP, enabled programmatically here)
        //
        Logger logger = LoggerBuilder.newBuilder()
                .withConfig(LoggerConfig.load())   // reads logger.properties
                .withConsole(true)                 // ANSI colour on
                .withFile("logs/application.log")  // daily-rotating file
                .withCloud("https://mock-log-service.example.com/ingest") // mock cloud
                .build();

        // Register shutdown hook — ensures queue is drained on Ctrl+C / JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(logger::shutdown, "shutdown-hook"));

        // ── 2. Basic log calls ────────────────────────────────────────────────
        System.out.println("\n══════════ Basic Log Levels ══════════");
        logger.debug("Application starting up — loading modules");
        logger.info("Server listening on port 8080");
        logger.warn("Connection pool running low: 3 / 10 available");
        logger.error("Failed to reach payment gateway — retrying");
        logger.fatal("Disk quota exceeded — no write space remaining");

        // ── 3. Log with a Throwable ───────────────────────────────────────────
        System.out.println("\n══════════ Exception Logging ══════════");
        try {
            riskyOperation();
        } catch (Exception e) {
            logger.error("Unhandled exception in riskyOperation()", e);
        }

        // ── 4. Structured log via direct log() method ─────────────────────────
        System.out.println("\n══════════ Direct log() calls ══════════");
        logger.log(LogLevel.INFO, "User 'alice@example.com' authenticated successfully");
        logger.log(LogLevel.WARN, "Rate limit threshold reached for IP 192.168.1.42");

        // ── 5. Concurrent async logging from multiple threads ─────────────────
        System.out.println("\n══════════ Concurrent Async Stress Test ══════════");
        ExecutorService threads = Executors.newFixedThreadPool(10);

        IntStream.rangeClosed(1, 10).forEach(threadId ->
            threads.submit(() ->
                IntStream.rangeClosed(1, 5).forEach(msgId -> {
                    // Java 17: switch expression to assign level based on modulo
                    LogLevel level = switch (msgId % 3) {
                        case 0 -> LogLevel.ERROR;
                        case 1 -> LogLevel.INFO;
                        default -> LogLevel.DEBUG;
                    };
                    logger.log(level,
                            "Thread-%d | Message #%d | payload=sample-data".formatted(threadId, msgId));
                })
            )
        );

        threads.shutdown();
        threads.awaitTermination(10, TimeUnit.SECONDS);

        // Give async workers a moment to drain the queue before we print metrics
        Thread.sleep(500);

        // ── 6. Metrics report ─────────────────────────────────────────────────
        System.out.println("\n══════════ Logger Metrics ══════════");
        System.out.printf("  Total logged  : %d%n", logger.totalLogged());
        System.out.printf("  Total dropped : %d%n", logger.totalDropped());

        // ── 7. Shutdown (also called by the shutdown hook on JVM exit) ────────
        logger.shutdown();
    }

    // ── Demo helper ───────────────────────────────────────────────────────────

    /**
     * Simulates a method that throws a checked exception.
     * Java 17: instanceof pattern matching used in the catch clause upstream.
     */
    private static void riskyOperation() {
        throw new IllegalStateException("Simulated DB connection timeout after 30s");
    }
}
