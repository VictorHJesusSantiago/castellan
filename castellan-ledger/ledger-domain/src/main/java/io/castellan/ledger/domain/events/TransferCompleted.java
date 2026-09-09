package io.castellan.ledger.domain.events;

import io.castellan.ledger.domain.SagaId;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;

import java.time.Instant;

/** Step 2 (the happy path): the in-transit suspense account has been debited and the destination
 * credited — the saga's terminal success state. */
public record TransferCompleted(
        SagaId sagaId,
        TenantId tenantId,
        TransactionId captureTransactionId,
        Instant occurredAt
) implements DomainEvent {
}
