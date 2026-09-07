package io.castellan.broker.server;

import io.castellan.broker.raft.ClusterConfig;
import io.castellan.broker.raft.RaftNode;
import io.castellan.broker.storage.raft.FilePersistentState;
import io.castellan.broker.storage.raft.FileRaftLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * One runnable broker node: wires {@code broker-raft} (replication), {@code broker-storage} (the
 * durable Raft log/state and the partitioned data log), and {@code broker-protocol} (the wire
 * format) together with the two network listeners and consumer-group coordination described in
 * this module's package/class docs. See {@link RaftEventLoop} for the timer/threading model and
 * {@link CommandApplier} for the idempotent-producer dedup and Raft-to-storage wiring — this class
 * is purely composition.
 */
public final class BrokerServer implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(BrokerServer.class);

    private final NodeConfig config;
    private final FileRaftLog raftLog;
    private final FilePersistentState persistentState;
    private final PartitionLogRegistry partitionLogRegistry;
    private final CommandApplier commandApplier;
    private final RaftNode raftNode;
    private final RaftTransport raftTransport;
    private final RaftEventLoop eventLoop;
    private final GroupCoordinator groupCoordinator;
    private final ClientRequestServer clientRequestServer;

    public BrokerServer(NodeConfig config) {
        this.config = config;
        Path raftDir = config.dataDir().resolve("raft");
        Path dataDir = config.dataDir().resolve("data");

        this.raftLog = FileRaftLog.open(raftDir);
        this.persistentState = FilePersistentState.open(raftDir);
        this.partitionLogRegistry = new PartitionLogRegistry(dataDir, config.segmentBytesLimit());
        this.commandApplier = new CommandApplier(partitionLogRegistry);

        Set<String> peerIds = new HashSet<>();
        for (NodeAddress peer : config.peers()) {
            peerIds.add(peer.id());
        }
        ClusterConfig clusterConfig = new ClusterConfig(config.selfId(), peerIds);
        this.raftNode = new RaftNode(clusterConfig, raftLog, persistentState, commandApplier);

        this.raftTransport = new RaftTransport(config.self(), config.peers());
        this.eventLoop = new RaftEventLoop(raftNode, raftTransport::send,
                config.electionTimeoutMinMs(), config.electionTimeoutMaxMs(), config.heartbeatIntervalMs());
        raftTransport.setInboundHandler(eventLoop::handleIncoming);

        this.groupCoordinator = new GroupCoordinator(config.defaultPartitionCount(), config.sessionSweepIntervalMs());
        eventLoop.onLeadershipChange(isLeader -> {
            if (!isLeader) {
                log.info("{}: lost leadership, clearing in-memory consumer-group state", config.selfId());
                groupCoordinator.clear();
            } else {
                log.info("{}: became leader", config.selfId());
            }
        });

        BrokerRequestHandler requestHandler = new BrokerRequestHandler(
                eventLoop, commandApplier, partitionLogRegistry, groupCoordinator, config);
        this.clientRequestServer = new ClientRequestServer(config.self(), requestHandler);
    }

    public void start() {
        try {
            raftTransport.start();
            clientRequestServer.start();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start broker node " + config.selfId(), e);
        }
        eventLoop.start();
        log.info("{}: started, raft port {}, client port {}", config.selfId(),
                config.self().raftPort(), config.self().clientPort());
    }

    public boolean isLeader() {
        return eventLoop.isLeader();
    }

    public String nodeId() {
        return config.selfId();
    }

    @Override
    public void close() {
        clientRequestServer.close();
        raftTransport.close();
        eventLoop.close();
        groupCoordinator.close();
        partitionLogRegistry.close();
        raftLog.close();
        persistentState.close();
        log.info("{}: closed", config.selfId());
    }
}
