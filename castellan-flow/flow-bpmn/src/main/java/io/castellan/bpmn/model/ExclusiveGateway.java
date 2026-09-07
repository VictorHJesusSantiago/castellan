package io.castellan.bpmn.model;

/**
 * Routes a single token down exactly one outgoing flow: the first (in document order) whose
 * condition evaluates true, or the {@link #defaultFlowId()} flow if none do. A missing default
 * with no true condition is a modeling error, reported at interpretation time (not parse time,
 * since it depends on runtime variable values).
 */
public final class ExclusiveGateway extends Gateway {

    private final String defaultFlowId;

    public ExclusiveGateway(String id, String name, String defaultFlowId) {
        super(id, name);
        this.defaultFlowId = defaultFlowId;
    }

    /** Sequence flow id to take when no conditional outgoing flow evaluates true, or
     * {@code null} if this gateway declared no default. */
    public String defaultFlowId() {
        return defaultFlowId;
    }
}
