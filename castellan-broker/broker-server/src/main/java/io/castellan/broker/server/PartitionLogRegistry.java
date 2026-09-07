package io.castellan.broker.server;

import io.castellan.broker.protocol.client.TopicPartition;
import io.castellan.broker.storage.PartitionLog;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every {@link PartitionLog} this node currently has open, keyed by {@link TopicPartition} and
 * opened lazily on first use — either by {@link CommandApplier#onApply} appending a just-committed
 * record, or by a fetch request reading from a partition nothing has been produced to yet (an
 * empty log is a legitimate thing to fetch from, e.g. a brand-new topic). Both call paths share
 * this one registry rather than each opening their own {@code PartitionLog}, since
 * {@code PartitionLog}'s own docs are explicit that two instances must never point at the same
 * directory concurrently.
 *
 * <p>Directory layout: {@code <dataDir>/data/<topic>/partition-<n>/}. This is deliberately the
 * same for every node in the cluster — every node applies the exact same sequence of committed
 * Raft entries (that is Raft's whole guarantee), so every node's on-disk partition contents
 * converge to the same records at the same offsets, which is what lets a fetch be served by any
 * node rather than only the leader.
 */
final class PartitionLogRegistry implements Closeable {

    private final Path dataDir;
    private final long segmentBytesLimit;
    private final ConcurrentHashMap<TopicPartition, PartitionLog> logs = new ConcurrentHashMap<>();

    PartitionLogRegistry(Path dataDir, long segmentBytesLimit) {
        this.dataDir = dataDir;
        this.segmentBytesLimit = segmentBytesLimit;
    }

    PartitionLog get(TopicPartition tp) {
        return logs.computeIfAbsent(tp, key -> PartitionLog.open(
                dataDir.resolve(key.topic()).resolve("partition-" + key.partition()), segmentBytesLimit));
    }

    @Override
    public void close() {
        logs.values().forEach(PartitionLog::close);
    }
}
