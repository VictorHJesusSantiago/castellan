package io.castellan.rules;

/**
 * Passed to a firing rule's action so it can produce effects on the same working memory that
 * matched it — inserting a derived fact (e.g. a {@code Flagged} fact that a later rule's pattern
 * looks for) or retracting one of the facts that triggered this rule (a common idiom to prevent
 * the same rule from re-firing on the same match — since a retracted-then-reinserted fact gets a
 * fresh handle and is a "new" fact to the network).
 */
public final class RuleContext {

    private final RuleEngine engine;
    private final String ruleName;

    RuleContext(RuleEngine engine, String ruleName) {
        this.engine = engine;
        this.ruleName = ruleName;
    }

    public FactHandle insert(Object fact) {
        return engine.insert(fact);
    }

    public void retract(FactHandle handle) {
        engine.retract(handle);
    }

    public String ruleName() {
        return ruleName;
    }
}
