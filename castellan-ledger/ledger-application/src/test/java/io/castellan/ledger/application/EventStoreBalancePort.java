package io.castellan.ledger.application;

import io.castellan.ledger.application.ports.BalancePort;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.EntryType;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.Posting;
import io.castellan.ledger.domain.Streams;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.events.DomainEvent;
import io.castellan.ledger.domain.events.TransactionPosted;
import io.castellan.ledger.domain.ports.EventStore;

import java.util.Currency;

/** A brute-force {@link BalancePort}: replays the whole ledger stream and sums postings for the
 * requested account on every call. {@code ledger-infrastructure}'s real
 * {@code AccountBalanceProjection} is an incrementally-maintained read-model table, not this —
 * this exists purely so {@code ledger-application}'s tests can assert on real balances without
 * depending on that module. */
public final class EventStoreBalancePort implements BalancePort {

    private final EventStore eventStore;

    public EventStoreBalancePort(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    @Override
    public Money currentBalance(TenantId tenantId, AccountId accountId) {
        long total = 0;
        Currency currency = null;
        for (DomainEvent event : eventStore.load(Streams.ledger(tenantId))) {
            if (!(event instanceof TransactionPosted posted)) {
                continue;
            }
            for (Posting posting : posted.postings()) {
                if (!posting.accountId().equals(accountId)) {
                    continue;
                }
                currency = posting.amount().currency();
                total += posting.entryType() == EntryType.CREDIT ? posting.amount().minorUnits() : -posting.amount().minorUnits();
            }
        }
        return currency == null ? Money.zero(Currency.getInstance("USD")) : new Money(total, currency);
    }
}
