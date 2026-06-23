package io.casehub.worker.api;

import java.util.Objects;

public sealed interface WorkerOutcome {
    static WorkerOutcome success() { return new Success(null); }
    static WorkerOutcome success(PlannedAction action) {
        Objects.requireNonNull(action);
        return new Success(action);
    }
    record Success(PlannedAction plannedAction) implements WorkerOutcome {}
    record Declined(String reason) implements WorkerOutcome {}
    record Failed(String reason) implements WorkerOutcome {}
    record Expired(String reason) implements WorkerOutcome {}
}
