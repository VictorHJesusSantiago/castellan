package io.castellan.broker.protocol.client;

import java.util.List;

/**
 * Joins {@code groupId}, or re-joins after a rebalance/session-timeout. {@code memberId} is empty
 * ({@code ""}) for a brand-new member — the coordinator assigns a fresh id and returns it in
 * {@link JoinGroupResponse}; a returning member (rejoining after {@code REBALANCE_IN_PROGRESS} or
 * after being kicked for a missed heartbeat and wanting back in) passes its previously assigned id
 * so the coordinator can tell "this is the same logical member re-establishing membership" apart
 * from "this is a genuinely new member", which matters for the deterministic round-robin
 * assignment (see {@code broker-server}'s {@code GroupCoordinator}) being stable across a rebalance
 * that doesn't actually change the member set.
 *
 * <p>This protocol simplifies Kafka's own two-phase JoinGroup+SyncGroup handshake into one
 * round trip: the coordinator computes the assignment itself (a deterministic round-robin over
 * sorted member ids) and returns this caller's slice of it directly in {@link JoinGroupResponse},
 * rather than electing one group member as an ad-hoc "group leader" responsible for computing and
 * distributing the whole assignment via a second RPC. This is a real, deliberate scope
 * simplification: it loses Kafka's pluggable-assignment-strategy flexibility, but a rebalance is
 * fully decided and applied in one request instead of needing every member to complete two phases
 * in lockstep — simpler to implement correctly and to test.
 */
public record JoinGroupRequest(
        String groupId,
        String memberId,
        List<String> topics,
        int sessionTimeoutMs
) implements ClientMessage {

    public JoinGroupRequest {
        topics = List.copyOf(topics);
    }
}
