package io.castellan.bpmn.model;

/**
 * Conditional fork/join. As a fork: every outgoing flow whose condition evaluates true is taken
 * (at least one — falling back to {@link #defaultFlowId()} if none do), each producing a child
 * token. As a join: waits only for the branches that were actually activated by the *matching*
 * fork — see {@code ProcessInterpreter}'s fork/join wave-tracking javadoc for the scope cut this
 * implies (a join is matched to its fork by graph position, not full OR-join dataflow analysis).
 */
public final class InclusiveGateway extends Gateway {

    private final String defaultFlowId;

    public InclusiveGateway(String id, String name, String defaultFlowId) {
        super(id, name);
        this.defaultFlowId = defaultFlowId;
    }

    public String defaultFlowId() {
        return defaultFlowId;
    }
}
