package io.castellan.broker.raft;

import java.util.List;

/**
 * What handling one input event produced: zero or more {@link Envelope}s to send, and whether the
 * shell should reset its election-timeout timer. The reset flag exists because the *reason* to
 * reset is a Raft safety-relevant judgment call {@link RaftNode} is the only party positioned to
 * make correctly (Raft paper §5.2: reset on granting a vote, and on receiving a legitimate
 * AppendEntries from the current term's leader — including a failed consistency check, since a
 * failed check still proves the leader is alive) — pushing that decision into the shell would mean
 * duplicating Raft's own term/leader-legitimacy logic a second time outside this class.
 */
public record HandleResult(List<Envelope> outbound, boolean resetElectionTimer) {

    public static HandleResult outboundOnly(List<Envelope> outbound) {
        return new HandleResult(outbound, false);
    }

    public static HandleResult resettingTimer(List<Envelope> outbound) {
        return new HandleResult(outbound, true);
    }

    public static final HandleResult NONE = new HandleResult(List.of(), false);
}
