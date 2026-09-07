package io.castellan.broker.server;

import io.castellan.broker.protocol.Frame;
import io.castellan.broker.protocol.client.ClientMessage;
import io.castellan.broker.protocol.client.ClientProtocolCodec;
import io.castellan.broker.protocol.client.ErrorCode;
import io.castellan.broker.protocol.client.FetchRequest;
import io.castellan.broker.protocol.client.FetchResponse;
import io.castellan.broker.protocol.client.HeartbeatRequest;
import io.castellan.broker.protocol.client.HeartbeatResponse;
import io.castellan.broker.protocol.client.JoinGroupRequest;
import io.castellan.broker.protocol.client.JoinGroupResponse;
import io.castellan.broker.protocol.client.LeaveGroupRequest;
import io.castellan.broker.protocol.client.LeaveGroupResponse;
import io.castellan.broker.protocol.client.OffsetCommitRequest;
import io.castellan.broker.protocol.client.OffsetCommitResponse;
import io.castellan.broker.protocol.client.OffsetFetchRequest;
import io.castellan.broker.protocol.client.OffsetFetchResponse;
import io.castellan.broker.protocol.client.ProduceRequest;
import io.castellan.broker.protocol.client.ProduceResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real, end-to-end tests against a real 3-node cluster: real threads, real loopback TCP sockets
 * (never {@code broker-raft}'s already-proven in-memory {@code RaftClusterSimulationTest}), real
 * files under a temp directory. Proves the whole stack this module built — {@link RaftEventLoop}'s
 * timer-driven shell, {@link RaftTransport}'s wire I/O, {@link CommandApplier}'s Raft-to-storage
 * wiring and idempotent dedup, and {@link GroupCoordinator}'s rebalancing — actually works
 * together, not just in isolation.
 *
 * <p>Talks to the cluster purely over the real wire protocol via the small {@link WireClient}
 * helper below, deliberately not {@code broker-client} (a separate module with its own tests) —
 * this suite is exercising {@code broker-server}, not the client library.
 */
class BrokerClusterIntegrationTest {

    @TempDir
    Path tempDir;

    private List<NodeAddress> nodes;
    private List<BrokerServer> servers;

    @BeforeEach
    void startCluster() throws Exception {
        nodes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            nodes.add(new NodeAddress("n" + i, "127.0.0.1", freePort(), freePort()));
        }
        servers = new ArrayList<>();
        for (NodeAddress n : nodes) {
            NodeConfig config = new NodeConfig(n.id(), nodes, tempDir.resolve(n.id()), 3,
                    1024 * 1024, 150, 300, 50, 5_000, 200);
            BrokerServer server = new BrokerServer(config);
            server.start();
            servers.add(server);
        }
        awaitLeader();
    }

    @AfterEach
    void stopCluster() {
        if (servers != null) {
            for (BrokerServer s : servers) {
                s.close();
            }
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private void awaitCondition(String description, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for: " + description);
    }

    private BrokerServer awaitLeader() throws InterruptedException {
        awaitCondition("a leader to be elected", Duration.ofSeconds(5),
                () -> servers.stream().anyMatch(BrokerServer::isLeader));
        return servers.stream().filter(BrokerServer::isLeader).findFirst().orElseThrow();
    }

    private BrokerServer aFollower() {
        return servers.stream().filter(s -> !s.isLeader()).findFirst().orElseThrow();
    }

    private NodeAddress addressOf(BrokerServer server) {
        return nodes.stream().filter(n -> n.id().equals(server.nodeId())).findFirst().orElseThrow();
    }

    /** A minimal wire-level test client speaking the real protocol directly. */
    private static final class WireClient implements AutoCloseable {
        private final SocketChannel channel;

        WireClient(NodeAddress node) throws IOException {
            channel = SocketChannel.open(new InetSocketAddress(node.host(), node.clientPort()));
        }

        ClientMessage call(ClientMessage request) throws IOException {
            ClientProtocolCodec.encode(request).writeTo(channel);
            Frame response = Frame.readFrom(channel).orElseThrow(() -> new IOException("connection closed by server"));
            return ClientProtocolCodec.decode(response);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    @Test
    void electsExactlyOneLeaderAmongThreeRealNodes() {
        assertThat(servers.stream().filter(BrokerServer::isLeader).count()).isEqualTo(1);
    }

    @Test
    void producedRecordReplicatesAndBecomesReadableFromAFollower() throws Exception {
        BrokerServer leader = awaitLeader();
        BrokerServer follower = aFollower();

        try (WireClient producerConn = new WireClient(addressOf(leader))) {
            ProduceResponse pr = (ProduceResponse) producerConn.call(
                    new ProduceRequest("orders", 0, -1L, 0L, "k1".getBytes(), "v1".getBytes()));
            assertThat(pr.errorCode()).isEqualTo(ErrorCode.NONE);
            assertThat(pr.offset()).isEqualTo(0L);
        }

        awaitCondition("follower to replicate the record", Duration.ofSeconds(5), () -> {
            try (WireClient conn = new WireClient(addressOf(follower))) {
                FetchResponse fr = (FetchResponse) conn.call(new FetchRequest("orders", 0, 0, 10));
                return fr.records().size() == 1;
            } catch (IOException e) {
                return false;
            }
        });

        try (WireClient conn = new WireClient(addressOf(follower))) {
            FetchResponse fr = (FetchResponse) conn.call(new FetchRequest("orders", 0, 0, 10));
            assertThat(fr.records()).hasSize(1);
            assertThat(fr.records().get(0).value()).isEqualTo("v1".getBytes());
            assertThat(fr.records().get(0).key()).isEqualTo("k1".getBytes());
        }
    }

    @Test
    void producingToAFollowerIsRejectedWithNotLeaderAndALeaderHint() throws Exception {
        BrokerServer leader = awaitLeader();
        BrokerServer follower = aFollower();

        try (WireClient conn = new WireClient(addressOf(follower))) {
            ProduceResponse resp = (ProduceResponse) conn.call(
                    new ProduceRequest("orders", 0, -1L, 0L, null, "v".getBytes()));
            assertThat(resp.errorCode()).isEqualTo(ErrorCode.NOT_LEADER);
            assertThat(resp.leaderHint()).isEqualTo(leader.nodeId());
        }
    }

    @Test
    void idempotentProduceRetryIsNotDuplicated() throws Exception {
        BrokerServer leader = awaitLeader();
        long producerId = 42L;

        try (WireClient conn = new WireClient(addressOf(leader))) {
            ProduceResponse first = (ProduceResponse) conn.call(
                    new ProduceRequest("payments", 0, producerId, 0L, null, "attempt-1".getBytes()));
            assertThat(first.errorCode()).isEqualTo(ErrorCode.NONE);

            ProduceResponse retry = (ProduceResponse) conn.call(
                    new ProduceRequest("payments", 0, producerId, 0L, null, "attempt-1".getBytes()));
            assertThat(retry.errorCode()).isEqualTo(ErrorCode.NONE);
            assertThat(retry.offset()).isEqualTo(first.offset());

            ProduceResponse next = (ProduceResponse) conn.call(
                    new ProduceRequest("payments", 0, producerId, 1L, null, "attempt-2".getBytes()));
            assertThat(next.errorCode()).isEqualTo(ErrorCode.NONE);
            assertThat(next.offset()).isEqualTo(first.offset() + 1);
        }

        awaitCondition("both distinct records to be readable", Duration.ofSeconds(5), () -> {
            try (WireClient conn = new WireClient(addressOf(leader))) {
                FetchResponse fr = (FetchResponse) conn.call(new FetchRequest("payments", 0, 0, 10));
                return fr.records().size() == 2;
            } catch (IOException e) {
                return false;
            }
        });

        try (WireClient conn = new WireClient(addressOf(leader))) {
            FetchResponse fr = (FetchResponse) conn.call(new FetchRequest("payments", 0, 0, 10));
            assertThat(fr.records()).hasSize(2);
            assertThat(fr.records().get(0).value()).isEqualTo("attempt-1".getBytes());
            assertThat(fr.records().get(1).value()).isEqualTo("attempt-2".getBytes());
        }
    }

    @Test
    void consumerGroupRebalancesAcrossMembersOnJoinAndLeave() throws Exception {
        BrokerServer leader = awaitLeader();

        try (WireClient conn = new WireClient(addressOf(leader))) {
            JoinGroupResponse firstJoin = (JoinGroupResponse) conn.call(
                    new JoinGroupRequest("g1", "", List.of("orders"), 10_000));
            assertThat(firstJoin.errorCode()).isEqualTo(ErrorCode.NONE);
            assertThat(firstJoin.assignedPartitions()).hasSize(3);
            String member1 = firstJoin.memberId();
            int generation1 = firstJoin.generationId();

            JoinGroupResponse secondJoin = (JoinGroupResponse) conn.call(
                    new JoinGroupRequest("g1", "", List.of("orders"), 10_000));
            assertThat(secondJoin.errorCode()).isEqualTo(ErrorCode.NONE);
            String member2 = secondJoin.memberId();
            assertThat(member2).isNotEqualTo(member1);
            assertThat(secondJoin.assignedPartitions()).isNotEmpty();

            HeartbeatResponse staleHeartbeat = (HeartbeatResponse) conn.call(
                    new HeartbeatRequest("g1", member1, generation1));
            assertThat(staleHeartbeat.errorCode()).isEqualTo(ErrorCode.REBALANCE_IN_PROGRESS);

            JoinGroupResponse rejoin = (JoinGroupResponse) conn.call(
                    new JoinGroupRequest("g1", member1, List.of("orders"), 10_000));
            assertThat(rejoin.errorCode()).isEqualTo(ErrorCode.NONE);
            assertThat(rejoin.assignedPartitions().size() + secondJoin.assignedPartitions().size()).isEqualTo(3);

            LeaveGroupResponse leaveResp = (LeaveGroupResponse) conn.call(new LeaveGroupRequest("g1", member2));
            assertThat(leaveResp.errorCode()).isEqualTo(ErrorCode.NONE);

            HeartbeatResponse afterLeave = (HeartbeatResponse) conn.call(
                    new HeartbeatRequest("g1", member1, rejoin.generationId()));
            assertThat(afterLeave.errorCode()).isEqualTo(ErrorCode.REBALANCE_IN_PROGRESS);

            JoinGroupResponse finalJoin = (JoinGroupResponse) conn.call(
                    new JoinGroupRequest("g1", member1, List.of("orders"), 10_000));
            assertThat(finalJoin.errorCode()).isEqualTo(ErrorCode.NONE);
            assertThat(finalJoin.assignedPartitions()).hasSize(3);
        }
    }

    @Test
    void missedHeartbeatsEvictAMemberAndTriggerARebalanceWithoutAnExplicitLeave() throws Exception {
        BrokerServer leader = awaitLeader();

        try (WireClient conn = new WireClient(addressOf(leader))) {
            JoinGroupResponse doomed = (JoinGroupResponse) conn.call(
                    new JoinGroupRequest("g3", "", List.of("orders"), 300));
            assertThat(doomed.assignedPartitions()).hasSize(3);

            JoinGroupResponse survivor = (JoinGroupResponse) conn.call(
                    new JoinGroupRequest("g3", "", List.of("orders"), 60_000));
            assertThat(survivor.assignedPartitions().size()).isLessThan(3);

            awaitCondition("survivor to be reassigned all partitions after doomed member's eviction",
                    Duration.ofSeconds(5), () -> {
                        try {
                            HeartbeatResponse hb = (HeartbeatResponse) conn.call(
                                    new HeartbeatRequest("g3", survivor.memberId(), survivor.generationId()));
                            return hb.errorCode() == ErrorCode.REBALANCE_IN_PROGRESS;
                        } catch (IOException e) {
                            return false;
                        }
                    });

            JoinGroupResponse rejoin = (JoinGroupResponse) conn.call(
                    new JoinGroupRequest("g3", survivor.memberId(), List.of("orders"), 60_000));
            assertThat(rejoin.assignedPartitions()).hasSize(3);
        }
    }

    @Test
    void offsetCommitAndFetchRoundTripThroughRaftAndAreVisibleOnAnyNode() throws Exception {
        BrokerServer leader = awaitLeader();
        BrokerServer other = aFollower();

        try (WireClient conn = new WireClient(addressOf(leader))) {
            JoinGroupResponse joined = (JoinGroupResponse) conn.call(
                    new JoinGroupRequest("g2", "", List.of("orders"), 10_000));

            OffsetCommitResponse commitResp = (OffsetCommitResponse) conn.call(new OffsetCommitRequest(
                    "g2", joined.memberId(), joined.generationId(), "orders", 0, 41L));
            assertThat(commitResp.errorCode()).isEqualTo(ErrorCode.NONE);
        }

        awaitCondition("offset commit to replicate to a follower", Duration.ofSeconds(5), () -> {
            try (WireClient conn = new WireClient(addressOf(other))) {
                OffsetFetchResponse resp = (OffsetFetchResponse) conn.call(new OffsetFetchRequest("g2", "orders", 0));
                return resp.offset() == 41L;
            } catch (IOException e) {
                return false;
            }
        });

        try (WireClient conn = new WireClient(addressOf(leader))) {
            OffsetFetchResponse neverCommitted = (OffsetFetchResponse) conn.call(
                    new OffsetFetchRequest("g2", "orders", 1));
            assertThat(neverCommitted.offset()).isEqualTo(-1L);
        }
    }
}
