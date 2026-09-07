package io.castellan.flow.api.web.dto;

import io.castellan.flow.engine.ProcessDefinitionRecord;

import java.time.Instant;

/** {@code bpmnXml} is deliberately omitted -- a caller deploying or looking up a definition
 * already has the XML; returning it on every read would bloat every response for no benefit. */
public record ProcessDefinitionResponse(String processId, int version, String name, Instant deployedAt) {

    public static ProcessDefinitionResponse of(ProcessDefinitionRecord record) {
        return new ProcessDefinitionResponse(record.processId(), record.version(), record.name(), record.deployedAt());
    }
}
