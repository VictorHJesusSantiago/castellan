package io.castellan.rules;

import io.castellan.rules.network.Tuple;

import java.util.List;
import java.util.function.BiPredicate;

/** Builder state after three patterns (two joins) have been declared — the terminal arity of the
 * typed builder; see {@link Rule}'s javadoc for why. */
public final class RuleBuilder3<A, B, C> {

    private final String name;
    private final int salience;
    private final List<PatternSpec> patterns;
    private final List<BiPredicate<Tuple, Object>> joinTests;

    RuleBuilder3(String name, int salience, List<PatternSpec> patterns,
                 List<BiPredicate<Tuple, Object>> joinTests) {
        this.name = name;
        this.salience = salience;
        this.patterns = patterns;
        this.joinTests = joinTests;
    }

    public Rule then(Action3<A, B, C> action) {
        RuleAction compiled = (facts, ctx) -> {
            @SuppressWarnings("unchecked") A a = (A) facts.get(0);
            @SuppressWarnings("unchecked") B b = (B) facts.get(1);
            @SuppressWarnings("unchecked") C c = (C) facts.get(2);
            action.fire(a, b, c, ctx);
        };
        return new Rule(name, salience, patterns, joinTests, compiled);
    }
}
