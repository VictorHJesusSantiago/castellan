package io.castellan.ledger.domain.events;

import io.castellan.ledger.domain.IdempotencyKey;
import io.castellan.ledger.domain.Posting;
import io.castellan.ledger.domain.TenantId;
import io.castellan.ledger.domain.TransactionId;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The event that is, in effect, the ledger itself — one balanced {@link Posting} group,
 * committed atomically. {@code AccountBalanceProjection} (in {@code ledger-infrastructure})
 * folds a stream of these into per-account running balances; nothing about an account's balance
 * is ever stored anywhere else. */
public record TransactionPosted(
        TransactionId transactionId,
        TenantId tenantId,
        List<Posting> postings,
        String description,
        Map<String, String> metadata,
        IdempotencyKey idempotencyKey,
        Instant occurredAt
) implements DomainEvent {

    public TransactionPosted {
        postings = List.copyOf(postings);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
