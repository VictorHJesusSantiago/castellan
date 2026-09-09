package io.castellan.ledger.application.saga;

/** The transfer saga's states, in the order they're reached on the happy path: STARTED (recorded
 * intent, nothing moved yet) -> FUNDS_RESERVED (source debited into the in-transit account) ->
 * either COMPLETED (in-transit debited, destination credited) or, if something goes wrong after
 * reservation, FAILED -> COMPENSATED (in-transit debited back, source credited — the compensating
 * transaction). COMPLETED and COMPENSATED are the only two terminal states. */
public enum TransferSagaState {
    STARTED,
    FUNDS_RESERVED,
    COMPLETED,
    FAILED,
    COMPENSATED
}
