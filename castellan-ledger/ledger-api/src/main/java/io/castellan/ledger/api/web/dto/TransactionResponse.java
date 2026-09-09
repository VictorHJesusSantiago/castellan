package io.castellan.ledger.api.web.dto;

import io.castellan.ledger.application.results.TransactionResult;

public record TransactionResponse(
        String transactionId,
        String occurredAt,
        boolean wasIdempotentReplay
) {
    public static TransactionResponse of(TransactionResult result) {
        return new TransactionResponse(
                result.transactionId().toString(), result.occurredAt().toString(), result.wasIdempotentReplay());
    }
}
