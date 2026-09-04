package io.castellan.broker.protocol;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The read-side counterpart to {@link BufferWriter}: sequentially decodes primitives out of one
 * already-fully-received message payload. There is no partial-read/streaming support by design —
 * {@link Frame#readFrom} always buffers a whole frame before decoding begins, so a decoder never
 * needs to cope with a value split across two reads.
 *
 * <p>Any malformed input (a length prefix that runs past the end of the buffer, or a frame with
 * unconsumed trailing bytes once decoding believes it is done) surfaces as {@link ProtocolException}
 * rather than a raw {@link BufferUnderflowException} — decoders are the network's front door for
 * bytes it doesn't otherwise trust, and a narrow, catchable exception type lets a server tell "a
 * peer sent us garbage" apart from "this JVM has a bug" the way {@code broker-storage}'s
 * {@code CorruptRecordException} does for on-disk corruption.
 */
public final class BufferReader {

    private final ByteBuffer buf;

    public BufferReader(byte[] data) {
        this.buf = ByteBuffer.wrap(data);
    }

    public int readByte() {
        try {
            return buf.get() & 0xFF;
        } catch (BufferUnderflowException e) {
            throw new ProtocolException("truncated message: expected a byte", e);
        }
    }

    public int readInt() {
        try {
            return buf.getInt();
        } catch (BufferUnderflowException e) {
            throw new ProtocolException("truncated message: expected an int", e);
        }
    }

    public long readLong() {
        try {
            return buf.getLong();
        } catch (BufferUnderflowException e) {
            throw new ProtocolException("truncated message: expected a long", e);
        }
    }

    /** Reads a length-prefixed byte array previously written by {@link BufferWriter#writeBytes},
     * returning {@code null} for the {@code -1}-length sentinel. */
    public byte[] readBytes() {
        int length = readInt();
        if (length == -1) {
            return null;
        }
        if (length < 0 || length > buf.remaining()) {
            throw new ProtocolException("corrupt message: declared byte-array length " + length
                    + " but only " + buf.remaining() + " bytes remain");
        }
        byte[] out = new byte[length];
        buf.get(out);
        return out;
    }

    public String readString() {
        byte[] bytes = readBytes();
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    public boolean hasRemaining() {
        return buf.hasRemaining();
    }

    /** Throws if the payload has bytes left over after a decoder believes it has read every field
     * — evidence of a codec/version mismatch between peers rather than something safe to ignore. */
    public void expectExhausted() {
        if (buf.hasRemaining()) {
            throw new ProtocolException("corrupt message: " + buf.remaining() + " unexpected trailing bytes");
        }
    }
}
