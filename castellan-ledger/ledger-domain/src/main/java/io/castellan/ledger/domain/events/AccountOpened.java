package io.castellan.ledger.domain.events;

import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.TenantId;

import java.time.Instant;
import java.util.Currency;

public record AccountOpened(
        AccountId accountId,
        TenantId tenantId,
        Currency currency,
        String ownerName,
        Instant occurredAt
) implements DomainEvent {
}
