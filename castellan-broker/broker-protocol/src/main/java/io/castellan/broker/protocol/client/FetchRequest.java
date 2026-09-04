package io.castellan.broker.protocol.client;

/** Reads up to {@code maxRecords} records starting at the first offset {@code >=
 * fromOffsetInclusive}. Servable by any node (leader or follower) in this broker's design — every
 * committed record is replicated via Raft to every node's local {@code PartitionLog} before this
 * request can observe it, so reads don't need to be routed to the leader the way writes do. */
public record FetchRequest(
        String topic,
        int partition,
        long fromOffsetInclusive,
        int maxRecords
) implements ClientMessage {
}
