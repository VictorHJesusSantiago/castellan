package io.castellan.broker.protocol.client;

import io.castellan.broker.protocol.Frame;
import io.castellan.broker.protocol.ProtocolException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Encodes then decodes every {@link ClientMessage} variant and asserts exact equality — the
 * Definition of Done's specific requirement for this module's client-facing half. */
class ClientProtocolCodecTest {

    private static ClientMessage roundTrip(ClientMessage message) {
        Frame frame = ClientProtocolCodec.encode(message);
        return ClientProtocolCodec.decode(frame);
    }

    @Test
    void produceRequestRoundTripsWithAndWithoutAKeyAndWithIdempotenceFields() {
        ProduceRequest withKey = new ProduceRequest("orders", 2, 555L, 3L, "k1".getBytes(), "v1".getBytes());
        assertThat(roundTrip(withKey)).usingRecursiveComparison().isEqualTo(withKey);

        ProduceRequest noKeyNoIdempotence = new ProduceRequest("orders", 0, -1L, 0L, null, "v2".getBytes());
        assertThat(roundTrip(noKeyNoIdempotence)).usingRecursiveComparison().isEqualTo(noKeyNoIdempotence);
    }

    @Test
    void produceResponseRoundTripsSuccessAndNotLeaderShapes() {
        ProduceResponse ok = new ProduceResponse(ErrorCode.NONE, null, 41L);
        assertThat(roundTrip(ok)).isEqualTo(ok);

        ProduceResponse notLeader = new ProduceResponse(ErrorCode.NOT_LEADER, "n2", 0L);
        assertThat(roundTrip(notLeader)).isEqualTo(notLeader);
    }

    @Test
    void fetchRequestAndResponseRoundTrip() {
        FetchRequest req = new FetchRequest("orders", 1, 17L, 100);
        assertThat(roundTrip(req)).isEqualTo(req);

        FetchResponse resp = new FetchResponse(ErrorCode.NONE, List.of(
                new RecordWire(0, 1000L, "k".getBytes(), "v".getBytes()),
                new RecordWire(1, 1001L, null, "v2".getBytes())), 2L);
        assertThat(roundTrip(resp)).usingRecursiveComparison().isEqualTo(resp);

        FetchResponse empty = new FetchResponse(ErrorCode.NONE, List.of(), 0L);
        assertThat(roundTrip(empty)).isEqualTo(empty);
    }

    @Test
    void metadataRequestAndResponseRoundTrip() {
        MetadataRequest req = new MetadataRequest("orders");
        assertThat(roundTrip(req)).isEqualTo(req);
        assertThat(roundTrip(new MetadataRequest(null))).isEqualTo(new MetadataRequest(null));

        MetadataResponse resp = new MetadataResponse(3, "n0", List.of(
                new NodeInfo("n0", "127.0.0.1", 9001),
                new NodeInfo("n1", "127.0.0.1", 9002)));
        assertThat(roundTrip(resp)).isEqualTo(resp);
    }

    @Test
    void joinGroupRoundTrips() {
        JoinGroupRequest req = new JoinGroupRequest("g1", "", List.of("orders", "payments"), 10_000);
        assertThat(roundTrip(req)).isEqualTo(req);

        JoinGroupResponse resp = new JoinGroupResponse(ErrorCode.NONE, null, 4, "member-abc",
                List.of(new TopicPartition("orders", 0), new TopicPartition("orders", 2)));
        assertThat(roundTrip(resp)).isEqualTo(resp);

        JoinGroupResponse rejected = new JoinGroupResponse(ErrorCode.NOT_LEADER, "n1", 0, "", List.of());
        assertThat(roundTrip(rejected)).isEqualTo(rejected);
    }

    @Test
    void heartbeatRoundTrips() {
        HeartbeatRequest req = new HeartbeatRequest("g1", "member-abc", 4);
        assertThat(roundTrip(req)).isEqualTo(req);

        assertThat(roundTrip(new HeartbeatResponse(ErrorCode.NONE, null)))
                .isEqualTo(new HeartbeatResponse(ErrorCode.NONE, null));
        assertThat(roundTrip(new HeartbeatResponse(ErrorCode.REBALANCE_IN_PROGRESS, null)))
                .isEqualTo(new HeartbeatResponse(ErrorCode.REBALANCE_IN_PROGRESS, null));
    }

    @Test
    void leaveGroupRoundTrips() {
        assertThat(roundTrip(new LeaveGroupRequest("g1", "member-abc")))
                .isEqualTo(new LeaveGroupRequest("g1", "member-abc"));
        assertThat(roundTrip(new LeaveGroupResponse(ErrorCode.NONE)))
                .isEqualTo(new LeaveGroupResponse(ErrorCode.NONE));
    }

    @Test
    void offsetCommitAndFetchRoundTrip() {
        OffsetCommitRequest commitReq = new OffsetCommitRequest("g1", "member-abc", 4, "orders", 2, 99L);
        assertThat(roundTrip(commitReq)).isEqualTo(commitReq);
        assertThat(roundTrip(new OffsetCommitResponse(ErrorCode.NONE, null)))
                .isEqualTo(new OffsetCommitResponse(ErrorCode.NONE, null));

        OffsetFetchRequest fetchReq = new OffsetFetchRequest("g1", "orders", 2);
        assertThat(roundTrip(fetchReq)).isEqualTo(fetchReq);
        assertThat(roundTrip(new OffsetFetchResponse(ErrorCode.NONE, 99L)))
                .isEqualTo(new OffsetFetchResponse(ErrorCode.NONE, 99L));
        assertThat(roundTrip(new OffsetFetchResponse(ErrorCode.NONE, -1L)))
                .isEqualTo(new OffsetFetchResponse(ErrorCode.NONE, -1L));
    }

    @Test
    void everyErrorCodeValueRoundTrips() {
        for (ErrorCode code : ErrorCode.values()) {
            ProduceResponse resp = new ProduceResponse(code, null, 0L);
            assertThat(roundTrip(resp)).isEqualTo(resp);
        }
    }

    @Test
    void decodingAnUnrecognizedTypeByteThrowsProtocolException() {
        Frame bogus = new Frame((byte) 123, new byte[]{1, 2, 3});
        assertThatThrownBy(() -> ClientProtocolCodec.decode(bogus)).isInstanceOf(ProtocolException.class);
    }
}
