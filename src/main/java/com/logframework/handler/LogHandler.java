package com.logframework.handler;

import com.logframework.model.LogLevel;
import com.logframework.model.LogRecord;
import com.logframework.output.LogOutput;

import java.util.List;
import java.util.Optional;

/**
 * LogHandler defines the Chain of Responsibility for log-level routing.
 *
 * ╔═══════════════════════════════════════════════════════════════════╗
 * ║           CHAIN OF RESPONSIBILITY — Handler Hierarchy             ║
 * ║                                                                   ║
 * ║  LogRecord ──► DebugHandler ──► InfoHandler ──► WarnHandler       ║
 * ║                                                     │             ║
 * ║                                               ErrorHandler        ║
 * ║                                                     │             ║
 * ║                                               FatalHandler        ║
 * ║                                                                   ║
 * ║  Each handler: accepts its level, then forwards to next.          ║
 * ╚═══════════════════════════════════════════════════════════════════╝
 *
 * Java 17: sealed class — the compiler knows ALL permitted subtypes,
 * enabling exhaustive switch expressions downstream.
 *
 * SOLID - OCP: New levels → add a new permitted subclass here.
 * SOLID - SRP: Each subclass handles exactly one level.
 * SOLID - LSP: All subtypes honour the handle(LogRecord) contract.
 */
public sealed abstract class LogHandler
        permits LogHandler.DebugHandler,
                LogHandler.InfoHandler,
                LogHandler.WarnHandler,
                LogHandler.ErrorHandler,
                LogHandler.FatalHandler {

    /** The log level this handler is responsible for. */
    protected final LogLevel ownLevel;

    /** Outputs that receive every record this handler accepts. */
    protected final List<LogOutput> outputs;

    /** Next handler in the chain (absent = end of chain). */
    private Optional<LogHandler> next = Optional.empty();

    protected LogHandler(LogLevel ownLevel, List<LogOutput> outputs) {
        this.ownLevel = ownLevel;
        this.outputs  = List.copyOf(outputs); // defensive copy
    }

    // ── Chain wiring ──────────────────────────────────────────────────────────

    /**
     * Links the next handler and returns IT — enables fluent chaining:
     *   debug.setNext(info).setNext(warn).setNext(error)
     */
    public LogHandler setNext(LogHandler nextHandler) {
        this.next = Optional.of(nextHandler);
        return nextHandler;
    }

    // ── Core algorithm ────────────────────────────────────────────────────────

    /**
     * Handles the record if it matches this handler's level,
     * then always forwards to the next handler in the chain.
     *
     * Design choice: we use a "pass-through" chain (every handler in the
     * chain sees every message), not a "stop-on-match" chain. This allows
     * a single log event to be handled at multiple levels simultaneously —
     * e.g., write DEBUG+ to file but only ERROR+ to console.
     */
    public final void handle(LogRecord record) {
        if (record.level() == ownLevel) {
            dispatch(record);
        }
        // Always forward — even non-matching records travel the full chain
        next.ifPresent(n -> n.handle(record));
    }

    /**
     * Sends the record to all registered outputs.
     * Template Method Pattern: subclasses may override for level-specific logic.
     */
    protected void dispatch(LogRecord record) {
        // Java 17: Streams + method reference for clean iteration
        outputs.forEach(out -> out.write(record));
    }

    // ── Permitted subclasses ──────────────────────────────────────────────────

    /** Handles DEBUG-level records. */
    public static final class DebugHandler extends LogHandler {
        public DebugHandler(List<LogOutput> outputs) {
            super(LogLevel.DEBUG, outputs);
        }
    }

    /** Handles INFO-level records. */
    public static final class InfoHandler extends LogHandler {
        public InfoHandler(List<LogOutput> outputs) {
            super(LogLevel.INFO, outputs);
        }
    }

    /** Handles WARN-level records. */
    public static final class WarnHandler extends LogHandler {
        public WarnHandler(List<LogOutput> outputs) {
            super(LogLevel.WARN, outputs);
        }
    }

    /** Handles ERROR-level records. */
    public static final class ErrorHandler extends LogHandler {
        public ErrorHandler(List<LogOutput> outputs) {
            super(LogLevel.ERROR, outputs);
        }
    }

    /**
     * Handles FATAL-level records.
     * Override dispatch() to demonstrate level-specific behaviour:
     * FATAL records are printed with a prominent separator.
     */
    public static final class FatalHandler extends LogHandler {
        public FatalHandler(List<LogOutput> outputs) {
            super(LogLevel.FATAL, outputs);
        }

        @Override
        protected void dispatch(LogRecord record) {
            String separator = "=".repeat(70);
            // Java 17: pattern matching instanceof — check if output is console-like
            outputs.forEach(out -> {
                // Write separator first to visually isolate fatal events
                out.write(com.logframework.model.LogRecord.of(LogLevel.FATAL, separator));
                out.write(record);
                out.write(com.logframework.model.LogRecord.of(LogLevel.FATAL, separator));
            });
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Builds the default handler chain: DEBUG → INFO → WARN → ERROR → FATAL.
     * All handlers share the same output list.
     *
     * SOLID - DIP: Returns the head of the chain as an abstract LogHandler,
     *              so callers never depend on concrete handler types.
     */
    public static LogHandler buildDefaultChain(List<LogOutput> outputs) {
        LogHandler debug = new DebugHandler(outputs);
        LogHandler info  = new InfoHandler(outputs);
        LogHandler warn  = new WarnHandler(outputs);
        LogHandler error = new ErrorHandler(outputs);
        LogHandler fatal = new FatalHandler(outputs);

        // Fluent wiring
        debug.setNext(info).setNext(warn).setNext(error).setNext(fatal);

        return debug; // return head of chain
    }

    /**
     * Builds a chain where each level can route to DIFFERENT outputs.
     * Example: DEBUG+INFO → file, WARN+ERROR+FATAL → console + cloud.
     */
    public static LogHandler buildCustomChain(
            List<LogOutput> debugOutputs,
            List<LogOutput> infoOutputs,
            List<LogOutput> warnOutputs,
            List<LogOutput> errorOutputs,
            List<LogOutput> fatalOutputs) {

        LogHandler debug = new DebugHandler(debugOutputs);
        LogHandler info  = new InfoHandler(infoOutputs);
        LogHandler warn  = new WarnHandler(warnOutputs);
        LogHandler error = new ErrorHandler(errorOutputs);
        LogHandler fatal = new FatalHandler(fatalOutputs);

        debug.setNext(info).setNext(warn).setNext(error).setNext(fatal);
        return debug;
    }
}
