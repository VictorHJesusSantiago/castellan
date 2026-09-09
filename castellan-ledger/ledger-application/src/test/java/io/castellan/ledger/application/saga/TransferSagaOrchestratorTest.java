package io.castellan.ledger.application.saga;

import io.castellan.ledger.application.EventStoreAccountRepository;
import io.castellan.ledger.application.EventStoreBalancePort;
import io.castellan.ledger.application.FixedRecentActivityPort;
import io.castellan.ledger.application.InMemoryEventStore;
import io.castellan.ledger.application.InMemoryIdempotencyStore;
import io.castellan.ledger.application.PostTransactionHandler;
import io.castellan.ledger.application.commands.InitiateTransferCommand;
import io.castellan.ledger.application.fraud.FraudRuleEngine;
import io.castellan.ledger.application.ports.AccountRepository;
import io.castellan.ledger.application.results.TransferResult;
import io.castellan.ledger.domain.Account;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.EntryType;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.Posting;
import io.castellan.ledger.domain.Streams;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;
import io.castellan.ledger.domain.events.AccountOpened;
import io.castellan.ledger.domain.events.FundsReserved;
import io.castellan.ledger.domain.events.TransactionPosted;
import io.castellan.ledger.domain.events.TransferSagaStarted;
import io.castellan.ledger.domain.ports.EventStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferSagaOrchestratorTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Currency usd = Currency.getInstance("USD");
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private EventStore eventStore;
    private AccountRepository accountRepository;
    private EventStoreBalancePort balancePort;
    private TransferSagaOrchestrator orchestrator;
    private AccountId source;
    private AccountId destination;

    @BeforeEach
    void setUp() {
        eventStore = new InMemoryEventStore();
        accountRepository = new EventStoreAccountRepository(eventStore);
        balancePort = new EventStoreBalancePort(eventStore);

        PostTransactionHandler internalHandler = new PostTransactionHandler(
                eventStore, accountRepository, new InMemoryIdempotencyStore(), new FixedRecentActivityPort(),
                balancePort, new FraudRuleEngine(List.of()), Duration.ofMinutes(1), clock);
        orchestrator = new TransferSagaOrchestrator(eventStore, accountRepository, internalHandler, clock);

        source = openAccount("Alice");
        destination = openAccount("Bob");
        seedBalance(source, 10_000);
    }

    private AccountId openAccount(String owner) {
        AccountId id = AccountId.newId();
        accountRepository.append(tenant, id, 0, List.of(Account.open(id, tenant, usd, owner, clock.instant())));
        return id;
    }

    private void seedBalance(AccountId accountId, long minorUnits) {
        AccountId capital = AccountId.newId();
        accountRepository.append(tenant, capital, 0, List.of(Account.open(capital, tenant, usd, "SYSTEM:capital", clock.instant())));
        TransactionPosted seed = new TransactionPosted(
                TransactionId.newId(), tenant,
                List.of(
                        new Posting(capital, EntryType.DEBIT, new Money(minorUnits, usd)),
                        new Posting(accountId, EntryType.CREDIT, new Money(minorUnits, usd))
                ),
                "seed", null, new IdempotencyKey("seed-" + accountId), clock.instant());
        eventStore.append(Streams.ledger(tenant), eventStore.currentVersion(Streams.ledger(tenant)), List.of(seed));
    }

    private AccountId inTransitAccount() {
        return AccountId.systemAccount(tenant, "IN_TRANSIT", "USD");
    }

    @Test
    void happyPathMovesFundsFromSourceToDestinationAndLeavesInTransitAtZero() {
        InitiateTransferCommand command = new InitiateTransferCommand(
                tenant, source, destination, new Money(2_000, usd), "rent", new IdempotencyKey("transfer-1"));

        TransferResult result = orchestrator.execute(command);

        assertThat(result).isInstanceOf(TransferResult.Completed.class);
        assertThat(balancePort.currentBalance(tenant, source).minorUnits()).isEqualTo(8_000);
        assertThat(balancePort.currentBalance(tenant, destination).minorUnits()).isEqualTo(2_000);
        assertThat(balancePort.currentBalance(tenant, inTransitAccount()).minorUnits()).isZero();
    }

    @Test
    void transferToANonexistentDestinationCompensatesAndLeavesSourceWhole() {
        AccountId ghostDestination = AccountId.newId();
        InitiateTransferCommand command = new InitiateTransferCommand(
                tenant, source, ghostDestination, new Money(3_000, usd), "oops", new IdempotencyKey("transfer-2"));

        TransferResult result = orchestrator.execute(command);

        assertThat(result).isInstanceOf(TransferResult.Compensated.class);
        assertThat(((TransferResult.Compensated) result).reason()).contains("capture step failed");
        assertThat(balancePort.currentBalance(tenant, source).minorUnits()).isEqualTo(10_000);
        assertThat(balancePort.currentBalance(tenant, inTransitAccount()).minorUnits()).isZero();
    }

    @Test
    void transferToAFrozenDestinationCompensates() {
        accountRepository.append(tenant, destination, 1, List.of(
                Account.replay(eventStore.load(Streams.account(tenant, destination))).freeze("review", clock.instant())));

        TransferResult result = orchestrator.execute(new InitiateTransferCommand(
                tenant, source, destination, new Money(1_000, usd), "frozen dest", new IdempotencyKey("transfer-3")));

        assertThat(result).isInstanceOf(TransferResult.Compensated.class);
        assertThat(balancePort.currentBalance(tenant, source).minorUnits()).isEqualTo(10_000);
    }

    @Test
    void retryingTheSameIdempotencyKeyDoesNotMoveMoneyTwice() {
        InitiateTransferCommand command = new InitiateTransferCommand(
                tenant, source, destination, new Money(1_500, usd), "rent", new IdempotencyKey("transfer-4"));

        TransferResult first = orchestrator.execute(command);
        TransferResult second = orchestrator.execute(command);

        assertThat(((TransferResult.Completed) first).sagaId()).isEqualTo(((TransferResult.Completed) second).sagaId());
        assertThat(balancePort.currentBalance(tenant, source).minorUnits()).isEqualTo(8_500);
        assertThat(balancePort.currentBalance(tenant, destination).minorUnits()).isEqualTo(1_500);
    }

    @Test
    void resumesFromFundsReservedRatherThanReReservingAfterASimulatedCrash() {
        var sagaIdField = deriveSagaIdForTest(new IdempotencyKey("transfer-5"));
        String sagaStream = Streams.saga(tenant, sagaIdField);
        Instant now = clock.instant();
        var started = new TransferSagaStarted(sagaIdField, tenant, source, destination, new Money(1_000, usd),
                new IdempotencyKey("transfer-5"), now);
        eventStore.append(sagaStream, 0, List.of(started));

        AccountId inTransit = inTransitAccount();
        accountRepository.append(tenant, inTransit, 0, List.of(Account.open(inTransit, tenant, usd, "SYSTEM:in-transit", now)));
        TransactionPosted reservation = new TransactionPosted(
                TransactionId.newId(), tenant,
                List.of(
                        new Posting(source, EntryType.DEBIT, new Money(1_000, usd)),
                        new Posting(inTransit, EntryType.CREDIT, new Money(1_000, usd))
                ),
                "transfer reservation", null, new IdempotencyKey("saga:" + sagaIdField + ":reserve"), now);
        eventStore.append(Streams.ledger(tenant), eventStore.currentVersion(Streams.ledger(tenant)), List.of(reservation));
        eventStore.append(sagaStream, 1, List.of(new FundsReserved(sagaIdField, tenant, reservation.transactionId(), now)));

        assertThat(balancePort.currentBalance(tenant, source).minorUnits()).isEqualTo(9_000);

        TransferResult result = orchestrator.execute(new InitiateTransferCommand(
                tenant, source, destination, new Money(1_000, usd), "resumed", new IdempotencyKey("transfer-5")));

        assertThat(result).isInstanceOf(TransferResult.Completed.class);
        assertThat(balancePort.currentBalance(tenant, source).minorUnits()).isEqualTo(9_000);
        assertThat(balancePort.currentBalance(tenant, destination).minorUnits()).isEqualTo(1_000);
    }

    private io.castellan.ledger.domain.SagaId deriveSagaIdForTest(IdempotencyKey key) {
        String name = "transfer|" + tenant.value() + "|" + key.value();
        return new io.castellan.ledger.domain.SagaId(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
