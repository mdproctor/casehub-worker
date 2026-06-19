package io.casehub.worker.runtime;

import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;

import java.util.Map;

public interface WorkerExecutor {
    WorkerResult execute(Worker worker, Map<String, Object> input);
}
