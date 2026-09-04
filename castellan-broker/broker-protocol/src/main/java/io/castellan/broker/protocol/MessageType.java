package io.castellan.broker.protocol;

/**
 * The single byte identifying what a {@link Frame}'s payload decodes as. Split into two disjoint
 * ranges purely for human readability when staring at a packet capture — 1..9 for the four Raft
 * peer-to-peer RPCs ({@link RaftMessageCodec}), 10..29 for the client-facing protocol
 * ({@code ClientProtocolCodec}) — {@code broker-server} in fact never needs to branch on the range
 * itself, since Raft RPCs and client requests arrive on separate listening ports (see
 * {@code NodeAddress}'s {@code raftPort}/{@code clientPort} split) and so are never ambiguous about
 * which codec applies.
 */
public final class MessageType {

    public static final byte RAFT_REQUEST_VOTE_REQUEST = 1;
    public static final byte RAFT_REQUEST_VOTE_RESPONSE = 2;
    public static final byte RAFT_APPEND_ENTRIES_REQUEST = 3;
    public static final byte RAFT_APPEND_ENTRIES_RESPONSE = 4;

    public static final byte PRODUCE_REQUEST = 10;
    public static final byte PRODUCE_RESPONSE = 11;
    public static final byte FETCH_REQUEST = 12;
    public static final byte FETCH_RESPONSE = 13;
    public static final byte METADATA_REQUEST = 14;
    public static final byte METADATA_RESPONSE = 15;
    public static final byte JOIN_GROUP_REQUEST = 16;
    public static final byte JOIN_GROUP_RESPONSE = 17;
    public static final byte HEARTBEAT_REQUEST = 18;
    public static final byte HEARTBEAT_RESPONSE = 19;
    public static final byte LEAVE_GROUP_REQUEST = 20;
    public static final byte LEAVE_GROUP_RESPONSE = 21;
    public static final byte OFFSET_COMMIT_REQUEST = 22;
    public static final byte OFFSET_COMMIT_RESPONSE = 23;
    public static final byte OFFSET_FETCH_REQUEST = 24;
    public static final byte OFFSET_FETCH_RESPONSE = 25;

    private MessageType() {
    }
}
