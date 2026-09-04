package io.castellan.broker.protocol.client;

import io.castellan.broker.protocol.BufferReader;
import io.castellan.broker.protocol.BufferWriter;
import io.castellan.broker.protocol.Frame;
import io.castellan.broker.protocol.MessageType;
import io.castellan.broker.protocol.ProtocolException;

import java.util.ArrayList;
import java.util.List;

/** Encodes/decodes every {@link ClientMessage} to/from a {@link Frame}. See
 * {@code io.castellan.broker.protocol.RaftMessageCodec} for the equivalent on the Raft
 * peer-to-peer side; the two are separate classes (rather than one codec branching on message
 * range) because they encode types from two different, intentionally decoupled modules. */
public final class ClientProtocolCodec {

    private ClientProtocolCodec() {
    }

    public static Frame encode(ClientMessage message) {
        BufferWriter w = new BufferWriter();
        byte type = switch (message) {
            case ProduceRequest m -> {
                w.writeString(m.topic()).writeInt(m.partition()).writeLong(m.producerId())
                        .writeLong(m.sequence()).writeBytes(m.key()).writeBytes(m.value());
                yield MessageType.PRODUCE_REQUEST;
            }
            case ProduceResponse m -> {
                writeErrorCode(w, m.errorCode());
                w.writeString(m.leaderHint()).writeLong(m.offset());
                yield MessageType.PRODUCE_RESPONSE;
            }
            case FetchRequest m -> {
                w.writeString(m.topic()).writeInt(m.partition())
                        .writeLong(m.fromOffsetInclusive()).writeInt(m.maxRecords());
                yield MessageType.FETCH_REQUEST;
            }
            case FetchResponse m -> {
                writeErrorCode(w, m.errorCode());
                w.writeInt(m.records().size());
                for (RecordWire rec : m.records()) {
                    w.writeLong(rec.offset()).writeLong(rec.timestamp()).writeBytes(rec.key()).writeBytes(rec.value());
                }
                w.writeLong(m.highWatermark());
                yield MessageType.FETCH_RESPONSE;
            }
            case MetadataRequest m -> {
                w.writeString(m.topic());
                yield MessageType.METADATA_REQUEST;
            }
            case MetadataResponse m -> {
                w.writeInt(m.partitionCount()).writeString(m.leaderId()).writeInt(m.nodes().size());
                for (NodeInfo n : m.nodes()) {
                    w.writeString(n.id()).writeString(n.host()).writeInt(n.clientPort());
                }
                yield MessageType.METADATA_RESPONSE;
            }
            case JoinGroupRequest m -> {
                w.writeString(m.groupId()).writeString(m.memberId()).writeInt(m.topics().size());
                for (String topic : m.topics()) {
                    w.writeString(topic);
                }
                w.writeInt(m.sessionTimeoutMs());
                yield MessageType.JOIN_GROUP_REQUEST;
            }
            case JoinGroupResponse m -> {
                writeErrorCode(w, m.errorCode());
                w.writeString(m.leaderHint()).writeInt(m.generationId()).writeString(m.memberId());
                w.writeInt(m.assignedPartitions().size());
                for (TopicPartition tp : m.assignedPartitions()) {
                    w.writeString(tp.topic()).writeInt(tp.partition());
                }
                yield MessageType.JOIN_GROUP_RESPONSE;
            }
            case HeartbeatRequest m -> {
                w.writeString(m.groupId()).writeString(m.memberId()).writeInt(m.generationId());
                yield MessageType.HEARTBEAT_REQUEST;
            }
            case HeartbeatResponse m -> {
                writeErrorCode(w, m.errorCode());
                w.writeString(m.leaderHint());
                yield MessageType.HEARTBEAT_RESPONSE;
            }
            case LeaveGroupRequest m -> {
                w.writeString(m.groupId()).writeString(m.memberId());
                yield MessageType.LEAVE_GROUP_REQUEST;
            }
            case LeaveGroupResponse m -> {
                writeErrorCode(w, m.errorCode());
                yield MessageType.LEAVE_GROUP_RESPONSE;
            }
            case OffsetCommitRequest m -> {
                w.writeString(m.groupId()).writeString(m.memberId()).writeInt(m.generationId());
                w.writeString(m.topic()).writeInt(m.partition()).writeLong(m.offset());
                yield MessageType.OFFSET_COMMIT_REQUEST;
            }
            case OffsetCommitResponse m -> {
                writeErrorCode(w, m.errorCode());
                w.writeString(m.leaderHint());
                yield MessageType.OFFSET_COMMIT_RESPONSE;
            }
            case OffsetFetchRequest m -> {
                w.writeString(m.groupId()).writeString(m.topic()).writeInt(m.partition());
                yield MessageType.OFFSET_FETCH_REQUEST;
            }
            case OffsetFetchResponse m -> {
                writeErrorCode(w, m.errorCode());
                w.writeLong(m.offset());
                yield MessageType.OFFSET_FETCH_RESPONSE;
            }
        };
        return new Frame(type, w.toByteArray());
    }

