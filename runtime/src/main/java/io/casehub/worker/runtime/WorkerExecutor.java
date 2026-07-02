package io.casehub.worker.runtime;

import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;

import java.util.Map;

public interface WorkerExecutor {
    /**
     * Executes the given worker with policy enforcement.
     *
     * <p>All worker-level conditions (function exceptions, retry exhaustion,
     * timeout) are returned as {@link WorkerResult} outcomes. Only infrastructure
     * signals (thread interrupt, JVM errors) propagate as exceptions.
     */
    WorkerResult execute(Worker worker, Map<String, Object> input);
}
