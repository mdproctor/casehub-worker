package io.casehub.worker.runtime;

import io.casehub.platform.api.governance.BackoffStrategy;
import io.casehub.platform.api.governance.ExecutionPolicy;
import io.casehub.platform.api.governance.RetryPolicy;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerOutcome;
import io.casehub.worker.api.WorkerResult;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.smallrye.faulttolerance.api.Guard;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@ApplicationScoped
public class DefaultWorkerExecutor implements WorkerExecutor {

    private static final String                   INSTRUMENTATION_NAME = "io.casehub.worker";
    private static final org.jboss.logging.Logger LOG                  =
            org.jboss.logging.Logger.getLogger(DefaultWorkerExecutor.class);

    private final SchemaValidator                                                                                                                  schemaValidator;
    private final ConcurrentHashMap<ExecutionPolicy, Guard> guardCache =
            new ConcurrentHashMap<>();

    @Inject
    public DefaultWorkerExecutor(SchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Uni<WorkerResult> execute(Worker worker, Capability capability, Object input) {
        Objects.requireNonNull(capability, "capability");
        if (!worker.capabilityNames().contains(capability.name())) {
            throw new IllegalArgumentException(
                    "Capability '" + capability.name() + "' not in worker '"
                    + worker.name() + "' capabilities: " + worker.capabilityNames());
        }

        WorkerFunction<?, ?> fn = worker.function();
        Class<?>             inputType = fn.inputType();
        if (!inputType.isInstance(input)) {
            throw new IllegalArgumentException(
                    "Input type mismatch: expected " + inputType.getName()
                    + ", got " + (input == null ? "null" : input.getClass().getName()));
        }

        schemaValidator.ensureSchemaParsed(capability.inputSchema());
        schemaValidator.ensureSchemaParsed(capability.outputSchema());

        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME)
                                                                                       .spanBuilder("worker.execute")
                                                                                       .setAttribute(AttributeKey.stringKey("worker.name"), worker.name())
                                                                                       .setAttribute(AttributeKey.stringKey("worker.capability"), capability.name())
                                                                                       .startSpan();

        try (Scope ignored = span.makeCurrent()) {
            Optional<String> inputError = schemaValidator.validateInput(capability, input);
            if (inputError.isPresent()) {
                span.addEvent("worker.input.invalid", Attributes.of(
                        AttributeKey.stringKey("validation.error"), inputError.get()));
                WorkerResult result = WorkerResult.failed(inputError.get());
                span.setAttribute(AttributeKey.stringKey("worker.outcome"), "Failed");
                span.end();
                return Uni.createFrom().item(result);
            }

            Uni<WorkerResult> action = liftToUni(worker, input);

            Guard guard = guardCache.computeIfAbsent(
                    worker.executionPolicy(), this::buildGuard);
            Uni<WorkerResult> guarded;
            try {
                guarded = guard.call(() -> action, Uni.class);
            } catch (RuntimeException e) {
                span.end();
                throw e;
            } catch (Exception e) {
                span.end();
                throw new RuntimeException(e);
            }

            return guarded
                           .map(result -> {
                               if (result.outcome() instanceof WorkerOutcome.Success) {
                                   Optional<String> outputError = schemaValidator.validateOutput(capability, result.output());
                                   outputError.ifPresent(err -> {
                                       span.addEvent("worker.output.invalid", Attributes.of(
                                               AttributeKey.stringKey("validation.error"), err));
                                       LOG.warnf("Output schema violation for worker '%s' capability '%s': %s",
                                                 worker.name(), capability.name(), err);
                                   });
                               }
                               return result;
                           })
                           .onFailure(org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException.class)
                           .recoverWithItem(e -> {
                               span.addEvent("worker.timeout", Attributes.of(
                                       AttributeKey.stringKey("timeout.message"), e.getMessage()));
                               return WorkerResult.expired(e.getMessage());
                           })
                           .onFailure().recoverWithItem(e -> {
                        span.setStatus(StatusCode.ERROR, e.getMessage());
                        span.recordException(e);
                        Throwable root    = e.getCause() != null ? e.getCause() : e;
                        String    message = root.getMessage();
                        if (message == null) {message = root.getClass().getName();}
                        return WorkerResult.failed(message);
                    })
                           .onTermination().invoke((result, failure, cancelled) -> {
                        if (result != null) {
                            span.setAttribute(AttributeKey.stringKey("worker.outcome"),
                                              result.outcome().getClass().getSimpleName());
                        }
                        span.end();
                    });
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Uni<WorkerResult> liftToUni(Worker worker, Object input) {
        if (worker.function() instanceof WorkerFunction.Sync sync) {
            return Uni.createFrom().item(() ->
                                                 (WorkerResult) ((java.util.function.BiFunction) sync.fn()).apply(input, null));
        }
        throw new UnsupportedOperationException(
                "Unsupported function type: " + worker.function().getClass().getName());
    }

    private Guard buildGuard(ExecutionPolicy policy) {
        var builder = Guard.create();
        if (policy.timeoutMs() != null) {
            builder.withTimeout().duration(policy.timeoutMs(), ChronoUnit.MILLIS).done();
        }
        RetryPolicy retry = policy.retries();
        if (retry != null && retry.maxAttempts() != null && retry.maxAttempts() > 1) {
            var rb = builder.withRetry()
                            .maxRetries(retry.maxAttempts() - 1)
                            .delay(retry.delayMs() != null ? retry.delayMs() : 0, ChronoUnit.MILLIS);
            if (retry.backoffStrategy() != null) {
                switch (retry.backoffStrategy()) {
                    case EXPONENTIAL -> {
                        var eb = rb.withExponentialBackoff();
                        if (retry.maxDelayMs() != null) {
                            eb.maxDelay(retry.maxDelayMs(), ChronoUnit.MILLIS);
                        }
                        eb.done();
                    }
                    case EXPONENTIAL_WITH_JITTER -> {
                        var eb = rb.withExponentialBackoff();
                        if (retry.maxDelayMs() != null) {
                            eb.maxDelay(retry.maxDelayMs(), ChronoUnit.MILLIS);
                        }
                        eb.done();
                        rb.jitter(retry.delayMs() != null ? retry.delayMs() : 200, ChronoUnit.MILLIS);
                    }
                    default -> {}
                }
            }
            rb.done();
        }
        return builder.build();
    }
}
