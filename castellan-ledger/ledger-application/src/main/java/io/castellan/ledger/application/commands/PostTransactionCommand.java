package io.castellan.ledger.application.commands;

import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.TenantId;

import java.util.List;
import java.util.Map;

public record PostTransactionCommand(
        TenantId tenantId,
        List<PostingRequest> postings,
        String description,
        Map<String, String> metadata,
        IdempotencyKey idempotencyKey
) {

    public PostTransactionCommand {
        postings = List.copyOf(postings);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
