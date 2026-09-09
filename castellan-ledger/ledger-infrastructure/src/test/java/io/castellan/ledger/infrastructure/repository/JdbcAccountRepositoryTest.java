package io.castellan.ledger.infrastructure.repository;

import io.castellan.ledger.domain.Account;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.AccountStatus;
import io.castellan.ledger.domain.Streams;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.events.AccountOpened;
import io.castellan.ledger.domain.events.DomainEvent;
import io.castellan.ledger.domain.ports.ConcurrencyConflictException;
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
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcAccountRepositoryTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private JdbcTemplate jdbc;
    private JdbcEventStore eventStore;
    private JdbcAccountRepository repository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = TestDatabase.newDataSource();
        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        EventJsonCodec codec = new EventJsonCodec();
        eventStore = new JdbcEventStore(
                jdbc, transactionTemplate, codec,
                new AccountBalanceProjection(jdbc, codec),
                new TransactionHistoryProjection(jdbc, clock),
                clock);
        repository = new JdbcAccountRepository(eventStore);
    }

    @Test
    void loadingAnAccountThatWasNeverOpenedReturnsANonExistentAccount() {
        Account account = repository.load(tenant, AccountId.newId());

        assertThat(account.exists()).isFalse();
    }

    @Test
    void appendThenLoadRoundTripsAnOpenedAccount() {
        AccountId id = AccountId.newId();
        AccountOpened opened = Account.open(id, tenant, Currency.getInstance("USD"), "Ada Lovelace", clock.instant());

        repository.append(tenant, id, 0, List.of(opened));
        Account loaded = repository.load(tenant, id);

        assertThat(loaded.exists()).isTrue();
        assertThat(loaded.id()).isEqualTo(id);
        assertThat(loaded.tenantId()).isEqualTo(tenant);
        assertThat(loaded.currency()).isEqualTo(Currency.getInstance("USD"));
        assertThat(loaded.ownerName()).isEqualTo("Ada Lovelace");
        assertThat(loaded.status()).isEqualTo(AccountStatus.OPEN);
        assertThat(loaded.version()).isEqualTo(1);
    }

    @Test
    void lifecycleEventsAppendedOverMultipleCallsReplayToTheCorrectFinalState() {
        AccountId id = AccountId.newId();
        AccountOpened opened = Account.open(id, tenant, Currency.getInstance("USD"), "Ada Lovelace", clock.instant());
        repository.append(tenant, id, 0, List.of(opened));

        Account afterOpen = repository.load(tenant, id);
        var frozen = afterOpen.freeze("suspicious", clock.instant());
        repository.append(tenant, id, 1, List.of(frozen));

        Account afterFreeze = repository.load(tenant, id);
        assertThat(afterFreeze.status()).isEqualTo(AccountStatus.FROZEN);
        var unfrozen = afterFreeze.unfreeze("cleared", clock.instant());
        repository.append(tenant, id, 2, List.of(unfrozen));

        Account finalState = repository.load(tenant, id);
        assertThat(finalState.status()).isEqualTo(AccountStatus.OPEN);
        assertThat(finalState.version()).isEqualTo(3);
    }

    @Test
    void appendingWithAStaleExpectedVersionThrowsConcurrencyConflict() {
        AccountId id = AccountId.newId();
        repository.append(tenant, id, 0, List.of(
                Account.open(id, tenant, Currency.getInstance("USD"), "Ada", clock.instant())));

        Account current = repository.load(tenant, id);
        assertThatThrownBy(() -> repository.append(tenant, id, 0, List.of(current.freeze("x", clock.instant()))))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    void repositoryUsesTheExactStreamsAccountNamingConventionEventStoreItselfUses() {
        AccountId id = AccountId.newId();
        AccountOpened opened = Account.open(id, tenant, Currency.getInstance("USD"), "Ada", clock.instant());
        repository.append(tenant, id, 0, List.of(opened));

        List<DomainEvent> viaRawStreamId = eventStore.load(Streams.account(tenant, id));

        assertThat(viaRawStreamId).containsExactly(opened);
    }
}
