package io.castellan.ledger.application;

import io.castellan.ledger.application.commands.PostTransactionCommand;
import io.castellan.ledger.application.commands.PostingRequest;
import io.castellan.ledger.application.fraud.FraudRuleEngine;
import io.castellan.ledger.application.fraud.rules.LargeAmountRule;
import io.castellan.ledger.application.fraud.rules.VelocityRule;
import io.castellan.ledger.application.ports.AccountRepository;
import io.castellan.ledger.application.results.TransactionResult;
import io.castellan.ledger.domain.Account;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.EntryType;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.Streams;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.UnbalancedTransactionException;
import io.castellan.ledger.domain.events.AccountOpened;
import io.castellan.ledger.domain.events.DomainEvent;
import io.castellan.ledger.domain.events.FraudFlagRaised;
import io.castellan.ledger.domain.events.TransactionPosted;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostTransactionHandlerTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Currency usd = Currency.getInstance("USD");
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private EventStore eventStore;
    private AccountRepository accountRepository;
    private InMemoryIdempotencyStore idempotencyStore;
    private FixedRecentActivityPort recentActivityPort;
    private EventStoreBalancePort balancePort;
    private AccountId alice;
    private AccountId bob;

    @BeforeEach
    void setUp() {
        eventStore = new InMemoryEventStore();
        accountRepository = new EventStoreAccountRepository(eventStore);
        idempotencyStore = new InMemoryIdempotencyStore();
        recentActivityPort = new FixedRecentActivityPort();
        balancePort = new EventStoreBalancePort(eventStore);

        alice = openAccount("Alice");
        bob = openAccount("Bob");
        creditDirectly(alice, 10_000);
    }

    private AccountId openAccount(String owner) {
        AccountId id = AccountId.newId();
        AccountOpened opened = Account.open(id, tenant, usd, owner, clock.instant());
        accountRepository.append(tenant, id, 0, List.of(opened));
        return id;
    }

    /** Bootstraps an opening balance by appending straight to the ledger stream (from a synthetic
     * "capital" account allowed to run negative, since it represents money entering the system
     * from outside), deliberately bypassing {@link PostTransactionHandler} — including its own
     * insufficient-funds check — so test setup doesn't depend on the very features under test. */
    private void creditDirectly(AccountId accountId, long minorUnits) {
        AccountId capital = AccountId.newId();
        AccountOpened openedCapital = Account.open(capital, tenant, usd, "SYSTEM:capital", clock.instant());
        accountRepository.append(tenant, capital, 0, List.of(openedCapital));

        TransactionPosted seed = new TransactionPosted(
                io.castellan.ledger.domain.TransactionId.newId(), tenant,
                List.of(
                        new io.castellan.ledger.domain.Posting(capital, EntryType.DEBIT, new Money(minorUnits, usd)),
                        new io.castellan.ledger.domain.Posting(accountId, EntryType.CREDIT, new Money(minorUnits, usd))
                ),
                "opening balance", null, new IdempotencyKey("seed-" + accountId), clock.instant());
        eventStore.append(Streams.ledger(tenant), eventStore.currentVersion(Streams.ledger(tenant)), List.of(seed));
    }

    private PostTransactionHandler handler(FraudRuleEngine fraudRuleEngine) {
        return new PostTransactionHandler(
                eventStore, accountRepository, idempotencyStore, recentActivityPort, balancePort,
                fraudRuleEngine, Duration.ofMinutes(1), clock);
    }

    private PostTransactionHandler defaultHandler() {
        return handler(new FraudRuleEngine(List.of()));
    }

    @Test
    void postsABalancedTransactionAndUpdatesBothBalances() {
        TransactionResult result = defaultHandler().handle(new PostTransactionCommand(
                tenant,
                List.of(
                        new PostingRequest(alice, EntryType.DEBIT, new Money(500, usd)),
                        new PostingRequest(bob, EntryType.CREDIT, new Money(500, usd))
                ),
                "rent", null, new IdempotencyKey("tx-1")));

        assertThat(result.wasIdempotentReplay()).isFalse();
        assertThat(balancePort.currentBalance(tenant, alice).minorUnits()).isEqualTo(9_500);
        assertThat(balancePort.currentBalance(tenant, bob).minorUnits()).isEqualTo(500);
    }

    @Test
    void retryingTheSameIdempotencyKeyReturnsTheOriginalResultWithoutPostingTwice() {
        IdempotencyKey key = new IdempotencyKey("tx-retry");
        PostTransactionCommand command = new PostTransactionCommand(
                tenant,
                List.of(
                        new PostingRequest(alice, EntryType.DEBIT, new Money(500, usd)),
                        new PostingRequest(bob, EntryType.CREDIT, new Money(500, usd))
                ),
                "rent", null, key);
        PostTransactionHandler handler = defaultHandler();

        TransactionResult first = handler.handle(command);
        TransactionResult second = handler.handle(command);

        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(second.wasIdempotentReplay()).isTrue();
        assertThat(balancePort.currentBalance(tenant, alice).minorUnits()).isEqualTo(9_500);
    }

    @Test
    void resumesCorrectlyIfIdempotencyWasReservedButAppendNeverHappened() {
        var stuckTransactionId = io.castellan.ledger.domain.TransactionId.newId();
        IdempotencyKey key = new IdempotencyKey("tx-crashed");
        idempotencyStore.reserve(tenant, key, stuckTransactionId);

        TransactionResult result = defaultHandler().handle(new PostTransactionCommand(
                tenant,
                List.of(
                        new PostingRequest(alice, EntryType.DEBIT, new Money(100, usd)),
                        new PostingRequest(bob, EntryType.CREDIT, new Money(100, usd))
                ),
                "recovery", null, key));

        assertThat(result.transactionId()).isEqualTo(stuckTransactionId);
        assertThat(balancePort.currentBalance(tenant, bob).minorUnits()).isEqualTo(100);
    }

    @Test
    void rejectsAnUnbalancedCommand() {
        assertThatThrownBy(() -> defaultHandler().handle(new PostTransactionCommand(
                tenant,
                List.of(
                        new PostingRequest(alice, EntryType.DEBIT, new Money(500, usd)),
                        new PostingRequest(bob, EntryType.CREDIT, new Money(499, usd))
                ),
                "bad", null, new IdempotencyKey("tx-bad"))))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void rejectsAPostingAgainstAnUnknownAccount() {
        AccountId ghost = AccountId.newId();
        assertThatThrownBy(() -> defaultHandler().handle(new PostTransactionCommand(
                tenant,
                List.of(
                        new PostingRequest(ghost, EntryType.DEBIT, new Money(500, usd)),
                        new PostingRequest(bob, EntryType.CREDIT, new Money(500, usd))
                ),
                "bad", null, new IdempotencyKey("tx-ghost"))))
                .isInstanceOf(Account.AccountNotFoundException.class);
    }

    @Test
    void rejectsAPostingAgainstAFrozenAccount() {
        accountRepository.append(tenant, bob, 1, List.of(
                Account.replay(eventStore.load(Streams.account(tenant, bob))).freeze("review", clock.instant())));

        assertThatThrownBy(() -> defaultHandler().handle(new PostTransactionCommand(
                tenant,
                List.of(
                        new PostingRequest(alice, EntryType.DEBIT, new Money(500, usd)),
                        new PostingRequest(bob, EntryType.CREDIT, new Money(500, usd))
                ),
                "bad", null, new IdempotencyKey("tx-frozen"))))
                .isInstanceOf(Account.AccountNotPostableException.class);
    }

    @Test
    void rejectsADebitThatWouldOverdrawTheAccount() {
        assertThatThrownBy(() -> defaultHandler().handle(new PostTransactionCommand(
                tenant,
                List.of(
                        new PostingRequest(alice, EntryType.DEBIT, new Money(999_999, usd)),
                        new PostingRequest(bob, EntryType.CREDIT, new Money(999_999, usd))
                ),
                "too much", null, new IdempotencyKey("tx-overdraw"))))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(balancePort.currentBalance(tenant, alice).minorUnits()).isEqualTo(10_000);
    }

    @Test
    void fraudFlagIsRecordedButDoesNotBlockTheTransaction() {
        FraudRuleEngine engine = new FraudRuleEngine(List.of(new LargeAmountRule(new Money(400, usd))));
        TransactionResult result = handler(engine).handle(new PostTransactionCommand(
                tenant,
                List.of(
                        new PostingRequest(alice, EntryType.DEBIT, new Money(500, usd)),
                        new PostingRequest(bob, EntryType.CREDIT, new Money(500, usd))
                ),
                "large but allowed", null, new IdempotencyKey("tx-flagged")));

        assertThat(result.wasIdempotentReplay()).isFalse();
        List<DomainEvent> ledgerEvents = eventStore.load(Streams.ledger(tenant));
        assertThat(ledgerEvents).anyMatch(e -> e instanceof FraudFlagRaised f && f.severity() == FraudFlagRaised.Severity.FLAG);
        assertThat(ledgerEvents).anyMatch(TransactionPosted.class::isInstance);
    }

    @Test
    void fraudBlockPreventsPostingButStillRecordsTheAuditEvent() {
        recentActivityPort.setCount(alice, 10);
        FraudRuleEngine engine = new FraudRuleEngine(List.of(new VelocityRule(5)));

        assertThatThrownBy(() -> handler(engine).handle(new PostTransactionCommand(
                tenant,
                List.of(
                        new PostingRequest(alice, EntryType.DEBIT, new Money(100, usd)),
                        new PostingRequest(bob, EntryType.CREDIT, new Money(100, usd))
                ),
                "blocked", null, new IdempotencyKey("tx-blocked"))))
                .isInstanceOf(TransactionBlockedException.class);

        List<DomainEvent> ledgerEvents = eventStore.load(Streams.ledger(tenant));
        assertThat(ledgerEvents).anyMatch(e -> e instanceof FraudFlagRaised f && f.severity() == FraudFlagRaised.Severity.BLOCK);
        assertThat(ledgerEvents).filteredOn(TransactionPosted.class::isInstance).hasSize(1);
        assertThat(balancePort.currentBalance(tenant, alice).minorUnits()).isEqualTo(10_000);
    }
}
