package io.castellan.ledger.domain.events;

import io.castellan.ledger.domain.SagaId;
import io.castellan.ledger.domain.TenantId;

import java.time.Instant;

/** Recorded the moment the saga decides it cannot proceed to {@link TransferCompleted} — e.g. the
 * destination account is closed/frozen, or a fraud rule blocked the capture step. On its own this
 * event does not undo {@link FundsReserved}; the orchestrator always follows it with a
 * compensating transaction and a {@link TransferCompensated} event, so "failed" and "money is
 * still safely accounted for" are two separate, both-durable facts. */
public record TransferFailed(
        SagaId sagaId,
        TenantId tenantId,
        String reason,
        Instant occurredAt
) implements DomainEvent {
}
