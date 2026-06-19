package io.casehub.worker.runtime;

import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.platform.governance.DefaultPolicyEnforcer;
import io.casehub.platform.governance.PolicyEnforcementException;
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

    @Test
    void execute_successfulWorker() {
        Worker worker = Worker.builder()
            .name("greet")
            .capability(Capability.of("greet", "{}", "{}"))
            .function((WorkerFunction) input -> WorkerResult.of(Map.of("greeting", "hello " + input.get("name"))))
            .build();

        WorkerResult result = executor.execute(worker, Map.of("name", "world"));
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat(result.output()).containsEntry("greeting", "hello world");
    }

    @Test
    void execute_retriesTransientFailures() {
        AtomicInteger attempts = new AtomicInteger(0);
        Worker worker = Worker.builder()
            .name("flaky")
            .capability(Capability.of("process", "{}", "{}"))
            .function((WorkerFunction) input -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new RuntimeException("transient");
                }
                return WorkerResult.of(Map.of("recovered", true));
            })
            .executionPolicy(new ExecutionPolicy(null, new RetryPolicy(3, 10)))
            .build();

        WorkerResult result = executor.execute(worker, Map.of());
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void execute_exhaustsRetries_throwsPolicyException() {
        Worker worker = Worker.builder()
            .name("broken")
            .capability(Capability.of("fail", "{}", "{}"))
            .function((WorkerFunction) input -> { throw new RuntimeException("permanent"); })
            .executionPolicy(new ExecutionPolicy(null, new RetryPolicy(2, 10)))
            .build();

        assertThatThrownBy(() -> executor.execute(worker, Map.of()))
            .isInstanceOf(PolicyEnforcementException.class);
    }
}
