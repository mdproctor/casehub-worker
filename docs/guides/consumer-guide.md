# casehub-worker -- Consumer Guide

> Foundation-tier automated task primitives -- Worker, WorkerFunction, Capability, execution policy.

**Repo:** [casehubio/casehub-worker](https://github.com/casehubio/casehub-worker)
**Tier:** Foundation
**Package:** `io.casehub.worker.api` (API), `io.casehub.worker.runtime` (runtime), `io.casehub.worker.testing` (test fixtures)

---

## Overview

`casehub-worker` is the peer of `casehub-work` -- while `casehub-work` owns human task lifecycle (`WorkItem`), `casehub-worker` owns the identity, function, and capability vocabulary for automated workers.

Extracted from `casehub-engine-api` so that Workers are a shareable foundation primitive -- `casehub-desiredstate` and other downstream consumers can depend on `casehub-worker-api` without pulling in the full engine.

---

## Modules to Depend On

| Module | artifactId | What it is |
|--------|-----------|------------|
| `api/` | `casehub-worker-api` | Pure-Java value types + `WorkerFunction<T,R>` interface. Depends on `casehub-platform-api` for `ExecutionPolicy`. No Quarkus, no JPA. |
| `runtime/` | `casehub-worker` | `DefaultWorkerExecutor` -- capability-aware execution with typed input/output support, JSON Schema validation, SmallRye Fault Tolerance Guard (retry + timeout), and OTel instrumentation. Returns `Uni<WorkerResult>`. |
| `testing/` | `casehub-worker-testing` | `MockWorkerExecutor` + `TestWorkerBuilder` -- `@QuarkusTest` isolation. |

---

## Key Types (api module)

### Worker

```java
public record Worker(String name, Set<String> capabilities, WorkerFunction<?, ?> function,
                     ExecutionPolicy executionPolicy, String description)
```

Named automated task. The `function` field holds the executable logic; `capabilities` declares which Capabilities this worker can serve. If `executionPolicy` is null at construction, a default `ExecutionPolicy` is assigned (3 retries).

**Builder API:**

| Method | Purpose |
|--------|---------|
| `name(String)` | Worker name (required) |
| `capabilityName(String)` | Single capability |
| `capabilityNames(String...)` | Multiple capabilities (varargs) |
| `capabilityNames(Collection<String>)` | Multiple capabilities (collection) |
| `function(WorkerFunction<?,?>)` | Direct function assignment |
| `function(Function<Map,WorkerResult<Map>>)` | Convenience -- wraps as `Sync<Map,Map>` |
| `<T>fn()` | Typed builder entry point -- returns `TypedFunctionBuilder<T>` |
| `noFunction()` | Sets `WorkerFunction.NONE` -- for external/proxy workers |
| `executionPolicy(ExecutionPolicy)` | Override default retry/timeout policy |
| `description(String)` | Human-readable description |

### WorkerFunction<T, R>

```java
public interface WorkerFunction<T, R> {
    Class<T> inputType();
    Class<R> outputType();
}
```

Marker interface parameterised by input type `T` and output type `R`. Four implementations:

| Variant | Record | Signature | Purpose |
|---------|--------|-----------|---------|
| **Sync** | `Sync<T, R>(Class<T>, Class<R>, BiFunction<T, WorkerScope, WorkerResult<R>>)` | Synchronous execution receiving input + scope | Standard worker functions |
| **Persistent** | `Persistent<T>(Class<T>, Consumer<PersistentScope<T>>)` | Long-running event loop -- blocks on `nextEvent()` | Streaming/stateful workers. Output type is `Void`. |
| **None** | `None()` | No-op, `inputType()` and `outputType()` return `Void.class` | External/proxy workers with no local function |
| **ExchangeProcessor** | `ExchangeProcessor<T, R>(Class<T>, Class<R>, BiFunction<Exchange<T>, WorkerScope, WorkerResult<Exchange<R>>>)` | Exchange-aware execution with headers/properties | Worker-to-worker pipelines via `andThen()` composition |

Static constant: `WorkerFunction.NONE` -- singleton `None` instance.

### Capability

```java
public record Capability(String name, String inputProjection, String outputProjection, String description)
```

Named capability tag with JSON Schema for input/output validation. Fields `name`, `inputProjection`, and `outputProjection` are required (non-null). `description` is optional.

**Factory:** `Capability.of(name, inputSchema, outputSchema)` -- convenience without description.
**Builder:** `Capability.builder().name(...).inputSchema(...).outputSchema(...).description(...).build()`.

Empty schema `"{}"` means skip validation for that direction.

### WorkerResult<R>

```java
public record WorkerResult<R>(R output, WorkerOutcome<R> outcome)
```

Parameterised by output type `R`. Carries the output value and an outcome discriminator.

**Factory methods:**

| Method | Outcome | Output |
|--------|---------|--------|
| `of(R output)` | `Success(null)` | the output |
| `of(R output, PlannedAction)` | `Success(action)` | the output |
| `declined(String reason)` | `Declined(reason)` | null |
| `declined(String reason, R partial)` | `Declined(reason)` | partial output |
| `failed(String reason)` | `Failed(reason)` | null |
| `failed(String reason, R partial)` | `Failed(reason)` | partial output |
| `expired(String reason)` | `Expired(reason)` | null |
| `expired(String reason, R partial)` | `Expired(reason)` | partial output |
| `completed(R output)` | `Completed()` | the output |

### WorkerOutcome<R>

```java
public sealed interface WorkerOutcome<R>
```

Five-variant sealed hierarchy:

| Variant | Fields | Meaning |
|---------|--------|---------|
| `Success<R>(PlannedAction)` | optional `plannedAction` | Function completed normally. `plannedAction` may be null. |
| `Declined<R>(String reason)` | reason | Worker chose not to handle this input |
| `Failed<R>(String reason)` | reason | Unrecoverable error |
| `Expired<R>(String reason)` | reason | Execution exceeded timeout |
| `Completed<R>()` | none | Persistent worker finished its event loop cleanly |

Static factories: `WorkerOutcome.success()`, `WorkerOutcome.success(PlannedAction)`, `WorkerOutcome.completed()`.

Exhaustive `switch` is supported (sealed interface):

```java
switch (outcome) {
    case WorkerOutcome.Success<?> s   -> ...
    case WorkerOutcome.Declined<?> d  -> ...
    case WorkerOutcome.Failed<?> f    -> ...
    case WorkerOutcome.Expired<?> e   -> ...
    case WorkerOutcome.Completed<?> c -> ...
}
```

### PlannedAction

```java
public record PlannedAction(String description, String actionType, Map<String, Object> parameters)
```

Structured follow-on action attached to `Success` outcomes. `description` and `actionType` are required. `parameters` defaults to `Map.of()` if null.

**Factories:** `PlannedAction.of(description, actionType)`, `PlannedAction.of(description, actionType, parameters)`.

### WorkerScope

```java
public interface WorkerScope {
    UUID caseId();
    String taskId();
    <T, R> WorkerResult<R> execute(WorkerFunction<T, R> function, T input);
    WorkerResult<?> execute(String workerName, Map<String, Object> input);
    default Map<String, Object> accumulatedState();
}
```

Minimal execution scope passed as an explicit parameter to `Sync` functions. Provides case/task identity and the ability to invoke nested workers. `accumulatedState()` returns state accumulated across invocations (defaults to empty map).

`WorkerRuntime` (in `casehub-engine-api`) extends this interface with engine-specific methods (`context()`, `spawnCase()`). The `WorkerScope` type lives in worker-api to avoid circular dependencies with engine-api.

**Channel access:** `WorkerScope` provides `DataChannel` access for inter-worker communication:
- `channel(String name)` — returns a `DataChannel<T>` by name (requires engine context)
- `channel(ChannelRef<T> ref)` — type-safe variant using `ChannelRef`
- `createChannel(String name, Class<T> recordType)` — creates a new channel and returns its `ChannelRef`

### Exchange<T>

Immutable message envelope for worker-to-worker data flow. Carries a typed `body`, immutable `headers` (metadata), and `properties` (pipeline state). Factory: `Exchange.of(body)`, `Exchange.of(body, headers)`. Transform: `withBody(U)`, `withHeader(key, value)`, `withProperty(key, value)`, `withoutHeader(key)`. Typed accessors: `header(key)`, `header(key, default)`, `property(key)`.

### DataChannel<T>

Bidirectional typed channel for inter-worker communication. `send(Exchange<T>)` publishes, `receive()` blocks for the next exchange, `isClosed()` checks status, `close()` terminates. Implements `AutoCloseable`.

### ChannelRef<T>

Type-safe reference to a named channel. `ChannelRef.of(name, type)` creates a reference. Carries `name()` and `recordType()`. Implements `Serializable`.

### PersistentScope<T>

```java
public interface PersistentScope<T> extends WorkerScope {
    T nextEvent() throws ScopeTerminatedException;
    void emit(Map<String, Object> output);
}
```

Scope for `WorkerFunction.Persistent<T>` workers. `nextEvent()` blocks until the next input event arrives; throws `ScopeTerminatedException` when the engine terminates the scope. `emit()` sends output events without ending the worker.

### ScopeTerminatedException

```java
public class ScopeTerminatedException extends RuntimeException
```

Thrown by `PersistentScope.nextEvent()` when the engine initiates shutdown. Persistent workers should catch this to perform cleanup.

### TypedFunctionBuilder<T> and TypedOutputBuilder<T, R>

Builder helpers for type-safe function binding via the `Worker.Builder.<T>fn()` entry point.

| Step | Method | Returns |
|------|--------|---------|
| 1. Type input | `builder.<MyPojo>fn()` | `TypedFunctionBuilder<MyPojo>` |
| 2a. Type output | `.returning(ResultPojo.class)` | `TypedOutputBuilder<MyPojo, ResultPojo>` |
| 2b. Default output (Map) | `.apply(fn)` | `Worker.Builder` (output type defaults to `Map`) |
| 3. Apply with scope | `.apply((input, scope) -> ...)` | `Worker.Builder` |
| 3. Apply without scope | `.apply(input -> ...)` | `Worker.Builder` |

---

## Building Workers

### Untyped (Map input, Map output)

```java
Worker worker = Worker.builder()
    .name("greet")
    .capabilityName("greet")
    .function(input -> WorkerResult.of(Map.of("greeting", "hello " + input.get("name"))))
    .build();
```

### Typed input, Map output

```java
record GreetInput(String name) {}

Worker worker = Worker.builder()
    .name("greet")
    .capabilityName("greet")
    .<GreetInput>fn()
    .apply(input -> WorkerResult.of(Map.of("greeting", "hello " + input.name())))
    .build();
```

### Typed input and output

```java
Worker worker = Worker.builder()
    .name("length")
    .capabilityName("measure")
    .<String>fn()
    .returning(Integer.class)
    .apply(s -> WorkerResult.of(s.length()))
    .build();
```

### With WorkerScope access

```java
Worker worker = Worker.builder()
    .name("delegator")
    .capabilityName("delegate")
    .<String>fn()
    .returning(Integer.class)
    .apply((input, scope) -> {
        // Access case context, invoke nested workers
        UUID caseId = scope.caseId();
        return WorkerResult.of(input.length());
    })
    .build();
```

### External/proxy worker (no local function)

```java
Worker worker = Worker.builder()
    .name("external-service")
    .capabilityName("dispatch")
    .noFunction()
    .build();
```

### With execution policy

```java
Worker worker = Worker.builder()
    .name("resilient")
    .capabilityName("process")
    .function(input -> WorkerResult.of(Map.of()))
    .executionPolicy(new ExecutionPolicy(5000,
        new RetryPolicy(5, 500, BackoffStrategy.EXPONENTIAL)))
    .build();
```

### Persistent worker (event loop)

```java
Worker worker = Worker.builder()
    .name("listener")
    .capabilityName("monitor")
    .function(new WorkerFunction.Persistent<>(Event.class, scope -> {
        try {
            while (true) {
                Event event = scope.nextEvent();
                scope.emit(Map.of("processed", event.id()));
            }
        } catch (ScopeTerminatedException e) {
            // Clean shutdown
        }
    }))
    .build();
```

---

## Returning Results

```java
// Simple success
return WorkerResult.of(Map.of("key", "value"));

// Success with follow-on action
PlannedAction action = PlannedAction.of("File SAR", "sar.file", Map.of("accountId", "ACC-123"));
return WorkerResult.of(output, action);

// Declined (worker chose not to handle this)
return WorkerResult.declined("Input not applicable to this domain");

// Failed with partial output
return WorkerResult.failed("Validation error in step 3", Map.of("step", "validation"));

// Expired (set by executor, but can be returned directly)
return WorkerResult.expired("External API did not respond");

// Completed (persistent worker finished cleanly)
return WorkerResult.completed(Map.of("events_processed", 42));
```

---

## Dependency Rules

```
casehub-worker-api      ->  casehub-platform-api (ExecutionPolicy, RetryPolicy, BackoffStrategy)
casehub-worker          ->  casehub-worker-api, quarkus-smallrye-fault-tolerance, quarkus-arc,
                            opentelemetry-api, json-schema-validator (networknt)
casehub-worker-testing  ->  casehub-worker-api, casehub-worker (runtime), quarkus-arc
```

**Add to consumers:**

```xml
<!-- Compile dep (pure Java, no Quarkus) -->
<dependency>
  <groupId>io.casehub</groupId>
  <artifactId>casehub-worker-api</artifactId>
</dependency>

<!-- Test scope -->
<dependency>
  <groupId>io.casehub</groupId>
  <artifactId>casehub-worker-testing</artifactId>
  <scope>test</scope>
</dependency>
```

Version managed by `casehub-parent` BOM.

---

## Testing Support

### MockWorkerExecutor

`@DefaultBean @ApplicationScoped` -- displaced by `DefaultWorkerExecutor` when the runtime module is on the classpath. Returns `Uni<WorkerResult>`.

**What it validates:** capability membership (worker must declare the capability), input type check (`Sync` only -- rejects mismatched types).

**What it skips:** JSON Schema validation, execution policy enforcement (no retry, no timeout).

**Introspection methods:** `executionCount()`, `lastWorkerName()`, `lastCapabilityName()`, `reset()`.

### TestWorkerBuilder

Static factory methods for quickly constructing workers in tests:

| Method | Produces |
|--------|----------|
| `sync(name, fn)` | `Worker` with `Map` input/output, single capability matching name |
| `syncWithCapability(name, fn)` | `WorkerWithCapability` record (worker + matching `Capability` with empty schemas) |
| `syncWithCapability(name, inputSchema, outputSchema, fn)` | `WorkerWithCapability` with custom schemas |

`WorkerWithCapability` record: `record WorkerWithCapability(Worker worker, Capability capability)`.

---

## Structural Notes

- `api/` depends only on `casehub-platform-api` (for `ExecutionPolicy`) -- no Quarkus, no JPA, safe in any Java module
- `WorkerFunction<T,R>` implementations live in consuming repos (`AgentWorkerFunction`, `FlowWorkerFunction` in `casehub-engine-api`)
- Governance types (`ExecutionPolicy`, `RetryPolicy`, `BackoffStrategy`) live in `casehub-platform-api`, not here
- `WorkerScope` is defined here to avoid circular deps with engine-api; `WorkerRuntime` in engine-api extends it
- `WorkerExecutor.execute()` returns `Uni<WorkerResult>` -- all execution is reactive (Mutiny Uni pipeline)
