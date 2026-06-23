package io.casehub.worker.api;

import java.util.Map;
import java.util.Objects;

public record PlannedAction(String description, String actionType, Map<String, Object> parameters) {
    public PlannedAction {
        Objects.requireNonNull(description);
        Objects.requireNonNull(actionType);
        if (parameters == null) parameters = Map.of();
    }

    public static PlannedAction of(String description, String actionType) {
        return new PlannedAction(description, actionType, Map.of());
    }

    public static PlannedAction of(String description, String actionType, Map<String, Object> parameters) {
        return new PlannedAction(description, actionType, parameters);
    }
}
