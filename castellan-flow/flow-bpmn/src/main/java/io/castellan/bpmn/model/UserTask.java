package io.castellan.bpmn.model;

/**
 * A {@code <userTask>}: parks its token (status {@code WAITING_SIGNAL}, signal name = this task's
 * id) until an external "complete this task" signal arrives, optionally carrying variables to
 * merge (e.g. what a human approver decided). See {@code ProcessInterpreter#resume}.
 */
public final class UserTask extends Task {
    public UserTask(String id, String name) {
        super(id, name);
    }
}
