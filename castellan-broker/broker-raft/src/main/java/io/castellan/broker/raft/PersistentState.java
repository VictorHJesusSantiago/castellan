package io.castellan.broker.raft;

import java.util.Optional;

/**
 * The two pieces of per-server state the Raft paper (Figure 2) requires to be durable *before* a
 * server responds to any RPC: {@code currentTerm} and {@code votedFor}. (The log itself is the
 * third piece of persistent state; it has its own abstraction, {@link RaftLog}, since it has very
 * different access patterns and grows unboundedly.)
 *
 * <p>Losing {@code votedFor} across a crash-and-restart is a real safety violation, not a
 * theoretical one: a server that restarts, forgets it already voted for candidate A in term 5, and
 * then votes for candidate B in the same term 5 can produce two leaders in one term — the exact
 * split-brain Raft's single-vote-per-term rule exists to prevent. {@link RaftNode} calls
 * {@link #save(long, String)} synchronously before including a granted vote or an updated term in
 * any outbound message; a real implementation's {@link PersistentState} must block until the write
 * is durable (fsync'd) before returning, or this guarantee is only theater.
 */
public interface PersistentState {

    long currentTerm();

    Optional<String> votedFor();

    /** Durably persists both fields together, as one atomic unit. */
    void save(long currentTerm, String votedFor);
}
