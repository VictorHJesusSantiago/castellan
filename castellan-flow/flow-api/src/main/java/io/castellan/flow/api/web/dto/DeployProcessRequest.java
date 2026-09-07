package io.castellan.flow.api.web.dto;

import jakarta.validation.constraints.NotBlank;

public record DeployProcessRequest(@NotBlank String processId, @NotBlank String bpmnXml) {
}
