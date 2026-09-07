package io.castellan.flow.api.web.dto;

import io.castellan.flow.engine.JsonRuleDefinition;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record RuleDefinitionDto(@NotBlank String name, int salience, String condition, Map<String, String> outcomeExpressions) {

    public RuleDefinitionDto {
        outcomeExpressions = outcomeExpressions == null ? Map.of() : Map.copyOf(outcomeExpressions);
    }

    public JsonRuleDefinition toDomain() {
        return new JsonRuleDefinition(name, salience, condition, outcomeExpressions);
    }

    public static RuleDefinitionDto of(JsonRuleDefinition definition) {
        return new RuleDefinitionDto(definition.name(), definition.salience(), definition.condition(), definition.outcomeExpressions());
    }
}
