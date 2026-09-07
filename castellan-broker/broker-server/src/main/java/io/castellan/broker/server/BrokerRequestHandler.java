package io.castellan.broker.server;

import io.castellan.broker.protocol.client.ClientMessage;
import io.castellan.broker.protocol.client.ErrorCode;
import io.castellan.broker.protocol.client.FetchRequest;
import io.castellan.broker.protocol.client.FetchResponse;
import io.castellan.broker.protocol.client.HeartbeatRequest;
import io.castellan.broker.protocol.client.HeartbeatResponse;
import io.castellan.broker.protocol.client.JoinGroupRequest;
import io.castellan.broker.protocol.client.JoinGroupResponse;
import io.castellan.broker.protocol.client.LeaveGroupRequest;
import io.castellan.broker.protocol.client.LeaveGroupResponse;
import io.castellan.broker.protocol.client.MetadataRequest;
import io.castellan.broker.protocol.client.MetadataResponse;
import io.castellan.broker.protocol.client.NodeInfo;
import io.castellan.broker.protocol.client.OffsetCommitRequest;
import io.castellan.broker.protocol.client.OffsetCommitResponse;
import io.castellan.broker.protocol.client.OffsetFetchRequest;
import io.castellan.broker.protocol.client.OffsetFetchResponse;
import io.castellan.broker.protocol.client.ProduceRequest;
import io.castellan.broker.protocol.client.ProduceResponse;
import io.castellan.broker.protocol.client.RecordWire;
import io.castellan.broker.protocol.client.TopicPartition;
import io.castellan.broker.protocol.command.BrokerCommandCodec;
import io.castellan.broker.protocol.command.OffsetCommitCommand;
import io.castellan.broker.protocol.command.ProduceCommand;
import io.castellan.broker.storage.PartitionLog;
import io.castellan.broker.storage.Record;

