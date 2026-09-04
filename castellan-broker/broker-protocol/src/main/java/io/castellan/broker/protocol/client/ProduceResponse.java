package io.castellan.broker.protocol.client;

/** {@code offset} is the position the record now occupies in the partition (or, for a deduped
 * idempotent retry, the offset the original attempt was assigned). {@code leaderHint}, populated
 * only when {@code errorCode == NOT_LEADER}, is the node id of the current leader if this follower
 * has observed one — good enough for a client to retry against the right node most of the time,
 * matching {@code broker-raft}'s own {@code ProposeResult.NotLeader} contract. */
public record ProduceResponse(
        ErrorCode errorCode,
        String leaderHint,
        long offset
) implements ClientMessage {
}
