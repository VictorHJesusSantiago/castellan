package io.castellan.flow.engine;

import io.castellan.bpmn.exec.ServiceTaskHandler;
import io.castellan.bpmn.model.ServiceTask;
import io.castellan.rules.RuleEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The BPMN-to-rules integration point: a {@code serviceTask} naming a rule set (via {@link
 * ServiceTask#ruleSetName()}) has that rule set's *latest* deployed version resolved, fires it
 * against the process instance's current variables (inserted as one {@code Map<String,Object>}
 * fact), and returns every fired rule's outcome merged into a single variable map — which {@link
 * io.castellan.bpmn.exec.ProcessInterpreter} then merges into process variables, visible to
 * whatever gateway condition runs next.
 *
 * <p>Deliberately resolves "latest" rather than pinning to whatever rule set version was current
 * when the process definition was deployed: unlike a process definition (whose whole graph shape
 * a running instance depends on, so an in-flight instance must not have its execution semantics
 * change under it), a rule set invocation is a single synchronous call with no persisted
 * dependency on which version answered it — using the latest deployed rules for every evaluation
 * is normal business behavior (e.g. "fraud rules got stricter" should apply to every check from
 * that point on, not just new process instances). A caller wanting the pinned-version behavior
 * instead can resolve a specific version explicitly through {@link RuleSetJdbcRegistry#version}
 * and is not forced through this handler to do so.
 */
public final class RuleServiceTaskHandler implements ServiceTaskHandler {

    private final RuleSetJdbcRegistry ruleSets;

    public RuleServiceTaskHandler(RuleSetJdbcRegistry ruleSets) {
        this.ruleSets = ruleSets;
    }

    @Override
    public Map<String, Object> execute(ServiceTask task, Map<String, Object> variables) {
        String ruleSetName = task.ruleSetName();
        if (ruleSetName == null) {
            return Map.of();
        }
        RuleSetDefinitionRecord definition = ruleSets.latest(ruleSetName)
                .orElseThrow(() -> new IllegalStateException(
                        "service task " + task.id() + " references undeployed rule set: " + ruleSetName));

        List<Map<String, Object>> outcomes = new ArrayList<>();
        RuleEngine session = JsonRuleCompiler.compileSession(definition.rules(), outcomes);
        session.insert(new LinkedHashMap<>(variables));
        session.fireAllRules();

        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map<String, Object> outcome : outcomes) {
            merged.putAll(outcome);
        }
        return merged;
    }
}
