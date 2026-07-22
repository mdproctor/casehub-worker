package io.casehub.worker.api;

import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerTest {

    @Test
    void syncWorker_executesFunction() {
        Worker worker = Worker.builder()
                              .name("test-worker")
                              .capabilityName("process")
                              .function(input -> WorkerResult.of(Map.of("result", "done")))
                              .build();

        assertThat(worker.name()).isEqualTo("test-worker");
        assertThat(worker.capabilityNames()).containsExactly("process");

        WorkerFunction.Sync<?, ?> sync   = (WorkerFunction.Sync<?, ?>) worker.function();
        var result = ((WorkerFunction.Sync<Map<String, Object>, Map<String, Object>>) worker.function()).fn().apply(Map.of(), null);
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat((Map<String, Object>) result.output()).containsEntry("result", "done");
    }

    @Test
    void capabilityNames_isUnmodifiableSet() {
        Worker worker = Worker.builder()
                              .name("w")
                              .capabilityNames("a", "b", "c")
                              .function(input -> WorkerResult.of(Map.of()))
                              .build();

        assertThat(worker.capabilityNames()).containsExactlyInAnyOrder("a", "b", "c");
        assertThatThrownBy(() -> worker.capabilityNames().add("d"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void capabilityNames_fromCollection() {
        Worker worker = Worker.builder()
                              .name("w")
                              .capabilityNames(java.util.List.of("x", "y"))
                              .function(input -> WorkerResult.of(Map.of()))
                              .build();

        assertThat(worker.capabilityNames()).containsExactlyInAnyOrder("x", "y");
    }

    @Test
    void capabilityNames_rejectsNull() {
        assertThatThrownBy(() -> Worker.builder()
                                       .name("w")
                                       .capabilityNames((java.util.Collection<String>) null)
                                       .function(input -> WorkerResult.of(Map.of()))
                                       .build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void noFunction_builder_creates_worker_with_None() {
        Worker worker = Worker.builder()
            .name("external-worker")
            .capabilityName("dispatch")
            .noFunction()
            .build();

        assertThat(worker.function()).isSameAs(WorkerFunction.NONE);
        assertThat(worker.function()).isInstanceOf(WorkerFunction.None.class);
    }

    @Test
    void worker_defaultExecutionPolicy() {
        Worker worker = Worker.builder()
                              .name("default-policy")
                              .capabilityName("test")
                              .function(input -> WorkerResult.of(Map.of()))
                              .build();

        assertThat(worker.executionPolicy()).isNotNull();
        assertThat(worker.executionPolicy().retries().maxAttempts()).isEqualTo(3);
    }

    @Test
    void worker_customExecutionPolicy() {
        ExecutionPolicy policy = new ExecutionPolicy(5000,
                                                     new RetryPolicy(5, 500, BackoffStrategy.EXPONENTIAL));

        Worker worker = Worker.builder()
                              .name("custom-policy")
                              .capabilityName("test")
                              .function(input -> WorkerResult.of(Map.of()))
                              .executionPolicy(policy)
                              .build();

        assertThat(worker.executionPolicy().timeoutMs()).isEqualTo(5000);
        assertThat(worker.executionPolicy().retries().maxAttempts()).isEqualTo(5);
    }

    @Test
    void workerResult_factoryMethods() {
        var success = WorkerResult.of(Map.of("key", "value"));
        assertThat(success.outcome()).isInstanceOf(WorkerOutcome.Success.class);

        var declined = WorkerResult.declined("not my job");
        assertThat(declined.outcome()).isInstanceOf(WorkerOutcome.Declined.class);

        var failed = WorkerResult.failed("broken");
        assertThat(failed.outcome()).isInstanceOf(WorkerOutcome.Failed.class);

        var expired = WorkerResult.expired("too slow");
        assertThat(expired.outcome()).isInstanceOf(WorkerOutcome.Expired.class);
    }

    @Test
    void capability_withDescription() {
        Capability cap = Capability.builder()
            .name("analyse")
            .inputSchema("{\"type\":\"object\"}")
            .outputSchema("{\"type\":\"object\"}")
            .description("Analyses input data")
            .build();

        assertThat(cap.name()).isEqualTo("analyse");
        assertThat(cap.description()).isEqualTo("Analyses input data");
    }

    @Test
    void success_withoutAction_hasNullPlannedAction() {
        WorkerOutcome outcome = WorkerOutcome.success();
        assertThat(outcome).isInstanceOf(WorkerOutcome.Success.class);
        assertThat(((WorkerOutcome.Success) outcome).plannedAction()).isNull();
    }

    @Test
    void success_withAction_carriesPlannedAction() {
        PlannedAction action = PlannedAction.of("File SAR", "sar.file");
        WorkerOutcome outcome = WorkerOutcome.success(action);
        assertThat(outcome).isInstanceOf(WorkerOutcome.Success.class);
        assertThat(((WorkerOutcome.Success) outcome).plannedAction()).isSameAs(action);
    }

    @Test
    void success_withNullAction_rejected() {
        assertThatThrownBy(() -> WorkerOutcome.success(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void workerResult_ofWithAction_createsSuccessWithPlannedAction() {
        PlannedAction action = PlannedAction.of("File SAR", "sar.file", Map.of("accountId", "ACC-123"));
        var result = WorkerResult.of(Map.of("key", "value"), action);
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        WorkerOutcome.Success success = (WorkerOutcome.Success) result.outcome();
        assertThat(success.plannedAction()).isSameAs(action);
        assertThat(result.output()).containsEntry("key", "value");
    }

    @Test
    void workerResult_ofWithNullAction_rejected() {
        assertThatThrownBy(() -> WorkerResult.of(Map.of(), (PlannedAction) null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void workerResult_declinedWithPartialOutput() {
        Map<String, Object> partial = Map.of("progress", "50%");
        var result = WorkerResult.declined("not my job", partial);
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Declined.class);
        assertThat(((WorkerOutcome.Declined) result.outcome()).reason()).isEqualTo("not my job");
        assertThat(result.output()).isEqualTo(partial);
    }

    @Test
    void workerResult_failedWithPartialOutput() {
        Map<String, Object> partial = Map.of("step", "validation");
        var result = WorkerResult.failed("broken", partial);
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(((WorkerOutcome.Failed) result.outcome()).reason()).isEqualTo("broken");
        assertThat(result.output()).isEqualTo(partial);
    }

    @Test
    void workerResult_expiredWithPartialOutput() {
        Map<String, Object> partial = Map.of("elapsed", "30s");
        var result = WorkerResult.expired("too slow", partial);
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Expired.class);
        assertThat(((WorkerOutcome.Expired) result.outcome()).reason()).isEqualTo("too slow");
        assertThat(result.output()).isEqualTo(partial);
    }

    @Test
    void workerResult_declinedWithNullPartialOutput_allowed() {
        var result = WorkerResult.declined("reason", null);
        assertThat(result.output()).isNull();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Declined.class);
    }

    @Test
    void workerResult_failedWithNullPartialOutput_allowed() {
        var result = WorkerResult.failed("reason", null);
        assertThat(result.output()).isNull();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
    }

    @Test
    void workerResult_expiredWithNullPartialOutput_allowed() {
        var result = WorkerResult.expired("reason", null);
        assertThat(result.output()).isNull();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Expired.class);
    }

    @Test
    void fnApplyCreatesTypedSyncFunction() {
        record TestInput(String value) {}

        Worker worker = Worker.builder()
                              .name("test")
                              .capabilityName("cap")
                              .<TestInput>fn()
                              .apply(input -> WorkerResult.of(Map.of("v", input.value())))
                              .build();

        assertThat(worker.function()).isInstanceOf(WorkerFunction.Sync.class);
        WorkerFunction.Sync<?, ?> sync = (WorkerFunction.Sync<?, ?>) worker.function();
        assertThat(sync.inputType()).isEqualTo(TestInput.class);
    }

    @Test
    void fnApplyWithMapTypeResolvesMapClass() {
        Worker worker = Worker.builder()
                              .name("test")
                              .capabilityName("cap")
                              .<Map<String, Object>>fn()
                              .apply(input -> WorkerResult.of(input))
                              .build();

        WorkerFunction.Sync<?, ?> sync = (WorkerFunction.Sync<?, ?>) worker.function();
        assertThat(sync.inputType()).isEqualTo(Map.class);
    }

    @Test
    void legacyFunctionStillWorks() {
        Worker worker = Worker.builder()
                              .name("test")
                              .capabilityName("cap")
                              .function(input -> WorkerResult.of(input))
                              .build();

        WorkerFunction.Sync<?, ?> sync = (WorkerFunction.Sync<?, ?>) worker.function();
        assertThat(sync.inputType()).isEqualTo(Map.class);
    }

}
