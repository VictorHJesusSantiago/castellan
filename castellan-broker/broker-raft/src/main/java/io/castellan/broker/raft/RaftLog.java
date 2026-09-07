package io.castellan.broker.raft;

import java.util.List;
import java.util.Optional;

/**
 * The replicated log abstraction {@link RaftNode} programs against — it never touches a file or a
 * database directly. {@code broker-storage} provides the real segment-file-backed implementation;
 * {@link InMemoryRaftLog} here exists purely so {@code broker-raft} has zero dependency on
 * {@code broker-storage} (the layering runs the other way — storage depends on raft, not vice
 * versa, since storage's {@code RaftLog} implementation must satisfy an interface raft defines)
 * and so the deterministic simulation tests in this module never touch a filesystem.
 *
 * <p>Indices are 1-based, matching the Raft paper's own convention exactly: index 0 is the
 * sentinel "before the log begins" position, always defined to have term 0.
 */
public interface RaftLog {

    /** The index of the last entry in the log, or 0 if the log is empty. */
    long lastIndex();

    /** The term of the entry at {@code index}, or 0 if {@code index} is 0 (the sentinel). */
    long term(long index);

    /** The entry at {@code index}, if the log currently holds one there. */
    Optional<LogEntry> get(long index);

    /**
     * Entries from {@code fromIndexInclusive} to {@link #lastIndex()}, in order. Returns an empty
     * list if {@code fromIndexInclusive > lastIndex()}.
     */
    List<LogEntry> entriesFrom(long fromIndexInclusive);

    /**
     * Appends {@code entry} at the end of the log. The caller ({@link RaftNode}) is responsible for
     * ensuring {@code entry.index() == lastIndex() + 1} — this is an invariant of correct Raft
     * usage, not something the log itself needs to re-validate defensively on every call.
     */
    void append(LogEntry entry);

    /**
     * Deletes every entry from {@code fromIndexInclusive} through {@link #lastIndex()}. Used when a
     * follower's log conflicts with the leader's and must be truncated back to the point of
     * agreement (Raft paper §5.3, "If an existing entry conflicts with a new one...").
     */
    void truncateFrom(long fromIndexInclusive);
}
