package io.castellan.broker.server;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 * Starts one runnable broker node process. Every node in a cluster is started separately (there
 * is no cluster-bootstrap RPC — membership is static for a node's lifetime, matching
 * {@code broker-raft}'s own documented scope cut on joint-consensus reconfiguration), each with
 * the exact same {@code --cluster} list (including its own entry) and a distinct {@code --node-id}
 * telling it which of those entries is itself.
 *
 * <p>Example, a 3-node cluster all on localhost:
 * <pre>
 * java -jar broker-server.jar --node-id n0 --data-dir /var/castellan/n0 \
 *     --cluster n0@127.0.0.1:9100:9101,n1@127.0.0.1:9200:9201,n2@127.0.0.1:9300:9301
 * </pre>
 */
@Command(name = "broker-node", mixinStandardHelpOptions = true,
        description = "Starts one Castellan broker node.")
public final class BrokerNodeMain implements Callable<Integer> {

    @Option(names = "--node-id", required = true, description = "This process's id within --cluster.")
    private String nodeId;

    @Option(names = "--cluster", required = true, split = ",",
            description = "Every node in the cluster, including this one, as id@host:raftPort:clientPort.")
    private List<String> cluster;

    @Option(names = "--data-dir", required = true, description = "Directory for this node's Raft and partition data.")
    private Path dataDir;

    @Option(names = "--default-partitions", defaultValue = "3",
            description = "Partition count applied uniformly to every topic (no per-topic admin API).")
    private int defaultPartitions;

    @Option(names = "--election-timeout-min-ms", defaultValue = "150")
    private long electionTimeoutMinMs;

    @Option(names = "--election-timeout-max-ms", defaultValue = "300")
    private long electionTimeoutMaxMs;

    @Option(names = "--heartbeat-interval-ms", defaultValue = "50")
    private long heartbeatIntervalMs;

    @Option(names = "--request-timeout-ms", defaultValue = "5000")
    private long requestTimeoutMs;

    @Option(names = "--session-sweep-interval-ms", defaultValue = "500")
    private long sessionSweepIntervalMs;

    public static void main(String[] args) {
        System.exit(new CommandLine(new BrokerNodeMain()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        List<NodeAddress> nodes = cluster.stream().map(NodeAddress::parse).toList();
        NodeConfig config = new NodeConfig(nodeId, nodes, dataDir, defaultPartitions,
                64L * 1024 * 1024, electionTimeoutMinMs, electionTimeoutMaxMs,
                heartbeatIntervalMs, requestTimeoutMs, sessionSweepIntervalMs);

        BrokerServer server = new BrokerServer(config);
        server.start();

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            shutdownLatch.countDown();
        }, "broker-shutdown"));
        shutdownLatch.await();
        return 0;
    }
}
