package io.casehub.worker.testing;

import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;

import java.util.Map;
import java.util.function.Function;

public final class TestWorkerBuilder {
    private TestWorkerBuilder() {}

    public record WorkerWithCapability(Worker worker, Capability capability) {}

    public static Worker sync(String name, Function<Map<String, Object>, WorkerResult<Map<String, Object>>> fn) {
        return Worker.builder()
            .name(name)
            .capabilityName(name)
            .function(fn)
            .build();
    }

    public static WorkerWithCapability syncWithCapability(String name,
            Function<Map<String, Object>, WorkerResult<Map<String, Object>>> fn) {
        Worker worker = Worker.builder()
            .name(name)
            .capabilityName(name)
            .function(fn)
            .build();
        Capability capability = Capability.of(name, "{}", "{}");
        return new WorkerWithCapability(worker, capability);
    }

    public static WorkerWithCapability syncWithCapability(String name,
            String inputSchema, String outputSchema,
            Function<Map<String, Object>, WorkerResult<Map<String, Object>>> fn) {
        Worker worker = Worker.builder()
            .name(name)
            .capabilityName(name)
            .function(fn)
            .build();
        Capability capability = Capability.of(name, inputSchema, outputSchema);
        return new WorkerWithCapability(worker, capability);
    }

    // async methods removed — virtual threads supersede CompletionStage workers

    // asyncWithCapability(String, String, String, Function) removed — virtual threads supersede CompletionStage


}
