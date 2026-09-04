package io.castellan.broker.protocol.client;

/**
 * Every request/response shape in the client-facing protocol (producing, fetching, and consumer
 * group coordination), as a sealed interface — see {@code ClientProtocolCodec} for the exhaustive
 * {@code switch} this enables, and {@code io.castellan.broker.raft.RaftMessage}'s own docstring for
 * why {@code broker-raft} draws the same trade-off for its four RPC shapes.
 *
 * <p>Deliberately independent of {@code broker-raft}'s types even though {@code broker-server}
 * implements some of these requests (produce, offset-commit) <em>by</em> proposing a Raft command —
 * {@code broker-client} depends on this module but must never need to depend on {@code broker-raft}
 * itself, since a client has no business knowing consensus is involved at all.
 */
public sealed interface ClientMessage
        permits ProduceRequest, ProduceResponse, FetchRequest, FetchResponse, MetadataRequest, MetadataResponse,
        JoinGroupRequest, JoinGroupResponse, HeartbeatRequest, HeartbeatResponse, LeaveGroupRequest,
        LeaveGroupResponse, OffsetCommitRequest, OffsetCommitResponse, OffsetFetchRequest, OffsetFetchResponse {
}
