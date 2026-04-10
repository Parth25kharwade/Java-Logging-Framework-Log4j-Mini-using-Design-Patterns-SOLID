package com.logframework.logger;

import com.logframework.config.LoggerConfig;
import com.logframework.output.CloudOutput;
import com.logframework.output.ConsoleOutput;
import com.logframework.output.FileOutput;
import com.logframework.output.LogOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * LoggerBuilder provides a fluent DSL for assembling a Logger instance.
 *
 * Builder Pattern (Creational) — secondary pattern to the Singleton.
 *
 * Usage:
 * <pre>{@code
 *   Logger logger = LoggerBuilder.newBuilder()
 *       .withConfig(LoggerConfig.load())
 *       .withConsole()
 *       .withFile("logs/app.log")
 *       .withCloud("https://my-endpoint.example.com")
 *       .build();
 * }</pre>
 *
 * SOLID - SRP: Only responsibility is building the configured Logger.
 * SOLID - OCP: Adding a new output type (e.g., withSlack()) doesn't
 *              require changing Logger or any existing class.
 */
public final class LoggerBuilder {

    private LoggerConfig     config;
    private final List<LogOutput> outputs = new ArrayList<>();

    private LoggerBuilder() {}

    public static LoggerBuilder newBuilder() {
        return new LoggerBuilder();
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    public LoggerBuilder withConfig(LoggerConfig cfg) {
        this.config = Objects.requireNonNull(cfg, "LoggerConfig must not be null");
        return this;
    }

    // ── Output registration ───────────────────────────────────────────────────

    /** Add a coloured console output. */
    public LoggerBuilder withConsole() {
        outputs.add(new ConsoleOutput(true));
        return this;
    }

    /** Add a console output with configurable colour. */
    public LoggerBuilder withConsole(boolean useColor) {
        outputs.add(new ConsoleOutput(useColor));
        return this;
    }

    /** Add a daily-rolling file output. */
    public LoggerBuilder withFile(String path) {
        outputs.add(new FileOutput(Objects.requireNonNull(path)));
        return this;
    }

    /** Add a cloud/HTTP output. */
    public LoggerBuilder withCloud(String endpoint) {
        outputs.add(new CloudOutput(Objects.requireNonNull(endpoint)));
        return this;
    }

    /** Add any custom LogOutput implementation — extensibility hook. */
    public LoggerBuilder withOutput(LogOutput custom) {
        outputs.add(Objects.requireNonNull(custom));
        return this;
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    /**
     * Validates the configuration, wires everything together, and returns
     * the configured singleton Logger instance.
     *
     * @throws IllegalStateException if no config or outputs were registered
     */
    public Logger build() {
        if (config == null) {
            config = LoggerConfig.load(); // fall back to logger.properties / defaults
        }
        if (outputs.isEmpty()) {
            throw new IllegalStateException(
                    "At least one LogOutput must be registered before calling build().");
        }

        return Logger.INSTANCE.configure(config, outputs);
    }
}
