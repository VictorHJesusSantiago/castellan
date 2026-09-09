package io.castellan.ledger.application.results;

import io.castellan.ledger.domain.SagaId;

public sealed interface TransferResult permits TransferResult.Completed, TransferResult.Compensated {

    SagaId sagaId();

    record Completed(SagaId sagaId) implements TransferResult {
    }

    /** The transfer did not go through, but the money is accounted for — see
     * {@code TransferFailed}/{@code TransferCompensated}'s own docs for exactly what "safe"
     * means here: the source account was debited and then credited back, net effect zero, with
     * a full audit trail of why. */
    record Compensated(SagaId sagaId, String reason) implements TransferResult {
    }
}
