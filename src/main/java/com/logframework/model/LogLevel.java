package com.logframework.model;

/**
 * LogLevel enum defines all supported log levels with their priority.
 *
 * SOLID - Open/Closed Principle: Adding a new level (e.g., WARN, TRACE) requires
 * only adding a new enum constant here — no existing code changes needed.
 *
 * Java 17: switch expressions on this enum use exhaustive pattern matching.
 */
public enum LogLevel {
    DEBUG(1, "DEBUG"),
    INFO(2, "INFO"),
    WARN(3, "WARN"),
    ERROR(4, "ERROR"),
    FATAL(5, "FATAL");

    private final int priority;
    private final String label;

    LogLevel(int priority, String label) {
        this.priority = priority;
        this.label = label;
    }

    public int getPriority() {
        return priority;
    }

    public String getLabel() {
        return label;
    }

    /**
     * Returns true if this level is at least as severe as the given minimum level.
     */
    public boolean isAtLeast(LogLevel minimumLevel) {
        return this.priority >= minimumLevel.priority;
    }

    @Override
    public String toString() {
        return label;
    }
}
