package io.castellan.bpmn.expr;

/** Thrown for a malformed condition/script expression, or one that type-errors at evaluation time
 * (e.g. comparing a number to an undefined variable). */
public final class ExpressionSyntaxException extends RuntimeException {
    public ExpressionSyntaxException(String message) {
        super(message);
    }
}
