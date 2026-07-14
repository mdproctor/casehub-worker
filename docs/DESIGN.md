# CaseHub Worker — Design

## Architecture

_To be documented._

## Module Structure

| Module | Type | Purpose |
|--------|------|---------|
| `api` | API | Worker, WorkerFunction, Capability, WorkerResult, WorkerOutcome, PlannedAction |
| `runtime` | Runtime | WorkerExecutor with PolicyEnforcer + OTel tracing |
| `testing` | Test support | MockWorkerExecutor + TestWorkerBuilder |

## Key Abstractions

- **PlannedAction** — a consequential action a worker intends to take. Carries `description` (human-readable summary), `actionType` (machine-readable identifier), and `parameters` (action arguments). Lives on `WorkerOutcome.Success` — the type system structurally enforces that only successful outcomes can declare an action. Identity (workerId, caseId) is NOT on PlannedAction — it belongs to the orchestration tier's `ClassificationContext`.
- **WorkerOutcome** — sealed interface with four variants: `Success(PlannedAction)`, `Declined(reason)`, `Failed(reason)`, `Expired(reason)`. PlannedAction on Success is nullable (most successes don't declare actions).

## Schema Validation

`SchemaValidator` (`@ApplicationScoped`, runtime module) validates worker inputs and outputs against JSON Schema documents declared on `Capability.inputSchema()` and `Capability.outputSchema()`. Uses `com.networknt:json-schema-validator` 1.0.83 (Draft 2020-12).

| Direction | Invalid result | Rationale |
|-----------|---------------|-----------|
| Input | `WorkerResult.failed()` — function never runs | Prevents wasting compute on garbage input |
| Output (Success only) | Log WARN, return result unchanged | Worker did its job; schema mismatch is observability |

- Empty schema `"{}"` skips validation (performance shortcut — empty JSON Schema validates everything)
- Malformed schema throws `IllegalArgumentException` (programming error, same category as null capability)
- Schema objects are cached in a `ConcurrentHashMap` keyed by schema string
- `Declined`/`Failed`/`Expired` outcomes are excluded from output validation — partial output is diagnostic, not a contract

## Execution Contract

`WorkerExecutor.execute()` always returns a `WorkerResult` for worker-level conditions. Programming errors propagate as exceptions. Infrastructure signals (thread interrupt, JVM errors) propagate as exceptions.

### Programming errors

| Error | Exception | When |
|-------|-----------|------|
| Null capability | `NullPointerException` | `capability` is null |
| Capability not in worker | `IllegalArgumentException` | `capability.name()` not in `worker.capabilityNames()` |
| Non-Sync function | `UnsupportedOperationException` | `worker.function()` is not `WorkerFunction.Sync` |
| Input type mismatch | `IllegalArgumentException` | `input` is not an instance of `WorkerFunction.inputType()` |
| Null input | `IllegalArgumentException` | `input` is null (subcase of type mismatch — `isInstance(null)` is false) |
| Malformed schema | `IllegalArgumentException` | `Capability.inputSchema()` or `outputSchema()` is not valid JSON Schema |

### Worker-level outcomes

| Exception source | WorkerOutcome |
|-----------------|---------------|
| `TimeoutPolicyException` | `Expired` |
| `RetryExhaustedException` | `Failed` (extracts worker's original message from cause) |
| Raw worker exception | `Failed` |
| `InterruptedPolicyException` | propagates (infrastructure) |

OTel: timeout-to-Expired records a `worker.timeout` event (not `StatusCode.ERROR`). All other exception paths set `StatusCode.ERROR` and record the exception. All paths set the `worker.outcome` span attribute.

## SPI Contracts

_To be documented._

## Data Model

_To be documented._

## Configuration

_To be documented._
