package io.castellan.flow.api.web.dto;

import io.castellan.bpmn.exec.Token;

import java.time.Instant;

public record TokenResponse(String id, String nodeId, String status, Instant timerDueAt,
                             String waitingSignalName, String forkId, String raceGroupId) {

    public static TokenResponse of(Token token) {
        return new TokenResponse(token.id(), token.nodeId(), token.status().name(), token.timerDueAt(),
                token.waitingSignalName(), token.forkId(), token.raceGroupId());
    }
}
