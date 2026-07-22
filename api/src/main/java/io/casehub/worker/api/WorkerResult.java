package io.casehub.worker.api;

public record WorkerResult<R>(R output, WorkerOutcome<R> outcome) {
    public static <R> WorkerResult<R> of(R output) {
        return new WorkerResult<>(output, WorkerOutcome.success());
    }

    public static <R> WorkerResult<R> of(R output, PlannedAction action) {
        java.util.Objects.requireNonNull(action);
        return new WorkerResult<>(output, new WorkerOutcome.Success<>(action));
    }

    public static <R> WorkerResult<R> declined(String reason) {
        return new WorkerResult<>(null, new WorkerOutcome.Declined<>(reason));
    }

    public static <R> WorkerResult<R> declined(String reason, R partialOutput) {
        return new WorkerResult<>(partialOutput, new WorkerOutcome.Declined<>(reason));
    }

    public static <R> WorkerResult<R> failed(String reason) {
        return new WorkerResult<>(null, new WorkerOutcome.Failed<>(reason));
    }

    public static <R> WorkerResult<R> failed(String reason, R partialOutput) {
        return new WorkerResult<>(partialOutput, new WorkerOutcome.Failed<>(reason));
    }

    public static <R> WorkerResult<R> expired(String reason) {
        return new WorkerResult<>(null, new WorkerOutcome.Expired<>(reason));
    }

    public static <R> WorkerResult<R> expired(String reason, R partialOutput) {
        return new WorkerResult<>(partialOutput, new WorkerOutcome.Expired<>(reason));
    }
}
