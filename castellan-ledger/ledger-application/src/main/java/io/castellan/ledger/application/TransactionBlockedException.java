package io.castellan.ledger.application;

import io.castellan.ledger.application.fraud.FraudVerdict;
import io.castellan.ledger.domain.TransactionId;

/** Thrown by {@link PostTransactionHandler} when the fraud rule pipeline blocks a transaction.
 * The audit trail ({@code FraudFlagRaised} events) is still committed before this is thrown —
 * blocking a transaction is itself a fact worth remembering, not a reason to pretend nothing
 * happened. */
public final class TransactionBlockedException extends RuntimeException {

    private final TransactionId transactionId;
    private final FraudVerdict verdict;

    public TransactionBlockedException(TransactionId transactionId, FraudVerdict verdict) {
        super("transaction " + transactionId + " blocked by fraud rules: " + verdict.flags());
        this.transactionId = transactionId;
        this.verdict = verdict;
    }

    public TransactionId transactionId() {
        return transactionId;
    }

    public FraudVerdict verdict() {
        return verdict;
    }
}
