package io.castellan.ledger.infrastructure.repository;

import io.castellan.ledger.application.ports.AccountRepository;
import io.castellan.ledger.domain.Account;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.Streams;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.events.DomainEvent;
import io.castellan.ledger.domain.ports.EventStore;

import java.util.List;

/** A thin replay/append wrapper over {@link EventStore}, exactly as {@code AccountRepository}'s
 * own docs prescribe -- no storage concern of its own, just the account-stream naming convention
 * ({@code Streams.account}) applied consistently. */
public final class JdbcAccountRepository implements AccountRepository {

    private final EventStore eventStore;

    public JdbcAccountRepository(EventStore eventStore) {
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
