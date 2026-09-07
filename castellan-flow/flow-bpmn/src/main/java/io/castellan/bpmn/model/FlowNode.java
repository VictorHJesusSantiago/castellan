package io.castellan.bpmn.model;

/**
 * One BPMN flow node: a start/end event, a task, a gateway, or a boundary/intermediate event.
 * Sealed so every place that must handle every node type (chiefly
 * {@link io.castellan.bpmn.exec.ProcessInterpreter}) gets an exhaustive {@code switch} checked at
 * compile time — adding a new node type is a compiler error everywhere it isn't yet handled,
 * rather than a silent "unsupported node, does nothing" at runtime.
 */
public sealed abstract class FlowNode
        permits StartEvent, EndEvent, Task, Gateway, BoundaryEvent, IntermediateCatchEvent {

    private final String id;
    private final String name;

    protected FlowNode(String id, String name) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("flow node id must not be blank");
        }
        this.id = id;
        this.name = name;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + id + "]";
    }
}
