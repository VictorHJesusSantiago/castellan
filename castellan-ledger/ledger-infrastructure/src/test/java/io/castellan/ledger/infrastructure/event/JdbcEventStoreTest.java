package io.castellan.ledger.infrastructure.event;

import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.EntryType;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.Posting;
import io.castellan.ledger.domain.Streams;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;
import io.castellan.ledger.domain.events.AccountOpened;
import io.castellan.ledger.domain.events.DomainEvent;
import io.castellan.ledger.domain.events.FraudFlagRaised;
import io.castellan.ledger.domain.events.TransactionPosted;
import io.castellan.ledger.domain.ports.ConcurrencyConflictException;
import io.castellan.ledger.infrastructure.projection.AccountBalanceProjection;
import io.castellan.ledger.infrastructure.projection.TransactionHistoryProjection;
import io.castellan.ledger.infrastructure.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link JdbcEventStore} against a real embedded H2 database (Flyway-migrated, per
 * {@link TestDatabase}) -- no mocks -- since the whole point of this class is to prove the actual
 * SQL and locking behavior work, not just that the Java code calls a mocked interface correctly.
 */
class JdbcEventStoreTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactionTemplate;
    private JdbcEventStore eventStore;

    @BeforeEach
    void setUp() {
        dataSource = TestDatabase.newDataSource();
        jdbc = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        EventJsonCodec codec = new EventJsonCodec();
        eventStore = new JdbcEventStore(
                jdbc, transactionTemplate, codec,
                new AccountBalanceProjection(jdbc, codec),
                new TransactionHistoryProjection(jdbc, clock),
                clock);
    }

    private AccountOpened accountOpened(AccountId id) {
        return new AccountOpened(id, tenant, Currency.getInstance("USD"), "Ada Lovelace", clock.instant());
    }

    @Test
    void appendThenLoadRoundTripsEventsInOrder() {
        AccountId id = AccountId.newId();
        String stream = Streams.account(tenant, id);
        AccountOpened opened = accountOpened(id);

        eventStore.append(stream, 0, List.of(opened));

        assertThat(eventStore.load(stream)).containsExactly(opened);
        assertThat(eventStore.currentVersion(stream)).isEqualTo(1);
    }

    @Test
    void loadingAStreamThatNeverHadAnythingAppendedReturnsEmptyNotAnError() {
        assertThat(eventStore.load("account-" + UUID.randomUUID())).isEmpty();
        assertThat(eventStore.currentVersion("account-" + UUID.randomUUID())).isZero();
    }

    @Test
    void appendingWithAnEmptyEventListIsANoOpAndDoesNotCreateTheStream() {
        String stream = Streams.account(tenant, AccountId.newId());

        eventStore.append(stream, 0, List.of());

        assertThat(eventStore.currentVersion(stream)).isZero();
        assertThat(eventStore.load(stream)).isEmpty();
    }

    @Test
    void multipleEventsInOneCallAreAppendedAtomicallyWithIncrementingVersions() {
        AccountId id = AccountId.newId();
        String stream = Streams.account(tenant, id);
        AccountOpened opened = accountOpened(id);

        eventStore.append(stream, 0, List.of(opened));
        var frozen = new io.castellan.ledger.domain.events.AccountFrozen(id, tenant, "review", clock.instant());
        var unfrozen = new io.castellan.ledger.domain.events.AccountUnfrozen(id, tenant, "cleared", clock.instant());
        eventStore.append(stream, 1, List.of(frozen, unfrozen));

        assertThat(eventStore.load(stream)).containsExactly(opened, frozen, unfrozen);
        assertThat(eventStore.currentVersion(stream)).isEqualTo(3);
    }

    @Test
    void appendingWithAStaleExpectedVersionThrowsConcurrencyConflictReportingTheRealVersion() {
        AccountId id = AccountId.newId();
        String stream = Streams.account(tenant, id);
        eventStore.append(stream, 0, List.of(accountOpened(id)));

        assertThatThrownBy(() -> eventStore.append(stream, 0, List.of(
                new io.castellan.ledger.domain.events.AccountFrozen(id, tenant, "review", clock.instant()))))
                .isInstanceOf(ConcurrencyConflictException.class)
                .satisfies(ex -> {
                    ConcurrencyConflictException conflict = (ConcurrencyConflictException) ex;
                    assertThat(conflict.expectedVersion()).isEqualTo(0);
                    assertThat(conflict.actualVersion()).isEqualTo(1);
                });

        assertThat(eventStore.currentVersion(stream)).isEqualTo(1);
    }

    @Test
    void appendingToABrandNewStreamWithANonZeroExpectedVersionFails() {
        String stream = Streams.account(tenant, AccountId.newId());

        assertThatThrownBy(() -> eventStore.append(stream, 5, List.of(
                accountOpened(AccountId.newId()))))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    /** A genuine race: two threads each open their own physical DB connection/transaction and try
     * to append to the very same brand-new stream with {@code expectedVersion = 0} at (as close
     * as a test can force) the same instant. {@code JdbcEventStore}'s own docs promise the
     * streams-row lock closes this exact gap -- this proves it does, against the real database,
     * not a mock: exactly one thread must win and the other must see a genuine
     * {@link ConcurrencyConflictException}, never both succeeding and never a corrupted stream. */
    @Test
    void exactlyOneOfTwoConcurrentAppendsToANewStreamWins() throws Exception {
        AccountId id = AccountId.newId();
        String stream = Streams.account(tenant, id);
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            List<Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final AccountId frozenById = id;
                results.add(pool.submit(() -> {
                    bothReady.countDown();
                    go.await();
                    try {
                        eventStore.append(stream, 0, List.of(accountOpened(frozenById)));
                        return true;
                    } catch (ConcurrencyConflictException conflict) {
                        return false;
                    }
                }));
            }
            bothReady.await();
            go.countDown();

            int wins = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    wins++;
                }
            }

            assertThat(wins).isEqualTo(1);
            assertThat(eventStore.currentVersion(stream)).isEqualTo(1);
            assertThat(eventStore.load(stream)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Same race, but with several threads all targeting the same *existing* stream at its
     * current version -- e.g. several concurrent transactions racing to post to one tenant's
     * ledger stream. Exactly one should win per version slot; the rest must retry (which this
     * test doesn't simulate) rather than corrupt the stream. */
    @Test
    void exactlyOneOfSeveralConcurrentAppendsAtTheSameExpectedVersionWins() throws Exception {
        AccountId id = AccountId.newId();
        String stream = Streams.account(tenant, id);
        eventStore.append(stream, 0, List.of(accountOpened(id)));

        int contenders = 5;
        CountDownLatch allReady = new CountDownLatch(contenders);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        AtomicInteger successes = new AtomicInteger();

        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                futures.add(pool.submit(() -> {
                    allReady.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        eventStore.append(stream, 1, List.of(
                                new io.castellan.ledger.domain.events.AccountFrozen(id, tenant, "race", clock.instant())));
                        successes.incrementAndGet();
                    } catch (ConcurrencyConflictException ignored) {
                    }
                }));
            }
            allReady.await();
            go.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }

            assertThat(successes.get()).isEqualTo(1);
            assertThat(eventStore.currentVersion(stream)).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void appendingToALedgerStreamMirrorsEventsIntoTheOutbox() {
        String ledgerStream = Streams.ledger(tenant);
        AccountId a = AccountId.newId();
        AccountId b = AccountId.newId();
        TransactionPosted posted = new TransactionPosted(
                TransactionId.newId(), tenant,
                List.of(
                        new Posting(a, EntryType.DEBIT, new Money(500, Currency.getInstance("USD"))),
                        new Posting(b, EntryType.CREDIT, new Money(500, Currency.getInstance("USD")))
                ),
                "rent", Map.of(), new IdempotencyKey("idem-1"), clock.instant());

        eventStore.append(ledgerStream, 0, List.of(posted));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT stream_id, event_type, published FROM outbox WHERE stream_id = ?", ledgerStream);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("EVENT_TYPE")).isEqualTo("TransactionPosted");
        assertThat(rows.get(0).get("PUBLISHED")).isEqualTo(false);
    }

    @Test
    void appendingToANonLedgerStreamDoesNotTouchTheOutbox() {
        AccountId id = AccountId.newId();
        String accountStream = Streams.account(tenant, id);

        eventStore.append(accountStream, 0, List.of(accountOpened(id)));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE stream_id = ?", Integer.class, accountStream);
        assertThat(count).isZero();
    }

    @Test
    void appendingAFraudFlagRaisedToTheLedgerStreamMirrorsToOutboxButDoesNotTouchBalanceProjection() {
        String ledgerStream = Streams.ledger(tenant);
        AccountId account = AccountId.newId();
        FraudFlagRaised flag = new FraudFlagRaised(
                TransactionId.newId(), tenant, "velocity", "too many", FraudFlagRaised.Severity.BLOCK, clock.instant());

        eventStore.append(ledgerStream, 0, List.of(flag));

        Integer outboxCount = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE stream_id = ?", Integer.class, ledgerStream);
        assertThat(outboxCount).isEqualTo(1);
        Integer balanceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_balances WHERE account_id = ?", Integer.class, account.value().toString());
        assertThat(balanceCount).isZero();
    }

    @Test
    void postingATransactionSynchronouslyUpdatesTheBalanceProjectionInTheSameCommit() {
        String ledgerStream = Streams.ledger(tenant);
        AccountId a = AccountId.newId();
        AccountId b = AccountId.newId();
        TransactionPosted posted = new TransactionPosted(
                TransactionId.newId(), tenant,
                List.of(
                        new Posting(a, EntryType.DEBIT, new Money(700, Currency.getInstance("USD"))),
                        new Posting(b, EntryType.CREDIT, new Money(700, Currency.getInstance("USD")))
                ),
                "pay", Map.of(), new IdempotencyKey("idem-2"), clock.instant());

        eventStore.append(ledgerStream, 0, List.of(posted));

        AccountBalanceProjection balances = new AccountBalanceProjection(jdbc, new EventJsonCodec());
        assertThat(balances.currentBalance(tenant, a).minorUnits()).isEqualTo(-700);
        assertThat(balances.currentBalance(tenant, b).minorUnits()).isEqualTo(700);
    }

    /** Proves atomicity guarantee #2 from {@link JdbcEventStore}'s own docs: the unique constraint
     * on {@code (stream_id, version)} is a second, independent guard against the streams-row lock
     * ever being bypassed or the two getting out of sync (e.g. through direct, ungoverned SQL, or
     * a future bug). Manually plants a "phantom" row at a version the {@code streams} table
     * doesn't yet know about, then appends two events starting from the honestly-current version
     * -- the first insert succeeds, the second collides with the phantom row, and the whole
     * two-event append (including the first, already-inserted event and its outbox mirror) must
     * roll back together, not leave a half-applied stream. */
    @Test
    void aUniqueConstraintViolationMidAppendRollsBackTheEntireCallIncludingEarlierEventsInTheBatch() {
        AccountId id = AccountId.newId();
        String stream = Streams.account(tenant, id);
        eventStore.append(stream, 0, List.of(accountOpened(id)));

        jdbc.update(
                "INSERT INTO events (stream_id, version, event_type, payload_json, occurred_at, recorded_at) "
                        + "VALUES (?, 3, 'AccountFrozen', '{}', ?, ?)",
                stream, java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()));

        var frozen = new io.castellan.ledger.domain.events.AccountFrozen(id, tenant, "step1", clock.instant());
        var unfrozen = new io.castellan.ledger.domain.events.AccountUnfrozen(id, tenant, "step2", clock.instant());

        assertThatThrownBy(() -> eventStore.append(stream, 1, List.of(frozen, unfrozen)))
                .isInstanceOf(ConcurrencyConflictException.class);

        assertThat(eventStore.currentVersion(stream)).isEqualTo(1);
        List<DomainEvent> events = eventStore.load(stream);
        assertThat(events).hasSize(2);
        assertThat(events).noneMatch(e -> e instanceof io.castellan.ledger.domain.events.AccountFrozen f
                && "step1".equals(f.reason()));
    }
}
