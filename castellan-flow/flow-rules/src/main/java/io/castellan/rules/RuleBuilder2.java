package io.castellan.rules;

import io.castellan.rules.network.Tuple;

import java.util.List;
import java.util.function.BiPredicate;

/** Builder state after two patterns (one join) have been declared. */
public final class RuleBuilder2<A, B> {

    private final String name;
    private final int salience;
    private final List<PatternSpec> patterns;
    private final List<BiPredicate<Tuple, Object>> joinTests;

    RuleBuilder2(String name, int salience, List<PatternSpec> patterns,
                 List<BiPredicate<Tuple, Object>> joinTests) {
        this.name = name;
        this.salience = salience;
        this.patterns = patterns;
        this.joinTests = joinTests;
    }

    public <C> RuleBuilder3<A, B, C> join(Class<C> type, Join3<A, B, C> joinTest, String description) {
        PatternSpec spec = new PatternSpec(type, fact -> true, description);
        List<PatternSpec> newPatterns = RuleBuilder1.append(patterns, spec);
        BiPredicate<Tuple, Object> compiled = (tuple, fact) -> {
            @SuppressWarnings("unchecked") A a = (A) tuple.facts().get(0);
            @SuppressWarnings("unchecked") B b = (B) tuple.facts().get(1);
            @SuppressWarnings("unchecked") C c = (C) fact;
            return joinTest.test(a, b, c);
        };
        List<BiPredicate<Tuple, Object>> newJoins = RuleBuilder1.append(joinTests, compiled);
        return new RuleBuilder3<>(name, salience, newPatterns, newJoins);
    }

    public Rule then(Action2<A, B> action) {
        RuleAction compiled = (facts, ctx) -> {
            @SuppressWarnings("unchecked") A a = (A) facts.get(0);
            @SuppressWarnings("unchecked") B b = (B) facts.get(1);
            action.fire(a, b, ctx);
        };
        return new Rule(name, salience, patterns, joinTests, compiled);
    }
}
