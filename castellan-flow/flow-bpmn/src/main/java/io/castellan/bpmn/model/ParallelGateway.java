package io.castellan.bpmn.model;

/**
 * Unconditional fork/join. As a fork (token arrives, node has &gt;1 outgoing flow): every
 * outgoing flow is taken, producing one child token each, regardless of process variables. As a
 * join (node has &gt;1 incoming flow): waits until a token has arrived via every incoming flow
 * before producing a single continuing token — see {@code ProcessInterpreter}'s join bookkeeping
 * javadoc for exactly how "every incoming flow" is tracked across concurrent branches.
 */
public final class ParallelGateway extends Gateway {
    public ParallelGateway(String id, String name) {
        super(id, name);
    }
}
