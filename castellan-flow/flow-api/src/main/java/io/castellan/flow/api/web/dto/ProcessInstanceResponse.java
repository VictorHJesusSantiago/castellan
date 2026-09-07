package io.castellan.flow.api.web.dto;

import io.castellan.bpmn.exec.ProcessState;
import io.castellan.flow.engine.ProcessInstanceRecord;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ProcessInstanceResponse(
        String id,
        String processId,
        int definitionVersion,
        String status,
        Map<String, Object> variables,
        List<TokenResponse> tokens,
        List<String> completedActivityIds,
        List<String> compensatedActivityIds,
        Instant createdAt,
        Instant updatedAt) {

    public static ProcessInstanceResponse of(ProcessInstanceRecord record) {
        ProcessState state = record.state();
        List<TokenResponse> tokens = state.tokens().stream().map(TokenResponse::of).toList();
        return new ProcessInstanceResponse(
                record.id(), record.processId(), record.definitionVersion(), record.status().name(),
                state.variables(), tokens, state.completedActivityIds(), state.compensatedActivityIds(),
                record.createdAt(), record.updatedAt());
    }
}
