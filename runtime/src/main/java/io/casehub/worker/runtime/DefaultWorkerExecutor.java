package io.casehub.worker.runtime;

import io.casehub.platform.governance.PolicyEnforcer;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class DefaultWorkerExecutor implements WorkerExecutor {

    private static final String INSTRUMENTATION_NAME = "io.casehub.worker";

    private final PolicyEnforcer policyEnforcer;

    @Inject
    public DefaultWorkerExecutor(PolicyEnforcer policyEnforcer) {
        this.policyEnforcer = policyEnforcer;
    }

    @Override
    public WorkerResult execute(Worker worker, Map<String, Object> input) {
        Span span = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME)
            .spanBuilder("worker.execute")
            .setAttribute(AttributeKey.stringKey("worker.name"), worker.name())
            .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            WorkerResult result = policyEnforcer.execute(
                worker.executionPolicy(),
                () -> worker.function().execute(input));
            span.setAttribute(AttributeKey.stringKey("worker.outcome"),
                result.outcome().getClass().getSimpleName());
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
