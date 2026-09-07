package io.castellan.bpmn.model;

/**
 * A boundary event attached to an activity. Two flavors are modeled, discriminated by which of
 * {@link #timer()} / {@link #isCompensation()} is set:
 *
 * <ul>
 *   <li><b>Timer boundary</b> ({@code cancelActivity=true}, the BPMN default, is the only mode
 *       implemented — non-interrupting boundary events are a documented scope cut): while the
 *       attached activity's token is active, a sibling timer token races it. If the timer fires
 *       first, the activity's token is discarded and execution continues down this event's
 *       outgoing flow.</li>
 *   <li><b>Compensation boundary</b>: carries no outgoing sequence flow. Instead it is linked via
 *       a BPMN {@code <association>} to a compensation handler activity (marked
 *       {@code isForCompensation="true"}), invoked only when {@code ProcessInterpreter.compensate}
 *       is called for the attached (already-completed) activity — see that method's javadoc for
 *       exactly what's covered.</li>
 * </ul>
 */
public final class BoundaryEvent extends FlowNode {

    private final String attachedToActivityId;
    private final TimerDefinition timer;
    private final boolean compensation;

    public BoundaryEvent(String id, String name, String attachedToActivityId,
                          TimerDefinition timer, boolean compensation) {
        super(id, name);
        if (timer != null && compensation) {
            throw new IllegalArgumentException("a boundary event models exactly one of timer or compensation");
        }
        this.attachedToActivityId = attachedToActivityId;
        this.timer = timer;
        this.compensation = compensation;
    }

    public String attachedToActivityId() {
        return attachedToActivityId;
    }

    public TimerDefinition timer() {
        return timer;
    }

    public boolean isCompensation() {
        return compensation;
    }
}
