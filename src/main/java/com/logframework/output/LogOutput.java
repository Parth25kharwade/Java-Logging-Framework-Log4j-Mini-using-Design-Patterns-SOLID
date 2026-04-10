package com.logframework.output;

import com.logframework.model.LogRecord;

/**
 * LogOutput defines the IMPLEMENTATION side of the Bridge Pattern.
 *
 * ╔══════════════════════════════════════════════════════════╗
 * ║              BRIDGE PATTERN — Implementation Side        ║
 * ║                                                          ║
 * ║  Abstraction (Logger) ──────► LogOutput (interface)      ║
 * ║                                   ├── ConsoleOutput      ║
 * ║                                   ├── FileOutput         ║
 * ║                                   └── CloudOutput        ║
 * ╚══════════════════════════════════════════════════════════╝
 *
 * SOLID - Dependency Inversion Principle (DIP):
 *   High-level logger code depends on THIS interface, never on
 *   concrete classes like ConsoleOutput or FileOutput.
 *
 * SOLID - Open/Closed Principle (OCP):
 *   New outputs (e.g., DatabaseOutput, SlackOutput) can be added
 *   by implementing this interface — zero changes to existing code.
 *
 * SOLID - Interface Segregation Principle (ISP):
 *   This interface is deliberately narrow: write() + close().
 *   No bloated base class forcing unnecessary implementations.
 */
public interface LogOutput extends AutoCloseable {

    /**
     * Writes the given log record to the underlying output medium.
     *
     * @param record the fully constructed, immutable log record
     */
    void write(LogRecord record);

    /**
     * Returns a human-readable name for this output (used in diagnostics).
     */
    String name();

    /**
     * Releases any resources held by this output (files, connections, etc.).
     * Default no-op so simple outputs don't need to override.
     */
    @Override
    default void close() {
        // no-op by default
    }
}
