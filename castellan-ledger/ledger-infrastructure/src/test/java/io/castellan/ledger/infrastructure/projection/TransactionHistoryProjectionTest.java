package io.castellan.ledger.infrastructure.projection;

import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.EntryType;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.Posting;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;
import io.castellan.ledger.domain.events.TransactionPosted;
import io.castellan.ledger.infrastructure.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionHistoryProjectionTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Currency usd = Currency.getInstance("USD");
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

    private TransactionHistoryProjection projection;

    @BeforeEach
    void setUp() {
        DataSource dataSource = TestDatabase.newDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        java.time.Clock movableClock = new java.time.Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        projection = new TransactionHistoryProjection(jdbc, movableClock);
    }

    private TransactionPosted postingAt(AccountId a, AccountId b, Instant occurredAt) {
        return new TransactionPosted(
                TransactionId.newId(), tenant,
                List.of(
                        new Posting(a, EntryType.DEBIT, new Money(100, usd)),
                        new Posting(b, EntryType.CREDIT, new Money(100, usd))
                ),
                "tx", Map.of(), new IdempotencyKey("k-" + UUID.randomUUID()), occurredAt);
    }

    @Test
    void countRecentTransactionsOnAnAccountWithNoHistoryIsZero() {
        assertThat(projection.countRecentTransactions(tenant, AccountId.newId(), Duration.ofMinutes(5))).isZero();
    }

    @Test
    void applyingATransactionRecordsHistoryForEveryDistinctAccountItTouches() {
        AccountId a = AccountId.newId();
        AccountId b = AccountId.newId();
        projection.apply(tenant, postingAt(a, b, now.get()));

        assertThat(projection.countRecentTransactions(tenant, a, Duration.ofMinutes(5))).isEqualTo(1);
        assertThat(projection.countRecentTransactions(tenant, b, Duration.ofMinutes(5))).isEqualTo(1);
    }

    @Test
    void reapplyingTheSameEventIsASafeNoOpNotADoubleCount() {
        AccountId a = AccountId.newId();
        AccountId b = AccountId.newId();
        TransactionPosted posted = postingAt(a, b, now.get());

        projection.apply(tenant, posted);
        projection.apply(tenant, posted);

        assertThat(projection.countRecentTransactions(tenant, a, Duration.ofMinutes(5))).isEqualTo(1);
    }

    @Test
    void aTransactionTouchingTheSameAccountTwiceCountsAsOneHistoryEntryNotTwo() {
        AccountId account = AccountId.newId();
        AccountId other = AccountId.newId();
        TransactionPosted posted = new TransactionPosted(
                TransactionId.newId(), tenant,
                List.of(
                        new Posting(account, EntryType.DEBIT, new Money(100, usd)),
                        new Posting(account, EntryType.DEBIT, new Money(50, usd)),
                        new Posting(other, EntryType.CREDIT, new Money(150, usd))
                ),
                "split", Map.of(), new IdempotencyKey("k-split"), now.get());

        projection.apply(tenant, posted);

        assertThat(projection.countRecentTransactions(tenant, account, Duration.ofMinutes(5))).isEqualTo(1);
    }

    @Test
    void transactionsOlderThanTheWindowAreExcludedFromTheCount() {
        AccountId a = AccountId.newId();
        AccountId b = AccountId.newId();
        Instant tenMinutesAgo = now.get().minus(Duration.ofMinutes(10));
        projection.apply(tenant, postingAt(a, b, tenMinutesAgo));

        assertThat(projection.countRecentTransactions(tenant, a, Duration.ofMinutes(5))).isZero();
        assertThat(projection.countRecentTransactions(tenant, a, Duration.ofMinutes(15))).isEqualTo(1);
    }

    @Test
    void countIsScopedPerAccountAndDoesNotLeakAcrossUnrelatedAccounts() {
        AccountId a = AccountId.newId();
        AccountId b = AccountId.newId();
        AccountId unrelated = AccountId.newId();
        projection.apply(tenant, postingAt(a, b, now.get()));

        assertThat(projection.countRecentTransactions(tenant, unrelated, Duration.ofMinutes(5))).isZero();
    }
}
