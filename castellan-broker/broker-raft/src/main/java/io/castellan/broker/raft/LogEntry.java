package io.castellan.broker.raft;

/**
 * One entry in the replicated log: the term in which the leader that created it was in office, the
 * entry's 1-based index in the log, and an opaque command payload — Raft itself never interprets
 * {@code command}, it only guarantees every server applies the same sequence of commands in the
 * same order (see {@link ApplyListener}). Index 0 is reserved as the sentinel "before the log
 * starts" position (see {@link RaftLog#term(long)}'s contract at index 0), so real entries are
 * indexed from 1, matching the paper's own convention exactly.
 */
public record LogEntry(long term, long index, byte[] command) {

    public LogEntry {
        if (term < 0) {
            throw new IllegalArgumentException("term must be >= 0, got " + term);
        }
        if (index < 1) {
            throw new IllegalArgumentException("index must be >= 1, got " + index);
        }
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
    }
}
