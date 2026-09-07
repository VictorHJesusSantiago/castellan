package io.castellan.flow.api.web.dto;

import java.util.Map;

public record StartInstanceRequest(Map<String, Object> variables) {

    public StartInstanceRequest {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
