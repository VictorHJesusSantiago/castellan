package io.castellan.broker.protocol.client;

/** Durably records {@code offset} as {@code (groupId, topic, partition)}'s next offset to resume
 * from. Committed via the same replicated command log a produce uses (see {@code broker-server}'s
 * {@code OffsetCommitCommand}), so a committed offset survives a leader failover — unlike group
 * membership itself, which this design deliberately does not replicate (see this project's
 * top-level report on that scope cut). {@code generationId} must match the coordinator's current
 * one for the same staleness reasons as {@link HeartbeatRequest}. */
public record OffsetCommitRequest(
        String groupId,
        String memberId,
        int generationId,
        String topic,
        int partition,
        long offset
) implements ClientMessage {
}
