package io.castellan.rules;

import io.castellan.rules.network.Tuple;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * An immutable, compiled rule: an ordered list of patterns (the first compiled to a root alpha
 * node, each subsequent one compiled to a join against the accumulated tuple) plus the action to
 * run when a tuple satisfies all of them. Built exclusively through the typed fluent builder
 * starting at {@link #name(String)} — mirroring the Rule.when(...).then(...) shape asked for, but
 * split into per-arity builder classes ({@link RuleBuilder1}, {@link RuleBuilder2},
 * {@link RuleBuilder3}) so each stage's lambdas are strongly typed rather than erased to
 * {@code Object[]}.
 *
 * <p><b>Scope cut:</b> the typed builder tops out at three joined patterns. The underlying
 * network model ({@link Tuple}, chained {@link io.castellan.rules.network.JoinNode}s) is
 * arity-agnostic; nothing in the engine itself limits a rule to three patterns. The ergonomic
 * typed builder simply doesn't grow a fourth generic-parameter class, which would be repetitive
 * boilerplate for a case this codebase doesn't need.
 */
public final class Rule {

    private final String name;
    private final int salience;
    private final List<PatternSpec> patterns;
    private final List<BiPredicate<Tuple, Object>> joinTests;
    private final RuleAction action;

    Rule(String name, int salience, List<PatternSpec> patterns,
         List<BiPredicate<Tuple, Object>> joinTests, RuleAction action) {
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("a rule needs at least one pattern");
        }
        if (joinTests.size() != patterns.size() - 1) {
            throw new IllegalStateException("join test count must be patterns.size() - 1");
        }
        this.name = name;
        this.salience = salience;
        this.patterns = List.copyOf(patterns);
        this.joinTests = List.copyOf(joinTests);
        this.action = action;
    }

    public static RuleNameStep name(String name) {
        return new RuleNameStep(name);
    }

    public String name() {
        return name;
    }

    public int salience() {
        return salience;
    }

    List<PatternSpec> patterns() {
        return patterns;
    }

    List<BiPredicate<Tuple, Object>> joinTests() {
        return joinTests;
    }

    RuleAction action() {
        return action;
    }

    @Override
    public String toString() {
        return "Rule[" + name + ", salience=" + salience + ", patterns=" + patterns.size() + "]";
    }
}
