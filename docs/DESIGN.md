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

## SPI Contracts

_To be documented._

## Data Model

_To be documented._

## Configuration

_To be documented._
