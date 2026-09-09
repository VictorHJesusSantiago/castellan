package io.castellan.ledger.infrastructure.idempotency;

import io.castellan.ledger.application.ports.IdempotencyStore;
import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The real {@link IdempotencyStore}: a primary key on {@code (tenant_id, idempotency_key)} is the
 * entire race-safety mechanism. {@link #reserve} always attempts the {@code INSERT} first; a
 * unique-constraint violation means someone (possibly a concurrent retry of the very same
 * logical request) already holds this key. If that existing reservation happens to name the exact
 * same {@link TransactionId} we were about to reserve, this call is a harmless repeat of work
 * already done and returns normally -- matching {@code InMemoryIdempotencyStore}'s test-double
 * semantics exactly, since {@code PostTransactionHandler} itself only ever calls {@link #reserve}
 * after a {@link #find} that returned empty, so this branch exists purely to keep the two
 * implementations behaviorally identical rather than because the handler depends on it.
 */
public final class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcIdempotencyStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<TransactionId> find(TenantId tenantId, IdempotencyKey key) {
        List<String> rows = jdbc.query(
                "SELECT transaction_id FROM idempotency_reservations WHERE tenant_id = ? AND idempotency_key = ?",
                (rs, i) -> rs.getString(1), tenantId.value().toString(), key.value());
        return rows.isEmpty() ? Optional.empty() : Optional.of(new TransactionId(UUID.fromString(rows.get(0))));
    }

    @Override
    public void reserve(TenantId tenantId, IdempotencyKey key, TransactionId transactionId) {
        try {
            jdbc.update(
                    "INSERT INTO idempotency_reservations (tenant_id, idempotency_key, transaction_id, reserved_at) "
                            + "VALUES (?, ?, ?, ?)",
                    tenantId.value().toString(), key.value(), transactionId.value().toString(),
                    Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException conflict) {
            Optional<TransactionId> existing = find(tenantId, key);
            if (existing.isPresent() && existing.get().equals(transactionId)) {
                return;
            }
            throw new AlreadyReservedException(tenantId, key);
        }
    }
}
