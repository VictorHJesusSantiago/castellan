package io.castellan.ledger.infrastructure.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;

/**
 * Polls the {@code outbox} table for unpublished rows and hands each to an {@link OutboxPublisher},
 * marking it published only after the publish call returns successfully. This is a deliberately
 * separate process from {@code JdbcEventStore.append} -- the whole point of the outbox pattern is
 * that "an event was durably committed" (append's job) and "something downstream was notified"
 * (this class's job) are two independently-reliable facts, each retryable on its own, rather than
 * one fragile step that can half-succeed. {@link #relay()} is normally invoked on a fixed delay by
 * Spring's scheduler (wired via {@code @EnableScheduling} in {@code ledger-api}), but is also a
 * plain public method a test can call directly and synchronously.
 */
public final class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final OutboxPublisher publisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public OutboxRelay(JdbcTemplate jdbc, OutboxPublisher publisher, TransactionTemplate transactionTemplate, Clock clock) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** Publishes up to one batch of unpublished outbox rows. Returns how many were published, so
     * callers (tests especially) can assert real progress without depending on log output. */
    public int relay() {
        return transactionTemplate.execute(status -> {
            List<OutboxEvent> batch = jdbc.query(
                    "SELECT id, stream_id, event_type, payload_json, occurred_at FROM outbox "
                            + "WHERE published = FALSE ORDER BY id ASC LIMIT " + BATCH_SIZE,
                    (rs, i) -> new OutboxEvent(
                            rs.getLong("id"),
                            rs.getString("stream_id"),
                            rs.getString("event_type"),
                            rs.getString("payload_json"),
                            rs.getTimestamp("occurred_at").toInstant()));

            Timestamp now = Timestamp.from(clock.instant());
            int publishedCount = 0;
            for (OutboxEvent event : batch) {
                try {
                    publisher.publish(event);
                } catch (RuntimeException publishFailure) {
                    log.warn("outbox publish failed for id={}, will retry on next poll", event.id(), publishFailure);
                    continue;
                }
                jdbc.update("UPDATE outbox SET published = TRUE, published_at = ? WHERE id = ?", now, event.id());
                publishedCount++;
            }
            return publishedCount;
        });
    }
}
