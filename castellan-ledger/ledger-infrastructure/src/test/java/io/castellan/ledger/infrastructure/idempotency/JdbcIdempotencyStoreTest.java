package io.castellan.ledger.infrastructure.idempotency;

import io.castellan.ledger.application.ports.IdempotencyStore;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;
import io.castellan.ledger.infrastructure.support.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcIdempotencyStoreTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private JdbcIdempotencyStore store;

    @BeforeEach
    void setUp() {
        DataSource dataSource = TestDatabase.newDataSource();
        store = new JdbcIdempotencyStore(new JdbcTemplate(dataSource), clock);
    }

    @Test
    void findOnAFreshKeyReturnsEmpty() {
        assertThat(store.find(tenant, new IdempotencyKey("never-seen"))).isEmpty();
    }

    @Test
    void reserveThenFindReturnsTheReservedTransactionId() {
        IdempotencyKey key = new IdempotencyKey("key-1");
        TransactionId txnId = TransactionId.newId();

        store.reserve(tenant, key, txnId);

        assertThat(store.find(tenant, key)).contains(txnId);
    }

    @Test
    void reservingTheSameKeyForTheSameTransactionIdTwiceIsAHarmlessNoOp() {
        IdempotencyKey key = new IdempotencyKey("key-2");
        TransactionId txnId = TransactionId.newId();

        store.reserve(tenant, key, txnId);
        store.reserve(tenant, key, txnId);

        assertThat(store.find(tenant, key)).contains(txnId);
    }

    @Test
    void reservingTheSameKeyForADifferentTransactionIdThrowsAlreadyReserved() {
        IdempotencyKey key = new IdempotencyKey("key-3");
        store.reserve(tenant, key, TransactionId.newId());

        assertThatThrownBy(() -> store.reserve(tenant, key, TransactionId.newId()))
                .isInstanceOf(IdempotencyStore.AlreadyReservedException.class);
    }

    @Test
    void theSameKeyIsIndependentPerTenant() {
        TenantId otherTenant = new TenantId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey("shared-key");
        TransactionId txnForTenant1 = TransactionId.newId();
        TransactionId txnForTenant2 = TransactionId.newId();

        store.reserve(tenant, key, txnForTenant1);
        store.reserve(otherTenant, key, txnForTenant2);

        assertThat(store.find(tenant, key)).contains(txnForTenant1);
        assertThat(store.find(otherTenant, key)).contains(txnForTenant2);
    }

    /** The race {@code IdempotencyStore}'s own docs call out by name: two concurrent retries of
     * the same logical request must not both "win" the same key with two different transaction
     * ids. Uses two independent physical connections/transactions so the database's own unique
     * constraint -- not application-level synchronization -- is what's actually being proven. */
    @Test
    void exactlyOneOfTwoConcurrentReservesForTheSameKeyWithDifferentTransactionIdsWins() throws Exception {
        IdempotencyKey key = new IdempotencyKey("race-key");
        TransactionId candidateA = TransactionId.newId();
        TransactionId candidateB = TransactionId.newId();
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger successes = new AtomicInteger();

        try {
            Future<?> f1 = pool.submit(() -> attemptReserve(key, candidateA, bothReady, go, successes));
            Future<?> f2 = pool.submit(() -> attemptReserve(key, candidateB, bothReady, go, successes));
            bothReady.await();
            go.countDown();
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);

            assertThat(successes.get()).isEqualTo(1);
            TransactionId winner = store.find(tenant, key).orElseThrow();
            assertThat(winner).isIn(candidateA, candidateB);
        } finally {
            pool.shutdownNow();
        }
    }

    private void attemptReserve(IdempotencyKey key, TransactionId candidate, CountDownLatch bothReady,
            CountDownLatch go, AtomicInteger successes) {
        bothReady.countDown();
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            store.reserve(tenant, key, candidate);
            successes.incrementAndGet();
        } catch (IdempotencyStore.AlreadyReservedException expected) {
        }
    }
}
