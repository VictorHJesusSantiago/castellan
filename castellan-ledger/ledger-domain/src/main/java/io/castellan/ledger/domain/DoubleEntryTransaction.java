package io.castellan.ledger.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;

/**
 * An immutable, balanced group of {@link Posting}s — the actual unit of truth in this ledger.
 * "Balanced" is enforced, not documented: {@link #of} is the only way to construct one, and it
 * groups postings by currency and requires {@code sum(DEBIT) == sum(CREDIT)} <em>within each
 * currency</em> (a transfer that also does an FX conversion is modeled as two balanced
 * transactions plus an FX-spread posting, never as one transaction balanced only in aggregate
 * across currencies — allowing that would let a bug quietly launder a shortfall in one currency
 * behind a surplus in another).
 */
public record DoubleEntryTransaction(
        TransactionId id,
        TenantId tenantId,
        List<Posting> postings,
        String description,
        Instant occurredAt,
        Map<String, String> metadata
) {

    public DoubleEntryTransaction {
        if (id == null || tenantId == null || occurredAt == null) {
            throw new IllegalArgumentException("id, tenantId, and occurredAt must not be null");
        }
        if (postings == null || postings.size() < 2) {
            throw new IllegalArgumentException("a double-entry transaction needs at least 2 postings");
        }
        postings = List.copyOf(postings);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        description = description == null ? "" : description;
        requireBalancedPerCurrency(postings);
    }

    public static DoubleEntryTransaction of(
            TransactionId id, TenantId tenantId, List<Posting> postings, String description,
            Instant occurredAt, Map<String, String> metadata) {
        return new DoubleEntryTransaction(id, tenantId, postings, description, occurredAt, metadata);
    }

    private static void requireBalancedPerCurrency(List<Posting> postings) {
        Map<Currency, long[]> totals = new java.util.HashMap<>();
        for (Posting p : postings) {
            long[] bucket = totals.computeIfAbsent(p.amount().currency(), c -> new long[2]);
            if (p.entryType() == EntryType.DEBIT) {
                bucket[0] = Math.addExact(bucket[0], p.amount().minorUnits());
            } else {
                bucket[1] = Math.addExact(bucket[1], p.amount().minorUnits());
            }
        }
        for (Map.Entry<Currency, long[]> e : totals.entrySet()) {
            long debitTotal = e.getValue()[0];
            long creditTotal = e.getValue()[1];
            if (debitTotal != creditTotal) {
                throw new UnbalancedTransactionException(e.getKey(), debitTotal, creditTotal);
            }
        }
    }

    /** Every account this transaction touches, in posting order, duplicates included if an
     * account appears in more than one posting. */
    public List<AccountId> affectedAccounts() {
        return postings.stream().map(Posting::accountId).toList();
    }
}
