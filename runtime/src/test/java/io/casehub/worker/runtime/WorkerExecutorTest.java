package io.casehub.worker.runtime;

import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerExecutorTest {
    private static final String REQUIRE_NAME_SCHEMA = """
                                                      {
                                                        "type": "object",
                                                        "properties": {
                                                          "name": { "type": "string" }
                                                        },
                                                        "required": ["name"]
                                                      }""";
    private static final String REQUIRE_RESULT_SCHEMA = """
                                                        {
                                                          "type": "object",
                                                          "properties": {
                                                            "result": { "type": "number" }
                                                          },
                                                          "required": ["result"]
                                                        }""";
    private final DefaultWorkerExecutor executor =
            new DefaultWorkerExecutor(new SchemaValidator());

    private static Capability cap(String name) {
        return Capability.of(name, "{}", "{}");
    }

    private static Capability cap(String name, String inputSchema, String outputSchema) {
        return Capability.of(name, inputSchema, outputSchema);
    }

    @Test
    void execute_successfulWorker() {
        Worker worker = Worker.builder()
                              .name("greet").capabilityName("greet")
                              .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) -> WorkerResult.of(Map.of("greeting", "hello " + input.get("name")))))
                              .build();

        var result = executor.execute(worker, cap("greet"), Map.of("name", "world")).await().indefinitely();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat((Map<String, Object>) result.output()).containsEntry("greeting", "hello world");
    }

    @Test
    void execute_retriesTransientFailures() {
        AtomicInteger attempts = new AtomicInteger(0);
        Worker worker = Worker.builder()
                              .name("flaky").capabilityName("process")
                              .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) -> {
                                  if (attempts.incrementAndGet() < 3) {
                                      throw new RuntimeException("transient");
                                  }
                                  return WorkerResult.of(Map.of("recovered", true));
                              }))
                              .executionPolicy(new ExecutionPolicy(null, new RetryPolicy(3, 10)))
                              .build();

        var result = executor.execute(worker, cap("process"), Map.of()).await().indefinitely();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void execute_exhaustsRetries_returnsFailed() {
        Worker worker = Worker.builder()
                              .name("broken").capabilityName("fail")
                              .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) -> {
                                  throw new RuntimeException("permanent");
                              }))
                              .executionPolicy(new ExecutionPolicy(null, new RetryPolicy(2, 10)))
                              .build();

        var result = executor.execute(worker, cap("fail"), Map.of()).await().indefinitely();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(((WorkerOutcome.Failed<?>) result.outcome()).reason()).isEqualTo("permanent");
    }

    @Test
    void execute_workerThrows_returnsFailed() {
        Worker worker = Worker.builder()
                              .name("throws").capabilityName("boom")
                              .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) -> {
                                  throw new IllegalStateException("bad state");
                              }))
                              .build();

        var result = executor.execute(worker, cap("boom"), Map.of()).await().indefinitely();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(((WorkerOutcome.Failed<?>) result.outcome()).reason()).isEqualTo("bad state");
    }

    @Test
    void execute_timeout_returnsExpired() {
        Worker worker = Worker.builder()
                              .name("slow").capabilityName("crawl")
                              .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) -> {
                                  try {Thread.sleep(500);} catch (InterruptedException e) {
                                      Thread.currentThread().interrupt();
                                  }
                                  return WorkerResult.of(Map.of());
                              }))
                              .executionPolicy(new ExecutionPolicy(50, new RetryPolicy(1, 0)))
                              .build();

        var result = executor.execute(worker, cap("crawl"), Map.of()).await().indefinitely();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Expired.class);
        assertThat(((WorkerOutcome.Expired) result.outcome()).reason()).contains("timed out");
    }

    @Test
    void execute_workerThrowsNullMessage_returnsFailedWithClassName() {
        Worker worker = Worker.builder()
                              .name("npe").capabilityName("null")
                              .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) -> {
                                  throw new NullPointerException();
                              }))
                              .build();

        var result = executor.execute(worker, cap("null"), Map.of()).await().indefinitely();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(((WorkerOutcome.Failed<?>) result.outcome()).reason()).isEqualTo("java.lang.NullPointerException");
    }

    @Test
    void execute_nullCapability_throwsNPE() {
        Worker worker = Worker.builder()
                              .name("w").capabilityName("c")
                              .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) -> WorkerResult.of(Map.of())))
                              .build();

        assertThatThrownBy(() -> executor.execute(worker, null, Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("capability");
    }

    // --- Validation tests ---

    @Test
    void execute_capabilityNotInWorker_throwsIAE() {
        Worker worker = Worker.builder()
                              .name("w").capabilityName("supported")
                              .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) -> WorkerResult.of(Map.of())))
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
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Input type mismatch");
    }

    @Test
    void execute_invalidInput_returnsFailed_functionNeverCalled() {
        AtomicInteger callCount = new AtomicInteger(0);
        Worker worker = Worker.builder()
                              .name("strict").capabilityName("validate")
                              .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) -> {
                                  callCount.incrementAndGet();
                                  return WorkerResult.of(Map.of());
                              }))
                              .build();

        var result = executor.execute(worker,
                                               cap("validate", REQUIRE_NAME_SCHEMA, "{}"),
                                               Map.of("age", 30)).await().indefinitely();

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(((WorkerOutcome.Failed<?>) result.outcome()).reason()).contains("name");
        assertThat(callCount.get()).isZero();
    }

    // --- Schema validation tests ---

    @Test
    void execute_validInput_invalidOutput_returnsSuccessWithWarning() {
        Worker worker = Worker.builder()
                              .name("bad-output").capabilityName("compute")
                              .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) ->
                                                                                     WorkerResult.of(Map.of("result", "not-a-number"))))
                              .build();

        var result = executor.execute(worker,
                                               cap("compute", "{}", REQUIRE_RESULT_SCHEMA),
                                               Map.of()).await().indefinitely();

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat((Map<String, Object>) result.output()).containsEntry("result", "not-a-number");
    }

    @Test
    void execute_malformedSchema_throwsIAE() {
        Worker worker = Worker.builder()
                              .name("broken").capabilityName("bad")
                              .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) -> WorkerResult.of(Map.of())))
                              .build();

        assertThatThrownBy(() -> executor.execute(worker,
                                                  cap("bad", "not valid json", "{}"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void execute_declinedWithPartialOutput_noOutputValidation() {
        Worker worker = Worker.builder()
                              .name("decliner").capabilityName("dec")
                              .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) ->
                                                                                     WorkerResult.declined("nope", Map.of("partial", "data"))))
                              .build();

        var result = executor.execute(worker,
                                               cap("dec", "{}", REQUIRE_RESULT_SCHEMA),
                                               Map.of()).await().indefinitely();

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Declined.class);
        assertThat((Map<String, Object>) result.output()).containsEntry("partial", "data");
    }

    @Test
    void execute_failedWithPartialOutput_noOutputValidation() {
        Worker worker = Worker.builder()
                              .name("failer").capabilityName("fail")
                              .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) ->
                                                                                     WorkerResult.failed("error", Map.of("partial", "data"))))
                              .build();

        var result = executor.execute(worker,
                                               cap("fail", "{}", REQUIRE_RESULT_SCHEMA),
                                               Map.of()).await().indefinitely();

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat((Map<String, Object>) result.output()).containsEntry("partial", "data");
    }

    @Test
    void execute_emptySchemas_noValidation() {
        Worker worker = Worker.builder()
                              .name("legacy").capabilityName("old")
                              .function(new WorkerFunction.Sync<>(Map.class, Map.class, (input, scope) ->
                                                                                     WorkerResult.of(Map.of("anything", "goes"))))
                              .build();

        var result = executor.execute(worker, cap("old"), Map.of("random", 42)).await().indefinitely();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
    }

    @Test
    void schemaValidator_validatesPojoInput() {
        SchemaValidator validator = new SchemaValidator();
        Capability      cap       = cap("test", REQUIRE_NAME_SCHEMA, "{}");
        validator.ensureSchemaParsed(cap.inputSchema());

        Optional<String> valid = validator.validateInput(cap, new TestPojo("alice", 30));
        assertThat(valid).isEmpty();

        Optional<String> invalid = validator.validateInput(cap, new TestPojo(null, 30));
        assertThat(invalid).isPresent();
    }

    @Test
    void execute_typedPojoInput_passedToFunction() {
        Worker worker = Worker.builder()
                              .name("typed").capabilityName("process")
                              .<TestPojo>fn().apply(pojo -> WorkerResult.of(Map.of("greeting", "hello " + pojo.name())))
                              .build();

        var result = executor.execute(worker, cap("process"), new TestPojo("alice", 30)).await().indefinitely();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat((Map<String, Object>) result.output()).containsEntry("greeting", "hello alice");
    }

    @Test
    void execute_inputTypeMismatch_throwsIAE() {
        Worker worker = Worker.builder()
                              .name("typed").capabilityName("process")
                              .<TestPojo>fn().apply(pojo -> WorkerResult.of(Map.of()))
                              .build();

        assertThatThrownBy(() -> executor.execute(worker, cap("process"), Map.of("name", "alice")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TestPojo")
                .hasMessageContaining("Map");
    }

    @Test
    void execute_nullInput_throwsIAE() {
        Worker worker = Worker.builder()
                              .name("typed").capabilityName("process")
                              .<TestPojo>fn().apply(pojo -> WorkerResult.of(Map.of()))
                              .build();

        assertThatThrownBy(() -> executor.execute(worker, cap("process"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void execute_syncWorker_completesSuccessfully() {
        Worker worker = Worker.builder()
                              .name("sync").capabilityName("fetch")
                              .function(input -> WorkerResult.of(Map.of("fetched", true)))
                              .build();

        var result = executor.execute(worker, cap("fetch"), Map.of())
                                      .await().indefinitely();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat((Map<String, Object>) result.output()).containsEntry("fetched", true);
    }

    @Test
    void execute_syncWorker_exceptionInFn_returnsFailed() {
        Worker worker = Worker.builder()
                              .name("failing").capabilityName("fail")
                              .function(input -> { throw new RuntimeException("sync boom"); })
                              .build();

        var result = executor.execute(worker, cap("fail"), Map.of())
                                      .await().indefinitely();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(((WorkerOutcome.Failed<?>) result.outcome()).reason()).isEqualTo("sync boom");
    }

    @Test
    void execute_syncWorker_throwsDuringDispatch_returnsFailed() {
        Worker worker = Worker.builder()
                              .name("dispatch-fail").capabilityName("boom")
                              .function(input -> {throw new IllegalStateException("dispatch error");})
                              .build();

        var result = executor.execute(worker, cap("boom"), Map.of())
                                      .await().indefinitely();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(((WorkerOutcome.Failed<?>) result.outcome()).reason()).isEqualTo("dispatch error");
    }

    @Test
    void execute_typedWorker_passesTypedInput() {
        Worker worker = Worker.builder()
                              .name("typed").capabilityName("process")
                              .<TestPojo>fn()
                              .apply(pojo -> WorkerResult.of(Map.of("greeting", "hello " + pojo.name())))
                              .build();

        var result = executor.execute(worker, cap("process"),
                                      new TestPojo("alice", 30)).await().indefinitely();
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
        assertThat((Map<String, Object>) result.output()).containsEntry("greeting", "hello alice");
    }

    @Test
    void execute_typedWorker_inputTypeMismatch_throwsIAE() {
        Worker worker = Worker.builder()
                              .name("typed").capabilityName("process")
                              .<TestPojo>fn()
                              .apply(pojo -> WorkerResult.of(Map.of()))
                              .build();

        assertThatThrownBy(() -> executor.execute(worker, cap("process"), Map.of("name", "alice")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TestPojo");
    }

    @Test
    void execute_asyncWorker_schemaValidatesInput() {
        AtomicInteger callCount = new AtomicInteger(0);
        Worker worker = Worker.builder()
                              .name("strict-async").capabilityName("validate")
                              .function(input -> {
                                  callCount.incrementAndGet();
                                  return WorkerResult.of(Map.of());
                              })
                              .build();

        var result = executor.execute(worker,
                                               cap("validate", REQUIRE_NAME_SCHEMA, "{}"),
                                               Map.of("age", 30)).await().indefinitely();

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
        assertThat(((WorkerOutcome.Failed<?>) result.outcome()).reason()).contains("name");
        assertThat(callCount.get()).isZero();
    }


    record TestPojo(String name, int age) {}
}
