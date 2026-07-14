package io.casehub.worker.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.casehub.worker.api.Capability;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class SchemaValidator {

    private static final String EMPTY_SCHEMA = "{}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonSchemaFactory schemaFactory =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final ConcurrentHashMap<String, JsonSchema> cache = new ConcurrentHashMap<>();

    public void ensureSchemaParsed(String schema) {
        if (EMPTY_SCHEMA.equals(schema)) return;
        cache.computeIfAbsent(schema, this::parseSchema);
    }

    public Optional<String> validateInput(Capability capability, Object input) {
        return validate(capability.inputSchema(), input);
    }

    public Optional<String> validateOutput(Capability capability, Object output) {
        return validate(capability.outputSchema(), output);
    }

    private Optional<String> validate(String schemaString, Object data) {
        if (EMPTY_SCHEMA.equals(schemaString)) {return Optional.empty();}
        JsonSchema             schema = cache.computeIfAbsent(schemaString, this::parseSchema);
        JsonNode               node   = objectMapper.valueToTree(data);
        Set<ValidationMessage> errors = schema.validate(node);
        if (errors.isEmpty()) {return Optional.empty();}
        String message = errors.stream()
                               .map(ValidationMessage::getMessage)
                               .collect(Collectors.joining("\n"));
        return Optional.of(message);
    }

    private JsonSchema parseSchema(String schemaString) {
        try {
            JsonNode schemaNode = objectMapper.readTree(schemaString);
            return schemaFactory.getSchema(schemaNode);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Malformed JSON Schema: " + e.getMessage(), e);
        }
    }
}
