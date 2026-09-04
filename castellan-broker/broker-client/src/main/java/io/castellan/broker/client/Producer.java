package io.castellan.broker.client;

import io.castellan.broker.protocol.client.ErrorCode;
import io.castellan.broker.protocol.client.NodeInfo;
import io.castellan.broker.protocol.client.ProduceRequest;
import io.castellan.broker.protocol.client.ProduceResponse;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Appends records to a Castellan broker cluster, bootstrapped from any subset of the cluster's
 * client-facing addresses (the rest are learned via {@code Metadata}, see {@link LeaderRouter}).
 * Every {@link #send} blocks until the record has been durably committed via Raft (or the leader
 * redirect budget is exhausted) — there is no fire-and-forget or batching mode, matching this
 * project's scope (see the top-level report's honest cut list).
 *
 * <h2>Idempotent mode</h2>
 * Constructed with {@code idempotent = true}, this producer is assigned one random
 * {@code producerId} for its lifetime and stamps every record with a per-{@code (topic,
 * partition)} monotonically increasing sequence number. A retried {@link #send} for a request the
 * broker already applied (e.g. after a timeout whose response was lost, not whose write was lost)
 * is answered with the original assigned offset instead of appending a duplicate — see
 * {@code broker-server}'s {@code CommandApplier} dedup table, which this producer's wire format
 * (producerId/sequence on every {@link ProduceRequest}) exists to drive. Non-idempotent mode
 * (the default) sends {@code producerId = -1}, and every send — including an application-level
 * retry — becomes a new record.
 *
 * <p>Thread-safe: multiple threads may call {@link #send} concurrently: sequence numbers are
 * assigned atomically per partition, and the single shared connection per broker node serializes
 * the actual wire calls (matching {@code broker-server}'s one-request-in-flight-per-connection
 * design).
 */
public final class Producer implements Closeable {

    private static final int MAX_LEADER_REDIRECTS = 5;

    private final LeaderRouter router;
    private final long producerId;
    private final Map<TopicPartitionKey, AtomicLong> sequences = new ConcurrentHashMap<>();

    public Producer(List<NodeInfo> bootstrapNodes) {
        this(bootstrapNodes, false);
    }

    public Producer(List<NodeInfo> bootstrapNodes, boolean idempotent) {
        this.router = new LeaderRouter(bootstrapNodes);
        this.producerId = idempotent ? ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE) : -1L;
    }

    public boolean isIdempotent() {
        return producerId != -1L;
    }

    public ProduceResult send(String topic, int partition, byte[] key, byte[] value) {
        long sequence = producerId == -1L ? 0L : nextSequence(topic, partition);
        ProduceRequest request = new ProduceRequest(topic, partition, producerId, sequence, key, value);
        ProduceResponse response = router.callLeaderWithRetry(
                request, ProduceResponse::errorCode, ProduceResponse::leaderHint, MAX_LEADER_REDIRECTS);
        if (response.errorCode() != ErrorCode.NONE) {
            throw new BrokerClientException(
                    "produce to " + topic + "-" + partition + " failed: " + response.errorCode());
        }
        return new ProduceResult(topic, partition, response.offset());
    }

    private long nextSequence(String topic, int partition) {
        return sequences.computeIfAbsent(new TopicPartitionKey(topic, partition), k -> new AtomicLong())
                .getAndIncrement();
    }

    @Override
    public void close() {
        router.close();
    }

    private record TopicPartitionKey(String topic, int partition) {
    }
}
