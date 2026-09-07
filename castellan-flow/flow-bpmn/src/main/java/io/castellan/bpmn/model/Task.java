package io.castellan.bpmn.model;

/**
 * Base for the four task flavors this module models. {@link GenericTask} (plain {@code <task>})
 * and {@link ScriptTask} run to completion synchronously the moment a token arrives — a task
 * never blocks). {@link UserTask} parks its token until an external signal delivers the "user
 * completed this" event. {@link ServiceTask} runs a pluggable {@link
 * io.castellan.bpmn.exec.ServiceTaskHandler} synchronously; flow-engine wires that handler to a
 * BPMN-to-rules integration, but flow-bpmn itself has no dependency on flow-rules.
 */
public sealed abstract class Task extends FlowNode permits GenericTask, ServiceTask, UserTask, ScriptTask {
    protected Task(String id, String name) {
        super(id, name);
    }
}
