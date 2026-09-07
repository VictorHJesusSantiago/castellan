package io.castellan.rules;

/** Consequence of a three-pattern (two join) rule. */
@FunctionalInterface
public interface Action3<A, B, C> {
    void fire(A a, B b, C c, RuleContext ctx);
}
