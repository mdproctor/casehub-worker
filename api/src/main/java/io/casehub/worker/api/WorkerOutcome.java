package io.casehub.worker.api;

public sealed interface WorkerOutcome<R> {
    static <R> WorkerOutcome<R> success() {return new Success<>(null);}

    static <R> WorkerOutcome<R> success(PlannedAction action) {
        java.util.Objects.requireNonNull(action);
        return new Success<>(action);
    }

    record Success<R>(PlannedAction plannedAction) implements WorkerOutcome<R> {}

    record Declined<R>(String reason) implements WorkerOutcome<R> {}

    record Failed<R>(String reason) implements WorkerOutcome<R> {}

    record Expired<R>(String reason) implements WorkerOutcome<R> {}
}
