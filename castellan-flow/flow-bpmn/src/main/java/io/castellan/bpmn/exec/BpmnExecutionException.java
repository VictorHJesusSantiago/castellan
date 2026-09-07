package io.castellan.bpmn.exec;

/** Thrown for a process/graph state that can't be interpreted: an exclusive gateway with no
 * matching condition and no default flow, a signal aimed at a token that isn't waiting for it,
 * a join reached with no matching fork wave, and similar modeling or misuse errors. */
public final class BpmnExecutionException extends RuntimeException {
    public BpmnExecutionException(String message) {
        super(message);
    }
}
