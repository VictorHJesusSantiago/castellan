package io.castellan.bpmn.model;

/**
 * A standalone (not boundary-attached) intermediate catch event on the main flow. This module
 * only models the timer flavor: a token arriving here parks (status {@code WAITING_TIMER}) until
 * its due time, then continues down the single outgoing flow — no racing activity, unlike a
 * boundary timer.
 */
public final class IntermediateCatchEvent extends FlowNode {

    private final TimerDefinition timer;

    public IntermediateCatchEvent(String id, String name, TimerDefinition timer) {
        super(id, name);
        this.timer = timer;
    }

    public TimerDefinition timer() {
        return timer;
    }
}
