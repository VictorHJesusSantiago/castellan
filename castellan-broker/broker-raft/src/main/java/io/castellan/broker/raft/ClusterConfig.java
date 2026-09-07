package io.castellan.broker.raft;

import java.util.Set;

/**
 * Static cluster membership for one {@link RaftNode}: its own id and the ids of every other
 * voting member. Membership is fixed for the node's lifetime — no joint-consensus reconfiguration
 * (Raft paper §6) is implemented; adding or removing a server means restarting every node with a
 * new {@code ClusterConfig}, which is a real, deliberate scope cut (see this module's package
 * documentation), not an oversight.
 */
public record ClusterConfig(String selfId, Set<String> peerIds) {

    public ClusterConfig {
        if (selfId == null || selfId.isBlank()) {
            throw new IllegalArgumentException("selfId must not be blank");
        }
        if (peerIds == null) {
            throw new IllegalArgumentException("peerIds must not be null");
        }
        if (peerIds.contains(selfId)) {
            throw new IllegalArgumentException("peerIds must not contain selfId");
        }
        peerIds = Set.copyOf(peerIds);
    }

    /** Total voting members, including this node itself. */
    public int clusterSize() {
        return peerIds.size() + 1;
    }

    /** The smallest number of votes (including this node's own) that constitutes a majority. */
    public int majority() {
        return clusterSize() / 2 + 1;
    }
}
