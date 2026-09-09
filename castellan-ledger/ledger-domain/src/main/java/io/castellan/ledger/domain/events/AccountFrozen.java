package io.castellan.ledger.domain.events;

import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.TenantId;

import java.time.Instant;

/** Raised when an account is placed on hold — by an operator, or automatically by
 * {@code FraudRuleEngine} raising a {@link FraudFlagRaised} that the application layer escalates
 * into a freeze. A frozen account can still be read (balance queries, audit) but rejects new
 * postings, enforced by {@code Account#requireCanPost}. */
public record AccountFrozen(
        AccountId accountId,
        TenantId tenantId,
        String reason,
        Instant occurredAt
) implements DomainEvent {
}
