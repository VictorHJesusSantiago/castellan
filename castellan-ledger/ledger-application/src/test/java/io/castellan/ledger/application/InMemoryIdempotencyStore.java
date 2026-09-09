package io.castellan.ledger.application;

import io.castellan.ledger.application.ports.IdempotencyStore;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, TransactionId> reservations = new ConcurrentHashMap<>();

    @Override
    public Optional<TransactionId> find(TenantId tenantId, IdempotencyKey key) {
        return Optional.ofNullable(reservations.get(compositeKey(tenantId, key)));
    }

    @Override
    public void reserve(TenantId tenantId, IdempotencyKey key, TransactionId transactionId) {
        TransactionId existing = reservations.putIfAbsent(compositeKey(tenantId, key), transactionId);
        if (existing != null && !existing.equals(transactionId)) {
            throw new AlreadyReservedException(tenantId, key);
        }
    }

    private static String compositeKey(TenantId tenantId, IdempotencyKey key) {
        return tenantId.value() + "|" + key.value();
    }
}
