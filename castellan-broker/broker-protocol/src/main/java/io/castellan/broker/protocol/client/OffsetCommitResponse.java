package io.castellan.broker.protocol.client;

public record OffsetCommitResponse(ErrorCode errorCode, String leaderHint) implements ClientMessage {
}
