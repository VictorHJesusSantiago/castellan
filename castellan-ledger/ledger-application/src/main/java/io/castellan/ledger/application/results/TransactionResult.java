package io.castellan.ledger.application.results;

import io.castellan.ledger.domain.TransactionId;

import java.time.Instant;

public record TransactionResult(TransactionId transactionId, Instant occurredAt, boolean wasIdempotentReplay) {
}
