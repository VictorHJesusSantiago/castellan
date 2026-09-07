package io.castellan.bpmn.exec;

import io.castellan.bpmn.model.ServiceTask;

import java.util.Map;

/**
 * Pluggable execution for {@code serviceTask}s, invoked synchronously the instant a token arrives.
 * flow-bpmn has no dependency on flow-rules; flow-engine supplies an implementation that reads
 * {@link ServiceTask#ruleSetName()} and fires the named rule set, feeding the outcome back as the
 * returned variable map (merged into process variables, visible to the next gateway condition).
 */
@FunctionalInterface
public interface ServiceTaskHandler {

    /** Returns variables to merge into the process instance's variables. */
    Map<String, Object> execute(ServiceTask task, Map<String, Object> variables);

    /** The default used when nothing else is wired up: a service task with no handler-recognized
     * work simply completes without side effects (see {@link ServiceTask}'s javadoc on scope). */
    ServiceTaskHandler NO_OP = (task, variables) -> Map.of();
}
