package io.castellan.bpmn.model;

/** A BPMN {@code <association>}. This module only uses associations for one purpose: linking a
 * compensation boundary event to its compensation handler activity. */
public record Association(String id, String sourceRef, String targetRef) {
}
