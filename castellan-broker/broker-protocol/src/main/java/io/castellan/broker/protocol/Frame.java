package io.castellan.broker.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Optional;

/**
 * The on-the-wire framing every message in this broker uses, over a real {@link ReadableByteChannel}
 * / {@link WritableByteChannel} (a {@code SocketChannel} in production, a {@code Pipe} in tests —
 * see {@code FrameTest} — with no behavioral difference, since both are plain NIO channels):
 *
 * <pre>[4-byte frameLength (big-endian, signed)][1-byte messageType][payload...]</pre>
 *
 * {@code frameLength} counts everything <em>after</em> the length field itself — i.e.
 * {@code 1 + payload.length} — matching the framing convention {@code broker-storage}'s
 * {@code Segment} and {@code FileRaftLog} already use for their own on-disk record frames, so a
 * reader familiar with one immediately recognizes the other. {@code messageType} is one of the byte
 * constants in {@link MessageType}; {@code payload} is whatever {@link RaftMessageCodec} or
 * {@code ClientProtocolCodec} produced for that type — {@code Frame} itself is deliberately
 * ignorant of what the payload bytes mean, the same separation {@code broker-raft}'s
 * {@code Envelope} draws between "who to send to" and "what the message means".
 */
public record Frame(byte type, byte[] payload) {

    private static final int LENGTH_FIELD_BYTES = 4;
    private static final int TYPE_FIELD_BYTES = 1;

    /** The largest frame this reader will accept — a defensive bound against a corrupt or
     * malicious peer claiming a multi-gigabyte frame and forcing an equally large allocation
     * before any of it has even been validated. Comfortably above any real produce/fetch batch
     * this project's tests or intended scale ever construct. */
    private static final int MAX_FRAME_LENGTH = 64 * 1024 * 1024;

    /**
     * Blocks until a complete frame has been read, or returns {@link Optional#empty()} if the
     * channel hit a clean end-of-stream exactly at a frame boundary (the normal way a peer closing
     * its connection between requests is observed). An end-of-stream in the <em>middle</em> of a
     * frame is a real error (a peer that died mid-write), reported as {@link EOFException} rather
     * than silently returning a truncated frame or an empty one.
     */
    public static Optional<Frame> readFrom(ReadableByteChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(LENGTH_FIELD_BYTES);
        if (!readFully(channel, header)) {
            if (header.position() == 0) {
                return Optional.empty();
            }
            throw new EOFException("channel closed mid-frame while reading the 4-byte length header");
        }
        header.flip();
        int frameLength = header.getInt();
        if (frameLength < TYPE_FIELD_BYTES || frameLength > MAX_FRAME_LENGTH) {
            throw new ProtocolException("corrupt frame: declared length " + frameLength + " out of bounds");
        }
        ByteBuffer body = ByteBuffer.allocate(frameLength);
        if (!readFully(channel, body)) {
            throw new EOFException("channel closed mid-frame after header declared length " + frameLength);
        }
        body.flip();
        byte type = body.get();
        byte[] payload = new byte[frameLength - TYPE_FIELD_BYTES];
        body.get(payload);
        return Optional.of(new Frame(type, payload));
    }

    /** Writes this frame in full, blocking as needed — {@link WritableByteChannel#write} is free
     * to write fewer bytes than requested even in blocking mode, so a single call is not enough. */
    public void writeTo(WritableByteChannel channel) throws IOException {
        int frameLength = TYPE_FIELD_BYTES + payload.length;
        ByteBuffer out = ByteBuffer.allocate(LENGTH_FIELD_BYTES + frameLength);
        out.putInt(frameLength);
        out.put(type);
        out.put(payload);
        out.flip();
        while (out.hasRemaining()) {
            channel.write(out);
        }
    }

    /** Reads until {@code buf} is full, or end-of-stream is reached first. Returns {@code false}
     * on end-of-stream (callers distinguish "clean" from "torn" by checking {@code buf.position()}
     * — see {@link #readFrom}), {@code true} once {@code buf} is completely filled. */
    private static boolean readFully(ReadableByteChannel channel, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            int n = channel.read(buf);
            if (n < 0) {
                return false;
            }
        }
        return true;
    }
}
