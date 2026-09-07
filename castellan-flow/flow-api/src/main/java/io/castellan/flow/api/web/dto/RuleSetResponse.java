package io.castellan.flow.api.web.dto;

import io.castellan.flow.engine.RuleSetDefinitionRecord;

import java.time.Instant;
import java.util.List;

public record RuleSetResponse(String name, int version, List<RuleDefinitionDto> rules, Instant deployedAt) {

    public static RuleSetResponse of(RuleSetDefinitionRecord record) {
        return new RuleSetResponse(record.name(), record.version(),
                record.rules().stream().map(RuleDefinitionDto::of).toList(), record.deployedAt());
    }
}
