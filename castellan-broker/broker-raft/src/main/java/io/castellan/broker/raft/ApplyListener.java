package io.castellan.broker.raft;

/**
 * Invoked by {@link RaftNode} exactly once per log entry, in log order, the moment that entry
 * becomes committed on <em>this</em> server (its own {@code commitIndex} has advanced past it) —
 * on the leader, once a majority has replicated it; on a follower, once the leader's advancing
 * {@code leaderCommit} says it's safe. This is the one place Raft's guarantee ("every server
 * applies the same commands in the same order") becomes observable to the rest of the system —
 * {@code broker-storage} wires this to actually writing the entry into the partitioned log.
 */
@FunctionalInterface
public interface ApplyListener {
    void onApply(LogEntry entry);
}
