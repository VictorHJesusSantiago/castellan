package io.castellan.rules;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Conflict resolution: when more than one rule (or the same rule via more than one tuple) matches
 * at once, this decides firing order. Two strategies are implemented, applied in this fixed
 * precedence:
 *
 * <ol>
 *   <li><b>Salience</b> — the {@code Rule.name(...).salience(n)} value, higher fires first.
 *       This is the only user-controllable priority knob.</li>
 *   <li><b>Insertion order</b> — among equal salience, the activation that appeared on the
 *       agenda earliest (i.e. whichever fact/tuple combination satisfied its rule's patterns
 *       first, chronologically) fires first. This is a plain FIFO tiebreak, tracked by a
 *       monotonic {@code sequence} counter stamped on every {@link Activation} at the moment its
 *       tuple completes a match — not by rule declaration order and not by fact recency.</li>
 * </ol>
 *
 * <p>Strategies explicitly <em>not</em> implemented: no "specificity" ordering (rules with more
 * conditions preferred), no "recency"/MEA (most-recently-asserted fact preferred), no rule
 * refraction beyond what {@link RuleEngine} already gives by construction (a tuple only ever
 * activates a rule once — re-activation requires the underlying facts to be retracted and
 * reinserted, which mints new handles and is therefore a genuinely new tuple).
 */
final class Agenda {

    private final PriorityQueue<Activation> queue = new PriorityQueue<>(
            Comparator.comparingInt((Activation a) -> -a.rule().salience())
                    .thenComparingLong(Activation::sequence));

    void add(Activation activation) {
        queue.add(activation);
    }

    void remove(Activation activation) {
        queue.remove(activation);
    }

    Activation poll() {
        return queue.poll();
    }

    boolean isEmpty() {
        return queue.isEmpty();
    }

    int size() {
        return queue.size();
    }
}
