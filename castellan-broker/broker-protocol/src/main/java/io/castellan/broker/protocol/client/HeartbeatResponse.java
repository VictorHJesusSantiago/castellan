package io.castellan.broker.protocol.client;

/** {@code errorCode == REBALANCE_IN_PROGRESS} is how a member first learns its assignment is
 * stale (another member joined, left, or was evicted since this member's generation) — it must
 * call {@code JoinGroup} again to get a current assignment before continuing to poll. */
public record HeartbeatResponse(
        ErrorCode errorCode,
        String leaderHint
) implements ClientMessage {
}
