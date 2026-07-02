# Exception-to-Outcome Conversion — worker#8

**Date:** 2026-07-01
**Issue:** casehubio/casehub-worker#8
**Epic:** casehubio/casehub-worker#3 (worker execution model)
**Status:** Approved

## Summary

Close the `WorkerExecutor` result model by converting all worker-level exceptions into `WorkerOutcome` values. After this change, `execute()` always returns a `WorkerResult` for worker-level conditions — only infrastructure collapse (interrupt, `Error`) propagates as an exception.

Requires a prerequisite change in `casehub-platform-governance` to add typed exception subclasses, fixing a design flaw where `PolicyEnforcementException` conflates timeout, retry exhaustion, and interruption into a single untyped exception.

## Problem

`DefaultWorkerExecutor.execute()` catches all exceptions, records them on the OTel span, then re-throws. Callers must handle two failure channels for the same concept:
1. `WorkerOutcome.Failed` / `WorkerOutcome.Expired` — returned as results
2. Uncaught exceptions — propagated from the worker function or `PolicyEnforcer`

This forces dual error handling at every call site: check the result outcome AND catch exceptions. The `Expired` outcome exists in the sealed hierarchy but nothing in the runtime produces it.

Additionally, `DefaultPolicyEnforcer` double-wraps exceptions: a timeout becomes `PolicyEnforcementException("All 1 attempts failed", PolicyEnforcementException("timed out"))`. The retry loop always wraps the last exception, even for `maxAttempts=1`, destroying type information at the top level.

## Design Decisions

### Typed exception subclasses in platform-governance

`PolicyEnforcementException` is a single untyped exception used for three semantically distinct failure modes. The correct fix is typed subclasses — not cause-based or message-based dispatch in the worker executor. Type-safe `catch` blocks are what exception hierarchies are for.

Three subclasses:

| Class | Thrown by | Semantics |
|-------|----------|-----------|
| `TimeoutPolicyException` | `executeWithTimeout` on `Future.get()` `TimeoutException` | Action exceeded its deadline |
| `InterruptedPolicyException` | `executeWithTimeout` on `InterruptedException` | Thread was interrupted (shutdown, cancellation) |
| `RetryExhaustedException` | Retry loop when all attempts fail with a non-policy exception | Worker function kept throwing after N retries |

### Re-throw policy exceptions directly from retry loop

The retry loop currently wraps ALL last exceptions in `PolicyEnforcementException("All N attempts failed", lastException)`. This is wrong for policy-level failures: a timeout is a timeout regardless of how many retries preceded it. Wrapping it in "All N attempts failed" changes the semantics from "timed out" to "retries exhausted."

After the change:
- If `lastException instanceof PolicyEnforcementException` → re-throw directly. The failure is a policy-level concern (timeout, interrupt) and should surface as such.
- Otherwise → throw `RetryExhaustedException("All N attempts failed", lastException)`. This IS a retry exhaustion — the worker's own exception exhausted the policy.

### Uniform interrupt handling in DefaultPolicyEnforcer

Interrupts signal shutdown — not transient failures. Three changes make interrupt handling uniform across all `DefaultPolicyEnforcer` code paths:

1. **Retry loop breaks on `InterruptedPolicyException`.** An interrupt is not a reason to retry. The loop exits immediately, and the post-loop logic re-throws the `InterruptedPolicyException` directly.

2. **`sleep()` throws `InterruptedPolicyException` on interrupt.** If the thread is interrupted during backoff delay, the retry loop exits immediately rather than silently continuing to the next attempt. Without this, an interrupt during sleep sets the flag, returns normally, then the next `executeWithTimeout` immediately throws `InterruptedPolicyException` — burning through attempts in microseconds.

3. **No-timeout path detects interrupts.** When `timeoutMs` is null, `action.get()` runs directly on the current thread with no `Future.get()` to catch `InterruptedException`. If the action throws and the interrupt flag is set, the enforcer surfaces `InterruptedPolicyException` — matching the timeout path's behavior.

### Exception-to-outcome mapping in DefaultWorkerExecutor

| Exception type | WorkerOutcome | Rationale |
|---------------|---------------|-----------|
| `TimeoutPolicyException` | `Expired` | Worker exceeded its deadline — the semantic definition of `Expired` |
| `InterruptedPolicyException` | propagate | Infrastructure concern, not a worker outcome. No `Cancelled` variant exists. Thread interrupt signals must not be swallowed. |
| `RetryExhaustedException` | `Failed` | Worker function threw repeatedly. Extract the cause message — "account not found" is more useful than "All 3 attempts failed." |
| Any other `Exception` | `Failed` | Raw worker exception (defensive — PolicyEnforcer should have wrapped it, but guard against direct calls or future WorkerFunction variants). |
| `Error` | propagate | JVM is dying (OOM, StackOverflow). Not a worker outcome. |

