package io.castellan.broker.protocol.client;

/** An explicit, graceful departure (as opposed to being evicted after missing heartbeats) —
 * triggers an immediate rebalance of the remaining members rather than waiting out a session
 * timeout first. */
public record LeaveGroupRequest(String groupId, String memberId) implements ClientMessage {
}
