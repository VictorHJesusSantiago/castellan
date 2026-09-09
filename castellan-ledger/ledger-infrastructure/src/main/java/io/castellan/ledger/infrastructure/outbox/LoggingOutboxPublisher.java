package io.castellan.ledger.infrastructure.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default {@link OutboxPublisher}: logs the event and returns. This is explicitly a stand-in
 * for a real message bus integration, not one -- see {@link OutboxPublisher}'s own docs. It exists
 * so the outbox pattern's "durably committed" / "handed to a publisher" split is real and
 * observable (the {@code outbox.published}/{@code published_at} columns genuinely flip once this
 * runs) even though nothing outside this JVM actually receives anything in this project.
 */
public final class LoggingOutboxPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info("outbox publish stand-in: stream={} type={} occurredAt={} (a real deployment would forward "
                        + "this to Kafka or castellan-broker instead of only logging it)",
                event.streamId(), event.eventType(), event.occurredAt());
    }
}
