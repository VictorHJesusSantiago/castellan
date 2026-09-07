package io.castellan.broker.raft;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Behavior a single {@link RaftNode} exhibits without needing any peer — the cheapest, fastest
 * layer of tests, exercising the state machine's basic shape before the multi-node simulation
 * (see {@link RaftClusterSimulationTest}) exercises the interesting emergent behavior. */
class RaftNodeSingleNodeTest {

    private InMemoryRaftLog log;
    private InMemoryPersistentState state;
    private List<LogEntry> applied;
    private RaftNode node;

    @BeforeEach
    void setUp() {
        log = new InMemoryRaftLog();
        state = new InMemoryPersistentState();
        applied = new ArrayList<>();
        node = new RaftNode(new ClusterConfig("n1", Set.of("n2", "n3")), log, state, applied::add);
    }

    @Test
    void startsAsFollowerWithTermZero() {
        assertThat(node.role()).isEqualTo(RaftRole.FOLLOWER);
        assertThat(node.currentTerm()).isZero();
        assertThat(node.currentLeaderId()).isEmpty();
    }

    @Test
    void electionTimeoutTurnsFollowerIntoCandidateAndRequestsVotesFromEveryPeer() {
        HandleResult result = node.handleElectionTimeout();

        assertThat(node.role()).isEqualTo(RaftRole.CANDIDATE);
        assertThat(node.currentTerm()).isEqualTo(1);
        assertThat(state.votedFor()).contains("n1");
        assertThat(result.resetElectionTimer()).isTrue();
        assertThat(result.outbound()).hasSize(2);
        assertThat(result.outbound()).extracting(Envelope::to).containsExactlyInAnyOrder("n2", "n3");
        for (Envelope e : result.outbound()) {
            assertThat(e.message()).isInstanceOfSatisfying(RequestVoteRequest.class, req -> {
                assertThat(req.term()).isEqualTo(1);
                assertThat(req.candidateId()).isEqualTo("n1");
                assertThat(req.lastLogIndex()).isZero();
                assertThat(req.lastLogTerm()).isZero();
            });
        }
    }

    @Test
    void leaderTimeoutIsANoOp() {
        RaftNode single = new RaftNode(new ClusterConfig("solo", Set.of()), new InMemoryRaftLog(),
                new InMemoryPersistentState(), e -> { });
        single.handleElectionTimeout();
        assertThat(single.role()).isEqualTo(RaftRole.LEADER);

        HandleResult result = single.handleElectionTimeout();
        assertThat(result).isEqualTo(HandleResult.NONE);
        assertThat(single.role()).isEqualTo(RaftRole.LEADER);
    }

    @Test
    void singleNodeClusterBecomesLeaderInstantlyOnElectionTimeout() {
        RaftNode single = new RaftNode(new ClusterConfig("solo", Set.of()), new InMemoryRaftLog(),
                new InMemoryPersistentState(), e -> { });

        HandleResult result = single.handleElectionTimeout();

        assertThat(single.role()).isEqualTo(RaftRole.LEADER);
        assertThat(single.currentLeaderId()).contains("solo");
        assertThat(result.outbound()).isEmpty();
    }

    @Test
    void proposeCommandWhenNotLeaderReturnsNotLeaderWithNoLogMutation() {
        ProposeResult result = node.proposeCommand("hello".getBytes());

        assertThat(result).isInstanceOf(ProposeResult.NotLeader.class);
        assertThat(log.lastIndex()).isZero();
    }

    @Test
    void grantsVoteToFirstEligibleCandidateInATerm() {
        RequestVoteRequest req = new RequestVoteRequest(1, "n2", 0, 0);

        HandleResult result = node.handleRequestVote(req);

        assertThat(result.resetElectionTimer()).isTrue();
        assertThat(result.outbound()).singleElement().satisfies(e -> {
            assertThat(e.to()).isEqualTo("n2");
            assertThat(e.message()).isInstanceOfSatisfying(RequestVoteResponse.class, resp -> {
                assertThat(resp.voteGranted()).isTrue();
                assertThat(resp.term()).isEqualTo(1);
            });
        });
        assertThat(state.votedFor()).contains("n2");
    }

    @Test
    void refusesASecondVoteInTheSameTermForADifferentCandidate() {
        node.handleRequestVote(new RequestVoteRequest(1, "n2", 0, 0));

        HandleResult second = node.handleRequestVote(new RequestVoteRequest(1, "n3", 0, 0));

        assertThat(second.outbound()).singleElement().satisfies(e ->
                assertThat(e.message()).isInstanceOfSatisfying(RequestVoteResponse.class,
                        resp -> assertThat(resp.voteGranted()).isFalse()));
    }

