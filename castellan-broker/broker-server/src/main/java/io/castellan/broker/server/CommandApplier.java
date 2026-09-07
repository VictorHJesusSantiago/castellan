package io.castellan.broker.server;

import io.castellan.broker.protocol.client.TopicPartition;
import io.castellan.broker.protocol.command.BrokerCommand;
import io.castellan.broker.protocol.command.BrokerCommandCodec;
import io.castellan.broker.protocol.command.OffsetCommitCommand;
import io.castellan.broker.protocol.command.ProduceCommand;
import io.castellan.broker.raft.ApplyListener;
import io.castellan.broker.raft.LogEntry;
import io.castellan.broker.storage.PartitionLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one {@link ApplyListener} every node's {@link io.castellan.broker.raft.RaftNode} is wired
 * to — see that interface's own docs: this is "the one place Raft's guarantee... becomes
 * observable to the rest of the system". {@link #onApply} always runs on the single Raft
 * event-loop thread (it is called synchronously from inside
 * {@code RaftNode.handleAppendEntries}/{@code handleAppendEntriesResponse}, which
 * {@link RaftEventLoop} only ever invokes from its own single thread), so every mutation this
 * class makes to its dedup table and offset table happens with no concurrent writer — the same
 * single-threaded-owner argument {@code RaftNode} itself relies on. External readers (a fetch
 * thread reading committed offsets, a client thread registering/cancelling a wait) are real
 * concurrent access from other threads, which is why those specific structures are
 * {@link ConcurrentHashMap}s rather than plain {@code HashMap}s.
 *
 * <h2>Idempotent-producer dedup</h2>
 *
 * Kafka's own design, applied at the point of commit (not at the point a request is first
 * received): for {@code producerId != -1}, this class remembers, per {@code (partition,
 * producerId)}, only the single most recently applied {@code sequence} and the offset it was
 * assigned. A retried {@link ProduceCommand} whose {@code sequence} is {@code <=} that remembered
 * value is recognized as the same logical write being retried (the client's original attempt was
 * accepted and replicated, but its response was lost, so it resent the identical request) and is
 * <em>not</em> appended a second time — the remembered offset is returned instead, so a retry is
 * idempotent from the producer's point of view even though the underlying transport has no
 * exactly-once delivery guarantee of its own. This is deliberately applied during {@code onApply},
 * not during request validation, because dedup itself must be linearized in exactly the same
 * order as the appends it is deduplicating against — every node needs to reach the identical
 * dedup decision for the identical command, which is only guaranteed if the decision is a pure
 * function of "this command plus every earlier committed command", applied in commit order.
 *
 * <p>A real limitation, worth being explicit about: only the single latest sequence per
 * {@code (partition, producerId)} is remembered, not a short window of recent ones (Kafka itself
 * keeps the last 5). A retry of an attempt older than the immediately preceding one is still
 * <em>not</em> double-appended (this class still short-circuits on {@code sequence <=
 * lastSequence}), but such a very-stale retry gets back the latest offset rather than its own
 * original one — acceptable for this project's scope (a client only ever has one produce in
 * flight per partition at a time in practice, matching {@code broker-client}'s synchronous
 * request/response send path) but a real gap relative to Kafka's fuller window.
 */
final class CommandApplier implements ApplyListener {

    private static final Logger log = LoggerFactory.getLogger(CommandApplier.class);

    private record SequenceState(long lastSequence, long lastOffset) {
    }

    private final PartitionLogRegistry partitionLogs;
    private final ConcurrentHashMap<TopicPartition, ConcurrentHashMap<Long, SequenceState>> dedup = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<GroupTopicPartition, Long> committedOffsets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();

    CommandApplier(PartitionLogRegistry partitionLogs) {
        this.partitionLogs = partitionLogs;
    }

    record GroupTopicPartition(String groupId, String topic, int partition) {
    }

    /** Called by {@link RaftEventLoop#proposeAndAwaitApply} right after a propose is accepted,
     * strictly before that same event-loop task returns — so there is no window in which
     * {@link #onApply} for this exact index could run before the wait is registered. */
    void registerWait(long raftIndex, CompletableFuture<Object> future) {
        pending.put(raftIndex, future);
    }

    /** Called by the awaiting thread if it times out — removes the registration so a
     * never-committed (e.g. truncated by a new leader) entry doesn't leak a future forever. */
    void cancelWait(long raftIndex, CompletableFuture<Object> future) {
        pending.remove(raftIndex, future);
    }

    Long committedOffset(String groupId, String topic, int partition) {
        return committedOffsets.get(new GroupTopicPartition(groupId, topic, partition));
    }

    @Override
    public void onApply(LogEntry entry) {
        BrokerCommand command = BrokerCommandCodec.decode(entry.command());
        Object outcome = switch (command) {
            case ProduceCommand c -> applyProduce(c);
            case OffsetCommitCommand c -> applyOffsetCommit(c);
        };
        CompletableFuture<Object> future = pending.remove(entry.index());
        if (future != null) {
            future.complete(outcome);
        }
    }

    private ApplyOutcome.Produced applyProduce(ProduceCommand c) {
        TopicPartition tp = new TopicPartition(c.topic(), c.partition());
        PartitionLog partitionLog = partitionLogs.get(tp);

        if (c.producerId() == -1) {
            long offset = partitionLog.append(c.key(), c.value());
            return new ApplyOutcome.Produced(offset);
        }

        ConcurrentHashMap<Long, SequenceState> perProducer = dedup.computeIfAbsent(tp, k -> new ConcurrentHashMap<>());
        SequenceState existing = perProducer.get(c.producerId());
        if (existing != null && c.sequence() <= existing.lastSequence()) {
            log.debug("deduping retried produce: topic={} partition={} producerId={} sequence={} (already applied at offset {})",
                    c.topic(), c.partition(), c.producerId(), c.sequence(), existing.lastOffset());
            return new ApplyOutcome.Produced(existing.lastOffset());
        }
        long offset = partitionLog.append(c.key(), c.value());
        perProducer.put(c.producerId(), new SequenceState(c.sequence(), offset));
        return new ApplyOutcome.Produced(offset);
    }

    private ApplyOutcome.OffsetCommitted applyOffsetCommit(OffsetCommitCommand c) {
        committedOffsets.put(new GroupTopicPartition(c.groupId(), c.topic(), c.partition()), c.offset());
        return new ApplyOutcome.OffsetCommitted();
    }
}
