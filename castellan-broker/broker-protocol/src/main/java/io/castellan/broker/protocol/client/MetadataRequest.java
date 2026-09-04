package io.castellan.broker.protocol.client;

/** Asks any node for the cluster's client-facing addresses, the current Raft leader (if known to
 * that node), and {@code topic}'s partition count. {@code topic} may be {@code null} to ask only
 * about cluster/leader information without implying interest in any particular topic. */
public record MetadataRequest(String topic) implements ClientMessage {
}