**OTel semantics:** Timeout-to-Expired is a normal outcome — the span records a `worker.timeout` event but does not set `StatusCode.ERROR` or record an exception. This aligns with issue #6's acceptance criterion: "OTel span records the timeout as an event, not an error." All other exception paths (retry exhaustion, raw exceptions) set `StatusCode.ERROR` and record the exception. All paths that return a `WorkerResult` set the `worker.outcome` span attribute for observability dashboard consistency.

### Failed reason extraction

`WorkerResult.failed(reason)` should carry the worker's original message, not the retry wrapper's message. For `RetryExhaustedException`, extract `getCause().getMessage()`. For raw exceptions, use `getMessage()` directly.

```java
Throwable root = e.getCause() != null ? e.getCause() : e;
String message = root.getMessage();
if (message == null) message = root.getClass().getName();
return WorkerResult.failed(message);
```

A worker that throws `new NullPointerException()` (no message) would otherwise produce `Failed(null)`. Callers pattern-matching with `case Failed(String reason) -> reason.length()` would NPE. The fallback to class name is consistent with OTel's `span.recordException(e)` which includes the class name.

### WorkerExecutor interface contract

This spec changes the contract of `WorkerExecutor.execute()`: all worker-level conditions (function exceptions, retry exhaustion, timeout) are returned as `WorkerResult` outcomes. Only infrastructure signals (thread interrupt, JVM errors) propagate as exceptions. This contract applies to the interface, not just `DefaultWorkerExecutor` — all implementations must honour it, including `MockWorkerExecutor`.

## Changes

### 1. casehub-platform-governance — typed exceptions

**New files** in `io.casehub.platform.governance`:

```java
public class TimeoutPolicyException extends PolicyEnforcementException {
    public TimeoutPolicyException(String message) { super(message); }
}
```

```java
public class InterruptedPolicyException extends PolicyEnforcementException {
    public InterruptedPolicyException(String message, Throwable cause) { super(message, cause); }
}
```

```java
public class RetryExhaustedException extends PolicyEnforcementException {
    public RetryExhaustedException(String message, Throwable cause) { super(message, cause); }
}
```

### 2. casehub-platform-governance — DefaultPolicyEnforcer throw sites

`executeWithTimeout()` — all three catch blocks shown. The `ExecutionException` handler retains the base `PolicyEnforcementException` because this path only triggers for checked exceptions escaping a `Supplier<T>` (which cannot throw them). It is a defensive catch for an unreachable condition, not one of the three semantic failure modes:

```java
private <T> T executeWithTimeout(Integer timeoutMs, Supplier<T> action) {
    if (timeoutMs == null) {
        try {
            return action.get();
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedPolicyException("Interrupted during execution", e);
            }
            throw e;
        }
    }
    Callable<T> callable = action::get;
    Future<T> future = timeoutExecutor.submit(callable);
    try {
        return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
        future.cancel(true);
        throw new TimeoutPolicyException("Action timed out after " + timeoutMs + "ms");
    } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException re) throw re;
        throw new PolicyEnforcementException("Action failed", cause);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedPolicyException("Interrupted during execution", e);
    }
}
```

`sleep()` — throw on interrupt instead of silently continuing:
```java
private void sleep(long ms) {
    try {
        Thread.sleep(ms);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedPolicyException("Interrupted during backoff", e);
    }
}
```

`execute()` retry loop — break on interrupt, re-throw policy exceptions:
```java
Exception lastException = null;
for (int attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
        return executeWithTimeout(policy.timeoutMs(), action);
    } catch (Exception e) {
        lastException = e;
        if (e instanceof InterruptedPolicyException) break;
        if (attempt < maxAttempts) {
            sleep(computeDelay(delayMs, backoff, attempt, maxDelayMs));
        }
    }
}
if (lastException instanceof PolicyEnforcementException pe) {
    throw pe;
}
throw new RetryExhaustedException(
    "All " + maxAttempts + " attempts failed", lastException);
```

### 3. casehub-platform-governance — test updates

