package io.castellan.bpmn.exec;

import java.time.Instant;

/**
 * One blocked token, in a form deliberately restricted to plain, serializable data — no reference
 * to the {@code Process} it belongs to, no lambda, no live thread. This is the whole point of
 * "token-based interpretation with externalizable state": flow-engine persists a {@code List<Token>}
 * (as part of {@link ProcessState}) as a JSON blob (or a normalized row set) in H2, and can
 * reconstruct execution days later, in a different JVM, purely from that data plus the
 * (independently persisted, versioned) BPMN process definition — never from a suspended Java
 * call stack.
 *
 * @param id                this token's identity, stable across persistence round-trips
 * @param nodeId             the flow node this token is currently parked at
 * @param status              why it's parked
 * @param timerDueAt          set iff {@code status == WAITING_TIMER}: when this token should fire
 * @param waitingSignalName   set iff {@code status == WAITING_SIGNAL}: the task id the signal
 *                            targets (informational/queryable; resumption is by token id)
 * @param forkId              the fork "wave" this token belongs to, or {@code null} for a token
 *                            not descended from an unresolved parallel/inclusive fork — see
 *                            {@link ProcessInterpreter}'s join-tracking javadoc
 * @param raceGroupId         set iff this token is racing a sibling token for the same
 *                            interrupting boundary event (one {@code WAITING_SIGNAL} token at the
 *                            activity, one {@code WAITING_TIMER} token at the boundary event);
 *                            resolving either one removes the other
 */
public record Token(String id, String nodeId, TokenStatus status, Instant timerDueAt,
                     String waitingSignalName, String forkId, String raceGroupId) {
}