    public static ClientMessage decode(Frame frame) {
        BufferReader r = new BufferReader(frame.payload());
        ClientMessage result = switch (frame.type()) {
            case MessageType.PRODUCE_REQUEST -> new ProduceRequest(
                    r.readString(), r.readInt(), r.readLong(), r.readLong(), r.readBytes(), r.readBytes());
            case MessageType.PRODUCE_RESPONSE -> new ProduceResponse(readErrorCode(r), r.readString(), r.readLong());
            case MessageType.FETCH_REQUEST -> new FetchRequest(
                    r.readString(), r.readInt(), r.readLong(), r.readInt());
            case MessageType.FETCH_RESPONSE -> {
                ErrorCode ec = readErrorCode(r);
                int count = r.readInt();
                List<RecordWire> records = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    records.add(new RecordWire(r.readLong(), r.readLong(), r.readBytes(), r.readBytes()));
                }
                yield new FetchResponse(ec, records, r.readLong());
            }
            case MessageType.METADATA_REQUEST -> new MetadataRequest(r.readString());
            case MessageType.METADATA_RESPONSE -> {
                int partitionCount = r.readInt();
                String leaderId = r.readString();
                int nodeCount = r.readInt();
                List<NodeInfo> nodes = new ArrayList<>(nodeCount);
                for (int i = 0; i < nodeCount; i++) {
                    nodes.add(new NodeInfo(r.readString(), r.readString(), r.readInt()));
                }
                yield new MetadataResponse(partitionCount, leaderId, nodes);
            }
            case MessageType.JOIN_GROUP_REQUEST -> {
                String groupId = r.readString();
                String memberId = r.readString();
                int topicCount = r.readInt();
                List<String> topics = new ArrayList<>(topicCount);
                for (int i = 0; i < topicCount; i++) {
                    topics.add(r.readString());
                }
                yield new JoinGroupRequest(groupId, memberId, topics, r.readInt());
            }
            case MessageType.JOIN_GROUP_RESPONSE -> {
                ErrorCode ec = readErrorCode(r);
                String leaderHint = r.readString();
                int generationId = r.readInt();
                String memberId = r.readString();
                int count = r.readInt();
                List<TopicPartition> assigned = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    assigned.add(new TopicPartition(r.readString(), r.readInt()));
                }
                yield new JoinGroupResponse(ec, leaderHint, generationId, memberId, assigned);
            }
            case MessageType.HEARTBEAT_REQUEST -> new HeartbeatRequest(r.readString(), r.readString(), r.readInt());
            case MessageType.HEARTBEAT_RESPONSE -> new HeartbeatResponse(readErrorCode(r), r.readString());
            case MessageType.LEAVE_GROUP_REQUEST -> new LeaveGroupRequest(r.readString(), r.readString());
            case MessageType.LEAVE_GROUP_RESPONSE -> new LeaveGroupResponse(readErrorCode(r));
            case MessageType.OFFSET_COMMIT_REQUEST -> new OffsetCommitRequest(
                    r.readString(), r.readString(), r.readInt(), r.readString(), r.readInt(), r.readLong());
            case MessageType.OFFSET_COMMIT_RESPONSE -> new OffsetCommitResponse(readErrorCode(r), r.readString());
            case MessageType.OFFSET_FETCH_REQUEST -> new OffsetFetchRequest(
                    r.readString(), r.readString(), r.readInt());
            case MessageType.OFFSET_FETCH_RESPONSE -> new OffsetFetchResponse(readErrorCode(r), r.readLong());
            default -> throw new ProtocolException("not a client protocol message type: " + frame.type());
        };
        r.expectExhausted();
        return result;
    }

    private static void writeErrorCode(BufferWriter w, ErrorCode code) {
        w.writeByte(code.ordinal());
    }

    private static ErrorCode readErrorCode(BufferReader r) {
        int ordinal = r.readByte();
        ErrorCode[] values = ErrorCode.values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new ProtocolException("corrupt message: unknown ErrorCode ordinal " + ordinal);
        }
        return values[ordinal];
    }
}
