package io.castellan.ledger.application.saga;

import io.castellan.ledger.domain.AccountId;
import io.castellan.ledger.domain.Money;
import io.castellan.ledger.domain.SagaId;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;
import io.castellan.ledger.domain.events.DomainEvent;
import io.castellan.ledger.domain.events.FundsReserved;
import io.castellan.ledger.domain.events.TransferCompensated;
import io.castellan.ledger.domain.events.TransferCompleted;
import io.castellan.ledger.domain.events.TransferFailed;
import io.castellan.ledger.domain.events.TransferSagaStarted;

import java.util.List;

/**
 * A transfer saga's own event-sourced state — replayed from its own stream
 * ({@code Streams.saga(tenantId, sagaId)}) exactly like {@code Account} is replayed from its
 * stream, and for the same reason: {@link TransferSagaOrchestrator#execute} can be called again
 * on a saga that's already partway through (because the caller genuinely retried, or because a
 * previous call crashed mid-flight) and, by replaying first, resume from wherever it actually
 * left off rather than either restarting from scratch (double-spending the reservation step) or
 * refusing to proceed at all.
 */
public final class TransferSaga {

    private SagaId id;
    private TenantId tenantId;
    private AccountId sourceAccountId;
    private AccountId destinationAccountId;
    private Money amount;
    private TransferSagaState state;
    private TransactionId reservationTransactionId;
    private TransactionId settlementTransactionId;
    private String failureReason;
    private long version;

    private TransferSaga() {
    }

    public static TransferSaga replay(List<DomainEvent> events) {
        TransferSaga saga = new TransferSaga();
        for (DomainEvent event : events) {
            saga.apply(event);
        }
        return saga;
    }

    public void apply(DomainEvent event) {
        switch (event) {
            case TransferSagaStarted e -> {
                this.id = e.sagaId();
                this.tenantId = e.tenantId();
                this.sourceAccountId = e.sourceAccountId();
                this.destinationAccountId = e.destinationAccountId();
                this.amount = e.amount();
                this.state = TransferSagaState.STARTED;
            }
            case FundsReserved e -> {
                this.reservationTransactionId = e.reservationTransactionId();
                this.state = TransferSagaState.FUNDS_RESERVED;
            }
            case TransferCompleted e -> {
                this.settlementTransactionId = e.captureTransactionId();
                this.state = TransferSagaState.COMPLETED;
            }
            case TransferFailed e -> {
                this.failureReason = e.reason();
                this.state = TransferSagaState.FAILED;
            }
            case TransferCompensated e -> {
                this.settlementTransactionId = e.compensationTransactionId();
                this.state = TransferSagaState.COMPENSATED;
            }
            default -> {
            }
        }
        version++;
    }

    public boolean exists() {
        return id != null;
    }

    public SagaId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public AccountId sourceAccountId() {
        return sourceAccountId;
    }

    public AccountId destinationAccountId() {
        return destinationAccountId;
    }

    public Money amount() {
        return amount;
    }

    public TransferSagaState state() {
        return state;
    }

    public TransactionId reservationTransactionId() {
        return reservationTransactionId;
    }

    public TransactionId settlementTransactionId() {
        return settlementTransactionId;
    }

    public String failureReason() {
        return failureReason;
    }

    public long version() {
        return version;
    }
}
