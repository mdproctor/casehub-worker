package io.casehub.worker.api;

import java.util.Map;
import java.util.Objects;

public record WorkerResult(Map<String, Object> output, WorkerOutcome outcome) {
    public static WorkerResult of(Map<String, Object> output) {
        return new WorkerResult(output, WorkerOutcome.success());
    }
    public static WorkerResult of(Map<String, Object> output, PlannedAction action) {
        Objects.requireNonNull(action);
        return new WorkerResult(output, new WorkerOutcome.Success(action));
    }
    public static WorkerResult declined(String reason) {
        return new WorkerResult(Map.of(), new WorkerOutcome.Declined(reason));
    }
    public static WorkerResult declined(String reason, Map<String, Object> partialOutput) {
        Objects.requireNonNull(partialOutput);
        return new WorkerResult(partialOutput, new WorkerOutcome.Declined(reason));
    }
    public static WorkerResult failed(String reason) {
        return new WorkerResult(Map.of(), new WorkerOutcome.Failed(reason));
    }
    public static WorkerResult failed(String reason, Map<String, Object> partialOutput) {
        Objects.requireNonNull(partialOutput);
        return new WorkerResult(partialOutput, new WorkerOutcome.Failed(reason));
    }
    public static WorkerResult expired(String reason) {
        return new WorkerResult(Map.of(), new WorkerOutcome.Expired(reason));
    }
    public static WorkerResult expired(String reason, Map<String, Object> partialOutput) {
        Objects.requireNonNull(partialOutput);
        return new WorkerResult(partialOutput, new WorkerOutcome.Expired(reason));
    }
}
