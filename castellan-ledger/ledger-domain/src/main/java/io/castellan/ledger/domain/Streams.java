package io.castellan.ledger.domain;

/**
 * The one place stream-id naming is decided — both replay logic (this codebase) and
 * {@code ledger-infrastructure}'s storage layer must agree on these exact strings, so it lives in
 * {@code ledger-domain} rather than being reinvented per caller.
 *
 * <p>Three kinds of stream, each with a different concurrency shape:
 * <ul>
 *   <li>{@link #account} — one per account, for lifecycle events only (open/freeze/close).
 *       Low write frequency, low contention.</li>
 *   <li>{@link #ledger} — one per <em>tenant</em>, for every {@code TransactionPosted}. All of a
 *       tenant's transactions optimistic-concurrency-check against this single stream's version,
 *       which deliberately serializes a tenant's writes — the simplest possible correctness story
 *       for "the ledger is one true append-only sequence", at the cost of some throughput ceiling
 *       per tenant. A sharded/partitioned ledger stream is a legitimate future evolution, not
 *       attempted here (see the project's own ROADMAP).</li>
 *   <li>{@link #saga} — one per transfer saga instance, for its own lifecycle events.</li>
 * </ul>
 */
public final class Streams {

    private Streams() {
    }

    public static String account(TenantId tenantId, AccountId accountId) {
        return "account-" + tenantId.value() + "-" + accountId.value();
    }

    public static String ledger(TenantId tenantId) {
        return "ledger-" + tenantId.value();
    }

    public static String saga(TenantId tenantId, SagaId sagaId) {
        return "saga-" + tenantId.value() + "-" + sagaId.value();
    }
}
