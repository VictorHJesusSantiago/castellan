package io.castellan.flow.engine;

import java.time.Instant;
import java.util.List;

/** A deployed, immutable rule set version — the JSON-rule analogue of {@link
 * ProcessDefinitionRecord}, versioned the same way. */
public record RuleSetDefinitionRecord(String name, int version, List<JsonRuleDefinition> rules, Instant deployedAt) {

    public RuleSetDefinitionRecord {
        rules = List.copyOf(rules);
    }
}
