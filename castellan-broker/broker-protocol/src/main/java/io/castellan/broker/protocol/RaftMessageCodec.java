package io.castellan.broker.protocol;

import io.castellan.broker.raft.AppendEntriesRequest;
import io.castellan.broker.raft.AppendEntriesResponse;
import io.castellan.broker.raft.LogEntry;
import io.castellan.broker.raft.RaftMessage;
import io.castellan.broker.raft.RequestVoteRequest;
import io.castellan.broker.raft.RequestVoteResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes/decodes {@code broker-raft}'s four {@link RaftMessage} variants to/from a {@link Frame}.
 * This is the one codec in the module that reaches into another module's domain types directly
 * (rather than defining its own mirror records the way {@code io.castellan.broker.protocol.client}
 * does for the client-facing protocol) — {@code broker-raft}'s {@link RequestVoteRequest} etc. are
 * already exactly the shape the wire needs, and {@code broker-protocol} already depends on
 * {@code broker-raft} for {@link LogEntry}, so introducing a parallel set of wire-only records here
 * would be pure duplication with no decoupling benefit (unlike the client protocol, which
 * deliberately does <em>not</em> want a compile-time dependency on {@code broker-raft} from
 * {@code broker-client}).
 */
public final class RaftMessageCodec {

    private RaftMessageCodec() {
    }

    public static Frame encode(RaftMessage message) {
        BufferWriter w = new BufferWriter();
        byte type = switch (message) {
            case RequestVoteRequest req -> {
                w.writeLong(req.term()).writeString(req.candidateId())
                        .writeLong(req.lastLogIndex()).writeLong(req.lastLogTerm());
                yield MessageType.RAFT_REQUEST_VOTE_REQUEST;
            }
            case RequestVoteResponse resp -> {
                w.writeLong(resp.term()).writeByte(resp.voteGranted() ? 1 : 0).writeString(resp.voterId());
                yield MessageType.RAFT_REQUEST_VOTE_RESPONSE;
            }
            case AppendEntriesRequest req -> {
                w.writeLong(req.term()).writeString(req.leaderId())
                        .writeLong(req.prevLogIndex()).writeLong(req.prevLogTerm());
                writeEntries(w, req.entries());
                w.writeLong(req.leaderCommit());
                yield MessageType.RAFT_APPEND_ENTRIES_REQUEST;
            }
            case AppendEntriesResponse resp -> {
                w.writeLong(resp.term()).writeByte(resp.success() ? 1 : 0).writeString(resp.followerId())
                        .writeLong(resp.matchIndex()).writeLong(resp.conflictTerm()).writeLong(resp.conflictIndex());
                yield MessageType.RAFT_APPEND_ENTRIES_RESPONSE;
            }
        };
        return new Frame(type, w.toByteArray());
    }

    public static RaftMessage decode(Frame frame) {
        BufferReader r = new BufferReader(frame.payload());
        RaftMessage result = switch (frame.type()) {
            case MessageType.RAFT_REQUEST_VOTE_REQUEST -> new RequestVoteRequest(
                    r.readLong(), r.readString(), r.readLong(), r.readLong());
            case MessageType.RAFT_REQUEST_VOTE_RESPONSE -> new RequestVoteResponse(
                    r.readLong(), r.readByte() != 0, r.readString());
            case MessageType.RAFT_APPEND_ENTRIES_REQUEST -> {
                long term = r.readLong();
                String leaderId = r.readString();
                long prevLogIndex = r.readLong();
                long prevLogTerm = r.readLong();
                List<LogEntry> entries = readEntries(r);
                long leaderCommit = r.readLong();
                yield new AppendEntriesRequest(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit);
            }
            case MessageType.RAFT_APPEND_ENTRIES_RESPONSE -> new AppendEntriesResponse(
                    r.readLong(), r.readByte() != 0, r.readString(), r.readLong(), r.readLong(), r.readLong());
            default -> throw new ProtocolException("not a Raft message type: " + frame.type());
        };
        r.expectExhausted();
        return result;
    }

    private static void writeEntries(BufferWriter w, List<LogEntry> entries) {
        w.writeInt(entries.size());
        for (LogEntry entry : entries) {
            w.writeLong(entry.term()).writeLong(entry.index()).writeBytes(entry.command());
        }
    }

    private static List<LogEntry> readEntries(BufferReader r) {
        int count = r.readInt();
        List<LogEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new LogEntry(r.readLong(), r.readLong(), r.readBytes()));
        }
        return entries;
    }
}
