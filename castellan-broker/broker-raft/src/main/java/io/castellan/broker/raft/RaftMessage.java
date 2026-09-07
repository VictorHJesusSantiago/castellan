package io.castellan.broker.raft;

/**
 * The four RPC message shapes from the Raft paper's Figure 2, as a sealed interface — a
 * {@code switch} over this type (see any consumer in {@code broker-server}) is exhaustive-checked
 * by the compiler, so adding a fifth message kind would be a compile error everywhere one is
 * missed, not a silent runtime gap.
 */
public sealed interface RaftMessage
        permits RequestVoteRequest, RequestVoteResponse, AppendEntriesRequest, AppendEntriesResponse {

    /** Every Raft RPC carries a term — the one place all four variants agree on a common accessor. */
    long term();
}
