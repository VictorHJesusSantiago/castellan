package io.castellan.broker.client;

import io.castellan.broker.protocol.client.ErrorCode;
import io.castellan.broker.protocol.client.FetchRequest;
import io.castellan.broker.protocol.client.FetchResponse;
import io.castellan.broker.protocol.client.HeartbeatRequest;
import io.castellan.broker.protocol.client.HeartbeatResponse;
import io.castellan.broker.protocol.client.JoinGroupRequest;
import io.castellan.broker.protocol.client.JoinGroupResponse;
import io.castellan.broker.protocol.client.LeaveGroupRequest;
import io.castellan.broker.protocol.client.LeaveGroupResponse;
import io.castellan.broker.protocol.client.NodeInfo;
import io.castellan.broker.protocol.client.OffsetCommitRequest;
import io.castellan.broker.protocol.client.OffsetCommitResponse;
import io.castellan.broker.protocol.client.OffsetFetchRequest;
import io.castellan.broker.protocol.client.OffsetFetchResponse;
import io.castellan.broker.protocol.client.RecordWire;
import io.castellan.broker.protocol.client.TopicPartition;

import java.io.Closeable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A member of a consumer group against a Castellan broker cluster: {@link #join} establishes (or
 * re-establishes) membership and receives a slice of the group's partitions, {@link #poll} fetches
 * newly available records from every currently assigned partition, and {@link #commit} durably
 * records progress so a restarted consumer resumes where this one left off (see
 * {@code broker-server}'s {@code GroupCoordinator} for the server-side rebalance protocol this
 * drives).
 *
 * <p>Not thread-safe for concurrent {@link #join}/{@link #poll}/{@link #commit} calls from
 * multiple application threads — the one exception is the optional background heartbeat thread
 * started by {@link #startHeartbeating}, which only ever calls the already-synchronized
 * {@link #heartbeat()}.
 *
 * <h2>Rebalance handling</h2>
 * A {@code Heartbeat} response of {@code REBALANCE_IN_PROGRESS} (this member's generation has been
 * superseded by another member joining, leaving, or being evicted) is handled transparently by
 * silently calling {@link #join} again to learn the current assignment — an application only needs
 * to register a {@link RebalanceListener} if it wants to react to *which* partitions it gained or
 * lost, not to keep polling working across a rebalance.
 */
public final class Consumer implements Closeable {

    private static final int MAX_LEADER_REDIRECTS = 5;

    private final LeaderRouter router;
    private final String groupId;
    private final List<String> topics;
    private final int sessionTimeoutMs;
    private final RebalanceListener listener;
    private final Map<TopicPartition, Long> positions = new ConcurrentHashMap<>();

    private String memberId = "";
    private int generationId = -1;
    private List<TopicPartition> assignedPartitions = List.of();
    private ScheduledExecutorService heartbeatExecutor;

    public Consumer(List<NodeInfo> bootstrapNodes, String groupId, List<String> topics, int sessionTimeoutMs) {
        this(bootstrapNodes, groupId, topics, sessionTimeoutMs, null);
    }

    public Consumer(List<NodeInfo> bootstrapNodes, String groupId, List<String> topics, int sessionTimeoutMs,
                     RebalanceListener listener) {
        this.router = new LeaderRouter(bootstrapNodes);
        this.groupId = groupId;
        this.topics = List.copyOf(topics);
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.listener = listener;
    }

    /** Joins (or re-joins) {@code groupId}, applying whatever assignment the coordinator hands
     * back — including one covering zero partitions, if every topic's partitions are already
     * fully claimed by other members. Safe to call again at any time to explicitly force a fresh
     * assignment (e.g. after {@link #leave}). */
    public synchronized List<TopicPartition> join() {
        JoinGroupRequest request = new JoinGroupRequest(groupId, memberId, topics, sessionTimeoutMs);
        JoinGroupResponse response = router.callLeaderWithRetry(
                request, JoinGroupResponse::errorCode, JoinGroupResponse::leaderHint, MAX_LEADER_REDIRECTS);
        if (response.errorCode() != ErrorCode.NONE) {
            throw new BrokerClientException("join group " + groupId + " failed: " + response.errorCode());
        }
        applyAssignment(response.memberId(), response.generationId(), response.assignedPartitions());
        return assignedPartitions;
    }

    private void applyAssignment(String newMemberId, int newGenerationId, List<TopicPartition> newAssignment) {
        List<TopicPartition> previous = assignedPartitions;
        memberId = newMemberId;
        generationId = newGenerationId;
        assignedPartitions = List.copyOf(newAssignment);

        List<TopicPartition> revoked = previous.stream().filter(tp -> !assignedPartitions.contains(tp)).toList();
        if (!revoked.isEmpty()) {
            positions.keySet().removeAll(revoked);
            if (listener != null) {
                listener.onPartitionsRevoked(revoked);
            }
        }

        List<TopicPartition> added = assignedPartitions.stream().filter(tp -> !previous.contains(tp)).toList();
        for (TopicPartition tp : added) {
            positions.put(tp, fetchInitialPosition(tp));
        }
        if (!added.isEmpty() && listener != null) {
            listener.onPartitionsAssigned(added);
        }
    }

    private long fetchInitialPosition(TopicPartition tp) {
        OffsetFetchResponse response = router.callAny(new OffsetFetchRequest(groupId, tp.topic(), tp.partition()));
        return response.offset() < 0 ? 0L : response.offset();
    }

    /** Starts a daemon background thread that calls {@link #heartbeat()} every {@code interval} —
     * a real long-lived consumer needs this so the coordinator doesn't evict it on session timeout
     * purely because the application is busy processing a poll batch. A background heartbeat
     * failure is swallowed (it surfaces on the next explicit {@link #join}/{@link #poll}/
     * {@link #commit} call instead of killing the thread), matching how a transient network blip
     * shouldn't be fatal to an otherwise-healthy consumer. */
    public synchronized void startHeartbeating(Duration interval) {
        if (heartbeatExecutor != null) {
            return;
        }
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "broker-consumer-heartbeat-" + groupId);
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(
                this::heartbeatQuietly, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void heartbeatQuietly() {
        try {
            heartbeat();
        } catch (RuntimeException ignored) {
        }
    }

    /** Sends one heartbeat for this member's current generation. A {@code REBALANCE_IN_PROGRESS}
     * response is handled by transparently rejoining to learn the current assignment; any other
     * non-{@code NONE} error is thrown. A no-op if this consumer has never joined. */
    public synchronized void heartbeat() {
        if (generationId < 0) {
            return;
        }
        HeartbeatRequest request = new HeartbeatRequest(groupId, memberId, generationId);
        HeartbeatResponse response = router.callLeaderWithRetry(
                request, HeartbeatResponse::errorCode, HeartbeatResponse::leaderHint, MAX_LEADER_REDIRECTS);
        if (response.errorCode() == ErrorCode.REBALANCE_IN_PROGRESS) {
            join();
        } else if (response.errorCode() != ErrorCode.NONE) {
            throw new BrokerClientException(
                    "heartbeat for " + groupId + "/" + memberId + " failed: " + response.errorCode());
        }
    }

    /** Fetches up to {@code maxRecordsPerPartition} new records from each currently assigned
     * partition (in assignment order), advancing this consumer's in-memory fetch position past
     * whatever it returns. Positions are purely in-memory until {@link #commit} durably records
     * one — a consumer that never commits re-reads everything from its last committed offset (or
     * the beginning) after every restart. */
    public List<ConsumerRecord> poll(int maxRecordsPerPartition) {
        List<ConsumerRecord> out = new ArrayList<>();
        for (TopicPartition tp : assignedPartitions) {
            long fromOffset = positions.getOrDefault(tp, 0L);
            FetchResponse response = router.callAny(
                    new FetchRequest(tp.topic(), tp.partition(), fromOffset, maxRecordsPerPartition));
            if (response.errorCode() != ErrorCode.NONE || response.records().isEmpty()) {
                continue;
            }
            for (RecordWire rec : response.records()) {
                out.add(new ConsumerRecord(tp.topic(), tp.partition(), rec.offset(), rec.timestamp(), rec.key(), rec.value()));
            }
            long lastOffset = response.records().get(response.records().size() - 1).offset();
            positions.put(tp, lastOffset + 1);
        }
        return out;
    }

    /** Durably commits {@code offset} as {@code tp}'s next offset to resume from, via this
     * member's current generation — a stale generation (this member's assignment was superseded by
     * a rebalance it hasn't yet rejoined for) is rejected with an exception rather than silently
     * committing against an assignment that may no longer be valid. */
    public void commit(TopicPartition tp, long offset) {
        OffsetCommitRequest request = new OffsetCommitRequest(groupId, memberId, generationId, tp.topic(), tp.partition(), offset);
        OffsetCommitResponse response = router.callLeaderWithRetry(
                request, OffsetCommitResponse::errorCode, OffsetCommitResponse::leaderHint, MAX_LEADER_REDIRECTS);
        if (response.errorCode() != ErrorCode.NONE) {
            throw new BrokerClientException(
                    "offset commit for " + groupId + " " + tp + " failed: " + response.errorCode());
        }
    }

    /** Gracefully departs the group, triggering an immediate rebalance of the remaining members
     * rather than making them wait out this member's session timeout, and stops the background
     * heartbeat thread if one was started. A no-op if this consumer has never joined. */
    public synchronized void leave() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
        if (generationId >= 0) {
            LeaveGroupResponse response = router.callLeaderWithRetry(
                    new LeaveGroupRequest(groupId, memberId), LeaveGroupResponse::errorCode, r -> null, MAX_LEADER_REDIRECTS);
            if (response.errorCode() != ErrorCode.NONE) {
                throw new BrokerClientException("leave group " + groupId + " failed: " + response.errorCode());
            }
            List<TopicPartition> revoked = assignedPartitions;
            generationId = -1;
            assignedPartitions = List.of();
            positions.clear();
            if (!revoked.isEmpty() && listener != null) {
                listener.onPartitionsRevoked(revoked);
            }
        }
    }

    public synchronized List<TopicPartition> assignment() {
        return assignedPartitions;
    }

    public synchronized String memberId() {
        return memberId;
    }

    @Override
    public void close() {
        leave();
        router.close();
    }
}
