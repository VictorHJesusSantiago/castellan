package io.castellan.flow.engine;

import io.castellan.bpmn.exec.ProcessState;

import java.time.Instant;

/** A persisted process instance: which definition version it's pinned to, its current
 * externalized {@link ProcessState}, and lifecycle timestamps. */
public record ProcessInstanceRecord(String id, String processId, int definitionVersion, InstanceStatus status,
                                     ProcessState state, Instant createdAt, Instant updatedAt) {
}
