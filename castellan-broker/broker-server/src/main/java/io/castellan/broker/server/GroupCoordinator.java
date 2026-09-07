package io.castellan.broker.server;

import io.castellan.broker.protocol.client.ErrorCode;
import io.castellan.broker.protocol.client.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Consumer-group membership, session-timeout eviction, and partition assignment — deliberately
 * scoped to run only on the Raft leader (see {@link BrokerCommand}'s javadoc for why membership
 * itself is not Raft-replicated) and reset entirely whenever this node loses leadership
 * ({@link #clear()}, wired to {@link RaftEventLoop#onLeadershipChange} by {@link BrokerServer}) —
 * a follower's stale copy of "who was in the group" must never be served once it becomes leader,
 * every member must simply rejoin the new leader from scratch, which they naturally do the next
 * time a heartbeat or produce/fetch call gets a {@code NOT_LEADER} response and redirects.
 *
 * <h2>Rebalance protocol (a deliberate simplification of Kafka's own two-phase handshake)</h2>
 *
 * A single {@code JoinGroup} round trip both (re)establishes membership <em>and</em> returns that
 * member's slice of a freshly recomputed assignment — there is no separate {@code SyncGroup}
 * phase or client-elected "group leader" responsible for computing assignment. The coordinator
 * itself computes a deterministic round-robin assignment (sorted member ids, sorted (topic,
 * partition) pairs, members subscribed to a topic split its partitions evenly) every time
 * membership changes: a join, an explicit leave, or a session-timeout eviction. Every recomputation
 * bumps {@code generationId}; a member's next {@code Heartbeat} or {@code OffsetCommit} carrying a
 * stale generation is told {@code REBALANCE_IN_PROGRESS}/{@code ILLEGAL_GENERATION} and must
 * {@code JoinGroup} again to learn its current assignment — this is how the <em>other</em>
 * members of a group eventually learn a rebalance happened, since nothing pushes it to them
 * proactively.
 */
final class GroupCoordinator implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(GroupCoordinator.class);

    private final int partitionCount;
    private final long sessionSweepIntervalMs;
    private final Map<String, Group> groups = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    GroupCoordinator(int partitionCount, long sessionSweepIntervalMs) {
        this.partitionCount = partitionCount;
        this.sessionSweepIntervalMs = sessionSweepIntervalMs;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "group-session-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(this::sweep, sessionSweepIntervalMs, sessionSweepIntervalMs, TimeUnit.MILLISECONDS);
    }

    private static final class Member {
        final String memberId;
        final List<String> topics;
        final int sessionTimeoutMs;
        volatile long lastHeartbeatMillis;

        Member(String memberId, List<String> topics, int sessionTimeoutMs) {
            this.memberId = memberId;
            this.topics = topics;
            this.sessionTimeoutMs = sessionTimeoutMs;
            this.lastHeartbeatMillis = System.currentTimeMillis();
        }
    }

    private static final class Group {
        final String groupId;
        final Map<String, Member> members = new LinkedHashMap<>();
        final TreeSet<String> topics = new TreeSet<>();
        int generationId = 0;
        Map<String, List<TopicPartition>> assignment = Map.of();

        Group(String groupId) {
            this.groupId = groupId;
        }
    }

    record JoinResult(int generationId, String memberId, List<TopicPartition> assignedPartitions) {
    }

    record HeartbeatOutcome(ErrorCode errorCode) {
        static final HeartbeatOutcome OK = new HeartbeatOutcome(ErrorCode.NONE);
    }

    JoinResult join(String groupId, String requestedMemberId, List<String> topics, int sessionTimeoutMs) {
        Group group = groups.computeIfAbsent(groupId, Group::new);
        synchronized (group) {
            String memberId = (requestedMemberId == null || requestedMemberId.isBlank())
                    ? groupId + "-" + UUID.randomUUID()
                    : requestedMemberId;
            group.members.put(memberId, new Member(memberId, topics, sessionTimeoutMs));
            group.topics.addAll(topics);
            rebalance(group);
            List<TopicPartition> assigned = group.assignment.getOrDefault(memberId, List.of());
            log.info("group {} member {} joined: generation={} assigned={}", groupId, memberId, group.generationId, assigned);
            return new JoinResult(group.generationId, memberId, assigned);
        }
    }

    HeartbeatOutcome heartbeat(String groupId, String memberId, int generationId) {
        Group group = groups.get(groupId);
        if (group == null) {
            return new HeartbeatOutcome(ErrorCode.UNKNOWN_MEMBER_ID);
        }
        synchronized (group) {
            Member member = group.members.get(memberId);
            if (member == null) {
                return new HeartbeatOutcome(ErrorCode.UNKNOWN_MEMBER_ID);
            }
            if (generationId != group.generationId) {
                return new HeartbeatOutcome(ErrorCode.REBALANCE_IN_PROGRESS);
            }
            member.lastHeartbeatMillis = System.currentTimeMillis();
            return HeartbeatOutcome.OK;
        }
    }

    ErrorCode leave(String groupId, String memberId) {
        Group group = groups.get(groupId);
        if (group == null) {
            return ErrorCode.NONE;
        }
        synchronized (group) {
            if (group.members.remove(memberId) != null) {
                rebalance(group);
            }
            return ErrorCode.NONE;
        }
    }

    /** Validates that {@code memberId}/{@code generationId} are current for {@code groupId}
     * before an {@code OffsetCommit} is allowed to propose — a stale generation must not be
     * allowed to durably commit an offset based on an assignment that no longer holds. */
    ErrorCode validateForCommit(String groupId, String memberId, int generationId) {
        Group group = groups.get(groupId);
        if (group == null) {
            return ErrorCode.UNKNOWN_MEMBER_ID;
        }
        synchronized (group) {
            if (!group.members.containsKey(memberId)) {
                return ErrorCode.UNKNOWN_MEMBER_ID;
            }
            if (generationId != group.generationId) {
                return ErrorCode.ILLEGAL_GENERATION;
            }
            return ErrorCode.NONE;
        }
    }

    /** Discards every group's membership — called on losing leadership (see class docs) so a
     * demoted former leader never answers a stale query with membership only it remembers. */
    void clear() {
        groups.clear();
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Group group : groups.values()) {
            synchronized (group) {
                boolean evicted = group.members.values()
                        .removeIf(m -> now - m.lastHeartbeatMillis > m.sessionTimeoutMs);
                if (evicted) {
                    log.info("group {} evicted stale member(s) on session timeout; rebalancing", group.groupId);
                    rebalance(group);
                }
            }
        }
    }

    /** Must be called with {@code group}'s monitor held. Deterministic round-robin: sorted
     * (topic, partition) pairs (restricted to a topic's own subscribers) handed out in rotation
     * across that topic's subscribers, sorted by member id for reproducibility. */
    private void rebalance(Group group) {
        group.generationId++;
        Map<String, List<TopicPartition>> newAssignment = new LinkedHashMap<>();
        for (String memberId : group.members.keySet()) {
            newAssignment.put(memberId, new ArrayList<>());
        }
        for (String topic : group.topics) {
            List<String> subscribers = group.members.values().stream()
                    .filter(m -> m.topics.contains(topic))
                    .map(m -> m.memberId)
                    .sorted()
                    .toList();
            if (subscribers.isEmpty()) {
                continue;
            }
            for (int partition = 0; partition < partitionCount; partition++) {
                String assignee = subscribers.get(partition % subscribers.size());
                newAssignment.get(assignee).add(new TopicPartition(topic, partition));
            }
        }
        group.assignment = newAssignment;
    }

    @Override
    public void close() {
        sweeper.shutdownNow();
    }
}
