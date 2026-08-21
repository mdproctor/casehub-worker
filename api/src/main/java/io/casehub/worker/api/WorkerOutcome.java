package io.casehub.worker.api;

public sealed interface WorkerOutcome<R> {
    static <R> WorkerOutcome<R> success() {return new Success<>(null);}

    static <R> WorkerOutcome<R> success(PlannedAction action) {
        java.util.Objects.requireNonNull(action);
        return new Success<>(action);
    }

    record Success<R>(PlannedAction plannedAction) implements WorkerOutcome<R> {}

    record Declined<R>(String reason) implements WorkerOutcome<R> {}

    record Failed<R>(String reason, FailureClass hint) implements WorkerOutcome<R> {
        public Failed(String reason) {
            this(reason, null);
        }
    }

    record Expired<R>(String reason) implements WorkerOutcome<R> {}

    static <R> WorkerOutcome<R> completed() {return new Completed<>();}

    record Completed<R>() implements WorkerOutcome<R> {}
}
