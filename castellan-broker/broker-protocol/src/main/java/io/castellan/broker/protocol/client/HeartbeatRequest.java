package io.castellan.broker.protocol.client;

/** Sent periodically by every group member so the coordinator can detect a dead member (see
 * {@code broker-server}'s {@code GroupCoordinator} session-timeout sweep) without waiting for a
 * full session timeout on every poll cycle. */
public record HeartbeatRequest(
        String groupId,
        String memberId,
        int generationId
) implements ClientMessage {
}
