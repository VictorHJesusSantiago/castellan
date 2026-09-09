package io.castellan.ledger.api.web.dto;

import jakarta.validation.constraints.NotBlank;

public record OpenAccountRequest(
        @NotBlank String ownerName,
        @NotBlank String currency
) {
}
