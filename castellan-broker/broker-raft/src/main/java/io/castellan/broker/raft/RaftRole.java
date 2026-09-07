package io.castellan.broker.raft;

/**
 * The three roles a Raft server can be in (Figure 4 of the Raft paper). Every server starts as a
 * {@link #FOLLOWER}; the only transitions are FOLLOWER/CANDIDATE {@literal ->} CANDIDATE (election
 * timeout), CANDIDATE {@literal ->} LEADER (wins a majority of votes), and any role {@literal ->}
 * FOLLOWER (discovers a higher term, from any RPC or RPC response — see {@link RaftNode}'s single
 * "all servers" rule applied at the top of every handler).
 */
public enum RaftRole {
    FOLLOWER,
    CANDIDATE,
    LEADER
}
