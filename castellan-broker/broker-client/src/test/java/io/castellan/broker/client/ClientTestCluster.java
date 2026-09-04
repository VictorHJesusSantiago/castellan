package io.castellan.broker.client;

import io.castellan.broker.protocol.client.NodeInfo;
import io.castellan.broker.server.BrokerServer;
import io.castellan.broker.server.NodeAddress;
import io.castellan.broker.server.NodeConfig;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** A real, in-process {@code broker-server} cluster over real loopback TCP sockets, used to
 * exercise {@code broker-client} end-to-end against the genuine wire protocol and Raft-driven
 * write path rather than any mock. Mirrors {@code broker-server}'s own
 * {@code BrokerClusterIntegrationTest} cluster setup exactly, since both suites need the same
 * "real 3-node cluster under a temp dir" shape. */
final class ClientTestCluster implements AutoCloseable {

    final List<NodeAddress> nodes = new ArrayList<>();
    final List<BrokerServer> servers = new ArrayList<>();

    static ClientTestCluster start(Path tempDir, int nodeCount) throws IOException, InterruptedException {
        ClientTestCluster cluster = new ClientTestCluster();
        for (int i = 0; i < nodeCount; i++) {
            cluster.nodes.add(new NodeAddress("n" + i, "127.0.0.1", freePort(), freePort()));
        }
        for (NodeAddress n : cluster.nodes) {
            NodeConfig config = new NodeConfig(n.id(), cluster.nodes, tempDir.resolve(n.id()), 3,
                    1024 * 1024, 150, 300, 50, 5_000, 200);
            BrokerServer server = new BrokerServer(config);
            server.start();
            cluster.servers.add(server);
        }
        cluster.awaitLeader();
        return cluster;
    }

    List<NodeInfo> bootstrapNodes() {
        return nodes.stream().map(n -> new NodeInfo(n.id(), n.host(), n.clientPort())).toList();
    }

    /** A bootstrap list containing only a single, arbitrary node -- proves a client can discover
     * the rest of the cluster purely via Metadata rather than needing every address up front. */
    List<NodeInfo> singleBootstrapNode() {
        NodeAddress n = nodes.get(0);
        return List.of(new NodeInfo(n.id(), n.host(), n.clientPort()));
    }

    BrokerServer awaitLeader() throws InterruptedException {
        awaitCondition("a leader to be elected", Duration.ofSeconds(5),
                () -> servers.stream().anyMatch(BrokerServer::isLeader));
        return servers.stream().filter(BrokerServer::isLeader).findFirst().orElseThrow();
    }

    BrokerServer aFollower() {
        return servers.stream().filter(s -> !s.isLeader()).findFirst().orElseThrow();
    }

    void stopLeader() {
        BrokerServer leader = servers.stream().filter(BrokerServer::isLeader).findFirst().orElseThrow();
        leader.close();
        servers.remove(leader);
    }

    static void awaitCondition(String description, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for: " + description);
    }

    static void assertEventually(String description, Duration timeout, Runnable assertion) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        AssertionError last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                last = e;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(description, ie);
                }
            }
        }
        throw new AssertionError("timed out waiting for: " + description, last);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Override
    public void close() {
        for (BrokerServer s : servers) {
            s.close();
        }
    }
}
