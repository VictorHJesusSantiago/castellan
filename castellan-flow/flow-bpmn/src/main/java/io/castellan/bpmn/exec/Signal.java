package io.castellan.bpmn.exec;

import java.util.Map;

/** External input that can wake a blocked {@link Token}. flow-engine's timer scheduler produces
 * {@link TimerFired}; its {@code POST /instances/{id}/signal} endpoint produces {@link External}. */
public sealed interface Signal {

    String tokenId();

    /** Wakes a {@code WAITING_TIMER} token — either a boundary timer (interrupts its activity) or
     * a standalone intermediate timer catch event. */
    record TimerFired(String tokenId) implements Signal {
    }

    /** Wakes a {@code WAITING_SIGNAL} token (a parked {@code userTask}), optionally merging
     * variables the external actor supplied (e.g. what a human approver decided). */
    record External(String tokenId, Map<String, Object> variables) implements Signal {
        public External {
            variables = Map.copyOf(variables);
        }
    }
}
