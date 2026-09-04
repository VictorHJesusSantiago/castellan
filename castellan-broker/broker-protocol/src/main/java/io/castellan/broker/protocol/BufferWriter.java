package io.castellan.broker.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A growable little-endian-agnostic (Java's {@link ByteBuffer} default big-endian order, used
 * throughout this package for every encoded field) writer used to build one message's payload
 * before it is wrapped in a {@link Frame}. Every variable-length field (byte arrays, strings) is
 * length-prefixed with a 4-byte signed int, where {@code -1} is the reserved sentinel for "this
 * field is null" — the wire format has to represent nullability explicitly since, unlike Java
 * serialization, there is no separate out-of-band type tag to smuggle it in.
 *
 * <p>Not thread-safe; one writer builds exactly one message, then {@link #toByteArray()} is
 * called once.
 */
public final class BufferWriter {

    private byte[] array;
    private int position;

    public BufferWriter() {
        this(64);
    }

    public BufferWriter(int initialCapacity) {
        this.array = new byte[Math.max(initialCapacity, 8)];
        this.position = 0;
    }

    public BufferWriter writeByte(int value) {
        ensureCapacity(1);
        array[position++] = (byte) value;
        return this;
    }

    public BufferWriter writeInt(int value) {
        ensureCapacity(4);
        ByteBuffer.wrap(array, position, 4).putInt(value);
        position += 4;
        return this;
    }

    public BufferWriter writeLong(long value) {
        ensureCapacity(8);
        ByteBuffer.wrap(array, position, 8).putLong(value);
        position += 8;
        return this;
    }

    /** Writes a length-prefixed byte array; {@code null} is encoded as length {@code -1} with no
     * following bytes, distinct from a zero-length (empty, non-null) array. */
    public BufferWriter writeBytes(byte[] value) {
        if (value == null) {
            return writeInt(-1);
        }
        writeInt(value.length);
        ensureCapacity(value.length);
        System.arraycopy(value, 0, array, position, value.length);
        position += value.length;
        return this;
    }

    /** Writes a nullable UTF-8 string using the same length-prefix convention as
     * {@link #writeBytes}. */
    public BufferWriter writeString(String value) {
        return writeBytes(value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] toByteArray() {
        byte[] result = new byte[position];
        System.arraycopy(array, 0, result, 0, position);
        return result;
    }

    private void ensureCapacity(int additional) {
        int required = position + additional;
        if (required <= array.length) {
            return;
        }
        int newCapacity = array.length * 2;
        while (newCapacity < required) {
            newCapacity *= 2;
        }
        byte[] grown = new byte[newCapacity];
        System.arraycopy(array, 0, grown, 0, position);
        array = grown;
    }
}
