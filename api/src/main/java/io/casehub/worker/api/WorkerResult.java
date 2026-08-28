package io.casehub.worker.api;

public record WorkerResult<R>(R output, WorkerOutcome<R> outcome, String reasoning) {

    public WorkerResult(R output, WorkerOutcome<R> outcome) {
        this(output, outcome, null);
    }

    public WorkerResult<R> withReasoning(String reasoning) {
        return new WorkerResult<>(this.output, this.outcome, reasoning);
    }

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

    public static <R> WorkerResult<R> completed(R output) {
        return new WorkerResult<>(output, new WorkerOutcome.Completed<>());
    }
}
