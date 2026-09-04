package io.castellan.broker.protocol.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrokerCommandCodecTest {

    @Test
    void produceCommandRoundTripsWithAndWithoutIdempotenceAndWithNullKey() {
        ProduceCommand withIdempotence = new ProduceCommand("orders", 1, 42L, 7L, "key".getBytes(), "value".getBytes());
        assertThat(BrokerCommandCodec.decode(BrokerCommandCodec.encode(withIdempotence)))
                .usingRecursiveComparison().isEqualTo(withIdempotence);

        ProduceCommand plain = new ProduceCommand("orders", 0, -1L, 0L, null, "value2".getBytes());
        assertThat(BrokerCommandCodec.decode(BrokerCommandCodec.encode(plain)))
                .usingRecursiveComparison().isEqualTo(plain);
    }

    @Test
    void offsetCommitCommandRoundTrips() {
        OffsetCommitCommand cmd = new OffsetCommitCommand("group-1", "orders", 2, 123L);
        assertThat(BrokerCommandCodec.decode(BrokerCommandCodec.encode(cmd))).isEqualTo(cmd);
    }

    @Test
    void unknownKindByteIsRejected() {
        byte[] bogus = {99, 0, 0, 0, 0};
        assertThatThrownBy(() -> BrokerCommandCodec.decode(bogus))
                .isInstanceOf(io.castellan.broker.protocol.ProtocolException.class);
    }
}
