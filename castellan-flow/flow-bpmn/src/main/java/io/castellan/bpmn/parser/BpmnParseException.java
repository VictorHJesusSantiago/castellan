package io.castellan.bpmn.parser;

/** Thrown for malformed BPMN XML, or a well-formed document that doesn't contain what this parser
 * needs (no {@code <process>} element, no start event, references to undeclared elements). */
public final class BpmnParseException extends RuntimeException {
    public BpmnParseException(String message) {
        super(message);
    }

    public BpmnParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
