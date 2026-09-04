package io.castellan.broker.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BufferWriterReaderTest {

    @Test
    void roundTripsEveryPrimitiveInSequence() {
        byte[] encoded = new BufferWriter()
                .writeByte(0xFF)
                .writeInt(-12345)
                .writeLong(Long.MIN_VALUE)
                .writeBytes("hello".getBytes())
                .writeBytes(null)
                .writeBytes(new byte[0])
                .writeString("héllo, world")
                .writeString(null)
                .toByteArray();

        BufferReader r = new BufferReader(encoded);
        assertThat(r.readByte()).isEqualTo(0xFF);
        assertThat(r.readInt()).isEqualTo(-12345);
        assertThat(r.readLong()).isEqualTo(Long.MIN_VALUE);
        assertThat(r.readBytes()).isEqualTo("hello".getBytes());
        assertThat(r.readBytes()).isNull();
        assertThat(r.readBytes()).isEmpty();
        assertThat(r.readString()).isEqualTo("héllo, world");
        assertThat(r.readString()).isNull();
        r.expectExhausted();
    }

    @Test
    void growsPastItsInitialCapacityWithoutCorruptingEarlierWrites() {
        BufferWriter w = new BufferWriter(4);
        byte[] big = new byte[10_000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) i;
        }
        w.writeInt(1).writeBytes(big).writeInt(2);

        BufferReader r = new BufferReader(w.toByteArray());
        assertThat(r.readInt()).isEqualTo(1);
        assertThat(r.readBytes()).isEqualTo(big);
        assertThat(r.readInt()).isEqualTo(2);
    }

    @Test
    void trailingBytesAfterAFullyConsumedPayloadAreRejected() {
        byte[] encoded = new BufferWriter().writeInt(1).writeInt(2).toByteArray();
        BufferReader r = new BufferReader(encoded);
        r.readInt();

        assertThatThrownBy(r::expectExhausted).isInstanceOf(ProtocolException.class);
    }

    @Test
    void aTruncatedPayloadFailsWithProtocolExceptionNotABufferUnderflowException() {
        byte[] tooShort = new byte[]{0, 0};
        BufferReader r = new BufferReader(tooShort);

        assertThatThrownBy(r::readLong).isInstanceOf(ProtocolException.class);
    }

    @Test
    void aBogusByteArrayLengthPrefixIsRejectedRatherThanAllocatingWildly() {
        byte[] encoded = new BufferWriter().writeInt(Integer.MAX_VALUE).toByteArray();
        BufferReader r = new BufferReader(encoded);

        assertThatThrownBy(r::readBytes).isInstanceOf(ProtocolException.class);
    }
}
