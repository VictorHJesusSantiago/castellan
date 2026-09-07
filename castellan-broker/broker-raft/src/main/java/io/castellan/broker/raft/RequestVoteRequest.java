package io.castellan.broker.raft;

/**
 * Sent by a candidate to gather votes (Raft paper Figure 2). {@code candidateId} is carried
 * explicitly even though the shell also knows the sender from the transport layer, so
 * {@link RaftNode}'s handlers stay pure functions of "message in" with no dependency on
 * out-of-band transport metadata.
 */
public record RequestVoteRequest(
        long term,
        String candidateId,
        long lastLogIndex,
        long lastLogTerm
) implements RaftMessage {
}
