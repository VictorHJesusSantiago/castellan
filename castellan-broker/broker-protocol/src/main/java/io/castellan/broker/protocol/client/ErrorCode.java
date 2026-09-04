package io.castellan.broker.protocol.client;

/**
 * Every error a client-facing response can carry, as a closed enum rather than a free-text
 * message — a client needs to branch on <em>which</em> error occurred (retry against a different
 * node on {@link #NOT_LEADER}, re-join the group on {@link #REBALANCE_IN_PROGRESS}, surface
 * everything else as a hard failure), and matching on a small fixed set is both safer and cheaper
 * than parsing a string.
 */
public enum ErrorCode {
    /** No error; the response's other fields are meaningful. */
    NONE,
    /** This node isn't the current Raft leader and can't accept the write — see the response's
     * {@code leaderHint} field for where to retry, if known. */
    NOT_LEADER,
    /** The requested (topic, partition) doesn't exist under this broker's configuration. */
    UNKNOWN_TOPIC_OR_PARTITION,
    /** The request's {@code generationId} is behind the coordinator's current one — the caller's
     * view of group membership is stale; it must re-join before it can be used again. */
    ILLEGAL_GENERATION,
    /** The request's {@code memberId} is not (or no longer) a member of the group. */
    UNKNOWN_MEMBER_ID,
    /** The group's membership changed since the caller last joined; its assignment may no longer
     * be valid and it should call JoinGroup again to get a current one. */
    REBALANCE_IN_PROGRESS,
    /** An unexpected server-side failure unrelated to any of the above. */
    INTERNAL_ERROR
}
