package io.casehub.worker.runtime;

import io.casehub.platform.governance.PolicyEnforcer;
import io.casehub.platform.governance.TimeoutPolicyException;
import io.casehub.platform.governance.InterruptedPolicyException;
import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerFunction;
import io.casehub.worker.api.WorkerResult;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class DefaultWorkerExecutor implements WorkerExecutor {

    private static final String INSTRUMENTATION_NAME = "io.casehub.worker";

    private final PolicyEnforcer policyEnforcer;

    @Inject
    public DefaultWorkerExecutor(PolicyEnforcer policyEnforcer) {
        this.policyEnforcer = policyEnforcer;
    }

    @Override
    public WorkerResult execute(Worker worker, Capability capability, Map<String, Object> input) {
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

        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME)
            .spanBuilder("worker.execute")
            .setAttribute(AttributeKey.stringKey("worker.name"), worker.name())
            .setAttribute(AttributeKey.stringKey("worker.capability"), capability.name())
            .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            WorkerResult result = policyEnforcer.execute(
                worker.executionPolicy(),
                () -> sync.fn().apply(input));
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
            Throwable root = e.getCause() != null ? e.getCause() : e;
            String message = root.getMessage();
            if (message == null) message = root.getClass().getName();
            WorkerResult result = WorkerResult.failed(message);
            span.setAttribute(AttributeKey.stringKey("worker.outcome"),
                result.outcome().getClass().getSimpleName());
            return result;
        } finally {
            span.end();
        }
    }
}
