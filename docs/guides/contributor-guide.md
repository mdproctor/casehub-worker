# casehub-worker -- Contributor Guide

> Architecture, execution model, and internals for platform builders modifying casehub-worker.

**Repo:** [casehubio/casehub-worker](https://github.com/casehubio/casehub-worker)
**Package:** `io.casehub.worker.api` (API), `io.casehub.worker.runtime` (runtime), `io.casehub.worker.testing` (test fixtures)

---

## Module Structure

| Module | artifactId | Detail |
|--------|-----------|--------|
| `api/` | `casehub-worker-api` | Pure-Java value types + `WorkerFunction<T,R>` interface. Depends on `casehub-platform-api` for `ExecutionPolicy`. No Quarkus, no JPA -- safe in any Java module. Jandex-indexed. |
| `runtime/` | `casehub-worker` | `DefaultWorkerExecutor` (`@ApplicationScoped`) -- capability-aware execution with typed input/output, JSON Schema validation (`SchemaValidator`), SmallRye Fault Tolerance `Guard` (retry + timeout), OTel instrumentation, and `Uni<WorkerResult>` reactive return. Quarkus extension module with `quarkus-maven-plugin`. |
| `testing/` | `casehub-worker-testing` | `MockWorkerExecutor` (`@DefaultBean @ApplicationScoped` -- mirrors validation guards but skips schema/policy enforcement), `TestWorkerBuilder` (convenience factories). Depends on both `casehub-worker-api` and `casehub-worker` (runtime). |

---

## API Type Inventory

All types in `io.casehub.worker.api`:

| Type | Kind | Type Parameters | Purpose |
|------|------|-----------------|---------|
| `Worker` | record | -- | Named automated task: `name`, `capabilities` (Set), `function` (WorkerFunction), `executionPolicy`, `description`. Inner `Builder` class. |
| `WorkerFunction<T,R>` | interface | T=input, R=output | Marker with `inputType()` and `outputType()`. Three implementations: `Sync`, `Persistent`, `None`. |
| `WorkerFunction.Sync<T,R>` | record | T=input, R=output | `fn` field: `BiFunction<T, WorkerScope, WorkerResult<R>>`. The standard execution variant. |
| `WorkerFunction.Persistent<T>` | record | T=input | `handler` field: `Consumer<PersistentScope<T>>`. Long-running event-loop workers. `outputType()` returns `Void.class`. Implements `WorkerFunction<T, Void>`. |
| `WorkerFunction.None` | record | -- | No-op. `inputType()` and `outputType()` return `Void.class`. Implements `WorkerFunction<Void, Void>`. Singleton: `WorkerFunction.NONE`. |
| `Capability` | record | -- | `name`, `inputProjection`, `outputProjection` (all required), `description` (optional). Factory: `Capability.of(...)`. Inner `Builder`. |
| `WorkerResult<R>` | record | R=output | `output` (R) + `outcome` (WorkerOutcome). 10 factory methods covering all outcome variants. |
| `WorkerOutcome<R>` | sealed interface | R=output | Five variants: `Success<R>(PlannedAction)`, `Declined<R>(reason)`, `Failed<R>(reason)`, `Expired<R>(reason)`, `Completed<R>()`. Static factories: `success()`, `success(PlannedAction)`, `completed()`. |
| `PlannedAction` | record | -- | `description` (required), `actionType` (required), `parameters` (Map, defaults to empty). Attached to `Success` outcomes. |
| `WorkerScope` | interface | -- | Execution context: `caseId()`, `taskId()`, `execute(WorkerFunction, input)`, `execute(workerName, input)`, `accumulatedState()`. Passed as second param to `Sync.fn`. Extended by `WorkerRuntime` in engine-api. |
| `PersistentScope<T>` | interface | T=event type | Extends `WorkerScope`. Adds `nextEvent()` (blocking, throws `ScopeTerminatedException`) and `emit(Map)`. For `Persistent` workers. |
| `ScopeTerminatedException` | class (RuntimeException) | -- | Thrown by `PersistentScope.nextEvent()` on engine-initiated shutdown. |
| `TypedFunctionBuilder<T>` | class | T=input | Builder step from `Worker.Builder.<T>fn()`. `returning(Class<R>)` chains to `TypedOutputBuilder`. `apply(fn)` defaults output to `Map`. |
| `TypedOutputBuilder<T,R>` | class | T=input, R=output | Builder step from `TypedFunctionBuilder.returning()`. `apply(BiFunction)` or `apply(Function)` completes the builder chain. |

---

## Runtime Internals

### WorkerExecutor Interface

```java
public interface WorkerExecutor {
    Uni<WorkerResult> execute(Worker worker, Capability capability, Object input);
}
```

Lives in `io.casehub.worker.runtime`. Returns `Uni<WorkerResult>` (Mutiny reactive type). The third parameter is `Object` (not `Map`) -- supports typed POJO inputs via `WorkerFunction<T,R>`.

**Note:** The raw `WorkerResult` in the return type loses type parameter `R` -- the executor operates on erased types internally. Callers that know the output type cast after awaiting.

### DefaultWorkerExecutor

`@ApplicationScoped`. The production implementation. Execution pipeline in order:

1. **Null check** -- `capability` must not be null (throws `NullPointerException`)
2. **Capability membership** -- `worker.capabilityNames().contains(capability.name())` (throws `IllegalArgumentException`)
3. **Input type check** -- `fn.inputType().isInstance(input)` (throws `IllegalArgumentException` with expected vs actual types)
4. **Schema parsing** -- `schemaValidator.ensureSchemaParsed()` on both input and output schemas (fail-fast on malformed JSON Schema -- throws `IllegalArgumentException`)
5. **OTel span** -- `worker.execute` with `worker.name` and `worker.capability` attributes
6. **Input schema validation** -- if invalid, returns `WorkerResult.failed(error)` without calling the function. OTel event: `worker.input.invalid`.
7. **Lift to Uni** -- `liftToUni()` wraps `Sync.fn` invocation in `Uni.createFrom().item()`
8. **Guard execution** -- `Guard.call(() -> action, Uni.class)` applies retry + timeout per `ExecutionPolicy`
9. **Output schema validation** (on `Success` only) -- **warn-only**: logs via JBoss Logger but returns success unchanged. OTel event: `worker.output.invalid`.
10. **Timeout recovery** -- `TimeoutException` (MicroProfile FT) maps to `WorkerResult.expired(message)`
11. **Exception recovery** -- all other exceptions map to `WorkerResult.failed(message)`. Unwraps cause chain. Null messages fall back to exception class name.
12. **Span lifecycle** -- `onTermination()` sets `worker.outcome` attribute and calls `span.end()`

Steps 1-4 are pre-flight checks that throw synchronously (programming errors). Steps 5-12 run within the reactive Uni pipeline.

### liftToUni

```java
private Uni<WorkerResult> liftToUni(Worker worker, Object input) {
    if (worker.function() instanceof WorkerFunction.Sync sync) {
        return Uni.createFrom().item(() -> sync.fn().apply(input, null));
    }
    throw new UnsupportedOperationException("Unsupported function type: ...");
}
```

Currently only `Sync` is supported in the executor. `Persistent` workers are handled by the engine runtime directly (not through `DefaultWorkerExecutor`). The `WorkerScope` parameter is passed as `null` -- scope injection is the responsibility of the engine runtime that wraps the executor.

### Guard Caching (SmallRye Fault Tolerance)

`ConcurrentHashMap<ExecutionPolicy, Guard>` caches built `Guard` instances by policy value. Since `ExecutionPolicy` is a record, structural equality means identical policies share a single Guard.

`buildGuard(ExecutionPolicy)` constructs:
- **Timeout:** `Guard.withTimeout().duration(timeoutMs, MILLIS)` -- if `policy.timeoutMs()` is non-null
- **Retry:** `Guard.withRetry().maxRetries(maxAttempts - 1).delay(delayMs, MILLIS)` -- if `retry.maxAttempts() > 1`
  - `EXPONENTIAL` backoff: `.withExponentialBackoff().maxDelay(maxDelayMs, MILLIS)`
  - `EXPONENTIAL_WITH_JITTER`: same exponential backoff plus `.jitter(delayMs, MILLIS)`
  - Other strategies: no additional backoff configuration

### SchemaValidator

`@ApplicationScoped` -- uses `com.networknt:json-schema-validator` with JSON Schema 2020-12 (`SpecVersion.VersionFlag.V202012`). Caches parsed `JsonSchema` instances in `ConcurrentHashMap<String, JsonSchema>`. Empty schema `"{}"` is treated as skip-validation. Converts input/output to `JsonNode` via Jackson `ObjectMapper.valueToTree()`.

### Input vs Output Validation Asymmetry

Input schema validation is **blocking** -- the function is never called if input is invalid. Output schema validation is **warn-only** -- logs but returns success. This is intentional: prevent bad data from entering while not breaking workers with evolving output schemas.

---

## Testing Internals

### MockWorkerExecutor

`@DefaultBean @ApplicationScoped` -- displaced by `DefaultWorkerExecutor` when the runtime module is on the classpath. Returns `Uni<WorkerResult>`.

**What it mirrors:** capability membership check, `Sync`-only guard, input type check (via `Sync.inputType().isInstance()`).

**What it skips:** JSON Schema validation, Guard/policy enforcement (no retry, no timeout), OTel instrumentation.

**Exception handling:** wraps `fn.apply()` in try-catch; exceptions become `WorkerResult.failed(message)`.

**Introspection:** `executionCount()`, `lastWorkerName()`, `lastCapabilityName()`, `reset()`.

### TestWorkerBuilder

Static factory methods in `io.casehub.worker.testing`:

| Method | Signature | Result |
|--------|-----------|--------|
| `sync` | `(String name, Function<Map,WorkerResult<Map>> fn)` | `Worker` with Map input/output |
| `syncWithCapability` | `(String name, Function<Map,WorkerResult<Map>> fn)` | `WorkerWithCapability` with empty schemas |
| `syncWithCapability` | `(String name, String inputSchema, String outputSchema, Function<Map,WorkerResult<Map>> fn)` | `WorkerWithCapability` with custom schemas |

Inner record: `WorkerWithCapability(Worker worker, Capability capability)`.

Async builder methods were removed -- virtual threads supersede CompletionStage workers.

---

## Dependency Graph

```
casehub-worker-api
  -> casehub-platform-api (ExecutionPolicy, RetryPolicy, BackoffStrategy -- pure Java)

casehub-worker (runtime)
  -> casehub-worker-api
  -> quarkus-smallrye-fault-tolerance (Guard -- retry, timeout, backoff)
  -> quarkus-arc (CDI)
  -> opentelemetry-api (tracing spans)
  -> com.networknt:json-schema-validator:1.0.83 (JSON Schema 2020-12)

casehub-worker-testing
  -> casehub-worker-api
  -> casehub-worker (runtime -- for WorkerExecutor interface)
  -> quarkus-arc (CDI -- @DefaultBean)
```

Test-only dependencies in runtime module: `quarkus-junit`, `assertj-core`, `smallrye-fault-tolerance-standalone`.

---

## Consumed By

| Repo | What it uses |
|------|-------------|
| `casehub-engine` | `Worker`, `Capability`, `WorkerFunction`, `WorkerScope`, `WorkerResult` -- full execution path |
| `casehub-desiredstate` | `Worker`, `Capability` -- node provisioning in desiredstate graph |

---

## Open Issues

| # | Title | Status |
|---|-------|--------|
| 3 | epic: worker execution model -- async, timeout, context, validation, error handling | Open (tracking epic) |
| 4 | feat: WorkerContext -- ambient execution state for worker functions | Open |

---

## Evolution History

Key commits on main (chronological):

1. **Initial extraction** -- Worker, WorkerFunction, Capability, WorkerResult, WorkerOutcome extracted from engine-api
2. **PlannedAction + enriched WorkerResult** -- structured follow-on actions, partial-output overloads
3. **WorkerFunction marker interface** -- `WorkerFunction` became a marker; `Sync` became a record implementing it
4. **Capability-aware execution** -- `Worker.capabilityNames` (Set<String>), executor validates membership
5. **Schema validation** -- `SchemaValidator` with JSON Schema 2020-12, input blocking / output warn-only
6. **Exception-to-outcome conversion** -- uncaught exceptions map to `Failed`, timeout to `Expired`
7. **Typed input** -- `WorkerFunction<T>` parameterisation, `TypedFunctionBuilder`, executor widened to `Object`
8. **Async WorkerFunction** -- `Uni<WorkerResult>` return type, SmallRye FT Guard replaces PolicyEnforcer
9. **WorkerFunction<T,R>, WorkerResult<R>, WorkerScope** -- full type parameterisation, scope as explicit parameter, `accumulatedState()`
10. **WorkerOutcome.Completed, WorkerFunction.Persistent, PersistentScope** -- persistent event-loop workers, scope termination

---

## Build and CI

Parent POM: `casehub-parent` (version `0.2-SNAPSHOT`).

```bash
# Full build
mvn --batch-mode -f /path/to/casehub-worker/pom.xml clean install

# Publish (CI only)
mvn --batch-mode deploy -DskipTests
```

CI workflow (`publish.yml`): build on push to main, deploy to GitHub Packages, dispatch downstream to engine + desiredstate.

Distribution: GitHub Packages at `https://maven.pkg.github.com/casehubio/casehub-worker`.
