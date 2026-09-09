package io.castellan.ledger.application.ports;

import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;

import java.util.Optional;

/**
 * Maps a (tenant, idempotency key) pair to the transaction it already produced — the mechanism
 * that makes {@code PostTransactionHandler} genuinely idempotent, not just duplicate-rejecting.
 * A client that retries the exact same command after a timeout gets back the exact same
 * {@code TransactionResult} it would have gotten from the original attempt, not an error and not
 * a second transaction.
 *
 * <p>{@code ledger-infrastructure}'s implementation must make the "check, then record" sequence
 * ({@code PostTransactionHandler}'s use of {@link #find} then {@link #reserve}) race-safe under
 * concurrent retries of the same key — a unique constraint on {@code (tenantId, key)} in the
 * backing table, with {@link #reserve} translating a constraint violation into
 * {@link AlreadyReservedException} rather than a generic SQL error, is the intended shape.
 */
public interface IdempotencyStore {

    Optional<TransactionId> find(TenantId tenantId, IdempotencyKey key);

    /**
     * Atomically claims {@code key} for {@code transactionId} — the first caller for a given
     * (tenant, key) pair wins; every subsequent caller (including concurrent ones racing the
     * first) must get {@link AlreadyReservedException} rather than silently overwriting the
     * mapping, which is what would let two different transactions both believe they "own" the
     * same idempotency key.
     */
    void reserve(TenantId tenantId, IdempotencyKey key, TransactionId transactionId);

    final class AlreadyReservedException extends RuntimeException {
        public AlreadyReservedException(TenantId tenantId, IdempotencyKey key) {
            super("idempotency key already reserved for tenant " + tenantId + ": " + key.value());
        }
    }
}
