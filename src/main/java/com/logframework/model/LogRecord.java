package com.logframework.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * LogRecord is the core data model for a single log entry.
 *
 * Java 17 Feature: Records — immutable, compact, auto-generates
 * equals(), hashCode(), toString(), and accessors.
 *
 * SOLID - Single Responsibility Principle (SRP):
 * This record's ONLY responsibility is to hold log data.
 * Formatting, routing, and output are handled elsewhere.
 */
public record LogRecord(
        LogLevel level,
        String message,
        Instant timestamp,
        String threadName,
        Optional<Throwable> throwable
) {
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                             .withZone(ZoneId.systemDefault());

    /**
     * Compact constructor — validates invariants at construction time.
     * Ensures no null level or message slips through.
     */
    public LogRecord {
        // Java 17: compact constructor (no parameter list — uses canonical params)
        if (level == null)   throw new IllegalArgumentException("LogLevel must not be null");
        if (message == null) throw new IllegalArgumentException("Message must not be null");
        if (timestamp == null) timestamp = Instant.now();
        if (threadName == null) threadName = Thread.currentThread().getName();
        if (throwable == null) throwable = Optional.empty();
    }

    /**
     * Convenience factory — most callers don't have a Throwable.
     */
    public static LogRecord of(LogLevel level, String message) {
        return new LogRecord(level, message, Instant.now(),
                Thread.currentThread().getName(), Optional.empty());
    }

    /**
     * Factory with throwable context for ERROR/FATAL logs.
     */
    public static LogRecord of(LogLevel level, String message, Throwable t) {
        return new LogRecord(level, message, Instant.now(),
                Thread.currentThread().getName(), Optional.of(t));
    }

    /**
     * Returns the canonical formatted log string.
     * Format: [LEVEL] [TIMESTAMP] [THREAD] Message
     */
    public String formatted() {
        // Java 17: Text blocks / String.formatted() for clean formatting
        String base = "[%s] [%s] [%s] %s".formatted(
                level.getLabel(),
                FORMATTER.format(timestamp),
                threadName,
                message
        );

        // Java 17: Enhanced switch expression on Optional presence
        return throwable.map(t -> base + "\n  Caused by: " + t.getClass().getName()
                        + ": " + t.getMessage())
                .orElse(base);
    }
}
