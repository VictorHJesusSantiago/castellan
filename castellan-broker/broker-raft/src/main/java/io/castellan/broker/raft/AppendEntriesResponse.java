package io.castellan.broker.raft;

/**
 * The reply to an {@link AppendEntriesRequest}.
 *
 * <p>On success, {@code matchIndex} tells the leader exactly how far this follower's log now
 * matches the leader's — used directly to update {@code matchIndex}/{@code nextIndex} for that
 * peer (Raft paper Figure 2's "leaders" rules).
 *
 * <p>On failure, {@code conflictTerm}/{@code conflictIndex} implement the log-backtracking
 * optimization from the paper's §5.3 footnote (and universally adopted by real implementations
 * instead of the naive "decrement nextIndex by one and retry" the main text describes, which needs
 * one round trip per divergent entry — untenable after a follower has been partitioned for a
 * while): {@code conflictTerm} is the term of the entry the follower found at {@code prevLogIndex}
 * (or {@code -1} if the follower's log doesn't even reach {@code prevLogIndex}), and
 * {@code conflictIndex} is the first index in the follower's log holding that term (or, in the
 * log-too-short case, one past the follower's last index) — see
 * {@link RaftNode#handleAppendEntriesResponse} for exactly how the leader uses these to jump
 * {@code nextIndex} back by more than one entry at a time.
 */
public record AppendEntriesResponse(
        long term,
        boolean success,
        String followerId,
        long matchIndex,
        long conflictTerm,
        long conflictIndex
) implements RaftMessage {
}
