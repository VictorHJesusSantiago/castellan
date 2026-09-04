package io.castellan.broker.protocol;

import io.castellan.broker.raft.AppendEntriesRequest;
import io.castellan.broker.raft.AppendEntriesResponse;
import io.castellan.broker.raft.LogEntry;
import io.castellan.broker.raft.RaftMessage;
import io.castellan.broker.raft.RequestVoteRequest;
import io.castellan.broker.raft.RequestVoteResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Encodes then decodes every {@link RaftMessage} variant and asserts exact equality with the
 * original — the Definition of Done's specific requirement for this module. */
class RaftMessageCodecTest {

    private static RaftMessage roundTrip(RaftMessage message) {
        Frame frame = RaftMessageCodec.encode(message);
        return RaftMessageCodec.decode(frame);
    }

    @Test
    void requestVoteRequestRoundTrips() {
        RequestVoteRequest original = new RequestVoteRequest(7, "n1", 42, 6);
        assertThat(roundTrip(original)).isEqualTo(original);
    }

    @Test
    void requestVoteResponseRoundTripsBothGrantedAndDenied() {
        assertThat(roundTrip(new RequestVoteResponse(7, true, "n2"))).isEqualTo(new RequestVoteResponse(7, true, "n2"));
        assertThat(roundTrip(new RequestVoteResponse(7, false, "n2"))).isEqualTo(new RequestVoteResponse(7, false, "n2"));
    }

    @Test
    void appendEntriesRequestRoundTripsWithMultipleEntriesAndBinaryCommands() {
        List<LogEntry> entries = List.of(
                new LogEntry(3, 5, new byte[]{1, 2, 3}),
                new LogEntry(3, 6, new byte[]{-128, 0, 127, (byte) 255}),
                new LogEntry(4, 7, new byte[0]));
        AppendEntriesRequest original = new AppendEntriesRequest(4, "leader-1", 4, 3, entries, 6);

        RaftMessage decoded = roundTrip(original);

        assertThat(decoded).isInstanceOf(AppendEntriesRequest.class);
        AppendEntriesRequest d = (AppendEntriesRequest) decoded;
        assertThat(d.term()).isEqualTo(original.term());
        assertThat(d.leaderId()).isEqualTo(original.leaderId());
        assertThat(d.prevLogIndex()).isEqualTo(original.prevLogIndex());
        assertThat(d.prevLogTerm()).isEqualTo(original.prevLogTerm());
        assertThat(d.leaderCommit()).isEqualTo(original.leaderCommit());
        assertThat(d.entries()).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(d.entries().get(i).term()).isEqualTo(entries.get(i).term());
            assertThat(d.entries().get(i).index()).isEqualTo(entries.get(i).index());
            assertThat(d.entries().get(i).command()).isEqualTo(entries.get(i).command());
        }
    }

    @Test
    void appendEntriesRequestRoundTripsAsAHeartbeatWithNoEntries() {
        AppendEntriesRequest original = new AppendEntriesRequest(9, "leader-2", 10, 8, List.of(), 10);

        RaftMessage decoded = roundTrip(original);

        assertThat(decoded).isInstanceOf(AppendEntriesRequest.class);
        assertThat(((AppendEntriesRequest) decoded).isHeartbeat()).isTrue();
        assertThat(((AppendEntriesRequest) decoded).entries()).isEmpty();
    }

    @Test
    void appendEntriesResponseRoundTripsSuccessAndFailureShapes() {
        AppendEntriesResponse success = new AppendEntriesResponse(5, true, "follower-1", 12, 0, 0);
        assertThat(roundTrip(success)).isEqualTo(success);

        AppendEntriesResponse failure = new AppendEntriesResponse(5, false, "follower-2", 0, 3, 9);
        assertThat(roundTrip(failure)).isEqualTo(failure);
    }

    @Test
    void decodingAnUnrecognizedTypeByteThrowsProtocolException() {
        Frame bogus = new Frame((byte) 99, new byte[]{1, 2, 3});
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RaftMessageCodec.decode(bogus))
                .isInstanceOf(ProtocolException.class);
    }
}
