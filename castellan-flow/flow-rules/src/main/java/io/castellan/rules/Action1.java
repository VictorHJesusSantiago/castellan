package io.castellan.rules;

/** Consequence of a single-pattern rule. */
@FunctionalInterface
public interface Action1<A> {
    void fire(A a, RuleContext ctx);
}
