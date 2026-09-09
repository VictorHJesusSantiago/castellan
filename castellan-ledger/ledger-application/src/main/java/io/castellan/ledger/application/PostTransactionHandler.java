package io.castellan.ledger.application;

import io.castellan.ledger.application.commands.PostTransactionCommand;
import io.castellan.ledger.application.commands.PostingRequest;
import io.castellan.ledger.application.fraud.FraudCheckContext;
import io.castellan.ledger.application.fraud.FraudRuleEngine;
import io.castellan.ledger.application.fraud.FraudVerdict;
import io.castellan.ledger.application.ports.AccountRepository;
import io.castellan.ledger.application.ports.BalancePort;
import io.castellan.ledger.application.ports.RecentActivityPort;
import io.castellan.ledger.application.ports.IdempotencyStore;
import io.castellan.ledger.application.results.TransactionResult;
import io.castellan.ledger.domain.Account;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.DoubleEntryTransaction;
import io.castellan.ledger.domain.EntryType;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.Posting;
import io.castellan.ledger.domain.Streams;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;
import io.castellan.ledger.domain.events.DomainEvent;
import io.castellan.ledger.domain.events.FraudFlagRaised;
import io.castellan.ledger.domain.events.TransactionPosted;
import io.castellan.ledger.domain.ports.EventStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one place a {@link PostTransactionCommand} actually becomes ledger truth. Every rule this
 * class enforces is enforced here and nowhere else: idempotency (§ below), account existence and
 * postability, the fraud pipeline, and the double-entry balance invariant (delegated to
 * {@link DoubleEntryTransaction}'s constructor, which makes an unbalanced transaction
 * unrepresentable rather than merely rejected after the fact).
 *
 * <h2>Idempotency, made crash-safe</h2>
 *
 * A naive "check idempotency key, then act" has a race: two concurrent retries can both pass the
 * check before either has recorded anything. A less naive "reserve the key atomically, then act"
 * fixes the race but opens a *different* hole: if the process crashes (or the ledger append fails
 * for any reason) after the key is reserved but before the transaction is actually appended, the
 * key is permanently burned pointing at a transaction that was never posted — a legitimate retry
 * would then be told "already done" for something that never happened.
 *
 * <p>This method closes both holes by making the reservation carry the {@link TransactionId}
 * itself, then treating "is there already a {@link TransactionPosted} event with this exact id in
 * the ledger stream" as the actual source of truth, checked fresh on every call: a first attempt
 * reserves a new id and finds nothing posted yet, so it proceeds; a retry (whether because the
 * client legitimately resent the command, or because a previous attempt crashed mid-flight) finds
 * the same reserved id, and either finds the matching event already posted (a true idempotent
 * replay — return the original result, do no further work) or finds nothing posted yet (the
 * previous attempt never got that far — safely proceed exactly as a first attempt would, using
 * the same, already-reserved id). Two concurrent attempts that both reach the append step race on
 * {@link EventStore}'s own optimistic-concurrency check on the ledger stream version; the loser
 * gets a {@link io.castellan.ledger.domain.ports.ConcurrencyConflictException} and, on retry,
 * lands in the "already posted" branch — self-healing, no special-case retry logic needed.
 */
public final class PostTransactionHandler {

    private final EventStore eventStore;
    private final AccountRepository accountRepository;
    private final IdempotencyStore idempotencyStore;
    private final RecentActivityPort recentActivityPort;
    private final BalancePort balancePort;
    private final FraudRuleEngine fraudRuleEngine;
    private final Duration velocityWindow;
    private final Clock clock;

    public PostTransactionHandler(
            EventStore eventStore,
            AccountRepository accountRepository,
            IdempotencyStore idempotencyStore,
            RecentActivityPort recentActivityPort,
            BalancePort balancePort,
            FraudRuleEngine fraudRuleEngine,
            Duration velocityWindow,
            Clock clock) {
        this.eventStore = eventStore;
        this.accountRepository = accountRepository;
        this.idempotencyStore = idempotencyStore;
        this.recentActivityPort = recentActivityPort;
        this.balancePort = balancePort;
        this.fraudRuleEngine = fraudRuleEngine;
        this.velocityWindow = velocityWindow;
        this.clock = clock;
    }

