# LogFramework — Mini Log4j

> A production-quality **Java 17** logging framework demonstrating **Singleton**, **Bridge**, and **Chain of Responsibility** design patterns with async logging, sealed classes, records, and switch expressions.

![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=openjdk)
![Maven](https://img.shields.io/badge/Maven-3.8+-C71A36?style=flat-square&logo=apachemaven)
![JUnit](https://img.shields.io/badge/JUnit-5.10-25A162?style=flat-square&logo=junit5)
![Dependencies](https://img.shields.io/badge/Runtime_Dependencies-0-brightgreen?style=flat-square)

---

## Table of Contents

- [Overview](#overview)
- [Quick Start](#quick-start)
- [Project Structure](#project-structure)
- [Architecture & Design Patterns](#architecture--design-patterns)
- [Configuration](#configuration)
- [Log Levels](#log-levels)
- [Output Implementations](#output-implementations)
- [Async Logging](#async-logging)
- [Java 17 Features](#java-17-features)
- [SOLID Principles](#solid-principles)
- [Metrics](#metrics)
- [Extending the Framework](#extending-the-framework)
- [Running Tests](#running-tests)

---

## Overview

LogFramework is a self-contained, dependency-free logging library that mirrors the architecture of production frameworks like Log4j and SLF4J. It ships records asynchronously via a bounded `BlockingQueue`, supports daily-rotating file output, and can optionally forward micro-batched records to an HTTP cloud ingestion endpoint. All configuration is externalised to a single `logger.properties` file.

**Core patterns in play:**

| Pattern | Category | Role |
|---|---|---|
| Singleton | Creational | `Logger.INSTANCE` — one JVM-guaranteed logger |
| Builder | Creational | `LoggerBuilder` — fluent DSL for output assembly |
| Bridge | Structural | `LogOutput` interface decouples I/O from routing |
| Chain of Responsibility | Behavioural | `LogHandler` sealed hierarchy routes by level |
| Template Method | Behavioural | `FatalHandler` overrides `dispatch()` for visual emphasis |

---

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.8+

### Build & Run

```bash
mvn clean package
java --enable-preview -jar target/logging-framework-1.0.0.jar
```

### Basic Usage

```java
Logger logger = LoggerBuilder.newBuilder()
        .withConfig(LoggerConfig.load())          // reads logger.properties
        .withConsole(true)                        // ANSI colour on
        .withFile("logs/application.log")         // daily-rotating file
        .withCloud("https://my-endpoint/ingest")  // optional HTTP sink
        .build();

// Register shutdown hook — drains queue on JVM exit / Ctrl+C
Runtime.getRuntime().addShutdownHook(new Thread(logger::shutdown, "shutdown-hook"));

// Convenience methods
logger.debug("Loading modules...");
logger.info("Server listening on port 8080");
logger.warn("Connection pool running low: 3 / 10 available");
logger.error("Payment gateway unreachable", exception);
logger.fatal("Disk quota exceeded");

// Direct log() with level variable
logger.log(LogLevel.INFO, "User authenticated");
```

---

## Project Structure

```
logging-framework/
├── pom.xml
└── src/
    └── main/
        ├── java/com/logframework/
        │   ├── config/
        │   │   └── LoggerConfig.java           # Loads logger.properties, exposes typed values
        │   ├── handler/
        │   │   └── LogHandler.java             # Sealed abstract + 5 inner permitted subclasses
        │   ├── logger/
        │   │   ├── Logger.java                 # Enum Singleton — public API, async queue, metrics
        │   │   └── LoggerBuilder.java          # Builder DSL — fluent output registration
        │   ├── main/
        │   │   └── Main.java                   # Runnable demo with concurrent stress test
        │   ├── model/
        │   │   ├── LogLevel.java               # Enum with priority values
        │   │   └── LogRecord.java              # Java 17 record — immutable log entry
        │   └── output/
        │       ├── LogOutput.java              # Bridge interface (AutoCloseable)
        │       ├── ConsoleOutput.java          # ANSI-coloured stdout/stderr
        │       ├── FileOutput.java             # Daily-rotating buffered file writer
        │       └── CloudOutput.java            # Micro-batched HTTP ingestion sink
        └── resources/
            └── logger.properties
```

---

## Architecture & Design Patterns

### Layer Map

```
┌─────────────────────────────────────────────────────────┐
│  Application Code                                       │
│     logger.info("msg")                                  │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│  Logger (Enum Singleton)               [CREATIONAL]     │
│  • Level filter → asyncQueue.offer()                    │
│  • Async worker threads drain the queue                 │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│  LogHandler Chain (Sealed Hierarchy)   [BEHAVIOURAL]    │
│  DebugHandler → InfoHandler → WarnHandler               │
│               → ErrorHandler → FatalHandler             │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│  LogOutput (Bridge Interface)          [STRUCTURAL]     │
│  ConsoleOutput │ FileOutput │ CloudOutput               │
└─────────────────────────────────────────────────────────┘
```

### Singleton — `Logger`

```java
// Enum-based singleton (Josh Bloch, Effective Java)
// ✅ JVM-guaranteed single instance across classloaders
// ✅ Serialisation-safe — no readResolve() needed
// ✅ Immune to reflection attacks
// ✅ No double-checked locking boilerplate
public enum Logger {
    INSTANCE;

    public void info(String message) { log(LogLevel.INFO, message); }
    // ...
}
```

### Bridge — `LogOutput`

```java
// IMPLEMENTATION side — every concrete output implements this
public interface LogOutput extends AutoCloseable {
    void write(LogRecord record);
    String name();
    default void close() { /* no-op */ }
}

// ABSTRACTION side — Logger never references Console/File/Cloud directly
Logger ──► LogHandler chain ──► LogOutput
```

### Chain of Responsibility — `LogHandler`

```java
// Java 17 sealed class — compiler knows ALL permitted subtypes
public sealed abstract class LogHandler
        permits LogHandler.DebugHandler, LogHandler.InfoHandler,
                LogHandler.WarnHandler, LogHandler.ErrorHandler,
                LogHandler.FatalHandler {

    public final void handle(LogRecord record) {
        if (record.level() == ownLevel) {
            dispatch(record);         // this handler matches → write to outputs
        }
        next.ifPresent(n -> n.handle(record)); // always forward — pass-through chain
    }
}

// Chain is wired once at startup:
// DEBUG → INFO → WARN → ERROR → FATAL
LogHandler chain = LogHandler.buildDefaultChain(outputs);
```

### Builder — `LoggerBuilder`

```java
// Fluent DSL — new outputs plug in without touching Logger
Logger logger = LoggerBuilder.newBuilder()
        .withConfig(LoggerConfig.load())
        .withConsole()
        .withFile("logs/app.log")
        .withCloud("https://endpoint/ingest")
        .withOutput(new MyCustomOutput())   // extensibility hook
        .build();
```

---

## Configuration

Place `logger.properties` in `src/main/resources/`. `LoggerConfig.load()` reads it at startup; sensible defaults apply when the file is absent.

```properties
# Minimum log level: DEBUG | INFO | WARN | ERROR | FATAL
log.level=DEBUG

# Daily-rotating log file
# Output example: logs/application-2025-04-09.log
log.file.path=logs/application.log

# Async worker threads draining the log queue
log.async.threads=2

# Cloud HTTP sink
log.cloud.enabled=false
log.cloud.endpoint=https://mock-log-service.example.com/ingest
```

| Property | Default | Description |
|---|---|---|
| `log.level` | `DEBUG` | Records below this level are silently discarded |
| `log.file.path` | `application.log` | Base path; date inserted before extension on rotation |
| `log.async.threads` | `2` | Worker threads draining the async queue |
| `log.cloud.enabled` | `false` | Set `true` to activate HTTP shipping |
| `log.cloud.endpoint` | *(mock URL)* | Target ingestion endpoint |

---

## Log Levels

| Level | Priority | Console Colour | Use When |
|---|---|---|---|
| `DEBUG` | 1 | Cyan | Verbose diagnostic data; typically off in production |
| `INFO` | 2 | Green | Normal lifecycle events: startup, state changes |
| `WARN` | 3 | Yellow | Potentially harmful but non-fatal situations |
| `ERROR` | 4 | Red | Operation failed; app can continue |
| `FATAL` | 5 | Purple | Severe failure; rendered with `=` separator lines |

```java
// isAtLeast() used for early level filtering in Logger
LogLevel.WARN.isAtLeast(LogLevel.INFO);  // true  — WARN priority (3) >= INFO priority (2)
LogLevel.DEBUG.isAtLeast(LogLevel.INFO); // false — DEBUG priority (1) < INFO priority (2)
```

---

## Output Implementations

### `ConsoleOutput`

Routes `DEBUG`/`INFO`/`WARN` to `System.out` and `ERROR`/`FATAL` to `System.err`. Thread-safe via `PrintStream`'s built-in synchronisation.

```java
.withConsole(true)   // ANSI colour on  — coloured terminal output
.withConsole(false)  // ANSI colour off — plain text (CI/log aggregators)
```

**Colour mapping** (Java 17 switch expression):

```java
String ansi = switch (level) {
    case DEBUG -> ANSI_CYAN;
    case INFO  -> ANSI_GREEN;
    case WARN  -> ANSI_YELLOW;
    case ERROR -> ANSI_RED;
    case FATAL -> ANSI_PURPLE;
};
```

---

### `FileOutput`

Appends to a daily-rotating log file. A `ReentrantLock` serialises concurrent writes from the async thread pool. Buffered I/O is flushed after every record for durability.

```java
.withFile("logs/application.log")
// Produces: logs/application-2025-04-09.log
//           logs/application-2025-04-10.log  ← auto-rotated at midnight
```

**Rotation logic:** at each `write()` call, `rotateIfNeeded()` checks `LocalDate.now()`. If the date has changed, the current writer is closed and a new dated file is opened.

---

### `CloudOutput`

Micro-batches records and ships them via HTTP POST on a background scheduler thread.

| Setting | Value |
|---|---|
| Internal queue capacity | 1 000 records |
| Batch size | 50 records |
| Flush interval | Every 5 seconds |
| Back-pressure handling | Drop + increment counter |

```java
.withCloud("https://my-log-service.example.com/ingest")
```

> **Production note:** Replace `simulateHttpPost()` with `java.net.http.HttpClient` for real HTTP delivery. The stub is intentionally left as a mock so the project runs without external dependencies.

---

### Custom Output

Implement `LogOutput` (two required methods) and register via the builder:

```java
public class SlackOutput implements LogOutput {
    private final String webhookUrl;

    public SlackOutput(String webhookUrl) { this.webhookUrl = webhookUrl; }

    @Override
    public void write(LogRecord record) {
        // POST record.formatted() to Slack webhook
    }

    @Override
    public String name() { return "SlackOutput[" + webhookUrl + "]"; }
}

// Register — zero changes to Logger or any existing class
LoggerBuilder.newBuilder()
        .withConfig(LoggerConfig.load())
        .withConsole()
        .withOutput(new SlackOutput("https://hooks.slack.com/..."))
        .build();
```

---

## Async Logging

All `log()` calls return immediately. Records travel through the pipeline on a separate thread pool.

```
Application Thread                    Async Worker Thread(s)
──────────────────                    ──────────────────────
logger.info("msg")
  │
  ├─ level check vs minimumLevel ──✗──► drop silently (below threshold)
  │  ✓
  ├─ LogRecord.of(INFO, "msg")         drainLoop() — BlockingQueue.poll()
  │                                      │
  ├─ asyncQueue.offer(record)            ├─ DebugHandler.handle() → level≠DEBUG → forward
  │  non-blocking; drops if full         ├─ InfoHandler.handle()  → level==INFO → dispatch()
  │                                      │    ├─ ConsoleOutput.write(record)
  └─ returns immediately                 │    ├─ FileOutput.write(record)
                                         │    └─ CloudOutput.write(record)
                                         ├─ WarnHandler.handle()  → level≠WARN  → forward
                                         ├─ ErrorHandler.handle() → level≠ERROR → forward
                                         └─ FatalHandler.handle() → level≠FATAL → end of chain
```

### Graceful Shutdown

```java
// Option A: explicit call
logger.shutdown();

// Option B: JVM shutdown hook (recommended)
Runtime.getRuntime().addShutdownHook(new Thread(logger::shutdown, "shutdown-hook"));
```

`shutdown()` signals the worker pool, waits up to **15 seconds** for the queue to drain, then calls `close()` on every registered output (flushes file buffers, ships final cloud batch).

---

## Java 17 Features

| Feature | Where Used |
|---|---|
| **Records** | `LogRecord.java` — immutable log entry; auto-generates accessors, `equals`, `hashCode`, `toString` |
| **Sealed classes** | `LogHandler.java` — `permits` clause makes handler hierarchy exhaustive and compiler-verified |
| **Switch expressions** | `ConsoleOutput.colorize()` and `Main.java` level routing |
| **Text blocks / `formatted()`** | `LogRecord.formatted()` and `CloudOutput.buildJsonPayload()` |
| **Pattern matching `instanceof`** | `LogRecord` compact constructor validation |
| **`Optional`** | `LogRecord.throwable`; `LogHandler.next` chain link |
| **Streams + `toList()`** | `CloudOutput.buildJsonPayload()`; `LogHandler.dispatch()` |
| **`var` (local type inference)** | `CloudOutput.buildJsonPayload()` entries variable |
| **Enum singleton** | `Logger.INSTANCE` — reflection-proof, serialisation-safe |

---

## SOLID Principles

| | Principle | Application |
|---|---|---|
| **S** | Single Responsibility | `LogRecord` holds data only. `LogOutput` writes only. `LoggerConfig` configures only. Each `LogHandler` subclass handles exactly one level. |
| **O** | Open / Closed | New output: implement `LogOutput`, zero existing changes. New level: add enum constant + permitted subclass, zero existing changes. |
| **L** | Liskov Substitution | All `LogOutput` implementations are fully interchangeable. All `LogHandler` subclasses honour the `handle(LogRecord)` contract. |
| **I** | Interface Segregation | `LogOutput` exposes only `write()` + `name()` + `close()`. No bloated base class. |
| **D** | Dependency Inversion | `Logger` and `LogHandler` depend on the `LogOutput` interface. `Main.java` depends on `Logger` and `LoggerBuilder` abstractions, never on concrete output classes. |

---

## Metrics

`Logger` exposes two atomic counters available at any time during the application lifetime:

```java
logger.totalLogged();    // records successfully enqueued for processing
logger.totalDropped();   // records lost — queue overflow or level filter
```

`CloudOutput` additionally tracks its own internal back-pressure drops:

```java
// Access via the CloudOutput reference if needed
cloudOutput.droppedCount();
```

---

## Extending the Framework

### Per-Level Output Routing

Use `LogHandler.buildCustomChain()` to route different levels to different output sets:

```java
LogOutput console = new ConsoleOutput(true);
LogOutput file    = new FileOutput("logs/app.log");
LogOutput cloud   = new CloudOutput("https://endpoint/ingest");

// DEBUG + INFO → file only
// WARN         → file + console
// ERROR + FATAL → file + console + cloud
LogHandler chain = LogHandler.buildCustomChain(
        List.of(file),                   // DEBUG
        List.of(file),                   // INFO
        List.of(file, console),          // WARN
        List.of(file, console, cloud),   // ERROR
        List.of(file, console, cloud)    // FATAL
);
```

### Replacing the Async Strategy

The async infrastructure lives entirely in `Logger`. To switch from `LinkedBlockingQueue` to a `Disruptor` or virtual threads (Java 21+), only `Logger.java` needs to change — zero impact on handlers or outputs.

---

## Running Tests

```bash
# Run all JUnit 5 tests
mvn test

# Run a specific test class
mvn test -Dtest=LogRecordTest

# Run with verbose output
mvn test -Dsurefire.useFile=false
```

> `--enable-preview` is pre-configured in the `maven-surefire-plugin` block of `pom.xml` — no extra flags needed.

---

## License

This project is provided as an educational reference implementation. See `LICENSE` for details.

---

*LogFramework v1.0.0 · Java 17 · Maven · JUnit 5 · No external runtime dependencies*
