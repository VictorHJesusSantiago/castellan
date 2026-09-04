package io.castellan.broker.protocol;

import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Round-trips {@link Frame} over a real {@link Pipe} — a genuine pair of NIO channels, not a
 * mock — proving the framing survives an actual channel read/write cycle including short reads,
 * which {@code Pipe}'s small internal buffer makes likely to occur for anything but a tiny
 * payload. */
class FrameTest {

    @Test
    void roundTripsASmallFrame() throws Exception {
        Pipe pipe = Pipe.open();
        Frame original = new Frame((byte) 42, "hello".getBytes());
        original.writeTo(pipe.sink());

        Optional<Frame> read = Frame.readFrom(pipe.source());

        assertThat(read).isPresent();
        assertThat(read.get().type()).isEqualTo((byte) 42);
        assertThat(read.get().payload()).isEqualTo("hello".getBytes());
    }

    @Test
    void roundTripsAFrameLargerThanThePipesInternalBuffer() throws Exception {
        Pipe pipe = Pipe.open();
        byte[] payload = new byte[1_000_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 256);
        }
        Frame original = new Frame((byte) 7, payload);

        Thread writer = new Thread(() -> {
            try {
                original.writeTo(pipe.sink());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        writer.start();

        Optional<Frame> read = Frame.readFrom(pipe.source());
        writer.join();

        assertThat(read).isPresent();
        assertThat(read.get().type()).isEqualTo((byte) 7);
        assertThat(read.get().payload()).isEqualTo(payload);
    }

    @Test
    void roundTripsAnEmptyPayload() throws Exception {
        Pipe pipe = Pipe.open();
        Frame original = new Frame((byte) 1, new byte[0]);
        original.writeTo(pipe.sink());

        Optional<Frame> read = Frame.readFrom(pipe.source());

        assertThat(read).isPresent();
        assertThat(read.get().payload()).isEmpty();
    }

    @Test
    void cleanEofAtAFrameBoundaryReturnsEmpty() throws Exception {
        Pipe pipe = Pipe.open();
        pipe.sink().close();

        Optional<Frame> read = Frame.readFrom(pipe.source());

        assertThat(read).isEmpty();
    }

    @Test
    void tornFrameMidHeaderThrowsEofException() throws Exception {
        Pipe pipe = Pipe.open();
        ByteBuffer partial = ByteBuffer.wrap(new byte[]{0, 0});
        pipe.sink().write(partial);
        pipe.sink().close();

        assertThatThrownBy(() -> Frame.readFrom(pipe.source())).isInstanceOf(EOFException.class);
    }

    @Test
    void declaredLengthBeyondSanityBoundIsRejectedAsProtocolException() throws Exception {
        Pipe pipe = Pipe.open();
        ByteBuffer header = ByteBuffer.allocate(4);
        header.putInt(Integer.MAX_VALUE);
        header.flip();
        pipe.sink().write(header);

        assertThatThrownBy(() -> Frame.readFrom(pipe.source())).isInstanceOf(ProtocolException.class);
    }
}
