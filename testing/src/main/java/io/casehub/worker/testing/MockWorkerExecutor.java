package io.casehub.worker.testing;

import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.casehub.worker.runtime.WorkerExecutor;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@DefaultBean
@ApplicationScoped
public class MockWorkerExecutor implements WorkerExecutor {
    private final AtomicInteger executionCount = new AtomicInteger(0);
    private final AtomicReference<String> lastWorkerName = new AtomicReference<>();
    private final AtomicReference<String> lastCapabilityName = new AtomicReference<>();

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public WorkerResult execute(Worker worker, Capability capability, Object input) {
        Objects.requireNonNull(capability, "capability");
        if (!worker.capabilityNames().contains(capability.name())) {
            throw new IllegalArgumentException(
                    "Capability '" + capability.name() + "' not in worker '"
                    + worker.name() + "' capabilities: " + worker.capabilityNames());
        }
        executionCount.incrementAndGet();
        lastWorkerName.set(worker.name());
        lastCapabilityName.set(capability.name());
        if (!(worker.function() instanceof WorkerFunction.Sync sync)) {
            throw new UnsupportedOperationException(
                    "MockWorkerExecutor supports Sync functions only, got: "
                    + worker.function().getClass().getName());
        }
        if (!sync.inputType().isInstance(input)) {
            throw new IllegalArgumentException(
                    "Input type mismatch: expected " + sync.inputType().getName()
                    + ", got " + (input == null ? "null" : input.getClass().getName()));
        }
        try {
            return (WorkerResult) ((java.util.function.Function) sync.fn()).apply(input);
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null) {message = e.getClass().getName();}
            return WorkerResult.failed(message);
        }
    }

    public int executionCount() { return executionCount.get(); }
    public String lastWorkerName() { return lastWorkerName.get(); }
    public String lastCapabilityName() { return lastCapabilityName.get(); }
    public void reset() {
        executionCount.set(0);
        lastWorkerName.set(null);
        lastCapabilityName.set(null);
    }
}
