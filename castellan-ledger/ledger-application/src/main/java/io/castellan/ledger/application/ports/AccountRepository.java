package io.castellan.ledger.application.ports;

import io.castellan.ledger.domain.Account;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.events.DomainEvent;

import java.util.List;

/** A thin, {@code EventStore}-backed convenience — not a second port with its own storage
 * concern, just "replay the account-lifecycle stream into an {@code Account}" and "append a new
 * lifecycle event to it", named for what a caller actually wants to do rather than making every
 * call site re-derive a stream id and call {@code EventStore} directly. */
public interface AccountRepository {

    Account load(TenantId tenantId, AccountId accountId);

    void append(TenantId tenantId, AccountId accountId, long expectedVersion, List<DomainEvent> events);
}
