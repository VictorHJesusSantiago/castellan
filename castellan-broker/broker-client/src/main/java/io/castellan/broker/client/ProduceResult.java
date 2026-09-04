package io.castellan.broker.client;

/** The outcome of a successful {@link Producer#send} — {@code offset} is the position the record
 * now occupies in {@code (topic, partition)}, or (for a deduplicated idempotent retry) the offset
 * the original attempt was assigned. */
public record ProduceResult(String topic, int partition, long offset) {
}
