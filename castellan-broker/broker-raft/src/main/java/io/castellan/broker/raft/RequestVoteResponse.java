package io.castellan.broker.raft;

/** The reply to a {@link RequestVoteRequest}. {@code voterId} identifies who's answering, so the
 * candidate's {@link RaftNode#handleRequestVoteResponse} can tally votes per-peer and never
 * double-count a retransmitted response from the same voter. */
public record RequestVoteResponse(
        long term,
        boolean voteGranted,
        String voterId
) implements RaftMessage {
}
