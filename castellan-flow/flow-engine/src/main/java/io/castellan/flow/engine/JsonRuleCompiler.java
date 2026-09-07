package io.castellan.flow.engine;

import io.castellan.bpmn.expr.ExpressionEvaluator;
import io.castellan.rules.Rule;
import io.castellan.rules.RuleEngine;
import io.castellan.rules.RuleSet;
import io.castellan.rules.RuleSetRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles {@link JsonRuleDefinition}s into a fresh flow-rules {@link RuleEngine} session, one
 * invocation at a time. This is the actual BPMN-to-Rete integration point: every rule becomes a
 * genuine single-pattern {@link Rule} (built through flow-rules' own typed builder — {@code
 * Rule.name(...).pattern(Map.class, ...).then(...)} — nothing here bypasses that API or reaches
 * into flow-rules' internals), matching against a {@code Map<String,Object>} fact built from
 * process variables.
 *
 * <p>Recompiling on every call (rather than once at deploy time) is deliberate, not laziness: a
 * compiled {@link Rule}'s {@code then} action is a Java closure, and the only way to get each
 * invocation's matched outcomes back out to the BPMN caller — {@link RuleEngine} exposes no
 * "enumerate facts of type X" query — is for that closure to write into a collection the caller
 * already holds. Capturing that collection at compile time means compiling fresh per call, which
 * costs little (rules here are plain objects, not parsed grammar — see {@link RuleEngine}'s own
 * class javadoc making the identical tradeoff) and, as a side benefit, guarantees no state leaks
 * between concurrent process instances invoking the same rule set.
 *
 * <p>{@link RuleSetRegistry} is used here purely as the (public) way to obtain a {@link RuleSet}
 * instance — its constructor is package-private to {@code io.castellan.rules} — not for its
 * versioning; a throwaway registry is created per call and discarded. flow-engine's own {@link
 * RuleSetJdbcRegistry} is the versioning authority for JSON rule sets.
 */
final class JsonRuleCompiler {

    private JsonRuleCompiler() {
    }

    static RuleEngine compileSession(List<JsonRuleDefinition> definitions, List<Map<String, Object>> outcomeSink) {
        List<Rule> rules = new ArrayList<>();
        for (JsonRuleDefinition def : definitions) {
            rules.add(compileOne(def, outcomeSink));
        }
        RuleSetRegistry oneShotCompiler = new RuleSetRegistry();
        RuleSet ruleSet = oneShotCompiler.deploy("json-rule-session", rules);
        return new RuleEngine(ruleSet);
    }

    @SuppressWarnings("unchecked")
    private static Rule compileOne(JsonRuleDefinition def, List<Map<String, Object>> outcomeSink) {
        String condition = def.condition();
        return Rule.name(def.name())
                .salience(def.salience())
                .pattern(Map.class,
                        fact -> matches(condition, (Map<String, Object>) fact),
                        "json-rule: " + def.name())
                .then((Map fact, io.castellan.rules.RuleContext ctx) -> {
                    Map<String, Object> typedFact = fact;
                    Map<String, Object> outcome = new LinkedHashMap<>();
                    for (Map.Entry<String, String> entry : def.outcomeExpressions().entrySet()) {
                        outcome.put(entry.getKey(), ExpressionEvaluator.evaluate(entry.getValue(), typedFact));
                    }
                    outcomeSink.add(outcome);
                });
    }

    private static boolean matches(String condition, Map<String, Object> fact) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        return ExpressionEvaluator.evaluateBoolean(condition, fact);
    }
}
