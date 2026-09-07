package io.castellan.rules;

/** The cross-fact join test for a rule's second pattern: does fact {@code b} belong with the
 * already-matched fact {@code a}? (e.g. "same account id"). Compiled into the
 * {@link io.castellan.rules.network.JoinNode} that joins pattern 0 with pattern 1. */
@FunctionalInterface
public interface Join2<A, B> {
    boolean test(A a, B b);
}
