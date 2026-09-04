package io.castellan.broker.client;

import io.castellan.broker.protocol.client.ClientMessage;
import io.castellan.broker.protocol.client.ErrorCode;
import io.castellan.broker.protocol.client.MetadataRequest;
import io.castellan.broker.protocol.client.MetadataResponse;
import io.castellan.broker.protocol.client.NodeInfo;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Shared plumbing behind both {@link Producer} and {@link Consumer}: which nodes this client knows
 * about (seeded from the bootstrap list the caller constructed it with, grown over time from every
 * {@link MetadataResponse} seen), which one is believed to be the current Raft leader, and the
 * connect-discover-redirect dance every leader-only request (produce, join/heartbeat/leave group,
 * offset commit) needs. Fetch and offset-fetch requests bypass all of this via {@link #callAny},
 * since {@code broker-server} answers reads from any node (see {@code BrokerRequestHandler}).
 *
 * <p>Leader discovery cost is paid once per redirect, not once per request: {@link #currentLeader}
 * is cached until a request actually comes back {@code NOT_LEADER} (or the cached node's
 * connection fails outright), matching how a real client should behave against a cluster that
 * mostly has a stable leader for long stretches at a time.
 */
final class LeaderRouter implements Closeable {

    private final Map<String, NodeInfo> knownNodes = new ConcurrentHashMap<>();
    private final Map<String, BrokerConnection> connections = new ConcurrentHashMap<>();
    private volatile NodeInfo currentLeader;

    LeaderRouter(List<NodeInfo> bootstrapNodes) {
        if (bootstrapNodes.isEmpty()) {
            throw new IllegalArgumentException("at least one bootstrap node is required");
        }
        for (NodeInfo n : bootstrapNodes) {
            knownNodes.put(n.id(), n);
        }
    }

    private BrokerConnection connectionTo(NodeInfo node) {
        knownNodes.put(node.id(), node);
        return connections.computeIfAbsent(node.id(), id -> new BrokerConnection(node));
    }

    /** Sends {@code request} to any currently known node, trying each in turn until one answers —
     * used for requests any node can serve (Fetch, OffsetFetch, Metadata itself). */
    @SuppressWarnings("unchecked")
    <T extends ClientMessage> T callAny(ClientMessage request) {
        BrokerClientException last = null;
        for (NodeInfo node : List.copyOf(knownNodes.values())) {
            try {
                return (T) connectionTo(node).call(request);
            } catch (BrokerClientException e) {
                last = e;
            }
        }
        throw last != null ? last : new BrokerClientException("no known broker nodes reachable");
    }

    /**
     * Sends {@code request} to the current (cached, or freshly discovered) leader, following
     * {@code leaderHint}-driven redirects — and retrying past outright connection failures by
     * forcing a full rediscovery — up to {@code maxRedirects} times before giving up.
     */
    @SuppressWarnings("unchecked")
    <T extends ClientMessage> T callLeaderWithRetry(
            ClientMessage request, Function<T, ErrorCode> errorCode, Function<T, String> leaderHint, int maxRedirects) {
        BrokerClientException lastFailure = null;
        for (int attempt = 0; attempt <= maxRedirects; attempt++) {
            NodeInfo leaderNode = leader();
            T response;
            try {
                response = (T) connectionTo(leaderNode).call(request);
            } catch (BrokerClientException e) {
                lastFailure = e;
                markStale(null);
                continue;
            }
            if (errorCode.apply(response) != ErrorCode.NOT_LEADER) {
                return response;
            }
            markStale(leaderHint.apply(response));
        }
        throw lastFailure != null ? lastFailure
                : new BrokerClientException("gave up locating the Raft leader after " + maxRedirects + " redirects");
    }

    private NodeInfo leader() {
        NodeInfo cached = currentLeader;
        return cached != null ? cached : refreshLeader();
    }

    private synchronized NodeInfo refreshLeader() {
        NodeInfo cached = currentLeader;
        if (cached != null) {
            return cached;
        }
        NodeInfo found = discoverLeader();
        currentLeader = found;
        return found;
    }

    private NodeInfo discoverLeader() {
        BrokerClientException last = null;
        for (NodeInfo node : List.copyOf(knownNodes.values())) {
            try {
                MetadataResponse response = (MetadataResponse) connectionTo(node).call(new MetadataRequest(null));
                for (NodeInfo n : response.nodes()) {
                    knownNodes.put(n.id(), n);
                }
                if (response.leaderId() != null) {
                    NodeInfo leader = knownNodes.get(response.leaderId());
                    if (leader != null) {
                        return leader;
                    }
                }
            } catch (BrokerClientException e) {
                last = e;
            }
        }
        throw last != null ? last
                : new BrokerClientException("no node in the cluster has observed a Raft leader yet");
    }

    /** Invalidates the cached leader after a redirect or connection failure. {@code hintId} is the
     * responder's best guess at who the real leader is (a {@code NOT_LEADER} response's
     * {@code leaderHint}); if it names an already-known node we jump straight there instead of
     * paying for a full rediscovery round, otherwise the next {@link #leader()} call rediscovers
     * from scratch. */
    private void markStale(String hintId) {
        currentLeader = hintId == null ? null : knownNodes.get(hintId);
    }

    @Override
    public void close() {
        for (BrokerConnection connection : connections.values()) {
            connection.close();
        }
        connections.clear();
    }
}
