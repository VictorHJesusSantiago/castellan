package io.castellan.ledger.infrastructure.outbox;

import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.EntryType;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.Posting;
import io.castellan.ledger.domain.Streams;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;
import io.castellan.ledger.domain.events.TransactionPosted;
import io.castellan.ledger.infrastructure.event.EventJsonCodec;
import io.castellan.ledger.infrastructure.event.JdbcEventStore;
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
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxRelayTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private TransactionTemplate transactionTemplate;
    private JdbcEventStore eventStore;

    @BeforeEach
    void setUp() {
        DataSource dataSource = TestDatabase.newDataSource();
        jdbc = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        EventJsonCodec codec = new EventJsonCodec();
        eventStore = new JdbcEventStore(
                jdbc, transactionTemplate, codec,
                new AccountBalanceProjection(jdbc, codec),
                new TransactionHistoryProjection(jdbc, clock),
                clock);
    }

    private void postToLedger(String description) {
        AccountId a = AccountId.newId();
        AccountId b = AccountId.newId();
        String ledgerStream = Streams.ledger(tenant);
        TransactionPosted posted = new TransactionPosted(
                TransactionId.newId(), tenant,
                List.of(
                        new Posting(a, EntryType.DEBIT, new Money(100, Currency.getInstance("USD"))),
                        new Posting(b, EntryType.CREDIT, new Money(100, Currency.getInstance("USD")))
                ),
                description, Map.of(), new IdempotencyKey("k-" + UUID.randomUUID()), clock.instant());
        eventStore.append(ledgerStream, eventStore.currentVersion(ledgerStream), List.of(posted));
    }

    @Test
    void relayPublishesEveryUnpublishedRowAndMarksItPublished() {
        postToLedger("first");
        postToLedger("second");

        List<OutboxEvent> published = new ArrayList<>();
        OutboxRelay relay = new OutboxRelay(jdbc, published::add, transactionTemplate, clock);

        int count = relay.relay();

        assertThat(count).isEqualTo(2);
        assertThat(published).hasSize(2);
        Integer unpublishedRemaining = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE published = FALSE", Integer.class);
        assertThat(unpublishedRemaining).isZero();
        Integer publishedAtSet = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE published_at IS NOT NULL", Integer.class);
        assertThat(publishedAtSet).isEqualTo(2);
    }

    @Test
    void relayIsIdempotentASecondCallWithNothingNewToPublishPublishesNothing() {
        postToLedger("only");
        OutboxRelay relay = new OutboxRelay(jdbc, event -> { }, transactionTemplate, clock);
        assertThat(relay.relay()).isEqualTo(1);

        assertThat(relay.relay()).isZero();
    }

    /**
     * Regression test for a real bug found while writing these tests: {@link OutboxRelay#relay()}
     * originally returned {@code batch.size()} unconditionally (via a dead {@code filter(e -> true)}
     * expression) instead of the count of events that actually published successfully, so a
     * partially-failed batch was misreported as fully published. Fixed to count only the rows this
     * call actually flipped to {@code published = TRUE}.
     */
    @Test
    void relayReturnsOnlyTheCountThatActuallyPublishedNotTheWholeBatchWhenSomeFail() {
        postToLedger("ok-1");
        postToLedger("fails");
        postToLedger("ok-2");

        AtomicInteger seen = new AtomicInteger();
        OutboxPublisher flakyPublisher = event -> {
            int n = seen.incrementAndGet();
            if (n == 2) {
                throw new RuntimeException("downstream unavailable");
            }
        };
        OutboxRelay relay = new OutboxRelay(jdbc, flakyPublisher, transactionTemplate, clock);

        int reportedCount = relay.relay();

        assertThat(reportedCount).isEqualTo(2);
        Integer actuallyPublished = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE published = TRUE", Integer.class);
        assertThat(actuallyPublished).isEqualTo(2);
        Integer stillPending = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE published = FALSE", Integer.class);
        assertThat(stillPending).isEqualTo(1);
    }

    @Test
    void aFailedPublishLeavesItsRowUnpublishedForTheNextPollToRetry() {
        postToLedger("will fail once");
        AtomicInteger attempts = new AtomicInteger();
        OutboxPublisher failsFirstTimeOnly = event -> {
            if (attempts.getAndIncrement() == 0) {
                throw new RuntimeException("simulated transient failure");
            }
        };
        OutboxRelay relay = new OutboxRelay(jdbc, failsFirstTimeOnly, transactionTemplate, clock);

        assertThat(relay.relay()).isZero();
        Integer stillPending = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE published = FALSE", Integer.class);
        assertThat(stillPending).isEqualTo(1);

        assertThat(relay.relay()).isEqualTo(1);
        Integer pendingAfterRetry = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE published = FALSE", Integer.class);
        assertThat(pendingAfterRetry).isZero();
    }

    @Test
    void loggingOutboxPublisherAcceptsAnyEventWithoutThrowing() {
        OutboxEvent event = new OutboxEvent(1L, "ledger-x", "TransactionPosted", "{}", clock.instant());
        new LoggingOutboxPublisher().publish(event);
    }
}
