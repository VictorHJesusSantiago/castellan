package io.castellan.ledger.application;

import io.castellan.ledger.application.ports.RecentActivityPort;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.TenantId;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A test double whose counts a test sets explicitly, rather than deriving them from real
 * transaction history — {@code VelocityRule} tests want precise control over "this account has
 * had exactly N recent transactions" without needing to actually post N transactions first. */
public final class FixedRecentActivityPort implements RecentActivityPort {

    private final Map<AccountId, Integer> counts = new ConcurrentHashMap<>();

    public void setCount(AccountId accountId, int count) {
        counts.put(accountId, count);
    }

    @Override
    public int countRecentTransactions(TenantId tenantId, AccountId accountId, Duration window) {
        return counts.getOrDefault(accountId, 0);
    }
}
