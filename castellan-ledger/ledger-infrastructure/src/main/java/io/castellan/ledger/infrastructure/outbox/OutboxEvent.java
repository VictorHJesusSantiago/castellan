package io.castellan.ledger.infrastructure.outbox;

import java.time.Instant;

/** One row read from the {@code outbox} table -- everything an {@link OutboxPublisher} needs to
 * hand the event to whatever downstream system it fronts. */
public record OutboxEvent(
        long id,
        String streamId,
        String eventType,
        String payloadJson,
        Instant occurredAt
) {
}
