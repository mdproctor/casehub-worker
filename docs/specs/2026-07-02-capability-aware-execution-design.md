# Capability-Aware Execution

**Issue:** casehubio/casehub-worker#9
**Date:** 2026-07-02
**Status:** Approved

## Problem

`WorkerExecutor.execute(Worker, Map<String, Object>)` takes a Worker and input but has no
way to know which capability is being exercised. A Worker declares multiple capability names
(`Set<String>`), and each maps to a `Capability` with its own `inputSchema`/`outputSchema`.
Without knowing which capability is being invoked, the executor cannot validate schemas (#7),
trace at capability granularity, or assert the Worker actually supports the requested capability.

## Architecture Context

Two independent executor hierarchies exist in the platform:

1. **Worker repo** (`casehub-worker-runtime`): `WorkerExecutor.execute(Worker, Map)` — synchronous,
   PolicyEnforcer governance, OTel tracing. Handles `WorkerFunction.Sync` only. No external consumers.
2. **Engine** (`casehub-engine-common`): `WorkerExecutor.execute(WorkerFunction, Map, WorkerContext,
   int, String, ExecutionMetadata)` — reactive (`Uni`), multi-function dispatch via `WorkerFunctionHandler`,
   output schema JQ evaluation. Does not delegate to the worker repo's executor.

The engine already has capability awareness at the scheduling layer — `WorkerScheduleEvent` carries
`Capability`, and `WorkerExecutionManager.submit()` takes `(eventLogId, instance, Worker, Capability,
inputData)`. The gap is only in the worker repo's executor.

Convergence where the engine's `SyncAgentWorkerFunctionHandler` delegates to this executor is
tracked in #10. This spec designs the API so it is adoptable by the engine without a second
redesign.

The engine's `QuartzWorkerExecutionJob` already reconstructs both `Worker` and `Capability` from
`CaseDefinition` before calling the engine's own `WorkerExecutor`. All viable convergence paths —
threading these objects through the handler, enriching `ExecutionMetadata`, or delegating at the
Quartz job level — have access to both objects and can call `execute(Worker, Capability, Map)`
without further changes to this repo's API. The engine-internal refactoring is a #10 concern.

## Design

### API Change

```java
public interface WorkerExecutor {
    WorkerResult execute(Worker worker, Capability capability, Map<String, Object> input);
}
```

The old two-parameter `execute(Worker, Map)` is removed. No deprecation, no overload — the
signature break forces every call site to be explicit about which capability is being exercised.

The interface Javadoc is updated to reflect a three-category exception contract:

1. **Worker-level conditions** (function exceptions, retry exhaustion, timeout) → returned as
   `WorkerResult` outcomes. Never propagate as exceptions.
2. **Programming errors** (null capability, capability not in worker, non-Sync function) →
   propagate as exceptions (`NullPointerException`, `IllegalArgumentException`,
   `UnsupportedOperationException`). These are call-site bugs, not execution outcomes.
3. **Infrastructure signals** (thread interrupt, JVM errors) → propagate as exceptions. Unchanged.

### Validation Contract

`DefaultWorkerExecutor.execute()` validates before any work:

- `capability` must not be null — `Objects.requireNonNull(capability, "capability")`
- `capability.name()` must be in `worker.capabilityNames()` — throws `IllegalArgumentException`
  if not. This catches programming errors at the call site.
- `worker.function()` must be `WorkerFunction.Sync` — throws `UnsupportedOperationException`
  otherwise. This check runs before `policyEnforcer.execute()` so programming errors are not
  subject to retry policy.

### DefaultWorkerExecutor

- Add `Capability capability` parameter to `execute()`
- Guard: reject null capability and non-Sync function types before `policyEnforcer.execute()` —
  programming errors must not be subject to retry policy
- Validate `capability.name() ∈ worker.capabilityNames()` before execution
- Extract `WorkerFunction.Sync sync` upfront via pattern match; pass `sync.fn()` to the
  policy enforcer lambda
- Add OTel span attribute `worker.capability` with value `capability.name()`
- PolicyEnforcer, exception conversion, tracing structure — unchanged

### MockWorkerExecutor

- Add `Capability capability` parameter to `execute()`
- Validate `capability.name() ∈ worker.capabilityNames()` — same contract as
  `DefaultWorkerExecutor`. Since `MockWorkerExecutor` is `@DefaultBean` in all `@QuarkusTest`
  environments, skipping validation would mask capability routing bugs.
- Track `lastCapabilityName` for test assertions
- New accessor: `String lastCapabilityName()`
- Update `reset()` to also clear `lastCapabilityName` — prevents stale capability state
  from leaking between test cases

### TestWorkerBuilder

Add a convenience that returns both a Worker and a matching Capability:

```java
public record WorkerWithCapability(Worker worker, Capability capability) {}

public static WorkerWithCapability syncWithCapability(String name,
    Function<Map<String, Object>, WorkerResult> fn) {
    Worker worker = Worker.builder()
        .name(name).capabilityName(name).function(fn).build();
    Capability capability = Capability.of(name, "{}", "{}");
    return new WorkerWithCapability(worker, capability);
}
```

The existing `sync(name, fn)` method stays for tests that construct their own `Capability`.

## What This Does NOT Include

- Input/output schema validation — #7
- Engine-side convergence — #10
- Non-Sync function type dispatch — the executor continues to handle `Sync` only. If a non-Sync
  function is passed, the current `ClassCastException` (thrown inside the retry lambda) is replaced
  with an explicit `UnsupportedOperationException` thrown before `policyEnforcer.execute()`, as a
  correctness fix within #9 scope.

## Platform Coherence

- **Module tier compliance:** `WorkerExecutor` stays in `casehub-worker-runtime` (Tier 3).
  `Capability` is already in `casehub-worker-api` (Tier 1). No tier violations.
- **No new cross-repo dependencies:** The API change is internal to this repo. External consumers
  import from `casehub-worker-api` (unchanged) or `casehub-worker-testing` (signature change only).
- **WorkerExecutor naming:** Both this repo (`io.casehub.worker.runtime.WorkerExecutor`) and the
  engine (`io.casehub.engine.common.internal.executor.WorkerExecutor`) define interfaces with the
  same simple name but different semantics (synchronous vs reactive `Uni`). Acceptable today —
  different packages, no import overlap. When #10 converges the Sync path, import disambiguation
  will be needed; resolution is a #10 concern.
- **Protocol compliance:** No SPI signatures change. No persistence. No Flyway. No CDI pattern changes.
- **PLATFORM.md alignment:** "Workers declare support by name, engine resolves authoritative
  Capability instances from CaseDefinition.getCapabilities()" — unchanged. The executor receives
  the resolved Capability from the caller; it does not resolve it itself.

## Deferred Concerns

| Concern | Tracked in |
|---------|-----------|
| Engine delegates SyncAgentWorkerFunctionHandler to this executor | #10 |
| WorkerExecutor naming collision resolution | #10 |
| Schema validation (input before execution, output after) | #7 |
| WorkerContext — ambient execution state | #4 |

### Signature evolution: #9 and #4

Both #9 and #4 break `WorkerExecutor.execute()`. If #9 ships first, #4 breaks the same interface
a second time. This is intentional: #9 is a prerequisite for #7 (schema validation) and should
ship independently. Two focused breaks with independent review and testing are preferred over
coupling delivery of two issues with different scopes (S vs M) and different dependency chains.
The migration for each is mechanical.
