package io.castellan.flow.api.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record DeployRuleSetRequest(@NotBlank String name, @NotEmpty List<@Valid RuleDefinitionDto> rules) {
}
