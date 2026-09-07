package io.castellan.flow.engine;

import java.time.Instant;

/** A deployed, immutable BPMN process definition version. */
public record ProcessDefinitionRecord(String processId, int version, String name, String bpmnXml, Instant deployedAt) {
}
