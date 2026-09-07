package io.castellan.flow.engine;

/** Thrown for an unknown process instance id. */
public final class NoSuchProcessInstanceException extends RuntimeException {
    public NoSuchProcessInstanceException(String instanceId) {
        super("no such process instance: " + instanceId);
    }
}