    public TransactionResult handle(PostTransactionCommand command) {
        TenantId tenantId = command.tenantId();
        String ledgerStream = Streams.ledger(tenantId);

        TransactionId transactionId = idempotencyStore.find(tenantId, command.idempotencyKey()).orElse(null);
        boolean isRetry = transactionId != null;
        if (!isRetry) {
            transactionId = TransactionId.newId();
            idempotencyStore.reserve(tenantId, command.idempotencyKey(), transactionId);
        }

        List<DomainEvent> existingLedgerEvents = eventStore.load(ledgerStream);
        Optional<TransactionPosted> alreadyPosted = findPosted(existingLedgerEvents, transactionId);
        if (alreadyPosted.isPresent()) {
            return new TransactionResult(transactionId, alreadyPosted.get().occurredAt(), isRetry);
        }

        List<Posting> postings = toPostings(command.postings());
        Instant now = clock.instant();
        DoubleEntryTransaction transaction = DoubleEntryTransaction.of(
                transactionId, tenantId, postings, command.description(), now, command.metadata());

        Map<AccountId, Account> involvedAccounts = loadAndValidateAccounts(tenantId, transaction);
        requireSufficientFunds(tenantId, postings);

        Map<AccountId, Integer> recentCounts = new LinkedHashMap<>();
        for (AccountId id : involvedAccounts.keySet()) {
            recentCounts.put(id, recentActivityPort.countRecentTransactions(tenantId, id, velocityWindow));
        }
        FraudVerdict verdict = fraudRuleEngine.evaluate(new FraudCheckContext(command, involvedAccounts, recentCounts));

        List<DomainEvent> toAppend = new ArrayList<>();
        for (FraudVerdict.RaisedFlag flag : verdict.flags()) {
            toAppend.add(new FraudFlagRaised(
                    transactionId, tenantId, flag.ruleName(), flag.reason(),
                    flag.isBlock() ? FraudFlagRaised.Severity.BLOCK : FraudFlagRaised.Severity.FLAG, now));
        }

        if (verdict.blocked()) {
            if (!toAppend.isEmpty()) {
                eventStore.append(ledgerStream, eventStore.currentVersion(ledgerStream), toAppend);
            }
            throw new TransactionBlockedException(transactionId, verdict);
        }

        toAppend.add(new TransactionPosted(
                transactionId, tenantId, postings, command.description(), command.metadata(),
                command.idempotencyKey(), now));
        eventStore.append(ledgerStream, eventStore.currentVersion(ledgerStream), toAppend);

        return new TransactionResult(transactionId, now, false);
    }

    private static Optional<TransactionPosted> findPosted(List<DomainEvent> events, TransactionId transactionId) {
        for (DomainEvent event : events) {
            if (event instanceof TransactionPosted posted && posted.transactionId().equals(transactionId)) {
                return Optional.of(posted);
            }
        }
        return Optional.empty();
    }

    private static List<Posting> toPostings(List<PostingRequest> requests) {
        return requests.stream()
                .map(r -> new Posting(r.accountId(), r.entryType(), r.amount()))
                .toList();
    }

    /** Accumulates net debit per account within this one transaction first, then checks each
     * against its current balance exactly once — two separate, individually-small debits against
     * the same account in one transaction must be checked against their combined effect, not
     * each in isolation against a balance that hasn't yet accounted for the other. */
    private void requireSufficientFunds(TenantId tenantId, List<Posting> postings) {
        Map<AccountId, Long> netDebitMinorUnits = new LinkedHashMap<>();
        for (Posting posting : postings) {
            if (posting.entryType() == EntryType.DEBIT) {
                netDebitMinorUnits.merge(posting.accountId(), posting.amount().minorUnits(), Long::sum);
            }
        }
        for (Map.Entry<AccountId, Long> entry : netDebitMinorUnits.entrySet()) {
            Money current = balancePort.currentBalance(tenantId, entry.getKey());
            long projected = current.minorUnits() - entry.getValue();
            if (projected < 0) {
                throw new InsufficientFundsException(
                        entry.getKey(), current, new Money(entry.getValue(), current.currency()));
            }
        }
    }

    private Map<AccountId, Account> loadAndValidateAccounts(TenantId tenantId, DoubleEntryTransaction transaction) {
        Map<AccountId, Account> accounts = new LinkedHashMap<>();
        for (AccountId id : new LinkedHashSet<>(transaction.affectedAccounts())) {
            Account account = accountRepository.load(tenantId, id);
            if (!account.exists()) {
                throw new Account.AccountNotFoundException(id);
            }
            account.requireCanPost();
            accounts.put(id, account);
        }
        return accounts;
    }
}
