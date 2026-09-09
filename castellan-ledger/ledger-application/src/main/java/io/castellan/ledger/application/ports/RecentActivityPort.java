package io.castellan.ledger.application.ports;

import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.TenantId;

import java.time.Duration;

/** Backs {@code VelocityRule}: how many transactions has this account posted within the last
 * {@code window}? {@code ledger-infrastructure}'s implementation answers this from the
 * transaction-history read model (a projection over {@code TransactionPosted} events), not by
 * replaying the whole ledger stream on every check. */
public interface RecentActivityPort {

    int countRecentTransactions(TenantId tenantId, AccountId accountId, Duration window);
}
