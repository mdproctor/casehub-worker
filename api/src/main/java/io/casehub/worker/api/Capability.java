package io.casehub.worker.api;

import java.util.Objects;

public record Capability(String name, String inputProjection, String outputProjection, String description) {
    public Capability {
        Objects.requireNonNull(name);
        Objects.requireNonNull(inputProjection);
        Objects.requireNonNull(outputProjection);
    }

    public static Capability of(String name, String inputSchema, String outputSchema) {
        return new Capability(name, inputSchema, outputSchema, null);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String name;
        private String inputSchema;
        private String outputSchema;
        private String description;

        public Builder name(String name) { this.name = name; return this; }
        public Builder inputSchema(String s) { this.inputSchema = s; return this; }
        public Builder outputSchema(String s) { this.outputSchema = s; return this; }
        public Builder description(String s) { this.description = s; return this; }

        public Capability build() {
            return new Capability(name, inputSchema, outputSchema, description);
        }
    }
}
