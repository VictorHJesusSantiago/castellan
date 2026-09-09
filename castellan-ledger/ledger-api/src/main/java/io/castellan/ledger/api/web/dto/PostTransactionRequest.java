package io.castellan.ledger.api.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

public record PostTransactionRequest(
        @NotEmpty @Valid List<PostingDto> postings,
        String description,
        Map<String, String> metadata,
        @NotBlank String idempotencyKey
) {
}
