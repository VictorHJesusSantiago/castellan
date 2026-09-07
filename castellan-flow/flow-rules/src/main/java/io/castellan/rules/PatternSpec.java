package io.castellan.rules;

import java.util.function.Predicate;

/** One rule pattern: "a fact of this type, satisfying this single-fact predicate." Compiled 1:1
 * into an {@link io.castellan.rules.network.AlphaNode}. */
record PatternSpec(Class<?> type, Predicate<Object> test, String description) {
}
