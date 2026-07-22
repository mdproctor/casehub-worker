package io.casehub.worker.testing;

import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockWorkerExecutorTest {
    record TestPojo(String name, int age) {}


    private static Capability cap(String name) {
        return Capability.of(name, "{}", "{}");
    }

    @Test
    void execute_bypassesPolicyEnforcement() {
        MockWorkerExecutor executor = new MockWorkerExecutor();
        var wc = TestWorkerBuilder.syncWithCapability("test",
                                                      input -> WorkerResult.of(Map.of("ok", true)));

        WorkerResult result = executor.execute(wc.worker(), wc.capability(), Map.of())
                                      .await().indefinitely();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat(executor.executionCount()).isEqualTo(1);
        assertThat(executor.lastWorkerName()).isEqualTo("test");
        assertThat(executor.lastCapabilityName()).isEqualTo("test");
    }

    @Test
    void execute_workerThrows_returnsFailed() {
        MockWorkerExecutor executor = new MockWorkerExecutor();
        Worker worker = TestWorkerBuilder.sync("throws",
                                               input -> {throw new RuntimeException("mock failure");});

        WorkerResult result = executor.execute(worker, cap("throws"), Map.of())
                                      .await().indefinitely();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(((WorkerOutcome.Failed) result.outcome()).reason()).isEqualTo("mock failure");
        assertThat(executor.executionCount()).isEqualTo(1);
    }

    @Test
    void execute_workerThrowsNullMessage_returnsFailedWithClassName() {
        MockWorkerExecutor executor = new MockWorkerExecutor();
        Worker worker = TestWorkerBuilder.sync("npe",
                                               input -> {throw new NullPointerException();});

        WorkerResult result = executor.execute(worker, cap("npe"), Map.of())
                                      .await().indefinitely();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(((WorkerOutcome.Failed) result.outcome()).reason())
                .isEqualTo("java.lang.NullPointerException");
    }

    @Test
    void execute_tracksCapabilityName() {
        MockWorkerExecutor executor = new MockWorkerExecutor();
        var wc = TestWorkerBuilder.syncWithCapability("worker",
                                                      input -> WorkerResult.of(Map.of()));

        executor.execute(wc.worker(), wc.capability(), Map.of()).await().indefinitely();
        assertThat(executor.lastCapabilityName()).isEqualTo("worker");
    }

    @Test
    void reset_clearsCapabilityName() {
        MockWorkerExecutor executor = new MockWorkerExecutor();
        var wc = TestWorkerBuilder.syncWithCapability("worker",
                                                      input -> WorkerResult.of(Map.of()));

        executor.execute(wc.worker(), wc.capability(), Map.of()).await().indefinitely();
        assertThat(executor.lastCapabilityName()).isEqualTo("worker");

        executor.reset();
        assertThat(executor.lastCapabilityName()).isNull();
        assertThat(executor.lastWorkerName()).isNull();
        assertThat(executor.executionCount()).isZero();
    }

    @Test
    void execute_capabilityNotInWorker_throwsIAE() {
        MockWorkerExecutor executor = new MockWorkerExecutor();
        Worker worker = TestWorkerBuilder.sync("w",
            input -> WorkerResult.of(Map.of()));

        assertThatThrownBy(() -> executor.execute(worker, cap("wrong"), Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("wrong");
    }

    @Test
    void execute_nullCapability_throwsNPE() {
        MockWorkerExecutor executor = new MockWorkerExecutor();
        Worker worker = TestWorkerBuilder.sync("w",
            input -> WorkerResult.of(Map.of()));

        assertThatThrownBy(() -> executor.execute(worker, null, Map.of()))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("capability");
    }

    @Test
    void execute_nonSyncOrAsyncFunction_throwsUnsupported() {
        MockWorkerExecutor executor = new MockWorkerExecutor();
        Worker worker = Worker.builder()
                              .name("external").capabilityName("ext")
                              .noFunction()
                              .build();

        assertThatThrownBy(() -> executor.execute(worker, cap("ext"), Map.of()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Sync and Async");
    }

    @Test
    void execute_typedPojoInput_passedToFunction() {
        MockWorkerExecutor executor = new MockWorkerExecutor();
        Worker worker = Worker.builder()
                              .name("typed").capabilityName("process")
                              .<TestPojo>fn().apply(pojo -> WorkerResult.of(Map.of("greeting", "hello " + pojo.name())))
                              .build();

        var result = executor.execute(worker, cap("process"), new TestPojo("alice", 30))
                             .await().indefinitely();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat((Map<String, Object>) result.output()).containsEntry("greeting", "hello alice");
    }

    @Test
    void execute_inputTypeMismatch_throwsIAE() {
        MockWorkerExecutor executor = new MockWorkerExecutor();
        Worker worker = Worker.builder()
                              .name("typed").capabilityName("process")
                              .<TestPojo>fn().apply(pojo -> WorkerResult.of(Map.of()))
                              .build();

        assertThatThrownBy(() -> executor.execute(worker, cap("process"), Map.of("name", "alice")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TestPojo");
    }

}
