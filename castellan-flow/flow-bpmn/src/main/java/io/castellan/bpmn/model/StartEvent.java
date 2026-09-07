package io.castellan.bpmn.model;

/** A process's entry point. A token is placed here when a process instance starts. */
public final class StartEvent extends FlowNode {
    public StartEvent(String id, String name) {
        super(id, name);
    }
}
