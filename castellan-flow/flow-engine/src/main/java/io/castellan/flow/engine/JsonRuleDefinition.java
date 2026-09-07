package io.castellan.flow.engine;

import java.util.Map;

/**
 * A rule set deployed as data rather than Java code — the shape a rule set takes when it arrives
 * over {@code POST /rule-sets} as JSON. Each rule is a single pattern over a process-variable-derived
 * fact (a plain {@code Map<String,Object>}): {@code condition} is evaluated with {@link
 * io.castellan.bpmn.expr.ExpressionEvaluator} against that map, and if it's true, every entry in
 * {@code outcomeExpressions} is itself evaluated against the same map and merged into the rule's
 * output (which {@link RuleServiceTaskHandler} feeds back into process variables).
 *
 * <p><b>Scope cut, relative to what flow-rules can actually express:</b> flow-rules' typed builder
 * supports up to three joined patterns with arbitrary Java join predicates; a JSON-declared rule
 * set only ever gets a single pattern (no joins) because a join test is a {@code BiPredicate<Tuple,
 * Object>} — genuinely arbitrary Java code — which has no meaningful textual/JSON form here. Rule
 * sets needing joins must be built directly against flow-rules' Java API and deployed by
 * constructing a {@code RuleSet} in code; this REST path is for the common single-condition case.
 *
 * @param condition           an {@link io.castellan.bpmn.expr.ExpressionEvaluator} boolean
 *                             expression; {@code null} or blank means "always matches"
 * @param outcomeExpressions   variable name -> expression, evaluated against the matched fact
 */
public record JsonRuleDefinition(String name, int salience, String condition, Map<String, String> outcomeExpressions) {

    public JsonRuleDefinition {
        outcomeExpressions = outcomeExpressions == null ? Map.of() : Map.copyOf(outcomeExpressions);
    }
}
