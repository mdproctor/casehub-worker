package io.casehub.worker.api;

import io.casehub.platform.api.governance.ExecutionPolicy;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record Worker(String name, Set<String> capabilities, WorkerFunction<?, ?> function,
                     ExecutionPolicy executionPolicy, String description,
                     String definitionRef) {
    public Worker {
        Objects.requireNonNull(name);
        Objects.requireNonNull(capabilities);
        Objects.requireNonNull(function);
        if (executionPolicy == null) {executionPolicy = new ExecutionPolicy();}
        capabilities = Set.copyOf(capabilities);
    }

    public Worker(String name, Set<String> capabilities, WorkerFunction<?, ?> function,
                  ExecutionPolicy executionPolicy, String description) {
        this(name, capabilities, function, executionPolicy, description, null);
    }

    public static Builder builder() {return new Builder();}

    public static class Builder {
        private String               name;
        private Set<String>          capabilityNames;
        private WorkerFunction<?, ?> function;
        private ExecutionPolicy      executionPolicy;
        private String               description;
        private String               definitionRef;

        public Builder name(String n) {
            this.name = n;
            return this;
        }

        public Builder capabilityNames(String... names) {
            this.capabilityNames = new LinkedHashSet<>(Arrays.asList(names));
            return this;
        }

        public Builder capabilityNames(Collection<String> names) {
            this.capabilityNames = new LinkedHashSet<>(names);
            return this;
        }

        public Builder capabilityName(String name) {
            this.capabilityNames = Set.of(name);
            return this;
        }

        public Builder function(WorkerFunction<?, ?> f) {
            this.function = f;
            return this;
        }

        @SuppressWarnings("unchecked")
        public Builder function(java.util.function.Function<java.util.Map<String, Object>, WorkerResult<java.util.Map<String, Object>>> fn) {
            this.function = new WorkerFunction.Sync<>((Class) java.util.Map.class, (Class) java.util.Map.class, (t, scope) -> fn.apply((java.util.Map<String, Object>) t));
            return this;
        }

        @SafeVarargs
        public final <T> TypedFunctionBuilder<T> fn(T... typeToken) {
            Class<?> runtimeType = typeToken.getClass().getComponentType();
            return new TypedFunctionBuilder<>(this, runtimeType);
        }

        @SuppressWarnings("unchecked")
        public Builder exchange(java.util.function.BiFunction<Exchange<java.util.Map<String, Object>>, WorkerScope, WorkerResult<Exchange<java.util.Map<String, Object>>>> fn) {
            this.function = new WorkerFunction.ExchangeProcessor<>((Class) java.util.Map.class, (Class) java.util.Map.class, fn);
            return this;
        }

        @SafeVarargs
        public final <T> ExchangeProcessorBuilder<T> exchange(T... typeToken) {
            Class<?> runtimeType = typeToken.getClass().getComponentType();
            return new ExchangeProcessorBuilder<>(this, runtimeType);
        }


        void setFunction(WorkerFunction<?, ?> f) {this.function = f;}

        public Builder noFunction() {
            this.function = WorkerFunction.NONE;
            return this;
        }

        public Builder executionPolicy(ExecutionPolicy p) {
            this.executionPolicy = p;
            return this;
        }

        public Builder description(String d) {
            this.description = d;
            return this;
        }

        public Builder definitionRef(String ref) {
            this.definitionRef = ref;
            return this;
        }

        public Worker build() {
            return new Worker(name, capabilityNames, function, executionPolicy, description, definitionRef);
        }
    }
}
