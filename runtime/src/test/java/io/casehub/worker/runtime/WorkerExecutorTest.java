package io.casehub.worker.runtime;

import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.platform.governance.DefaultPolicyEnforcer;

import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerExecutorTest {

    private final DefaultWorkerExecutor executor = new DefaultWorkerExecutor(new DefaultPolicyEnforcer());

    private static Capability cap(String name) {
        return Capability.of(name, "{}", "{}");
    }

    @Test
    void execute_successfulWorker() {
        Worker worker = Worker.builder()
            .name("greet").capabilityName("greet")
            .function(new WorkerFunction.Sync(input -> WorkerResult.of(Map.of("greeting", "hello " + input.get("name")))))
            .build();

        WorkerResult result = executor.execute(worker, cap("greet"), Map.of("name", "world"));
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat(result.output()).containsEntry("greeting", "hello world");
    }

    @Test
    void execute_retriesTransientFailures() {
        AtomicInteger attempts = new AtomicInteger(0);
        Worker worker = Worker.builder()
            .name("flaky").capabilityName("process")
            .function(new WorkerFunction.Sync(input -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new RuntimeException("transient");
                }
                return WorkerResult.of(Map.of("recovered", true));
            }))
            .executionPolicy(new ExecutionPolicy(null, new RetryPolicy(3, 10)))
            .build();

        WorkerResult result = executor.execute(worker, cap("process"), Map.of());
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void execute_exhaustsRetries_returnsFailed() {
        Worker worker = Worker.builder()
            .name("broken").capabilityName("fail")
            .function(new WorkerFunction.Sync(input -> { throw new RuntimeException("permanent"); }))
            .executionPolicy(new ExecutionPolicy(null, new RetryPolicy(2, 10)))
            .build();

        WorkerResult result = executor.execute(worker, cap("fail"), Map.of());
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(((WorkerOutcome.Failed) result.outcome()).reason()).isEqualTo("permanent");
    }

    @Test
    void execute_workerThrows_returnsFailed() {
        Worker worker = Worker.builder()
            .name("throws").capabilityName("boom")
            .function(new WorkerFunction.Sync(input -> { throw new IllegalStateException("bad state"); }))
            .build();

        WorkerResult result = executor.execute(worker, cap("boom"), Map.of());
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(((WorkerOutcome.Failed) result.outcome()).reason()).isEqualTo("bad state");
    }

    @Test
    void execute_timeout_returnsExpired() {
        Worker worker = Worker.builder()
            .name("slow").capabilityName("crawl")
            .function(new WorkerFunction.Sync(input -> {
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return WorkerResult.of(Map.of());
            }))
            .executionPolicy(new ExecutionPolicy(50, new RetryPolicy(1, 0)))
            .build();

        WorkerResult result = executor.execute(worker, cap("crawl"), Map.of());
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Expired.class);
        assertThat(((WorkerOutcome.Expired) result.outcome()).reason()).contains("timed out");
    }

    @Test
    void execute_workerThrowsNullMessage_returnsFailedWithClassName() {
        Worker worker = Worker.builder()
            .name("npe").capabilityName("null")
            .function(new WorkerFunction.Sync(input -> { throw new NullPointerException(); }))
            .build();

        WorkerResult result = executor.execute(worker, cap("null"), Map.of());
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(((WorkerOutcome.Failed) result.outcome()).reason()).isEqualTo("java.lang.NullPointerException");
    }

    // --- Validation tests ---

    @Test
    void execute_nullCapability_throwsNPE() {
        Worker worker = Worker.builder()
            .name("w").capabilityName("c")
            .function(new WorkerFunction.Sync(input -> WorkerResult.of(Map.of())))
            .build();

        assertThatThrownBy(() -> executor.execute(worker, null, Map.of()))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("capability");
    }

    @Test
    void execute_capabilityNotInWorker_throwsIAE() {
        Worker worker = Worker.builder()
            .name("w").capabilityName("supported")
            .function(new WorkerFunction.Sync(input -> WorkerResult.of(Map.of())))
            .build();

        assertThatThrownBy(() -> executor.execute(worker, cap("unsupported"), Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported")
            .hasMessageContaining("w");
    }

    @Test
    void execute_nonSyncFunction_throwsUnsupported() {
        Worker worker = Worker.builder()
            .name("external").capabilityName("ext")
            .noFunction()
            .build();

        assertThatThrownBy(() -> executor.execute(worker, cap("ext"), Map.of()))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("Sync");
    }
}
