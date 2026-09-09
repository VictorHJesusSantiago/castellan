package io.castellan.ledger.domain.events;

import io.castellan.ledger.domain.SagaId;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;

import java.time.Instant;

/** Step 1 of the transfer saga completed: the source account has been debited and the tenant's
 * in-transit suspense account credited by an equal amount (a real, balanced
 * {@link TransactionPosted}, referenced here by id) — the source no longer has the funds, but
 * the destination doesn't have them yet either. This is the saga's one committed, durable
 * checkpoint that a compensating action ({@link TransferCompensated}) can always safely reverse,
 * because it is itself just another balanced transaction. */
public record FundsReserved(
        SagaId sagaId,
        TenantId tenantId,
        TransactionId reservationTransactionId,
        Instant occurredAt
) implements DomainEvent {
}
