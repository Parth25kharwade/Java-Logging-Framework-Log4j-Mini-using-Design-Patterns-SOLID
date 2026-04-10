package com.logframework.output;

import com.logframework.model.LogLevel;
import com.logframework.model.LogRecord;

/**
 * ConsoleOutput writes log records to the console.
 *
 * Bridge Pattern — Concrete Implementation.
 * INFO/DEBUG/WARN  → System.out (stdout)
 * ERROR/FATAL      → System.err (stderr)
 *
 * SOLID - SRP: This class does exactly one thing — console output.
 * SOLID - LSP: Can substitute any LogOutput reference without breakage.
 *
 * Thread Safety: PrintStream (System.out/err) is itself synchronized;
 * no additional locking needed here.
 */
public final class ConsoleOutput implements LogOutput {

    private static final String ANSI_RESET  = "\u001B[0m";
    private static final String ANSI_CYAN   = "\u001B[36m";  // DEBUG
    private static final String ANSI_GREEN  = "\u001B[32m";  // INFO
    private static final String ANSI_YELLOW = "\u001B[33m";  // WARN
    private static final String ANSI_RED    = "\u001B[31m";  // ERROR
    private static final String ANSI_PURPLE = "\u001B[35m";  // FATAL

    private final boolean useColor;

    /**
     * @param useColor true to emit ANSI colour codes for terminal output
     */
    public ConsoleOutput(boolean useColor) {
        this.useColor = useColor;
    }

    /** Convenience constructor — colour on by default. */
    public ConsoleOutput() {
        this(true);
    }

    @Override
    public void write(LogRecord record) {
        String line = useColor
                ? colorize(record.level(), record.formatted())
                : record.formatted();

        // Java 17: pattern matching instanceof (used implicitly in colorize)
        // Route severe levels to stderr for proper log aggregation
        if (record.level() == LogLevel.ERROR || record.level() == LogLevel.FATAL) {
            System.err.println(line);
            // Print stack trace to stderr if throwable is present
            record.throwable().ifPresent(t -> t.printStackTrace(System.err));
        } else {
            System.out.println(line);
        }
    }

    @Override
    public String name() {
        return "ConsoleOutput";
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String colorize(LogLevel level, String message) {
        // Java 17: switch expression with arrow syntax — exhaustive over enum
        String ansi = switch (level) {
            case DEBUG -> ANSI_CYAN;
            case INFO  -> ANSI_GREEN;
            case WARN  -> ANSI_YELLOW;
            case ERROR -> ANSI_RED;
            case FATAL -> ANSI_PURPLE;
        };
        return ansi + message + ANSI_RESET;
    }
}
