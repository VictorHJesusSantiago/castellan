package io.castellan.bpmn.exec;

import java.util.List;
import java.util.Map;

/**
 * The complete externalized state of one process instance: process variables, every currently
 * blocked token, and the bookkeeping needed to resume correctly. Everything here is a plain,
 * immutable, JSON-serializable value — flow-engine round-trips this exact shape through H2
 * without any BPMN-graph-aware translation, which is what makes "pause mid-execution, persist,
 * restart the JVM, resume from exactly that point" possible (see {@link ProcessInterpreter}).
 *
 * @param variables            process-instance-scoped variables, visible to every token
 * @param tokens                every currently blocked token (see {@link Token})
 * @param forkExpectedArrivals  for each still-open parallel/inclusive fork wave (keyed by the
 *                              fork id stamped on its child tokens), how many arrivals its join
 *                              is waiting for — the join can't just count a join node's static
 *                              incoming-flow count for inclusive gateways, since only the branches
 *                              actually activated by that specific fork evaluation are expected;
 *                              see {@link ProcessInterpreter}'s join javadoc
 * @param completedActivityIds  activity ids that have run to completion, in completion order —
 *                              both an audit trail and the eligibility check for {@link
 *                              ProcessInterpreter#compensate}
 * @param compensatedActivityIds activity ids that have already been compensated
 */
public record ProcessState(
        Map<String, Object> variables,
        List<Token> tokens,
        Map<String, Integer> forkExpectedArrivals,
        List<String> completedActivityIds,
        List<String> compensatedActivityIds) {

    public ProcessState {
        variables = Map.copyOf(variables);
        tokens = List.copyOf(tokens);
        forkExpectedArrivals = Map.copyOf(forkExpectedArrivals);
        completedActivityIds = List.copyOf(completedActivityIds);
        compensatedActivityIds = List.copyOf(compensatedActivityIds);
    }

    /** A process instance with no blocked tokens has run to completion — every token that could
     * move did, and none are parked waiting on external input. */
    public boolean isTerminated() {
        return tokens.isEmpty();
    }
}
