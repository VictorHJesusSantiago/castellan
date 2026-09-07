package io.castellan.broker.raft;

import java.util.Optional;

/** The outcome of {@link RaftNode#proposeCommand}. */
public sealed interface ProposeResult permits ProposeResult.Accepted, ProposeResult.NotLeader {

    /** The command was appended to this (leader's) log at {@code index}/{@code term}. Being
     * appended is not the same as being committed — wait for {@link ApplyListener#onApply} with
     * a matching index to know it's durably agreed upon by a majority. */
    record Accepted(long index, long term) implements ProposeResult {
    }

    /** This node isn't the leader, so it can't accept writes. {@code leaderHint} is the last
     * leader this node has observed (from an AppendEntries it accepted), if any — enough for a
     * client to retry against the right node most of the time, though it can be stale. */
    record NotLeader(Optional<String> leaderHint) implements ProposeResult {
    }
}