    @Test
    void regrantingTheSameVoteInTheSameTermIsIdempotent() {
        node.handleRequestVote(new RequestVoteRequest(1, "n2", 0, 0));

        HandleResult second = node.handleRequestVote(new RequestVoteRequest(1, "n2", 0, 0));

        assertThat(second.outbound()).singleElement().satisfies(e ->
                assertThat(e.message()).isInstanceOfSatisfying(RequestVoteResponse.class,
                        resp -> assertThat(resp.voteGranted()).isTrue()));
    }

    @Test
    void refusesVoteWhenCandidateLogIsBehind() {
        log.append(new LogEntry(5, 1, "x".getBytes()));
        state.save(5, null);

        RequestVoteRequest staleCandidate = new RequestVoteRequest(6, "n2", 0, 0);
        HandleResult result = node.handleRequestVote(staleCandidate);

        assertThat(result.outbound()).singleElement().satisfies(e ->
                assertThat(e.message()).isInstanceOfSatisfying(RequestVoteResponse.class,
                        resp -> assertThat(resp.voteGranted()).isFalse()));
    }

    @Test
    void rejectsRequestVoteFromAnOlderTerm() {
        state.save(5, null);

        HandleResult result = node.handleRequestVote(new RequestVoteRequest(3, "n2", 0, 0));

        assertThat(result.resetElectionTimer()).isFalse();
        assertThat(result.outbound()).singleElement().satisfies(e ->
                assertThat(e.message()).isInstanceOfSatisfying(RequestVoteResponse.class, resp -> {
                    assertThat(resp.voteGranted()).isFalse();
                    assertThat(resp.term()).isEqualTo(5);
                }));
    }

    @Test
    void appendEntriesFromAnOlderTermIsRejectedWithoutResettingElectionTimer() {
        state.save(5, null);

        HandleResult result = node.handleAppendEntries(
                new AppendEntriesRequest(3, "oldLeader", 0, 0, List.of(), 0));

        assertThat(result.resetElectionTimer()).isFalse();
        assertThat(result.outbound()).singleElement().satisfies(e ->
                assertThat(e.message()).isInstanceOfSatisfying(AppendEntriesResponse.class,
                        resp -> assertThat(resp.success()).isFalse()));
    }

    @Test
    void appendEntriesHeartbeatFromCurrentLeaderIsAcceptedAndResetsElectionTimer() {
        HandleResult result = node.handleAppendEntries(
                new AppendEntriesRequest(1, "n2", 0, 0, List.of(), 0));

        assertThat(result.resetElectionTimer()).isTrue();
        assertThat(node.currentLeaderId()).contains("n2");
        assertThat(node.role()).isEqualTo(RaftRole.FOLLOWER);
        assertThat(result.outbound()).singleElement().satisfies(e ->
                assertThat(e.message()).isInstanceOfSatisfying(AppendEntriesResponse.class,
                        resp -> assertThat(resp.success()).isTrue()));
    }

    @Test
    void appendEntriesAppliesCommittedEntriesInOrder() {
        LogEntry e1 = new LogEntry(1, 1, "a".getBytes());
        LogEntry e2 = new LogEntry(1, 2, "b".getBytes());

        node.handleAppendEntries(new AppendEntriesRequest(1, "n2", 0, 0, List.of(e1, e2), 2));

        assertThat(node.commitIndex()).isEqualTo(2);
        assertThat(node.lastApplied()).isEqualTo(2);
        assertThat(applied).containsExactly(e1, e2);
    }

    @Test
    void appendEntriesRejectsWhenPrevLogIndexIsBeyondFollowersLog() {
        HandleResult result = node.handleAppendEntries(
                new AppendEntriesRequest(1, "n2", 5, 1, List.of(), 0));

        assertThat(result.outbound()).singleElement().satisfies(e ->
                assertThat(e.message()).isInstanceOfSatisfying(AppendEntriesResponse.class, resp -> {
                    assertThat(resp.success()).isFalse();
                    assertThat(resp.conflictTerm()).isEqualTo(-1);
                    assertThat(resp.conflictIndex()).isEqualTo(1);
                }));
    }

    @Test
    void appendEntriesTruncatesConflictingSuffixBeforeAppending() {
        log.append(new LogEntry(1, 1, "a".getBytes()));
        log.append(new LogEntry(1, 2, "stale".getBytes()));
        state.save(2, null);

        LogEntry replacement = new LogEntry(2, 2, "fresh".getBytes());
        node.handleAppendEntries(new AppendEntriesRequest(2, "n2", 1, 1, List.of(replacement), 0));

        assertThat(log.lastIndex()).isEqualTo(2);
        assertThat(log.get(2)).contains(replacement);
    }
}
