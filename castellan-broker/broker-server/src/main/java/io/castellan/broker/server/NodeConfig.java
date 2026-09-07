package io.castellan.broker.server;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything one {@link BrokerServer} instance needs to start: which of {@code nodes} is this
 * process ({@code selfId}), where to persist state, and the handful of timing/sizing knobs the
 * event loop and storage layer need. Timer bounds default to values realistic for a real
 * deployment (150-300ms election range, matching the Raft paper's own suggested magnitude); tests
 * that want fast, deterministic-ish convergence construct a {@code NodeConfig} with much smaller
 * values instead of waiting out production timings.
 */
public record NodeConfig(
        String selfId,
        List<NodeAddress> nodes,
        Path dataDir,
        int defaultPartitionCount,
        long segmentBytesLimit,
        long electionTimeoutMinMs,
        long electionTimeoutMaxMs,
        long heartbeatIntervalMs,
        long requestTimeoutMs,
        long sessionSweepIntervalMs
) {

    public NodeConfig {
        nodes = List.copyOf(nodes);
        if (nodes.stream().noneMatch(n -> n.id().equals(selfId))) {
            throw new IllegalArgumentException("selfId " + selfId + " is not present in nodes " + nodes);
        }
        if (electionTimeoutMinMs <= 0 || electionTimeoutMaxMs <= electionTimeoutMinMs) {
            throw new IllegalArgumentException("require 0 < electionTimeoutMinMs < electionTimeoutMaxMs");
        }
    }

    public NodeAddress self() {
        return nodes.stream().filter(n -> n.id().equals(selfId)).findFirst().orElseThrow();
    }

    public List<NodeAddress> peers() {
        return nodes.stream().filter(n -> !n.id().equals(selfId)).toList();
    }

    /** A reasonable production default: 150-300ms election range (the Raft paper's own suggested
     * magnitude), 50ms heartbeats, generous 5s request timeout, 500ms session sweep. */
    public static NodeConfig defaults(String selfId, List<NodeAddress> nodes, Path dataDir) {
        return new NodeConfig(selfId, nodes, dataDir, 3, 64L * 1024 * 1024, 150, 300, 50, 5_000, 500);
    }
}
