package io.castellan.broker.raft;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deterministic, in-process simulation of a Raft cluster: several real {@link RaftNode}s wired
 * together purely by routing each node's returned {@link Envelope}s to the addressed peer's
 * handler methods, on one test thread, with no real network, no real clock, and no sleeping. This
 * is where {@link RaftNode}'s actual distributed-systems correctness is proven — the single-node
 * tests in {@code RaftNodeSingleNodeTest} check the state machine's local shape, but only a
 * multi-node scenario can demonstrate an election actually converges, replication actually
 * reaches a majority, a partitioned minority can't keep committing, and a healed partition
 * catches back up.
 *
 * <p>{@code deliverAllPending()} is deliberately synchronous and exhaustive (drains the inbox
 * completely, including every envelope newly produced while draining) rather than "one round" —
 * this is what makes each test's assertions about final, settled state meaningful without a test
 * needing to know exactly how many RPC round trips a scenario takes internally.
 */
class RaftClusterSimulationTest {

    private record Targeted(String to, String from, RaftMessage message) {
    }

    private Map<String, RaftNode> nodes;
    private Map<String, InMemoryRaftLog> logs;
    private Map<String, List<LogEntry>> appliedByNode;
    private ArrayDeque<Targeted> inbox;
    private Set<String> partitioned;

