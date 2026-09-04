package io.castellan.broker.protocol.command;

import io.castellan.broker.protocol.BufferReader;
import io.castellan.broker.protocol.BufferWriter;
import io.castellan.broker.protocol.ProtocolException;

/**
 * Encodes/decodes a {@link BrokerCommand} to/from the raw {@code byte[]} that becomes a Raft
 * {@code LogEntry}'s command payload. Unlike {@link io.castellan.broker.protocol.Frame}-based
 * codecs elsewhere in this module, there is no length-prefixed frame wrapper here — {@code
 * LogEntry} already carries its command as a plain {@code byte[]} with its own length implicit in
 * the array, and {@code broker-raft}'s on-disk/in-memory log implementations already frame
 * *that* independently (see {@code FileRaftLog}'s on-disk record format) — wrapping it in a
 * second length-prefixed frame here would be pure redundancy. The first byte of the payload is
 * this codec's own command-kind discriminator (distinct from {@link
 * io.castellan.broker.protocol.MessageType}'s byte space, since this is a different, narrower
 * closed set with no need to share numbering).
 */
public final class BrokerCommandCodec {

    private static final byte KIND_PRODUCE = 1;
    private static final byte KIND_OFFSET_COMMIT = 2;

    private BrokerCommandCodec() {
    }

    public static byte[] encode(BrokerCommand command) {
        BufferWriter w = new BufferWriter();
        switch (command) {
            case ProduceCommand c -> {
                w.writeByte(KIND_PRODUCE);
                w.writeString(c.topic()).writeInt(c.partition()).writeLong(c.producerId())
                        .writeLong(c.sequence()).writeBytes(c.key()).writeBytes(c.value());
            }
            case OffsetCommitCommand c -> {
                w.writeByte(KIND_OFFSET_COMMIT);
                w.writeString(c.groupId()).writeString(c.topic()).writeInt(c.partition()).writeLong(c.offset());
            }
        }
        return w.toByteArray();
    }

    public static BrokerCommand decode(byte[] payload) {
        BufferReader r = new BufferReader(payload);
        int kind = r.readByte();
        BrokerCommand result = switch (kind) {
            case KIND_PRODUCE -> new ProduceCommand(
                    r.readString(), r.readInt(), r.readLong(), r.readLong(), r.readBytes(), r.readBytes());
            case KIND_OFFSET_COMMIT -> new OffsetCommitCommand(
                    r.readString(), r.readString(), r.readInt(), r.readLong());
            default -> throw new ProtocolException("unknown BrokerCommand kind byte: " + kind);
        };
        r.expectExhausted();
        return result;
    }
}
