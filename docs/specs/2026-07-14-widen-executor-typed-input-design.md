# Widen WorkerExecutor to Object Input — Honour WorkerFunction\<T\>

Refs #10

## Overview

The ContextBridge protocol (casehubio/engine#203) introduced typed context
propagation across CaseHub's execution boundaries. `WorkerFunction<T>` is
parameterised — the function declares its input type via `inputType()` and
`fn()` accepts `T`. But the worker executor ignores this:
`execute(Worker, Capability, Map<String, Object>)` forces Map input, and
`DefaultWorkerExecutor` casts fn to `Function<Map<String, Object>, WorkerResult>`.

This makes the worker executor a type bottleneck in the ContextBridge
pipeline. The bridge resolves typed input upstream, the function expects
typed input downstream, but the executor in the middle collapses everything
to Map.

## Motivation

The ContextBridge architecture (engine spec `2026-07-09-context-bridge-architecture.md`)
defines a tier-aware type propagation model:

```
casehub-worker-api (tier 1)
  WorkerFunction<T>.inputType() → Class<T>    ← type token
  WorkerFunction.Sync<T>.fn()   → Function<T, WorkerResult>

casehub-engine-api (tier 2)
  ContextBridge<T>.initialise()  → T           ← bridge resolution
  BridgeResolver.resolve()       → ContextBridge<?>
```

The type token (`Class<T>`) crosses tier boundaries cleanly. The bridge
instance stays in engine-api. The worker executor (tier 1) never references
`ContextBridge` — it receives already-resolved typed input and passes it
through to `fn.apply()`.

Today the executor breaks this by forcing `Map<String, Object>` at the
boundary between bridge resolution and function invocation.

## Design

### 1. WorkerExecutor interface

```java
public interface WorkerExecutor {
    WorkerResult execute(Worker worker, Capability capability, Object input);
}
```

`Map<String, Object>` → `Object`. Source compatible — existing callers passing
Map compile unchanged. Not binary compatible (method descriptor changes from
`Ljava/util/Map;` to `Ljava/lang/Object;`) — recompilation required.

### 2. DefaultWorkerExecutor changes

#### Input type validation

After extracting `WorkerFunction.Sync`, validate that input matches the
declared type with strict validation:

```java
if (!sync.inputType().isInstance(input)) {
    throw new IllegalArgumentException(
        "Input type mismatch: expected " + sync.inputType().getName()
        + ", got " + (input == null ? "null" : input.getClass().getName()));
}
```

Strict validation — the input must match the function's declared
`inputType()`. No Map escape hatch. This is correct for all cases:
- Map-typed function + Map input: `Map.class.isInstance(mapInput)` → true
- POJO-typed function + POJO input: `AmlTransaction.class.isInstance(amlTxn)` → true
- POJO-typed function + Map input: fails with clear IAE
- Null input: `isInstance(null)` → false → clear IAE (programming error)

A Map escape hatch was considered but rejected — lambda bridge methods
include a `checkcast` instruction, so a Map passed to a
`Function<AmlTransaction, WorkerResult>` would throw ClassCastException
inside the lambda body. Strict validation produces superior diagnostics
and is never bypassed by the type system.

#### Function invocation

Current (ignores T):
```java
((Function<Map<String, Object>, WorkerResult>) sync.fn()).apply(input);
```

After (honours T):
```java
((Function) sync.fn()).apply(input);
```

Raw-type invocation with `@SuppressWarnings`. The bridge already ensured
the input matches the function's declared type. The executor trusts the
upstream and passes the input through.

### 3. SchemaValidator widening

```java
public Optional<String> validateInput(Capability capability, Object input)
public Optional<String> validateOutput(Capability capability, Object output)

private Optional<String> validate(String schemaString, Object data) {
    if (EMPTY_SCHEMA.equals(schemaString)) return Optional.empty();
    JsonSchema schema = cache.computeIfAbsent(schemaString, this::parseSchema);
    JsonNode node = objectMapper.valueToTree(data);
    Set<ValidationMessage> errors = schema.validate(node);
    ...
}
```

`Map<String, Object>` → `Object` on all public methods and the private
`validate()` helper. The internal logic is unchanged — `valueToTree()`
already handles Map, POJO, and JsonNode input. JSON Schema validation
operates on JsonNode regardless of the runtime input type.

### 4. MockWorkerExecutor

Match the interface change: `execute(Worker, Capability, Object input)`.

The mock includes the same input type validation as the real executor
(`inputType().isInstance(input)`) so tests cannot silently pass invalid
input that the real executor would reject. Tests that need to bypass
validation can inject a dedicated test double.

Function invocation changes from the typed cast
`((Function<Map<String, Object>, WorkerResult>) sync.fn()).apply(input)`
to raw-type `((Function) sync.fn()).apply(input)`, matching the real
executor.

### 5. Test coverage

- Existing Map-based tests pass unchanged (Map is Object)
- New test: typed `WorkerFunction<T>` with POJO input — executor passes
  it to fn without Map cast
- New test: schema validation with POJO input — valueToTree produces
  valid JsonNode
- New test: type mismatch throws IllegalArgumentException (includes
  Map input to POJO-typed function, wrong POJO type, null input)

### 6. Documentation updates

Update `WorkerExecutor.java` Javadoc to add "input type mismatch" to the
programming errors list:

> **Programming errors** (null capability, capability not in worker,
> non-Sync function, **input type mismatch**) — propagate as exceptions

Update `docs/DESIGN.md` "Execution Contract" section to explicitly list
programming error categories, matching the Javadoc.

## Behavior changes

- **Null input handling** — null input now throws
  `IllegalArgumentException` at the type check instead of flowing to
  schema validation where `objectMapper.valueToTree(null)` produces
  `NullNode`. This is correct: null input to any function is a
  programming error, not a schema violation.

## What this does NOT change

- **WorkerResult.output()** stays `Map<String, Object>` — output goes
  back to CaseContext which is Map-based. The ContextBridge spec's
  `extractOutput(T)` returns Map for this reason. Output-side typing
  is not part of this change.

- **ContextBridge awareness** — the worker executor (tier 1) does not
  reference ContextBridge (tier 2). It receives already-resolved input
  and passes it through. Bridge resolution remains an engine concern.

- **PolicyEnforcer** — timeout/retry enforcement is unchanged. The
  executor still delegates to `PolicyEnforcer.execute(policy, action)`.

- **OTel tracing** — span creation with worker.name and capability.name
  is unchanged.

## Relationship to engine convergence

This change is a prerequisite for casehubio/engine#726 (Sync handler
delegates to worker executor). After this change, the engine's
`SyncWorkerFunctionHandler` can inject `io.casehub.worker.runtime.WorkerExecutor`
and delegate typed input to it — the executor no longer forces Map.

The engine convergence additionally threads `Worker` and `Capability`
through the engine's handler chain, splits `SyncAgentWorkerFunctionHandler`,
and drops the `timeoutMs` parameter. That work is scoped to engine#726.
