package io.castellan.ledger.domain.events;

import io.castellan.ledger.domain.TenantId;

import java.time.Instant;

/**
 * Everything that has ever happened in this system, as a sealed hierarchy — the event store
 * (a port in {@code ledger-domain}, implemented in {@code ledger-infrastructure}) only ever
 * stores and replays values of this type, and every {@code switch} over a {@code DomainEvent}
 * anywhere in this codebase (see {@link io.castellan.ledger.domain.Account#apply}) is exhaustive-
 * checked by the compiler: adding a twelfth event kind without updating every consumer is a
 * compile error, not a silent gap discovered in production.
 */
public sealed interface DomainEvent
        permits AccountOpened, AccountClosed, AccountFrozen, AccountUnfrozen,
        TransactionPosted, TransactionReversed,
        TransferSagaStarted, FundsReserved, TransferCompleted, TransferFailed, TransferCompensated,
        FraudFlagRaised {

    TenantId tenantId();

    Instant occurredAt();
}
