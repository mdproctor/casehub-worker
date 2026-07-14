package io.casehub.worker.runtime;

import io.casehub.platform.governance.InterruptedPolicyException;
import io.casehub.platform.governance.PolicyEnforcer;
import io.casehub.platform.governance.TimeoutPolicyException;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Objects;
import java.util.Optional;

@ApplicationScoped
public class DefaultWorkerExecutor implements WorkerExecutor {

    private static final String INSTRUMENTATION_NAME = "io.casehub.worker";
    private static final org.jboss.logging.Logger LOG =
        org.jboss.logging.Logger.getLogger(DefaultWorkerExecutor.class);

    private final PolicyEnforcer policyEnforcer;
    private final SchemaValidator schemaValidator;

    @Inject
    public DefaultWorkerExecutor(PolicyEnforcer policyEnforcer, SchemaValidator schemaValidator) {
        this.policyEnforcer = policyEnforcer;
        this.schemaValidator = schemaValidator;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public WorkerResult execute(Worker worker, Capability capability, Object input) {
        Objects.requireNonNull(capability, "capability");
        if (!worker.capabilityNames().contains(capability.name())) {
            throw new IllegalArgumentException(
                    "Capability '" + capability.name() + "' not in worker '"
                    + worker.name() + "' capabilities: " + worker.capabilityNames());
        }
        if (!(worker.function() instanceof WorkerFunction.Sync sync)) {
            throw new UnsupportedOperationException(
                    "DefaultWorkerExecutor supports Sync functions only, got: "
                    + worker.function().getClass().getName());
        }
        if (!sync.inputType().isInstance(input)) {
            throw new IllegalArgumentException(
                    "Input type mismatch: expected " + sync.inputType().getName()
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
                span.setAttribute(AttributeKey.stringKey("worker.outcome"),
                                  result.outcome().getClass().getSimpleName());
                return result;
            }

            WorkerResult result = policyEnforcer.execute(
                    worker.executionPolicy(),
                    () -> (WorkerResult) ((java.util.function.Function) sync.fn()).apply(input));

            if (result.outcome() instanceof WorkerOutcome.Success) {
                Optional<String> outputError = schemaValidator.validateOutput(capability, result.output());
                if (outputError.isPresent()) {
                    span.addEvent("worker.output.invalid", Attributes.of(
                            AttributeKey.stringKey("validation.error"), outputError.get()));
                    LOG.warnf("Output schema violation for worker '%s' capability '%s': %s",
                              worker.name(), capability.name(), outputError.get());
                }
            }

            span.setAttribute(AttributeKey.stringKey("worker.outcome"),
                              result.outcome().getClass().getSimpleName());
            return result;
        } catch (TimeoutPolicyException e) {
            span.addEvent("worker.timeout", Attributes.of(
                    AttributeKey.stringKey("timeout.message"), e.getMessage()));
            WorkerResult result = WorkerResult.expired(e.getMessage());
            span.setAttribute(AttributeKey.stringKey("worker.outcome"),
                              result.outcome().getClass().getSimpleName());
            return result;
        } catch (InterruptedPolicyException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            Throwable root    = e.getCause() != null ? e.getCause() : e;
            String    message = root.getMessage();
            if (message == null) {message = root.getClass().getName();}
            WorkerResult result = WorkerResult.failed(message);
            span.setAttribute(AttributeKey.stringKey("worker.outcome"),
                              result.outcome().getClass().getSimpleName());
            return result;
        } finally {
            span.end();
        }
    }
}
