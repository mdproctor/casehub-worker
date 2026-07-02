package io.casehub.worker.testing;

import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import io.casehub.worker.api.Worker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MockWorkerExecutorTest {

    @Test
    void execute_bypassesPolicyEnforcement() {
        MockWorkerExecutor executor = new MockWorkerExecutor();
        Worker worker = TestWorkerBuilder.sync("test", input -> WorkerResult.of(Map.of("ok", true)));

        WorkerResult result = executor.execute(worker, Map.of());
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat(executor.executionCount()).isEqualTo(1);
        assertThat(executor.lastWorkerName()).isEqualTo("test");
    }

    @Test
    void execute_workerThrows_returnsFailed() {
        MockWorkerExecutor mockExecutor = new MockWorkerExecutor();
        Worker worker = TestWorkerBuilder.sync("throws",
            input -> { throw new RuntimeException("mock failure"); });

        WorkerResult result = mockExecutor.execute(worker, Map.of());
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(((WorkerOutcome.Failed) result.outcome()).reason()).isEqualTo("mock failure");
        assertThat(mockExecutor.executionCount()).isEqualTo(1);
    }

    @Test
    void execute_workerThrowsNullMessage_returnsFailedWithClassName() {
        MockWorkerExecutor mockExecutor = new MockWorkerExecutor();
        Worker worker = TestWorkerBuilder.sync("npe",
            input -> { throw new NullPointerException(); });

        WorkerResult result = mockExecutor.execute(worker, Map.of());
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(((WorkerOutcome.Failed) result.outcome()).reason())
            .isEqualTo("java.lang.NullPointerException");
    }
}
