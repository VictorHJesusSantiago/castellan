package io.castellan.broker.protocol.client;

import java.util.List;

/** {@code generationId} increments every time the coordinator recomputes the group's assignment
 * (a member joining, leaving, or being evicted for a missed heartbeat) — every subsequent
 * {@code Heartbeat}/{@code OffsetCommit} the member sends must carry this exact value, and the
 * coordinator rejects a stale one with {@code ILLEGAL_GENERATION}/{@code REBALANCE_IN_PROGRESS} so
 * a member can never act on an assignment that has already been superseded. */
public record JoinGroupResponse(
        ErrorCode errorCode,
        String leaderHint,
        int generationId,
        String memberId,
        List<TopicPartition> assignedPartitions
) implements ClientMessage {

    public JoinGroupResponse {
        assignedPartitions = List.copyOf(assignedPartitions);
    }
}
