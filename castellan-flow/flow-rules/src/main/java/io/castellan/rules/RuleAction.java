package io.castellan.rules;

import java.util.List;

/** The compiled, type-erased form of a rule's consequence: given the matched facts in pattern
 * order and a context for working-memory effects, do something. The typed {@code Action1}/
 * {@code Action2}/{@code Action3} interfaces used by the builder API compile down to this. */
@FunctionalInterface
public interface RuleAction {
    void fire(List<Object> facts, RuleContext ctx);
}
