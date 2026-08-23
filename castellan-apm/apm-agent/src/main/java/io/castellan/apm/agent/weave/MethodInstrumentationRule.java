package io.castellan.apm.agent.weave;

import java.util.Optional;

/**
 * One instrumentation domain's matching policy (JDBC, outbound HTTP, Spring MVC — see the three
 * implementations in the sibling {@code jdbc}/{@code http}/{@code mvc} packages). {@link
 * CastellanClassFileTransformer} asks every registered rule, in order, about every method of every
 * class it probes; the first rule to return a non-empty {@link MethodWeavingPlan} wins for that
 * method, and no other rule is consulted for it.
 */
public interface MethodInstrumentationRule {

    /**
     * Cheap, class-level pre-filter — called once per class, before any per-method work. A rule
     * that returns {@code false} here is never asked about any of that class's methods, which is
     * what keeps {@link CastellanClassFileTransformer}'s probe pass affordable: most loaded classes
     * (every JDK class, every third-party library class that isn't a JDBC driver or a servlet
     * container internal) are rejected in O(1) here without inspecting a single method.
     */
    boolean appliesToClass(ClassContext classContext);

    /**
     * Decides whether {@code method} — one method of a class this rule already accepted via {@link
     * #appliesToClass} — should be woven, and if so, exactly how.
     */
    Optional<MethodWeavingPlan> planFor(ClassContext classContext, MethodProbe method);
}
