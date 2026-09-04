package io.castellan.broker.protocol.client;

public record LeaveGroupResponse(ErrorCode errorCode) implements ClientMessage {
}
