package io.castellan.ledger.domain;

/**
 * A client-supplied token identifying one logical write attempt, scoped to a tenant. Retrying the
 * same command with the same key — after a timeout, a crashed retry, or a duplicated network
 * request — must produce exactly the same result as the first attempt did, not a second
 * transaction; see {@code IdempotencyStore} and {@code PostTransactionHandler} for where that
 * guarantee is actually enforced. This type only validates the key's own shape.
 */
public record IdempotencyKey(String value) {

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
        if (value.length() > 200) {
            throw new IllegalArgumentException("idempotency key must be at most 200 characters, got " + value.length());
        }
    }
}
