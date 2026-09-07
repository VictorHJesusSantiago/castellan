package io.castellan.flow.api.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/** {@code tokenId} identifies exactly which waiting token this signal resolves -- discoverable
 * from a prior {@code GET /instances/{id}} response's {@code tokens} list. */
public record SignalRequest(@NotBlank String tokenId, Map<String, Object> variables) {

    public SignalRequest {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
