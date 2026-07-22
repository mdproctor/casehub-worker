package io.casehub.worker.testing;

import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestWorkerBuilderTest {

    @Test
    void syncWithCapability_createsMatchingPair() {
        var wc = TestWorkerBuilder.syncWithCapability("greet",
            input -> WorkerResult.of(Map.of("greeting", "hello")));

        assertThat(wc.worker().name()).isEqualTo("greet");
        assertThat(wc.worker().capabilityNames()).containsExactly("greet");
        assertThat(wc.capability().name()).isEqualTo("greet");
        assertThat(wc.capability().inputSchema()).isEqualTo("{}");
        assertThat(wc.capability().outputSchema()).isEqualTo("{}");
    }

    @Test
    void syncWithCapability_functionExecutes() {
        var wc = TestWorkerBuilder.syncWithCapability("echo",
            input -> WorkerResult.of(input));

        @SuppressWarnings("unchecked")
        var result = ((io.casehub.worker.api.WorkerFunction.Sync<Map<String, Object>, Map<String, Object>>) wc.worker().function())
            .fn().apply(Map.of("key", "value"), null);
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat((Map<String, Object>) result.output()).containsEntry("key", "value");
    }

    // async tests removed — Async variant and async builder methods deleted (virtual threads)

    // asyncWithCapability_withSchemas test removed — async methods deleted


}
