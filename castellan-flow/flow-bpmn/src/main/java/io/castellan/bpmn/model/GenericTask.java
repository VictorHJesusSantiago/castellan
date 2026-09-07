package io.castellan.bpmn.model;

/** A plain {@code <task>} element: no special behavior, completes the instant a token arrives. */
public final class GenericTask extends Task {
    public GenericTask(String id, String name) {
        super(id, name);
    }
}