`DefaultPolicyEnforcerTest`:
- `execute_exhaustsRetries_throws` → assert `RetryExhaustedException` (not base class)
- `execute_timeout_failsIfExceeded` → assert `TimeoutPolicyException` at top level (no longer double-wrapped)
- New: `execute_interrupted_throwsInterruptedPolicyException`
- New: `execute_interruptDuringSleep_throwsInterruptedPolicyException` — interrupt during backoff exits immediately
- New: `execute_interruptWithoutTimeout_throwsInterruptedPolicyException` — interrupt in no-timeout path detected

### 4. casehub-worker — DefaultWorkerExecutor catch block

Replace single `catch (Exception e) { throw e; }` with type-based dispatch:

```java
catch (TimeoutPolicyException e) {
    span.addEvent("worker.timeout", Attributes.of(
        AttributeKey.stringKey("timeout.message"), e.getMessage()));
    WorkerResult result = WorkerResult.expired(e.getMessage());
    span.setAttribute(AttributeKey.stringKey("worker.outcome"),
        result.outcome().getClass().getSimpleName());
    return result;
}
catch (InterruptedPolicyException e) {
    span.setStatus(StatusCode.ERROR, e.getMessage());
    span.recordException(e);
    throw e;
}
catch (Exception e) {
    span.setStatus(StatusCode.ERROR, e.getMessage());
    span.recordException(e);
    Throwable root = e.getCause() != null ? e.getCause() : e;
    String message = root.getMessage();
    if (message == null) message = root.getClass().getName();
    WorkerResult result = WorkerResult.failed(message);
    span.setAttribute(AttributeKey.stringKey("worker.outcome"),
        result.outcome().getClass().getSimpleName());
    return result;
}
```

### 5. casehub-worker — WorkerExecutor interface Javadoc

```java
/**
 * Executes the given worker with policy enforcement.
 *
 * <p>All worker-level conditions (function exceptions, retry exhaustion,
 * timeout) are returned as {@link WorkerResult} outcomes. Only infrastructure
 * signals (thread interrupt, JVM errors) propagate as exceptions.
 */
WorkerResult execute(Worker worker, Map<String, Object> input);
```

### 6. casehub-worker — MockWorkerExecutor exception-to-outcome conversion

Add try-catch to honour the `WorkerExecutor` interface contract:

```java
@Override
public WorkerResult execute(Worker worker, Map<String, Object> input) {
    executionCount.incrementAndGet();
    lastWorkerName.set(worker.name());
    try {
        return ((io.casehub.worker.api.WorkerFunction.Sync) worker.function()).fn().apply(input);
    } catch (Exception e) {
        String message = e.getMessage();
        if (message == null) message = e.getClass().getName();
        return WorkerResult.failed(message);
    }
}
```

### 7. casehub-worker — test updates

`WorkerExecutorTest`:
- `execute_exhaustsRetries_throwsPolicyException` → rename to `execute_exhaustsRetries_returnsFailed`, assert `WorkerOutcome.Failed` with the worker's exception message
- New: `execute_workerThrows_returnsFailed` — single exception, default policy, returns `Failed`
- New: `execute_timeout_returnsExpired` — timeout configured, slow worker, returns `Expired`
- New: `execute_workerThrowsNullMessage_returnsFailedWithClassName` — null message fallback
- New: `execute_timeout_setsWorkerOutcomeAttribute` — verifies `worker.outcome` set on Expired path

`MockWorkerExecutorTest`:
- New: `execute_workerThrows_returnsFailed` — exception converted to `Failed` outcome

### 8. casehub-worker — dependency version

Bump `casehub-platform-governance` dependency to pick up the new exception types. Build and publish platform-governance first.

## Execution Order

1. Platform-governance: add typed exceptions + update DefaultPolicyEnforcer (throw sites, sleep, retry loop, no-timeout path) + update tests
2. Platform-governance: build and install to local Maven repo
3. Worker: update WorkerExecutor Javadoc + DefaultWorkerExecutor catch block + MockWorkerExecutor try-catch + update tests
4. Worker: build and verify

## Out of Scope

- Async `WorkerFunction` — tracked in worker#5, a separate concern.
- `WorkerContext` — tracked in worker#4, a separate concern.
- Issue #6 (timeout enforcement) — this spec delivers the sync timeout-to-Expired mapping and OTel event semantics, which are prerequisites for #6. Issue #6 remains open: it covers async worker timeout (depends on #5), and the full acceptance criteria (async path, workers-within-deadline testing). #6 should be re-scoped to cover what this spec does not.
