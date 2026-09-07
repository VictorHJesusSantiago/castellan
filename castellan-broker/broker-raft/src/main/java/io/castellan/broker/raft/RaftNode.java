package io.castellan.broker.raft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A single Raft server's state machine, implementing every rule in Figure 2 of Ongaro &amp;
 * Ousterhout, "In Search of an Understandable Consensus Algorithm" (the "Raft paper") — leader
 * election (§5.2), log replication (§5.3), and the safety rules that make the two work together
 * without split-brain or lost commits (§5.4), including the paper's easy-to-get-wrong subtlety
 * that a leader may only <em>directly</em> commit a log entry from its own current term
 * ({@link #advanceCommitIndexIfPossible}, §5.4.2) — committing an older-term entry the moment a
 * majority merely holds a copy of it is the specific bug the "Figure 8" scenario in the paper is
 * about, and this implementation follows the paper's fix (commit it only indirectly, as a
 * byproduct of committing a later same-term entry) rather than the naive-and-unsafe shortcut.
 *
 * <h2>Design: a deterministic core, driven by an imperative shell</h2>
 *
 * This class touches no socket, no thread, and no clock. Every public method is a pure function
 * of "one input event, plus this object's current state" to "a new state, plus a
 * {@link HandleResult} describing what to send and whether to reset the election timer" — the
 * same {@code Ready()}-style pattern etcd's {@code raft} package popularized, chosen for the same
 * reason: it makes the hardest part of Raft (getting the state transitions exactly right)
 * testable by feeding a sequence of method calls and asserting on exact outputs, with no network
 * mocking, no sleeping in tests, and no flakiness. {@code RaftClusterSimulationTest} wires several
 * {@code RaftNode}s together purely by routing each one's returned {@link Envelope}s to the
 * addressed peer's handler, entirely on one test thread, and is where the interesting multi-node
 * scenarios (election under contention, partition and healing, a lagging follower catching up)
 * are actually proven correct.
 *
 * <h2>Thread safety</h2>
 *
 * None, deliberately: this class is <em>not</em> synchronized. A real deployment
 * ({@code broker-server}) must serialize every call into one {@code RaftNode} instance — the
 * standard way to do that correctly is a single-threaded event loop (one thread owns the node;
 * every timer firing, every inbound RPC, and every client write request becomes a task submitted
 * to that one thread), not locks around each method. Locks would make it easy to get individual
 * methods right while still allowing two threads to interleave in a way that violates a
 * multi-step invariant (e.g. "persist term, {@code then} decide the vote") — a single-threaded
 * owner sidesteps the whole category of bug by construction.
 */
public final class RaftNode {

    private final ClusterConfig config;
    private final RaftLog log;
    private final PersistentState persistentState;
    private final ApplyListener applyListener;

    private RaftRole role = RaftRole.FOLLOWER;
    private String currentLeaderId;
    private long commitIndex = 0;
    private long lastApplied = 0;

    private Set<String> votesReceived = new HashSet<>();

    private final Map<String, Long> nextIndex = new HashMap<>();
    private final Map<String, Long> matchIndex = new HashMap<>();

    public RaftNode(ClusterConfig config, RaftLog log, PersistentState persistentState, ApplyListener applyListener) {
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
        this.persistentState = Objects.requireNonNull(persistentState, "persistentState");
        this.applyListener = Objects.requireNonNull(applyListener, "applyListener");
    }


    public RaftRole role() {
        return role;
    }

    public long currentTerm() {
        return persistentState.currentTerm();
    }

    public Optional<String> currentLeaderId() {
        return Optional.ofNullable(currentLeaderId);
    }

    public long commitIndex() {
        return commitIndex;
    }

    public long lastApplied() {
        return lastApplied;
    }

    public boolean isLeader() {
        return role == RaftRole.LEADER;
    }

    public String selfId() {
        return config.selfId();
    }


    /**
     * Appends {@code command} to this leader's log. Returns immediately — appending is not the
     * same as committing; wait for {@link ApplyListener#onApply} to be called with a matching
     * index to know a majority has durably agreed. For low latency, call
     * {@link #handleHeartbeatTimeout()} right after a successful propose (in addition to on its
     * regular timer) — it reuses the exact same replication logic as a periodic heartbeat, and
     * sending immediately rather than waiting for the next tick is the difference between a
     * write's latency being "one round trip" and "one round trip plus up to one heartbeat
     * period".
     */
    public ProposeResult proposeCommand(byte[] command) {
        if (role != RaftRole.LEADER) {
            return new ProposeResult.NotLeader(currentLeaderId());
        }
        long newIndex = log.lastIndex() + 1;
        log.append(new LogEntry(currentTerm(), newIndex, command));
        return new ProposeResult.Accepted(newIndex, currentTerm());
    }


    /** The shell calls this when this node's randomized election timer fires with no valid
     * AppendEntries/granted-vote having reset it in the meantime. A no-op for a leader (leaders
     * don't run an election timer at all — the shell should simply not be running one while
     * {@link #role()} reports {@link RaftRole#LEADER}, but this method is defensively a no-op
     * either way). */
    public HandleResult handleElectionTimeout() {
        if (role == RaftRole.LEADER) {
            return HandleResult.NONE;
        }
        return becomeCandidateAndStartElection();
    }

    /** The shell calls this on its own fixed periodic cadence while this node is the leader (and,
     * per {@link #proposeCommand}'s own docs, immediately after every successful propose too). A
     * no-op for a non-leader. */
    public HandleResult handleHeartbeatTimeout() {
        if (role != RaftRole.LEADER) {
            return HandleResult.NONE;
        }
        return HandleResult.outboundOnly(buildAppendEntriesForAllPeers());
    }


    public HandleResult handleRequestVote(RequestVoteRequest req) {
        if (req.term() > currentTerm()) {
            stepDownToFollower(req.term());
        }
        if (req.term() < currentTerm()) {
            return HandleResult.outboundOnly(List.of(new Envelope(
                    req.candidateId(), new RequestVoteResponse(currentTerm(), false, config.selfId()))));
        }

        boolean canVote = persistentState.votedFor().isEmpty()
                || persistentState.votedFor().get().equals(req.candidateId());
        boolean candidateLogOk = candidateLogIsAtLeastAsUpToDate(req.lastLogTerm(), req.lastLogIndex());
        boolean granted = canVote && candidateLogOk;

        if (granted) {
            persistentState.save(currentTerm(), req.candidateId());
        }
        Envelope reply = new Envelope(req.candidateId(), new RequestVoteResponse(currentTerm(), granted, config.selfId()));
        return granted ? HandleResult.resettingTimer(List.of(reply)) : HandleResult.outboundOnly(List.of(reply));
    }

    public HandleResult handleRequestVoteResponse(String from, RequestVoteResponse resp) {
        if (resp.term() > currentTerm()) {
            stepDownToFollower(resp.term());
            return HandleResult.NONE;
        }
        if (role != RaftRole.CANDIDATE || resp.term() != currentTerm()) {
            return HandleResult.NONE;
        }
        if (resp.voteGranted()) {
            votesReceived.add(from);
            if (votesReceived.size() >= config.majority()) {
                return becomeLeader();
            }
        }
        return HandleResult.NONE;
    }


    public HandleResult handleAppendEntries(AppendEntriesRequest req) {
        if (req.term() > currentTerm()) {
            stepDownToFollower(req.term());
        }
        if (req.term() < currentTerm()) {
            return HandleResult.outboundOnly(List.of(new Envelope(req.leaderId(),
                    new AppendEntriesResponse(currentTerm(), false, config.selfId(), 0, -1, 0))));
        }

        role = RaftRole.FOLLOWER;
        currentLeaderId = req.leaderId();

        if (req.prevLogIndex() > log.lastIndex()) {
            return HandleResult.resettingTimer(List.of(new Envelope(req.leaderId(),
                    new AppendEntriesResponse(currentTerm(), false, config.selfId(), 0, -1, log.lastIndex() + 1))));
        }
        long termAtPrev = log.term(req.prevLogIndex());
        if (termAtPrev != req.prevLogTerm()) {
            long conflictTerm = termAtPrev;
            long conflictIndex = req.prevLogIndex();
            while (conflictIndex > 1 && log.term(conflictIndex - 1) == conflictTerm) {
                conflictIndex--;
            }
            return HandleResult.resettingTimer(List.of(new Envelope(req.leaderId(),
                    new AppendEntriesResponse(currentTerm(), false, config.selfId(), 0, conflictTerm, conflictIndex))));
        }

        long index = req.prevLogIndex();
        for (LogEntry newEntry : req.entries()) {
            index++;
            Optional<LogEntry> existing = log.get(index);
            if (existing.isPresent()) {
                if (existing.get().term() != newEntry.term()) {
                    log.truncateFrom(index);
                    log.append(newEntry);
                }
            } else {
                log.append(newEntry);
            }
        }

        long lastNewEntryIndex = req.prevLogIndex() + req.entries().size();
        if (req.leaderCommit() > commitIndex) {
            commitIndex = Math.min(req.leaderCommit(), lastNewEntryIndex);
            applyCommitted();
        }

        return HandleResult.resettingTimer(List.of(new Envelope(req.leaderId(),
                new AppendEntriesResponse(currentTerm(), true, config.selfId(), lastNewEntryIndex, -1, 0))));
    }

    public HandleResult handleAppendEntriesResponse(String from, AppendEntriesResponse resp) {
        if (resp.term() > currentTerm()) {
            stepDownToFollower(resp.term());
            return HandleResult.NONE;
        }
        if (role != RaftRole.LEADER || resp.term() != currentTerm()) {
            return HandleResult.NONE;
        }

        if (resp.success()) {
            matchIndex.put(from, resp.matchIndex());
            nextIndex.put(from, resp.matchIndex() + 1);

            List<Envelope> committedBroadcast = advanceCommitIndexIfPossible()
                    ? buildAppendEntriesForAllPeers()
                    : List.of();
            if (!committedBroadcast.isEmpty()) {
                return HandleResult.outboundOnly(committedBroadcast);
            }

            long ni = nextIndex.get(from);
            if (ni <= log.lastIndex()) {
                return HandleResult.outboundOnly(List.of(new Envelope(from, buildAppendEntriesRequestFor(from))));
            }
            return HandleResult.NONE;
        }

        long newNextIndex;
        if (resp.conflictTerm() == -1) {
            newNextIndex = resp.conflictIndex();
        } else {
            long lastIndexWithConflictTerm = -1;
            for (long i = log.lastIndex(); i >= 1; i--) {
                if (log.term(i) == resp.conflictTerm()) {
                    lastIndexWithConflictTerm = i;
                    break;
                }
            }
            newNextIndex = (lastIndexWithConflictTerm >= 1) ? lastIndexWithConflictTerm + 1 : resp.conflictIndex();
        }
        nextIndex.put(from, Math.max(1, newNextIndex));
        return HandleResult.outboundOnly(List.of(new Envelope(from, buildAppendEntriesRequestFor(from))));
    }


    private void stepDownToFollower(long newTerm) {
        persistentState.save(newTerm, null);
        role = RaftRole.FOLLOWER;
        currentLeaderId = null;
        votesReceived = new HashSet<>();
    }

    private HandleResult becomeCandidateAndStartElection() {
        long newTerm = persistentState.currentTerm() + 1;
        persistentState.save(newTerm, config.selfId());
        role = RaftRole.CANDIDATE;
        currentLeaderId = null;
        votesReceived = new HashSet<>();
        votesReceived.add(config.selfId());

        if (votesReceived.size() >= config.majority()) {
            return becomeLeader();
        }

        long lastLogIndex = log.lastIndex();
        long lastLogTerm = log.term(lastLogIndex);
        RequestVoteRequest req = new RequestVoteRequest(newTerm, config.selfId(), lastLogIndex, lastLogTerm);
        List<Envelope> envelopes = new ArrayList<>();
        for (String peer : config.peerIds()) {
            envelopes.add(new Envelope(peer, req));
        }
        return HandleResult.resettingTimer(envelopes);
    }

    private HandleResult becomeLeader() {
        role = RaftRole.LEADER;
        currentLeaderId = config.selfId();
        nextIndex.clear();
        matchIndex.clear();
        for (String peer : config.peerIds()) {
            nextIndex.put(peer, log.lastIndex() + 1);
            matchIndex.put(peer, 0L);
        }
        return HandleResult.outboundOnly(buildAppendEntriesForAllPeers());
    }

    private boolean candidateLogIsAtLeastAsUpToDate(long candidateLastLogTerm, long candidateLastLogIndex) {
        long myLastIndex = log.lastIndex();
        long myLastTerm = log.term(myLastIndex);
        if (candidateLastLogTerm != myLastTerm) {
            return candidateLastLogTerm > myLastTerm;
        }
        return candidateLastLogIndex >= myLastIndex;
    }

    /**
     * Raft paper §5.4.2's safety rule, applied exactly: find the highest N &gt; commitIndex such
     * that a majority of {@code matchIndex} (including this leader's own log, always "caught up"
     * with itself) is {@code >= N}, <strong>and</strong> {@code log.term(N) == currentTerm()} —
     * that last clause is what stops a leader from re-committing an older-term entry purely by
     * replication count, which Figure 8 of the paper shows can be unsafe (a later leader could
     * still overwrite it). Scanning from the top down and taking the first N that qualifies finds
     * the highest such N directly, with no need to scan the whole range from the bottom up.
     */
    /** Returns whether {@code commitIndex} actually advanced. */
    private boolean advanceCommitIndexIfPossible() {
        long lastIndex = log.lastIndex();
        for (long n = lastIndex; n > commitIndex; n--) {
            if (log.term(n) != currentTerm()) {
                continue;
            }
            int votes = 1;
            for (String peer : config.peerIds()) {
                if (matchIndex.getOrDefault(peer, 0L) >= n) {
                    votes++;
                }
            }
            if (votes >= config.majority()) {
                commitIndex = n;
                applyCommitted();
                return true;
            }
        }
        return false;
    }

    private void applyCommitted() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            log.get(lastApplied).ifPresent(applyListener::onApply);
        }
    }

    private List<Envelope> buildAppendEntriesForAllPeers() {
        List<Envelope> envelopes = new ArrayList<>();
        for (String peer : config.peerIds()) {
            envelopes.add(new Envelope(peer, buildAppendEntriesRequestFor(peer)));
        }
        return envelopes;
    }

    private AppendEntriesRequest buildAppendEntriesRequestFor(String peer) {
        long ni = nextIndex.getOrDefault(peer, log.lastIndex() + 1);
        long prevLogIndex = ni - 1;
        long prevLogTerm = log.term(prevLogIndex);
        List<LogEntry> entries = log.entriesFrom(ni);
        return new AppendEntriesRequest(currentTerm(), config.selfId(), prevLogIndex, prevLogTerm, entries, commitIndex);
    }
}
