package io.casehub.worker.api;

public interface WorkerFunction<T, R> {

    WorkerFunction<Void, Void> NONE = new None();

    Class<T> inputType();

    Class<R> outputType();

    record Sync<T, R>(Class<T> inputType, Class<R> outputType,
                      java.util.function.BiFunction<T, WorkerScope, WorkerResult<R>> fn)
            implements WorkerFunction<T, R> {
        public Sync {
            java.util.Objects.requireNonNull(inputType, "inputType must not be null");
            java.util.Objects.requireNonNull(outputType, "outputType must not be null");
            java.util.Objects.requireNonNull(fn, "fn must not be null");
        }
    }

    record None() implements WorkerFunction<Void, Void> {
        @Override
        public Class<Void> inputType()  {return Void.class;}

        @Override
        public Class<Void> outputType() {return Void.class;}
    }
}
