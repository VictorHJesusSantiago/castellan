package io.castellan.flow.api.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CompensateRequest(@NotBlank String activityId) {
}
