package io.castellan.bpmn.exec;

/**
 * The status a persisted {@link Token} can be in. Every token that survives a call into {@link
 * ProcessInterpreter} is in exactly one of these three states — the interpreter always runs a
 * process to a fixpoint where no token can move further without external input, so there is no
 * "running"/"active" status to persist: activity is instantaneous and never observed at rest.
 */
public enum TokenStatus {
    /** Parked at a boundary or intermediate timer event, waiting for {@code timerDueAt}. */
    WAITING_TIMER,
    /** Parked at a {@code userTask}, waiting for an external "task completed" signal. */
    WAITING_SIGNAL,
    /** Parked at a parallel/inclusive join gateway, waiting for sibling branches to arrive. */
    PARKED_AT_JOIN
}