    private void buildCluster(int size) {
        nodes = new HashMap<>();
        logs = new HashMap<>();
        appliedByNode = new HashMap<>();
        inbox = new ArrayDeque<>();
        partitioned = new HashSet<>();

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ids.add("n" + i);
        }
        for (String id : ids) {
            Set<String> peers = new HashSet<>(ids);
            peers.remove(id);
            InMemoryRaftLog log = new InMemoryRaftLog();
            List<LogEntry> applied = new ArrayList<>();
            RaftNode node = new RaftNode(new ClusterConfig(id, peers), log, new InMemoryPersistentState(), applied::add);
            logs.put(id, log);
            appliedByNode.put(id, applied);
            nodes.put(id, node);
        }
    }

    @BeforeEach
    void defaultThreeNodeCluster() {
        buildCluster(3);
    }


    private void enqueue(String senderId, List<Envelope> envelopes) {
        for (Envelope e : envelopes) {
            inbox.add(new Targeted(e.to(), senderId, e.message()));
        }
    }

    private void deliverOne(Targeted t) {
        if (partitioned.contains(t.to()) || partitioned.contains(t.from())) {
            return;
        }
        RaftNode target = nodes.get(t.to());
        HandleResult result = switch (t.message()) {
            case RequestVoteRequest req -> target.handleRequestVote(req);
            case RequestVoteResponse resp -> target.handleRequestVoteResponse(t.from(), resp);
            case AppendEntriesRequest req -> target.handleAppendEntries(req);
            case AppendEntriesResponse resp -> target.handleAppendEntriesResponse(t.from(), resp);
        };
        enqueue(t.to(), result.outbound());
    }

    private void deliverAllPending() {
        int guard = 0;
        while (!inbox.isEmpty()) {
            if (++guard > 10_000) {
                throw new IllegalStateException("message storm during test — likely an infinite retry loop bug");
            }
            deliverOne(inbox.poll());
        }
    }

    private void triggerElectionTimeout(String nodeId) {
        HandleResult result = nodes.get(nodeId).handleElectionTimeout();
        enqueue(nodeId, result.outbound());
    }

    private void triggerHeartbeat(String leaderId) {
        HandleResult result = nodes.get(leaderId).handleHeartbeatTimeout();
        enqueue(leaderId, result.outbound());
    }

    /** Elects `nodeId` leader by triggering its election timeout and settling the cluster,
     * failing the test outright if it doesn't actually win. */
    private void electLeader(String nodeId) {
        triggerElectionTimeout(nodeId);
        deliverAllPending();
        assertThat(nodes.get(nodeId).role()).as(nodeId + " should have won the election").isEqualTo(RaftRole.LEADER);
    }

    /** The core Raft safety property, checked across whatever the cluster's state is right now:
     * no term ever has two nodes that both believe they are its leader. */
    private void assertAtMostOneLeaderPerTerm() {
        Map<Long, List<String>> leadersByTerm = new HashMap<>();
        for (Map.Entry<String, RaftNode> e : nodes.entrySet()) {
            if (e.getValue().role() == RaftRole.LEADER) {
                leadersByTerm.computeIfAbsent(e.getValue().currentTerm(), t -> new ArrayList<>()).add(e.getKey());
            }
        }
        leadersByTerm.forEach((term, leaders) ->
                assertThat(leaders).as("term %d should have at most one leader, found %s", term, leaders).hasSizeLessThanOrEqualTo(1));
    }


    @Test
    void electionSucceedsWithNoContentionAndEveryoneAgreesOnTheLeader() {
        electLeader("n0");

        assertThat(nodes.get("n1").currentLeaderId()).contains("n0");
        assertThat(nodes.get("n2").currentLeaderId()).contains("n0");
        assertThat(nodes.get("n1").role()).isEqualTo(RaftRole.FOLLOWER);
        assertThat(nodes.get("n2").role()).isEqualTo(RaftRole.FOLLOWER);
        assertAtMostOneLeaderPerTerm();
    }

    @Test
    void proposedCommandReplicatesToAllFollowersAndCommitsOnAMajority() {
        electLeader("n0");

        ProposeResult result = nodes.get("n0").proposeCommand("transfer:100".getBytes());
        assertThat(result).isInstanceOf(ProposeResult.Accepted.class);
        triggerHeartbeat("n0");
        deliverAllPending();

        assertThat(nodes.get("n0").commitIndex()).isEqualTo(1);
        assertThat(nodes.get("n1").commitIndex()).isEqualTo(1);
        assertThat(nodes.get("n2").commitIndex()).isEqualTo(1);
        for (String id : List.of("n0", "n1", "n2")) {
            assertThat(appliedByNode.get(id)).hasSize(1);
            assertThat(appliedByNode.get(id).get(0).command()).isEqualTo("transfer:100".getBytes());
        }
    }

    @Test
    void multipleCommandsCommitInLogOrderOnEveryNode() {
        electLeader("n0");

        for (String cmd : List.of("a", "b", "c")) {
            nodes.get("n0").proposeCommand(cmd.getBytes());
            triggerHeartbeat("n0");
            deliverAllPending();
        }

        for (String id : List.of("n0", "n1", "n2")) {
            List<String> appliedCommands = appliedByNode.get(id).stream().map(e -> new String(e.command())).toList();
            assertThat(appliedCommands).containsExactly("a", "b", "c");
        }
    }

    @Test
    void isolatedLeaderAcceptsLocallyButCanNeverCommitWithoutAMajority() {
        electLeader("n0");
        partitioned.add("n1");
        partitioned.add("n2");

        ProposeResult result = nodes.get("n0").proposeCommand("stuck".getBytes());
        assertThat(result).isInstanceOf(ProposeResult.Accepted.class);
        triggerHeartbeat("n0");
        deliverAllPending();

        assertThat(nodes.get("n0").commitIndex()).isZero();
    }

    @Test
    void isolatedFormerLeaderStepsDownOnceItLearnsOfANewerTerm() {
        electLeader("n0");
        partitioned.add("n0");

        triggerElectionTimeout("n1");
        deliverAllPending();
        assertThat(nodes.get("n1").role()).isEqualTo(RaftRole.LEADER);
        assertThat(nodes.get("n1").currentTerm()).isGreaterThan(nodes.get("n0").currentTerm());

        partitioned.remove("n0");
        triggerHeartbeat("n1");
        deliverAllPending();

        assertThat(nodes.get("n0").role()).isEqualTo(RaftRole.FOLLOWER);
        assertThat(nodes.get("n0").currentLeaderId()).contains("n1");
        assertAtMostOneLeaderPerTerm();
    }

    @Test
    void laggingFollowerThatMissedEntriesCatchesUpAfterPartitionHeals() {
        electLeader("n0");
        partitioned.add("n2");

        for (String cmd : List.of("a", "b", "c")) {
            nodes.get("n0").proposeCommand(cmd.getBytes());
            triggerHeartbeat("n0");
            deliverAllPending();
        }
        assertThat(nodes.get("n2").commitIndex()).isZero();

        partitioned.remove("n2");
        triggerHeartbeat("n0");
        deliverAllPending();

        assertThat(nodes.get("n2").commitIndex()).isEqualTo(3);
        List<String> caughtUpCommands = appliedByNode.get("n2").stream().map(e -> new String(e.command())).toList();
        assertThat(caughtUpCommands).containsExactly("a", "b", "c");
    }

    @Test
    void conflictingUncommittedEntriesOnAFormerLeaderAreOverwrittenByTheNewLeader() {
        electLeader("n0");
        partitioned.add("n2");
        nodes.get("n0").proposeCommand("committed-elsewhere".getBytes());
        triggerHeartbeat("n0");
        deliverAllPending();

        partitioned.remove("n2");
        partitioned.add("n0");
        nodes.get("n0").proposeCommand("never-replicated".getBytes());
        assertThat(logs.get("n0").lastIndex()).isEqualTo(2);

        triggerElectionTimeout("n1");
        deliverAllPending();
        assertThat(nodes.get("n1").role()).isEqualTo(RaftRole.LEADER);
        nodes.get("n1").proposeCommand("actually-committed".getBytes());
        triggerHeartbeat("n1");
        deliverAllPending();

        partitioned.remove("n0");
        triggerHeartbeat("n1");
        deliverAllPending();

        assertThat(logs.get("n0").get(2)).isPresent();
        assertThat(new String(logs.get("n0").get(2).orElseThrow().command())).isEqualTo("actually-committed");
        assertAtMostOneLeaderPerTerm();
    }

    @Test
    void splitVoteEventuallyResolvesToExactlyOneLeaderAfterRetry() {
        buildCluster(5);
        triggerElectionTimeout("n0");
        triggerElectionTimeout("n1");
        deliverAllPending();

        long leaderCount = nodes.values().stream().filter(n -> n.role() == RaftRole.LEADER).count();
        assertAtMostOneLeaderPerTerm();

        if (leaderCount == 0) {
            triggerElectionTimeout("n0");
            deliverAllPending();
            assertThat(nodes.get("n0").role()).isEqualTo(RaftRole.LEADER);
        } else {
            assertThat(leaderCount).isEqualTo(1);
        }
        assertAtMostOneLeaderPerTerm();
    }

    @Test
    void higherTermInAnyMessageForcesImmediateStepDownEvenForALeader() {
        electLeader("n0");

        HandleResult result = nodes.get("n0").handleRequestVote(new RequestVoteRequest(99, "n1", 0, 0));

        assertThat(nodes.get("n0").role()).isEqualTo(RaftRole.FOLLOWER);
        assertThat(nodes.get("n0").currentTerm()).isEqualTo(99);
        assertThat(result.outbound()).singleElement().satisfies(e ->
                assertThat(e.message()).isInstanceOfSatisfying(RequestVoteResponse.class,
                        resp -> assertThat(resp.voteGranted()).isTrue()));
    }
}
