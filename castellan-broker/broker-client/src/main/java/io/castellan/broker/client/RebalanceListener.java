package io.castellan.broker.client;

import io.castellan.broker.protocol.client.TopicPartition;

import java.util.List;

/** Optional callback a {@link Consumer} invokes whenever its partition assignment changes: an
 * initial {@link Consumer#join}, a heartbeat that discovers a rebalance and silently rejoins, an
 * explicit rejoin, or an explicit {@link Consumer#leave} (reported as a revocation of everything
 * this member held, with no corresponding assignment). Within any single one of those events,
 * revocation is always reported before assignment, mirroring the order a caller needs to safely
 * stop processing partitions it is about to lose before it starts processing ones it has just
 * gained. */
public interface RebalanceListener {

    default void onPartitionsRevoked(List<TopicPartition> partitions) {
    }

    default void onPartitionsAssigned(List<TopicPartition> partitions) {
    }
}
