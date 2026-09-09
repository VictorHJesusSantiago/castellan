package io.castellan.ledger.infrastructure.projection;

import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.EntryType;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.Posting;
import io.castellan.ledger.domain.Streams;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;
import io.castellan.ledger.domain.events.AccountOpened;
import io.castellan.ledger.domain.events.TransactionPosted;
import io.castellan.ledger.infrastructure.event.EventJsonCodec;
import io.castellan.ledger.infrastructure.event.EventTypeRegistry;
import io.castellan.ledger.infrastructure.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountBalanceProjectionTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Currency usd = Currency.getInstance("USD");

    private JdbcTemplate jdbc;
    private AccountBalanceProjection projection;
    private EventJsonCodec codec;

    @BeforeEach
    void setUp() {
        DataSource dataSource = TestDatabase.newDataSource();
        jdbc = new JdbcTemplate(dataSource);
        codec = new EventJsonCodec();
        projection = new AccountBalanceProjection(jdbc, codec);
    }

    private void recordAccountOpened(AccountId id, Currency currency) {
        AccountOpened opened = new AccountOpened(id, tenant, currency, "Ada Lovelace", now);
        String stream = Streams.account(tenant, id);
        jdbc.update("INSERT INTO streams (stream_id, version) VALUES (?, 1)", stream);
        jdbc.update(
                "INSERT INTO events (stream_id, version, event_type, payload_json, occurred_at, recorded_at) "
                        + "VALUES (?, 1, ?, ?, ?, ?)",
                stream, EventTypeRegistry.tagFor(opened), codec.serialize(opened), Timestamp.from(now), Timestamp.from(now));
    }

    @Test
    void anAccountWithNeitherABalanceRowNorAnAccountOpenedEventDefaultsToZeroUsd() {
        Money balance = projection.currentBalance(tenant, AccountId.newId());

        assertThat(balance).isEqualTo(Money.zero(usd));
    }

    @Test
    void anOpenedAccountWithNoPostingsYetIsZeroInItsOwnCurrencyNotAlwaysUsd() {
        AccountId id = AccountId.newId();
        recordAccountOpened(id, Currency.getInstance("JPY"));

        Money balance = projection.currentBalance(tenant, id);

        assertThat(balance).isEqualTo(Money.zero(Currency.getInstance("JPY")));
    }

    @Test
    void applyingACreditIncreasesBalanceAndApplyingADebitDecreasesIt() {
        AccountId a = AccountId.newId();
        AccountId b = AccountId.newId();
        TransactionPosted posted = new TransactionPosted(
                TransactionId.newId(), tenant,
                java.util.List.of(
                        new Posting(a, EntryType.DEBIT, new Money(300, usd)),
                        new Posting(b, EntryType.CREDIT, new Money(300, usd))
                ),
                "pay", Map.of(), new IdempotencyKey("k1"), now);

        projection.apply(tenant, posted);

        assertThat(projection.currentBalance(tenant, a).minorUnits()).isEqualTo(-300);
        assertThat(projection.currentBalance(tenant, b).minorUnits()).isEqualTo(300);
    }

    @Test
    void balanceAccumulatesIncrementallyAcrossMultipleApplyCalls() {
        AccountId source = AccountId.newId();
        AccountId dest = AccountId.newId();

        for (int i = 0; i < 5; i++) {
            TransactionPosted posted = new TransactionPosted(
                    TransactionId.newId(), tenant,
                    java.util.List.of(
                            new Posting(source, EntryType.DEBIT, new Money(100, usd)),
                            new Posting(dest, EntryType.CREDIT, new Money(100, usd))
                    ),
                    "installment " + i, Map.of(), new IdempotencyKey("installment-" + i), now);
            projection.apply(tenant, posted);
        }

        assertThat(projection.currentBalance(tenant, source).minorUnits()).isEqualTo(-500);
        assertThat(projection.currentBalance(tenant, dest).minorUnits()).isEqualTo(500);
    }

    /** Proves the projection is genuinely incrementally-maintained, not a brute-force replay of
     * the ledger stream on every query: delete every underlying {@code events} row after applying
     * postings (something a real replay-based implementation could never survive) and confirm the
     * balance query is completely unaffected, because it reads only {@code account_balances}. */
    @Test
    void currentBalanceReadsOnlyTheProjectionTableNeverReplayingEvents() {
        AccountId source = AccountId.newId();
        AccountId dest = AccountId.newId();
        TransactionPosted posted = new TransactionPosted(
                TransactionId.newId(), tenant,
                java.util.List.of(
                        new Posting(source, EntryType.DEBIT, new Money(250, usd)),
                        new Posting(dest, EntryType.CREDIT, new Money(250, usd))
                ),
                "pay", Map.of(), new IdempotencyKey("k2"), now);
        projection.apply(tenant, posted);

        jdbc.update("DELETE FROM events");

        assertThat(projection.currentBalance(tenant, source).minorUnits()).isEqualTo(-250);
        assertThat(projection.currentBalance(tenant, dest).minorUnits()).isEqualTo(250);
    }

    @Test
    void twoPostingsAgainstTheSameAccountWithinOneTransactionBothApply() {
        AccountId account = AccountId.newId();
        AccountId other = AccountId.newId();
        TransactionPosted posted = new TransactionPosted(
                TransactionId.newId(), tenant,
                java.util.List.of(
                        new Posting(account, EntryType.DEBIT, new Money(100, usd)),
                        new Posting(account, EntryType.DEBIT, new Money(50, usd)),
                        new Posting(other, EntryType.CREDIT, new Money(150, usd))
                ),
                "split debit", Map.of(), new IdempotencyKey("k3"), now);

        projection.apply(tenant, posted);

        assertThat(projection.currentBalance(tenant, account).minorUnits()).isEqualTo(-150);
    }
}
