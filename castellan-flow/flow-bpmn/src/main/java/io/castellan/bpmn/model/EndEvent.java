package io.castellan.bpmn.model;

/** A token reaching this node is consumed; the branch it was on is finished. */
public final class EndEvent extends FlowNode {
    public EndEvent(String id, String name) {
        super(id, name);
    }
}
