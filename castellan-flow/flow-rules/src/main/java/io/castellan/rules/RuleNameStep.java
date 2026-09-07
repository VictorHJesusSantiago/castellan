package io.castellan.rules;

import java.util.List;
import java.util.function.Predicate;

/** First step of the fluent rule builder: name (required) and salience (optional, default 0),
 * then the rule's first pattern. */
public final class RuleNameStep {

    private final String name;
    private int salience = 0;

    RuleNameStep(String name) {
        this.name = name;
    }

    /** Higher salience fires first among simultaneously matched rules — see
     * {@link Agenda} for the exact conflict-resolution ordering. */
    public RuleNameStep salience(int salience) {
        this.salience = salience;
        return this;
    }

    public <A> RuleBuilder1<A> pattern(Class<A> type, Predicate<A> test, String description) {
        PatternSpec spec = new PatternSpec(type, castTest(test), description);
        return new RuleBuilder1<>(name, salience, List.of(spec));
    }

    @SuppressWarnings("unchecked")
    private static Predicate<Object> castTest(Predicate<?> test) {
        return (Predicate<Object>) test;
    }
}
