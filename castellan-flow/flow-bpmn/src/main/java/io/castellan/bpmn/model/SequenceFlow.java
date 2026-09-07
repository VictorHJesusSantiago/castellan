package io.castellan.bpmn.model;

/**
 * A directed edge between two {@link FlowNode}s. {@code conditionExpression} (raw, unparsed) is
 * evaluated by {@link io.castellan.bpmn.expr.ExpressionEvaluator} against process variables when
 * the edge leaves an {@link ExclusiveGateway} or {@link InclusiveGateway}; it's ignored on edges
 * leaving any other node type (unconditional flow).
 */
public record SequenceFlow(String id, String sourceRef, String targetRef, String conditionExpression) {

    public boolean hasCondition() {
        return conditionExpression != null && !conditionExpression.isBlank();
    }
}