import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Turns one decoded {@link ClientMessage} request into a response, the point where the
 * client-facing protocol, the Raft-driven write path ({@link RaftEventLoop#proposeAndAwaitApply}),
 * the replicated read path ({@link PartitionLogRegistry}), and consumer-group coordination
 * ({@link GroupCoordinator}) all meet. Called from a {@link ClientRequestServer} connection
 * thread — one per connected client — so a slow leader-commit wait for one client's produce never
 * blocks any other client's requests, only that one connection's next request.
 */
final class BrokerRequestHandler {

    private final RaftEventLoop eventLoop;
    private final CommandApplier applier;
    private final PartitionLogRegistry partitionLogs;
    private final GroupCoordinator groupCoordinator;
    private final NodeConfig config;
    private final List<NodeInfo> clusterNodeInfo;

    BrokerRequestHandler(RaftEventLoop eventLoop, CommandApplier applier, PartitionLogRegistry partitionLogs,
                          GroupCoordinator groupCoordinator, NodeConfig config) {
        this.eventLoop = eventLoop;
        this.applier = applier;
        this.partitionLogs = partitionLogs;
        this.groupCoordinator = groupCoordinator;
        this.config = config;
        this.clusterNodeInfo = config.nodes().stream()
                .map(n -> new NodeInfo(n.id(), n.host(), n.clientPort()))
                .toList();
    }

    ClientMessage handle(ClientMessage request) {
        return switch (request) {
            case ProduceRequest r -> handleProduce(r);
            case FetchRequest r -> handleFetch(r);
            case MetadataRequest r -> handleMetadata(r);
            case JoinGroupRequest r -> handleJoinGroup(r);
            case HeartbeatRequest r -> handleHeartbeat(r);
            case LeaveGroupRequest r -> handleLeaveGroup(r);
            case OffsetCommitRequest r -> handleOffsetCommit(r);
            case OffsetFetchRequest r -> handleOffsetFetch(r);
            default -> throw new IllegalArgumentException(
                    "not a valid inbound request: " + request.getClass().getSimpleName());
        };
    }

    private ProduceResponse handleProduce(ProduceRequest r) {
        ProduceCommand command = new ProduceCommand(r.topic(), r.partition(), r.producerId(), r.sequence(), r.key(), r.value());
        try {
            ApplyOutcome.Produced outcome = eventLoop.proposeAndAwaitApply(
                    BrokerCommandCodec.encode(command), applier, config.requestTimeoutMs());
            return new ProduceResponse(ErrorCode.NONE, null, outcome.offset());
        } catch (NotLeaderException e) {
            return new ProduceResponse(ErrorCode.NOT_LEADER, e.leaderHint().orElse(null), 0);
        } catch (TimeoutException e) {
            return new ProduceResponse(ErrorCode.INTERNAL_ERROR, null, 0);
        }
    }

    private FetchResponse handleFetch(FetchRequest r) {
        PartitionLog log = partitionLogs.get(new TopicPartition(r.topic(), r.partition()));
        List<Record> records = log.read(r.fromOffsetInclusive(), r.maxRecords());
        List<RecordWire> wire = records.stream()
                .map(rec -> new RecordWire(rec.offset(), rec.timestamp(), rec.key(), rec.value()))
                .toList();
        return new FetchResponse(ErrorCode.NONE, wire, log.latestOffset());
    }

    private MetadataResponse handleMetadata(MetadataRequest r) {
        return new MetadataResponse(config.defaultPartitionCount(), eventLoop.currentLeaderId().orElse(null), clusterNodeInfo);
    }

    private JoinGroupResponse handleJoinGroup(JoinGroupRequest r) {
        if (!eventLoop.isLeader()) {
            return new JoinGroupResponse(ErrorCode.NOT_LEADER, eventLoop.currentLeaderId().orElse(null), 0, "", List.of());
        }
        GroupCoordinator.JoinResult result = groupCoordinator.join(r.groupId(), r.memberId(), r.topics(), r.sessionTimeoutMs());
        return new JoinGroupResponse(ErrorCode.NONE, null, result.generationId(), result.memberId(), result.assignedPartitions());
    }

    private HeartbeatResponse handleHeartbeat(HeartbeatRequest r) {
        if (!eventLoop.isLeader()) {
            return new HeartbeatResponse(ErrorCode.NOT_LEADER, eventLoop.currentLeaderId().orElse(null));
        }
        GroupCoordinator.HeartbeatOutcome outcome = groupCoordinator.heartbeat(r.groupId(), r.memberId(), r.generationId());
        return new HeartbeatResponse(outcome.errorCode(), null);
    }

    private LeaveGroupResponse handleLeaveGroup(LeaveGroupRequest r) {
        if (!eventLoop.isLeader()) {
            return new LeaveGroupResponse(ErrorCode.NOT_LEADER);
        }
        return new LeaveGroupResponse(groupCoordinator.leave(r.groupId(), r.memberId()));
    }

    private OffsetCommitResponse handleOffsetCommit(OffsetCommitRequest r) {
        if (!eventLoop.isLeader()) {
            return new OffsetCommitResponse(ErrorCode.NOT_LEADER, eventLoop.currentLeaderId().orElse(null));
        }
        ErrorCode validation = groupCoordinator.validateForCommit(r.groupId(), r.memberId(), r.generationId());
        if (validation != ErrorCode.NONE) {
            return new OffsetCommitResponse(validation, null);
        }
        OffsetCommitCommand command = new OffsetCommitCommand(r.groupId(), r.topic(), r.partition(), r.offset());
        try {
            eventLoop.<ApplyOutcome.OffsetCommitted>proposeAndAwaitApply(
                    BrokerCommandCodec.encode(command), applier, config.requestTimeoutMs());
            return new OffsetCommitResponse(ErrorCode.NONE, null);
        } catch (NotLeaderException e) {
            return new OffsetCommitResponse(ErrorCode.NOT_LEADER, e.leaderHint().orElse(null));
        } catch (TimeoutException e) {
            return new OffsetCommitResponse(ErrorCode.INTERNAL_ERROR, null);
        }
    }

    private OffsetFetchResponse handleOffsetFetch(OffsetFetchRequest r) {
        Long offset = applier.committedOffset(r.groupId(), r.topic(), r.partition());
        return new OffsetFetchResponse(ErrorCode.NONE, offset == null ? -1L : offset);
    }
}
