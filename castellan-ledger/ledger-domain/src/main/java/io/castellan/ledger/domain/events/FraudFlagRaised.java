package io.castellan.ledger.domain.events;

import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;

import java.time.Instant;

/** Raised by the application layer's fraud rule pipeline whenever a rule flags (not necessarily
 * blocks) a proposed transaction — recorded for audit and downstream investigation regardless of
 * whether the transaction was ultimately allowed, held, or rejected. */
public record FraudFlagRaised(
        TransactionId relatedTransactionId,
        TenantId tenantId,
        String ruleName,
        String reason,
        Severity severity,
        Instant occurredAt
) implements DomainEvent {

    public enum Severity {
        FLAG,
        BLOCK
    }
}
