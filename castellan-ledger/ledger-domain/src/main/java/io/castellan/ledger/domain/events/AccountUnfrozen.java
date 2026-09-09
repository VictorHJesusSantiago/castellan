package io.castellan.ledger.domain.events;

import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.TenantId;

import java.time.Instant;

public record AccountUnfrozen(
        AccountId accountId,
        TenantId tenantId,
        String reason,
        Instant occurredAt
) implements DomainEvent {
}
