package io.casehub.worker.api;

public class TypedFunctionBuilder<T> {
    private final Worker.Builder parent;
    private final Class<?>       runtimeType;

    TypedFunctionBuilder(Worker.Builder parent, Class<?> runtimeType) {
        this.parent      = parent;
        this.runtimeType = runtimeType;
    }

    public <R> TypedOutputBuilder<T, R> returning(Class<R> outputType) {
        return new TypedOutputBuilder<>(parent, runtimeType, outputType);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Worker.Builder apply(java.util.function.Function<T, WorkerResult<java.util.Map<String, Object>>> fn) {
        parent.setFunction(new WorkerFunction.Sync(runtimeType, java.util.Map.class, (t, scope) -> fn.apply((T) t)));
        return parent;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Worker.Builder apply(java.util.function.BiFunction<T, WorkerScope, WorkerResult<java.util.Map<String, Object>>> fn) {
        parent.setFunction(new WorkerFunction.Sync(runtimeType, java.util.Map.class, fn));
        return parent;
    }
}
