package io.castellan.ledger.domain.events;

import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.SagaId;
import io.castellan.ledger.domain.TenantId;

import java.time.Instant;

public record TransferSagaStarted(
        SagaId sagaId,
        TenantId tenantId,
        AccountId sourceAccountId,
        AccountId destinationAccountId,
        Money amount,
        IdempotencyKey idempotencyKey,
        Instant occurredAt
) implements DomainEvent {
}
