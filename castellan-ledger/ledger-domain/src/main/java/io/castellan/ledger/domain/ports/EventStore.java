package io.castellan.ledger.domain.ports;

import io.castellan.ledger.domain.events.DomainEvent;

import java.util.List;

/**
 * The one port every aggregate and process manager in this system is built on: an append-only,
 * per-stream event log with optimistic concurrency control. {@code ledger-infrastructure}
 * provides the real JDBC/SQL-backed implementation (the actual system of record); nothing in
 * {@code ledger-domain} or {@code ledger-application} knows or cares that it's SQL underneath —
 * they only ever see this interface.
 *
 * <p>A "stream" is an arbitrary string key an aggregate's repository derives deterministically
 * from its id (e.g. {@code "account-" + accountId}, {@code "saga-" + sagaId}) — {@link EventStore}
 * itself has no opinion on what a stream "means", only that events appended to the same stream
 * are strictly ordered and optimistic-concurrency-checked against each other.
 */
public interface EventStore {

    /**
     * Appends {@code events} to {@code streamId}, atomically, iff the stream currently has
     * exactly {@code expectedVersion} events already in it (its version <em>before</em> this
     * call) — the standard event-sourcing optimistic-concurrency check: two concurrent commands
     * against the same aggregate, both loaded from the same starting version, can't both
     * succeed, and the second one must reload and retry rather than silently clobbering the
     * first's effect. Pass {@code expectedVersion = 0} to append to (and thereby create) a brand
     * new stream.
     *
     * @throws ConcurrencyConflictException if the stream's actual version does not match
     *         {@code expectedVersion}
     */
    void append(String streamId, long expectedVersion, List<DomainEvent> events);

    /** Every event ever appended to {@code streamId}, in append order. Returns an empty list for
     * a stream that has never had anything appended to it — not an error, since "does this
     * aggregate exist yet" is exactly the question an empty replay result answers. */
    List<DomainEvent> load(String streamId);

    /** The number of events currently in {@code streamId} — {@code load(streamId).size()}, but
     * a real implementation can usually answer this without deserializing every event, so it's
     * its own method rather than a derived default. */
    long currentVersion(String streamId);
}
