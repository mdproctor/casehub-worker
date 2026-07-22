package io.casehub.worker.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerFunctionTest {

    @SuppressWarnings("unchecked")
    @Test
    void sync_is_workerFunction() {
        WorkerFunction<?, ?> fn = new WorkerFunction.Sync<>(
                Map.class, Map.class, (input, scope) -> WorkerResult.of(Map.of()));
        assertThat(fn).isInstanceOf(WorkerFunction.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void sync_fn_returns_bifunction() {
        var sync = new WorkerFunction.Sync<>(
                Map.class, Map.class, (Map input, WorkerScope scope) -> WorkerResult.of(Map.of("key", "value")));
        var result = sync.fn().apply(Map.of(), null);
        assertThat(result.output()).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) result.output()).containsEntry("key", "value");
    }

    @Test
    void workerFunction_declares_inputType_and_outputType() {
        assertThat(WorkerFunction.class.getDeclaredMethods())
                .extracting("name")
                .contains("inputType", "outputType");
    }

    @SuppressWarnings("unchecked")
    @Test
    void typedSyncCarriesInputAndOutputType() {
        var fn = new WorkerFunction.Sync<>(
                String.class, Integer.class,
                (String s, WorkerScope scope) -> WorkerResult.of(s.length()));
        assertThat(fn.inputType()).isEqualTo(String.class);
        assertThat(fn.outputType()).isEqualTo(Integer.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void untypedSyncDefaultsToMapClass() {
        var fn = new WorkerFunction.Sync<>(
                Map.class, Map.class, (input, scope) -> WorkerResult.of(Map.of()));
        assertThat(fn.inputType()).isEqualTo(Map.class);
        assertThat(fn.outputType()).isEqualTo(Map.class);
    }

    @Test
    void noneHasVoidInputAndOutputType() {
        assertThat(WorkerFunction.NONE.inputType()).isEqualTo(Void.class);
        assertThat(WorkerFunction.NONE.outputType()).isEqualTo(Void.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void syncRejectsNullInputType() {
        assertThatThrownBy(
                () -> new WorkerFunction.Sync<>(null, Map.class, (input, scope) -> WorkerResult.of(Map.of())))
                .isInstanceOf(NullPointerException.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void syncRejectsNullOutputType() {
        assertThatThrownBy(
                () -> new WorkerFunction.Sync<>(Map.class, null, (input, scope) -> WorkerResult.of(Map.of())))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void syncRejectsNullFunction() {
        assertThatThrownBy(
                () -> new WorkerFunction.Sync<>(String.class, String.class, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void none_is_workerFunction() {
        assertThat(WorkerFunction.NONE).isInstanceOf(WorkerFunction.class);
    }

    @Test
    void none_is_not_sync() {
        assertThat(WorkerFunction.NONE).isNotInstanceOf(WorkerFunction.Sync.class);
    }

    @Test
    void none_singleton_equals_new_instance() {
        assertThat(WorkerFunction.NONE).isEqualTo(new WorkerFunction.None());
    }

    @Test
    void builder_function_createsMapMapSync() {
        Worker worker = Worker.builder()
                              .name("w").capabilityName("cap")
                              .function(input -> WorkerResult.of(Map.of("done", true)))
                              .build();
        assertThat(worker.function()).isInstanceOf(WorkerFunction.Sync.class);
        assertThat(worker.function().inputType()).isEqualTo(Map.class);
        assertThat(worker.function().outputType()).isEqualTo(Map.class);
    }

    @Test
    void builder_typedFn_returning_createsTypedSync() {
        Worker worker = Worker.builder()
                              .name("w").capabilityName("cap")
                              .<String>fn()
                              .returning(Integer.class)
                              .apply(s -> WorkerResult.of(s.length()))
                              .build();
        assertThat(worker.function().inputType()).isEqualTo(String.class);
        assertThat(worker.function().outputType()).isEqualTo(Integer.class);
    }

    @Test
    void builder_typedFn_apply_defaultsOutputToMap() {
        Worker worker = Worker.builder()
                              .name("w").capabilityName("cap")
                              .<String>fn()
                              .apply(s -> WorkerResult.of(Map.of("len", s.length())))
                              .build();
        assertThat(worker.function().inputType()).isEqualTo(String.class);
        assertThat(worker.function().outputType()).isEqualTo(Map.class);
    }

    @Test
    void builder_typedFn_returning_bifunction_with_scope() {
        Worker worker = Worker.builder()
                              .name("w").capabilityName("cap")
                              .<String>fn()
                              .returning(Integer.class)
                              .apply((s, scope) -> WorkerResult.of(s.length()))
                              .build();
        assertThat(worker.function().inputType()).isEqualTo(String.class);
        assertThat(worker.function().outputType()).isEqualTo(Integer.class);
    }
}
