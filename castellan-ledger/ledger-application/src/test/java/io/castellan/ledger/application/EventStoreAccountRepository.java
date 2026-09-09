package io.castellan.ledger.application;

import io.castellan.ledger.application.ports.AccountRepository;
import io.castellan.ledger.domain.Account;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.Streams;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.events.DomainEvent;
import io.castellan.ledger.domain.ports.EventStore;

import java.util.List;

/** The obvious, real implementation of {@link AccountRepository} — a thin replay/append wrapper
 * over {@link EventStore} — used in tests here and expected to be essentially what
 * {@code ledger-infrastructure} does too (modulo whatever caching it adds on top). */
public final class EventStoreAccountRepository implements AccountRepository {

    private final EventStore eventStore;

    public EventStoreAccountRepository(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    @Override
    public Account load(TenantId tenantId, AccountId accountId) {
        return Account.replay(eventStore.load(Streams.account(tenantId, accountId)));
    }

    @Override
    public void append(TenantId tenantId, AccountId accountId, long expectedVersion, List<DomainEvent> events) {
        eventStore.append(Streams.account(tenantId, accountId), expectedVersion, events);
    }
}
