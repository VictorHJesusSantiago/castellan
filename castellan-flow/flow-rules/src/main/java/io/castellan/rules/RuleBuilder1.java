package io.castellan.rules;

import io.castellan.rules.network.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** Builder state after one pattern has been declared: {@code Rule.name(...).pattern(A.class, ...)}. */
public final class RuleBuilder1<A> {

    private final String name;
    private final int salience;
    private final List<PatternSpec> patterns;

    RuleBuilder1(String name, int salience, List<PatternSpec> patterns) {
        this.name = name;
        this.salience = salience;
        this.patterns = patterns;
    }

    /** Adds a second pattern joined to the first via {@code joinTest}, e.g. "same account id".
     * The joined pattern itself is not independently alpha-filtered — see {@link Rule}'s javadoc
     * on that scope cut; encode any per-fact filter on {@code B} inside {@code joinTest} itself. */
    public <B> RuleBuilder2<A, B> join(Class<B> type, Join2<A, B> joinTest, String description) {
        PatternSpec spec = new PatternSpec(type, fact -> true, description);
        List<PatternSpec> newPatterns = append(patterns, spec);
        BiPredicate<Tuple, Object> compiled = (tuple, fact) -> {
            @SuppressWarnings("unchecked") A a = (A) tuple.facts().get(0);
            @SuppressWarnings("unchecked") B b = (B) fact;
            return joinTest.test(a, b);
        };
        return new RuleBuilder2<>(name, salience, newPatterns, List.of(compiled));
    }

    public Rule then(Action1<A> action) {
        RuleAction compiled = (facts, ctx) -> {
            @SuppressWarnings("unchecked") A a = (A) facts.get(0);
            action.fire(a, ctx);
        };
        return new Rule(name, salience, patterns, List.of(), compiled);
    }

    static <T> List<T> append(List<T> base, T extra) {
        List<T> copy = new ArrayList<>(base);
        copy.add(extra);
        return copy;
    }
}
