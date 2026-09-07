package io.castellan.rules;

import io.castellan.rules.network.Tuple;

/** One agenda entry: a rule whose full pattern list is currently satisfied by {@code tuple}.
 * {@code sequence} is the global order in which this activation first appeared on the agenda —
 * used as the conflict-resolution tiebreak among equal-salience activations (see {@link Agenda}). */
record Activation(Rule rule, Tuple tuple, long sequence) {
}
