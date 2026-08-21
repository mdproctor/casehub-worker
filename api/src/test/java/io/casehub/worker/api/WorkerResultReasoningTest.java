package io.casehub.worker.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerResultReasoningTest {

    @Test
    void reasoningDefaultsToNull() {
        var result = WorkerResult.of("output");
        assertThat(result.reasoning()).isNull();
    }

    @Test
    void withReasoningAttachesReasoning() {
        var result = WorkerResult.of("output").withReasoning("because X");
        assertThat(result.reasoning()).isEqualTo("because X");
        assertThat(result.output()).isEqualTo("output");
    }

    @Test
    void withReasoningPreservesOutcome() {
        var result = WorkerResult.<String>declined("scope mismatch")
                .withReasoning("detailed analysis...");
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Declined.class);
        assertThat(result.reasoning()).isEqualTo("detailed analysis...");
    }

    @Test
    void twoArgConstructorBackwardCompatible() {
        var result = new WorkerResult<>("output", WorkerOutcome.success());
        assertThat(result.reasoning()).isNull();
        assertThat(result.output()).isEqualTo("output");
    }

    @Test
    void threeArgConstructorCarriesReasoning() {
        var result = new WorkerResult<>("output", WorkerOutcome.success(), "reasoning");
        assertThat(result.reasoning()).isEqualTo("reasoning");
    }

    @Test
    void withReasoningReturnsNewInstance() {
        var original = WorkerResult.of("output");
        var withReasoning = original.withReasoning("why");
        assertThat(withReasoning).isNotSameAs(original);
        assertThat(original.reasoning()).isNull();
    }

    @Test
    void allFactoryMethodsDefaultToNullReasoning() {
        assertThat(WorkerResult.of("out").reasoning()).isNull();
        assertThat(WorkerResult.of("out", new PlannedAction("do", "thing", java.util.Map.of())).reasoning()).isNull();
        assertThat(WorkerResult.<String>declined("r").reasoning()).isNull();
        assertThat(WorkerResult.declined("r", "partial").reasoning()).isNull();
        assertThat(WorkerResult.<String>failed("r").reasoning()).isNull();
        assertThat(WorkerResult.failed("r", "partial").reasoning()).isNull();
        assertThat(WorkerResult.<String>expired("r").reasoning()).isNull();
        assertThat(WorkerResult.expired("r", "partial").reasoning()).isNull();
        assertThat(WorkerResult.completed("out").reasoning()).isNull();
    }
}
