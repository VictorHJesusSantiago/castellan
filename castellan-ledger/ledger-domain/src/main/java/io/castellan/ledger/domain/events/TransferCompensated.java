package io.castellan.ledger.domain.events;

import io.castellan.ledger.domain.SagaId;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;

import java.time.Instant;

/** The compensating action for a {@link TransferFailed} saga: a balanced transaction reversing
 * {@link FundsReserved} exactly (in-transit account debited, source account credited back by the
 * same amount) — the saga's terminal failure-but-safe state. */
public record TransferCompensated(
        SagaId sagaId,
        TenantId tenantId,
        TransactionId compensationTransactionId,
        Instant occurredAt
) implements DomainEvent {
}
