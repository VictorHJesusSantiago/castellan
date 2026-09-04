package io.castellan.broker.protocol.client;

import java.util.List;

/** {@code partitionCount} is this broker's single, cluster-wide configured partition count applied
 * uniformly to every topic (see this project's top-level report: there is no per-topic admin API,
 * so every topic that has ever been produced to or fetched from implicitly has exactly this many
 * partitions). {@code leaderId} is {@code null} if the responding node has not yet observed any
 * leader (e.g. mid-election). */
public record MetadataResponse(
        int partitionCount,
        String leaderId,
        List<NodeInfo> nodes
) implements ClientMessage {

    public MetadataResponse {
        nodes = List.copyOf(nodes);
    }
}
