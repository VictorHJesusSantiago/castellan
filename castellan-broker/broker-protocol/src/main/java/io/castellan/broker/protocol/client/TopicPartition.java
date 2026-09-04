package io.castellan.broker.protocol.client;

/** One partition of one topic — the unit consumer-group assignment is expressed in terms of. */
public record TopicPartition(String topic, int partition) {
}
