package io.castellan.flow.engine;

/** A process instance's lifecycle status, derived purely from {@link
 * io.castellan.bpmn.exec.ProcessState#isTerminated()} at the moment it was last persisted. */
public enum InstanceStatus {
    RUNNING,
    COMPLETED
}
