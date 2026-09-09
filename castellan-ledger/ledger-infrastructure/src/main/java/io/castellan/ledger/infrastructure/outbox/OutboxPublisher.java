package io.castellan.ledger.infrastructure.outbox;

/**
 * The pluggable "hand this durably-committed event to whatever's outside this database" step of
 * the transactional outbox pattern. {@link OutboxRelay} calls this once per unpublished row, in
 * commit order, and only marks a row published after this call returns without throwing -- a
 * throwing publisher leaves the row unpublished for the next poll to retry, so an implementation
 * needs to be safe to call more than once for the same event (at-least-once delivery, the
 * standard and only honest guarantee a poll-and-publish relay can make without a two-phase commit
 * with the downstream system itself).
 *
 * <p>{@link LoggingOutboxPublisher} is this project's only implementation: a deliberate stand-in
 * for a real message bus, documented as such rather than pretending to be one. A real deployment
 * would swap in an implementation that publishes to Kafka, or to this very project's own
 * {@code castellan-broker}, keyed by tenant/stream so downstream consumers can partition and
 * order correctly.
 */
public interface OutboxPublisher {

    void publish(OutboxEvent event);
}
