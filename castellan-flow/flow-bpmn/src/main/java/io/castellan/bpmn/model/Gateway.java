package io.castellan.bpmn.model;

/** Base for the three gateway types. Fork/join behavior lives in the interpreter (it depends on
 * the graph shape — how many incoming flows a gateway has — not on the gateway alone); this class
 * hierarchy only carries what's structurally different per type: exclusive and inclusive gateways
 * may declare a default outgoing flow, parallel gateways never evaluate conditions at all. */
public sealed abstract class Gateway extends FlowNode permits ExclusiveGateway, ParallelGateway, InclusiveGateway {
    protected Gateway(String id, String name) {
        super(id, name);
    }
}
