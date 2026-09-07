package io.castellan.rules;

/** Consequence of a two-pattern (one join) rule. */
@FunctionalInterface
public interface Action2<A, B> {
    void fire(A a, B b, RuleContext ctx);
}
