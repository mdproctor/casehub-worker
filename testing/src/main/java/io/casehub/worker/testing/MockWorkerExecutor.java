package io.casehub.worker.testing;

import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import io.casehub.worker.runtime.WorkerExecutor;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@DefaultBean
@ApplicationScoped
public class MockWorkerExecutor implements WorkerExecutor {
    private final AtomicInteger executionCount = new AtomicInteger(0);
    private final AtomicReference<String> lastWorkerName = new AtomicReference<>();

    @Override
    public WorkerResult execute(Worker worker, Map<String, Object> input) {
        executionCount.incrementAndGet();
        lastWorkerName.set(worker.name());
        return worker.function().execute(input);
    }

    public int executionCount() { return executionCount.get(); }
    public String lastWorkerName() { return lastWorkerName.get(); }
    public void reset() { executionCount.set(0); lastWorkerName.set(null); }
}
