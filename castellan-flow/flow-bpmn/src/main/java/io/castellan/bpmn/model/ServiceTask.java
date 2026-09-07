package io.castellan.bpmn.model;

/**
 * A {@code <serviceTask>}. The only "service" this module recognizes natively is a rule set
 * invocation, named via a vendor extension attribute:
 * {@code <serviceTask flow:ruleSet="fraud-check">}. That attribute is surfaced here as {@link
 * #ruleSetName()}; flow-engine's {@code RuleServiceTaskHandler} reads it, resolves the named rule
 * set through its own registry, and fires it. A {@code serviceTask} with no such attribute is a
 * documented scope cut: it runs its {@link io.castellan.bpmn.exec.ServiceTaskHandler} (which may
 * be the engine's default no-op) and completes immediately — there is no general-purpose Java
 * delegate / connector SPI here, only the rule-invocation path asked for.
 */
public final class ServiceTask extends Task {

    private final String ruleSetName;

    public ServiceTask(String id, String name, String ruleSetName) {
        super(id, name);
        this.ruleSetName = ruleSetName;
    }

    /** Name of the rule set to fire, or {@code null} if this service task isn't a rule invocation. */
    public String ruleSetName() {
        return ruleSetName;
    }
}
