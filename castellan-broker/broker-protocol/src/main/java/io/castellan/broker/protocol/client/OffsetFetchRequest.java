package io.castellan.broker.protocol.client;

/** Asks for {@code (groupId, topic, partition)}'s last committed offset, e.g. right after a
 * {@code JoinGroup} response hands this consumer a newly (re)assigned partition, so it knows where
 * to resume {@code Fetch}ing from. */
public record OffsetFetchRequest(String groupId, String topic, int partition) implements ClientMessage {
}
