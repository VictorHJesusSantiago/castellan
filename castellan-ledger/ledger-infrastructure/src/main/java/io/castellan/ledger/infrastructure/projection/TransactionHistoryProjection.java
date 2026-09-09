package io.castellan.ledger.infrastructure.projection;

import io.castellan.ledger.application.ports.RecentActivityPort;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.Posting;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.events.TransactionPosted;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The real, incrementally-maintained {@link RecentActivityPort}: one {@code transaction_history}
 * row per (account, transaction) pair, inserted by {@link #apply} in the same transaction as the
 * {@code TransactionPosted} event that caused it (see {@link AccountBalanceProjection}'s docs for
 * why that matters) -- {@link #countRecentTransactions} is then a single indexed range query, not
 * a replay of anything.
 */
public final class TransactionHistoryProjection implements RecentActivityPort {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TransactionHistoryProjection(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public int countRecentTransactions(TenantId tenantId, AccountId accountId, Duration window) {
        Instant cutoff = clock.instant().minus(window);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transaction_history WHERE tenant_id = ? AND account_id = ? AND occurred_at >= ?",
                Integer.class, tenantId.value().toString(), accountId.value().toString(), Timestamp.from(cutoff));
        return count == null ? 0 : count;
    }

    public void apply(TenantId tenantId, TransactionPosted posted) {
        Set<AccountId> distinctAccounts = new LinkedHashSet<>();
        for (Posting p : posted.postings()) {
            distinctAccounts.add(p.accountId());
        }
        for (AccountId accountId : distinctAccounts) {
            try {
                jdbc.update(
                        "INSERT INTO transaction_history (tenant_id, account_id, transaction_id, occurred_at) VALUES (?, ?, ?, ?)",
                        tenantId.value().toString(), accountId.value().toString(),
                        posted.transactionId().value().toString(), Timestamp.from(posted.occurredAt()));
            } catch (DuplicateKeyException alreadyRecorded) {
            }
        }
    }
}
