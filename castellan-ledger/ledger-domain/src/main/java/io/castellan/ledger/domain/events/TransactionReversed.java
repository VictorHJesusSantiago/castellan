package io.castellan.ledger.domain.events;

import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;

import java.time.Instant;

/** A reversal is never a mutation or deletion of the original {@link TransactionPosted} — the
 * ledger is append-only, full stop — it is a brand-new, equal-and-opposite
 * {@link TransactionPosted} (every DEBIT becomes a CREDIT and vice versa, same accounts, same
 * amounts) plus this event linking the two together for audit purposes. */
public record TransactionReversed(
        TransactionId originalTransactionId,
        TransactionId reversalTransactionId,
        TenantId tenantId,
        String reason,
        Instant occurredAt
) implements DomainEvent {
}
