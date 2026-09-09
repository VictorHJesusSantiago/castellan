package io.castellan.ledger.application.saga;

import io.castellan.ledger.application.PostTransactionHandler;
import io.castellan.ledger.application.commands.InitiateTransferCommand;
import io.castellan.ledger.application.commands.PostTransactionCommand;
import io.castellan.ledger.application.commands.PostingRequest;
import io.castellan.ledger.application.ports.AccountRepository;
import io.castellan.ledger.application.results.TransferResult;
import io.castellan.ledger.domain.Account;
import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.EntryType;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.SagaId;
import io.castellan.ledger.domain.Streams;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.events.DomainEvent;
import io.castellan.ledger.domain.events.FundsReserved;
import io.castellan.ledger.domain.events.TransferCompensated;
import io.castellan.ledger.domain.events.TransferCompleted;
import io.castellan.ledger.domain.events.TransferFailed;
import io.castellan.ledger.domain.events.TransferSagaStarted;
import io.castellan.ledger.domain.ports.EventStore;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates a transfer as an explicit saga — three separately-committed, individually-balanced
 * transactions (reserve, then capture <em>or</em> compensate) rather than one atomic
 * {@link PostTransactionHandler#handle} call — because a transfer, unlike a same-tenant same-
 * moment posting, has a step in the middle (here: validating the destination can actually accept
 * the funds) that can fail *after* money has already, correctly, left the source account. A saga
 * makes that gap explicit and durably recoverable instead of pretending it doesn't exist.
 *
 * <h2>Why a saga and not one bigger atomic transaction</h2>
 *
 * {@link PostTransactionHandler#handle} already gives perfect atomicity <em>within</em> one
 * balanced transaction — if this were only ever a same-ledger, same-instant, always-both-accounts-
 * valid movement, one call would be enough, no saga needed. The saga earns its keep specifically
 * because "reserve, then decide" is a genuinely two-phase problem: fraud/compliance checks,
 * destination-account validation, or (in a fuller system than this one) an external payment rail
 * confirmation all naturally happen <em>between</em> taking the money from the source and giving
 * it to the destination, and any of them can fail. Compensation ({@link #compensate}) is what
 * makes that failure safe rather than a stuck, half-finished transfer.
 *
 * <h2>Resumability</h2>
 *
 * {@link #execute} always starts by deriving a saga id deterministically from
 * {@code (tenantId, idempotencyKey)} and replaying whatever's already in that saga's own event
 * stream — a retried {@link InitiateTransferCommand} (same idempotency key) resumes exactly where
 * the previous attempt left off rather than starting a second, concurrent transfer. Combined with
 * {@link PostTransactionHandler}'s own crash-safe idempotency (each saga step uses its own
 * deterministic, saga-scoped idempotency key), a crash at any point in this method is always
 * safe to retry from the top.
 */
public final class TransferSagaOrchestrator {

    private final EventStore eventStore;
    private final AccountRepository accountRepository;
    /** Deliberately a *separate* handler instance from whatever the API layer uses for
     * customer-initiated postings, typically configured with no (or a minimal) fraud rule set —
     * the reserve/capture/compensate postings are the system's own internal bookkeeping moves,
     * not a fresh customer-initiated transaction that should be re-scrutinized by the same
     * customer-facing fraud pipeline a second and third time. */
    private final PostTransactionHandler internalPostingHandler;
    private final Clock clock;

    public TransferSagaOrchestrator(
            EventStore eventStore,
            AccountRepository accountRepository,
            PostTransactionHandler internalPostingHandler,
            Clock clock) {
        this.eventStore = eventStore;
        this.accountRepository = accountRepository;
        this.internalPostingHandler = internalPostingHandler;
        this.clock = clock;
    }

    public TransferResult execute(InitiateTransferCommand command) {
        TenantId tenantId = command.tenantId();
        SagaId sagaId = deriveSagaId(tenantId, command.idempotencyKey());
        String sagaStream = Streams.saga(tenantId, sagaId);

        TransferSaga saga = TransferSaga.replay(eventStore.load(sagaStream));
        if (!saga.exists()) {
            saga = start(sagaStream, sagaId, command);
        }
        if (saga.state() == TransferSagaState.STARTED) {
            saga = reserveFunds(sagaStream, saga);
        }
        if (saga.state() == TransferSagaState.FUNDS_RESERVED) {
            saga = captureOrMarkFailed(sagaStream, saga);
        }
        if (saga.state() == TransferSagaState.FAILED) {
            saga = compensate(sagaStream, saga);
        }

        return switch (saga.state()) {
            case COMPLETED -> new TransferResult.Completed(sagaId);
            case COMPENSATED -> new TransferResult.Compensated(sagaId, saga.failureReason());
            case STARTED, FUNDS_RESERVED, FAILED ->
                    throw new IllegalStateException("saga " + sagaId + " did not reach a terminal state, stuck at " + saga.state());
        };
    }

    private static SagaId deriveSagaId(TenantId tenantId, IdempotencyKey key) {
        String name = "transfer|" + tenantId.value() + "|" + key.value();
        return new SagaId(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
    }

    private TransferSaga start(String sagaStream, SagaId sagaId, InitiateTransferCommand command) {
        TransferSagaStarted started = new TransferSagaStarted(
                sagaId, command.tenantId(), command.sourceAccountId(), command.destinationAccountId(),
                command.amount(), command.idempotencyKey(), clock.instant());
        eventStore.append(sagaStream, 0, List.of(started));
        return TransferSaga.replay(List.of(started));
    }

    private TransferSaga reserveFunds(String sagaStream, TransferSaga saga) {
        AccountId inTransit = ensureInTransitAccount(saga.tenantId(), saga.amount());
        IdempotencyKey stepKey = new IdempotencyKey("saga:" + saga.id() + ":reserve");
        var result = internalPostingHandler.handle(new PostTransactionCommand(
                saga.tenantId(),
                List.of(
                        new PostingRequest(saga.sourceAccountId(), EntryType.DEBIT, saga.amount()),
                        new PostingRequest(inTransit, EntryType.CREDIT, saga.amount())
                ),
                "transfer reservation for saga " + saga.id(),
                java.util.Map.of("sagaId", saga.id().toString(), "step", "reserve"),
                stepKey));

        FundsReserved reserved = new FundsReserved(saga.id(), saga.tenantId(), result.transactionId(), clock.instant());
        appendToSaga(sagaStream, saga, reserved);
        saga.apply(reserved);
        return saga;
    }

    private TransferSaga captureOrMarkFailed(String sagaStream, TransferSaga saga) {
        AccountId inTransit = ensureInTransitAccount(saga.tenantId(), saga.amount());
        DomainEvent outcome;
        try {
            IdempotencyKey stepKey = new IdempotencyKey("saga:" + saga.id() + ":capture");
            var result = internalPostingHandler.handle(new PostTransactionCommand(
                    saga.tenantId(),
                    List.of(
                            new PostingRequest(inTransit, EntryType.DEBIT, saga.amount()),
                            new PostingRequest(saga.destinationAccountId(), EntryType.CREDIT, saga.amount())
                    ),
                    "transfer capture for saga " + saga.id(),
                    java.util.Map.of("sagaId", saga.id().toString(), "step", "capture"),
                    stepKey));
            outcome = new TransferCompleted(saga.id(), saga.tenantId(), result.transactionId(), clock.instant());
        } catch (RuntimeException failure) {
            outcome = new TransferFailed(saga.id(), saga.tenantId(),
                    "capture step failed: " + failure.getMessage(), clock.instant());
        }
        appendToSaga(sagaStream, saga, outcome);
        saga.apply(outcome);
        return saga;
    }

    private TransferSaga compensate(String sagaStream, TransferSaga saga) {
        AccountId inTransit = ensureInTransitAccount(saga.tenantId(), saga.amount());
        IdempotencyKey stepKey = new IdempotencyKey("saga:" + saga.id() + ":compensate");
        var result = internalPostingHandler.handle(new PostTransactionCommand(
                saga.tenantId(),
                List.of(
                        new PostingRequest(inTransit, EntryType.DEBIT, saga.amount()),
                        new PostingRequest(saga.sourceAccountId(), EntryType.CREDIT, saga.amount())
                ),
                "transfer compensation for saga " + saga.id() + " (" + saga.failureReason() + ")",
                java.util.Map.of("sagaId", saga.id().toString(), "step", "compensate"),
                stepKey));

        TransferCompensated compensated = new TransferCompensated(saga.id(), saga.tenantId(), result.transactionId(), clock.instant());
        appendToSaga(sagaStream, saga, compensated);
        saga.apply(compensated);
        return saga;
    }

    private void appendToSaga(String sagaStream, TransferSaga saga, DomainEvent event) {
        eventStore.append(sagaStream, saga.version(), List.of(event));
    }

    /** Auto-provisions the tenant's in-transit suspense account for this currency on first use —
     * a real deployment might instead provision it explicitly as part of tenant onboarding, but
     * lazily creating it here means the saga never fails purely because of missing setup. */
    private AccountId ensureInTransitAccount(TenantId tenantId, io.castellan.ledger.domain.Money amount) {
        AccountId id = AccountId.systemAccount(tenantId, "IN_TRANSIT", amount.currency().getCurrencyCode());
        Account account = accountRepository.load(tenantId, id);
        if (!account.exists()) {
            var opened = Account.open(id, tenantId, amount.currency(), "SYSTEM:in-transit", clock.instant());
            accountRepository.append(tenantId, id, 0, List.of(opened));
        }
        return id;
    }
}
