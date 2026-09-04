package io.castellan.broker.protocol.client;

/** One cluster member's client-facing address, as returned by a {@link MetadataResponse} — a
 * client bootstrapped with only some of the cluster's addresses learns the rest from here, and
 * uses {@code host}/{@code clientPort} (not the Raft peer port) to actually connect. */
public record NodeInfo(String id, String host, int clientPort) {
}
