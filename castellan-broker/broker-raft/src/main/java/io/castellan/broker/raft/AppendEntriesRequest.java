package io.castellan.broker.raft;

import java.util.List;

/**
 * Sent by the leader both to replicate log entries and, with {@code entries} empty, as a
 * heartbeat (Raft paper Figure 2 folds both into one RPC — a separate heartbeat message would
 * only duplicate the term/leader-legitimacy checks this one already does).
 */
public record AppendEntriesRequest(
        long term,
        String leaderId,
        long prevLogIndex,
        long prevLogTerm,
        List<LogEntry> entries,
        long leaderCommit
) implements RaftMessage {

    public AppendEntriesRequest {
        entries = List.copyOf(entries);
    }

    public boolean isHeartbeat() {
        return entries.isEmpty();
    }
}
