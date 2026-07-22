package io.casehub.worker.api;

import java.util.function.BiFunction;
import java.util.function.Function;

public class TypedOutputBuilder<T, R> {
    private final Worker.Builder parent;
    private final Class<?> runtimeInputType;
    private final Class<R> outputType;

    TypedOutputBuilder(Worker.Builder parent, Class<?> runtimeInputType, Class<R> outputType) {
        this.parent = parent;
        this.runtimeInputType = runtimeInputType;
        this.outputType = outputType;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Worker.Builder apply(BiFunction<T, WorkerScope, WorkerResult<R>> fn) {
        parent.setFunction(new WorkerFunction.Sync(runtimeInputType, outputType, fn));
        return parent;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Worker.Builder apply(Function<T, WorkerResult<R>> fn) {
        parent.setFunction(new WorkerFunction.Sync(runtimeInputType, outputType, (t, scope) -> fn.apply((T) t)));
        return parent;
    }
}
