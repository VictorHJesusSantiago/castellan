package io.castellan.bpmn.exec;

import io.castellan.bpmn.expr.ExpressionEvaluator;
import io.castellan.bpmn.model.Association;
import io.castellan.bpmn.model.BoundaryEvent;
import io.castellan.bpmn.model.EndEvent;
import io.castellan.bpmn.model.ExclusiveGateway;
import io.castellan.bpmn.model.FlowNode;
import io.castellan.bpmn.model.Gateway;
import io.castellan.bpmn.model.GenericTask;
import io.castellan.bpmn.model.InclusiveGateway;
import io.castellan.bpmn.model.IntermediateCatchEvent;
import io.castellan.bpmn.model.ParallelGateway;
import io.castellan.bpmn.model.Process;
import io.castellan.bpmn.model.ScriptTask;
import io.castellan.bpmn.model.SequenceFlow;
import io.castellan.bpmn.model.ServiceTask;
import io.castellan.bpmn.model.StartEvent;
import io.castellan.bpmn.model.Task;
import io.castellan.bpmn.model.UserTask;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Walks a {@link Process} graph directly, moving tokens along {@link SequenceFlow}s — never
 * compiling to Java bytecode or generated source. Every public method takes a {@link
 * ProcessState} and returns a new one, run to a fixpoint where every remaining token is blocked
 * on external input ({@link TokenStatus#WAITING_TIMER}, {@link TokenStatus#WAITING_SIGNAL}, or
 * {@link TokenStatus#PARKED_AT_JOIN}). The interpreter itself is stateless between calls — all
 * state lives in the {@link ProcessState} value passed in and returned, which is exactly what
 * lets flow-engine persist it, throw the interpreter instance away, and resume later (even in a
 * different process) by calling {@link #resume} against a freshly constructed interpreter.
 *
 * <h2>Parallel/inclusive join tracking</h2>
 * A join can't just wait for "any incoming flow to fire N times" — concurrent process instances
 * and (within one instance) unrelated branches could produce confusing false completions if joins
 * were tracked purely by node id. Instead, every fork ({@link ParallelGateway} or {@link
 * InclusiveGateway} with more than one outgoing flow) mints a fresh {@code forkId} and stamps it
 * on every child token it produces; {@link ProcessState#forkExpectedArrivals()} records how many
 * arrivals that wave's join should expect (for parallel: all activated branches, which is always
 * every outgoing flow; for inclusive: only the branches whose condition actually evaluated true —
 * this is what makes inclusive-join semantics correct, since a naive "wait for all incoming
 * flows" would deadlock forever on a branch that was never taken). A join gateway parks each
 * arriving token and fires once arrivals for that {@code forkId} at that node reach the expected
 * count, then drops the {@code forkId} (the wave is closed) and continues with a single token.
 *
 * <p><b>Scope cut:</b> only one fork/join "level" is tracked — a fork nested inside another
 * fork's branch, closing before reaching the outer join, is not supported (the inner join would
 * need to restore the outer {@code forkId} on its continuation token, which would require a
 * fork-id stack this module doesn't implement). Diagrams needing nested concurrency should model
 * it as two independent flat fork/join pairs. Cyclic flows (loops back through a gateway) are
 * also out of scope — the join-arrival counting above assumes each fork produces exactly one wave
 * of children that reaches its join exactly once.
 */
public final class ProcessInterpreter {

    private final ServiceTaskHandler serviceTaskHandler;
    private final Clock clock;

    public ProcessInterpreter() {
        this(ServiceTaskHandler.NO_OP, Clock.systemUTC());
    }

    public ProcessInterpreter(ServiceTaskHandler serviceTaskHandler) {
        this(serviceTaskHandler, Clock.systemUTC());
    }

    public ProcessInterpreter(ServiceTaskHandler serviceTaskHandler, Clock clock) {
        this.serviceTaskHandler = Objects.requireNonNull(serviceTaskHandler);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Starts a fresh process instance: one token at the start event, run to its first blocking
     * point (or straight to completion, for a process with no waiting points). */
    public ProcessState start(Process process, Map<String, Object> initialVariables) {
        Deque<ActiveToken> queue = new ArrayDeque<>();
        queue.add(ActiveToken.fresh(newId("tok"), process.startEvent().id(), null));
        ProcessState seed = new ProcessState(initialVariables, List.of(), Map.of(), List.of(), List.of());
        return run(process, seed, queue);
    }

    /** Delivers a {@link Signal} to the token it targets and runs to the next fixpoint. Throws
     * {@link BpmnExecutionException} if no token in {@code state} matches the signal's token id
     * and expected status. */
    public ProcessState resume(Process process, ProcessState state, Signal signal) {
        List<Token> remaining = new ArrayList<>(state.tokens());
        Token target = remaining.stream()
                .filter(t -> t.id().equals(signal.tokenId()))
                .findFirst()
                .orElseThrow(() -> new BpmnExecutionException("no waiting token with id: " + signal.tokenId()));
        remaining.remove(target);
        if (target.raceGroupId() != null) {
            remaining.removeIf(t -> target.raceGroupId().equals(t.raceGroupId()));
        }

        Deque<ActiveToken> queue = new ArrayDeque<>();
        Map<String, Object> variables = state.variables();

        switch (signal) {
            case Signal.TimerFired fired -> {
                if (target.status() != TokenStatus.WAITING_TIMER) {
                    throw new BpmnExecutionException("token " + target.id() + " is not waiting on a timer");
                }
                FlowNode node = process.node(target.nodeId());
                String from = switch (node) {
                    case BoundaryEvent be -> be.id();
                    case IntermediateCatchEvent ice -> ice.id();
                    default -> throw new BpmnExecutionException("unexpected timer host node: " + node);
                };
                queue.add(ActiveToken.fresh(newId("tok"), singleOutgoing(process, from).targetRef(), target.forkId()));
            }
            case Signal.External external -> {
                if (target.status() != TokenStatus.WAITING_SIGNAL) {
                    throw new BpmnExecutionException("token " + target.id() + " is not waiting on an external signal");
                }
                if (!external.variables().isEmpty()) {
                    Map<String, Object> merged = new LinkedHashMap<>(variables);
                    merged.putAll(external.variables());
                    variables = merged;
                }
                queue.add(ActiveToken.resumed(target.id(), target.nodeId(), target.forkId()));
            }
        }

        ProcessState trimmed = new ProcessState(variables, remaining, state.forkExpectedArrivals(),
                state.completedActivityIds(), state.compensatedActivityIds());
        return run(process, trimmed, queue);
    }

    /**
     * Invokes compensation for an already-completed activity: finds its compensation boundary
     * event, follows the {@link Association} to the handler activity, and runs the handler
     * synchronously (a {@code serviceTask} handler fires through {@link #serviceTaskHandler}; a
     * {@code scriptTask} runs its assignments; anything else is a documented no-op).
     *
     * <p><b>Scope covered:</b> exactly one compensation boundary event per activity, exactly one
     * associated handler, triggered explicitly by this call (there is no "throw compensation
     * end/intermediate event" that cascades automatically across a whole completed sub-process —
     * BPMN's default/cascading compensation semantics are out of scope). The handler runs once;
     * calling this twice for the same activity fires it twice (no idempotency guard beyond what
     * {@link ProcessState#compensatedActivityIds()} lets a caller check before calling).
     */
    public ProcessState compensate(Process process, ProcessState state, String activityId) {
        if (!state.completedActivityIds().contains(activityId)) {
            throw new BpmnExecutionException("cannot compensate an activity that hasn't completed: " + activityId);
        }
        BoundaryEvent compensationEvent = process.boundaryEventsFor(activityId).stream()
                .filter(BoundaryEvent::isCompensation)
                .findFirst()
                .orElseThrow(() -> new BpmnExecutionException("activity has no compensation boundary event: " + activityId));
        Association association = process.associations().stream()
                .filter(a -> a.sourceRef().equals(compensationEvent.id()))
                .findFirst()
                .orElseThrow(() -> new BpmnExecutionException(
                        "compensation boundary event has no associated handler: " + compensationEvent.id()));

        Map<String, Object> variables = new LinkedHashMap<>(state.variables());
        FlowNode handlerNode = process.node(association.targetRef());
        if (handlerNode instanceof ServiceTask svc) {
            variables.putAll(serviceTaskHandler.execute(svc, Map.copyOf(variables)));
        } else if (handlerNode instanceof ScriptTask script) {
            executeScript(script.script(), variables);
        }

        List<String> compensated = new ArrayList<>(state.compensatedActivityIds());
        compensated.add(activityId);
        return new ProcessState(variables, state.tokens(), state.forkExpectedArrivals(),
                state.completedActivityIds(), compensated);
    }


    private ProcessState run(Process process, ProcessState state, Deque<ActiveToken> queue) {
        RunCtx ctx = new RunCtx(state);
        while (!queue.isEmpty()) {
            ActiveToken at = queue.poll();
            if (at.resumedCompletion()) {
                ctx.completedActivityIds.add(at.nodeId());
                advanceFrom(process, at.nodeId(), at.forkId(), ctx, queue);
                continue;
            }
            dispatch(process, process.node(at.nodeId()), at, ctx, queue);
        }
        return ctx.toState();
    }

    private void dispatch(Process process, FlowNode node, ActiveToken at, RunCtx ctx, Deque<ActiveToken> queue) {
        switch (node) {
            case StartEvent start -> advanceFrom(process, start.id(), at.forkId(), ctx, queue);
            case EndEvent end -> {  }
            case Task task -> enterTask(process, task, at, ctx, queue);
            case Gateway gateway -> dispatchGateway(process, gateway, at, ctx, queue);
            case BoundaryEvent be -> throw new BpmnExecutionException(
                    "boundary event reached as a direct token arrival (should only be entered via timer resume): " + be.id());
            case IntermediateCatchEvent ice -> {
                Instant due = ice.timer().resolve(clock.instant());
                ctx.parked.add(new Token(at.tokenId(), ice.id(), TokenStatus.WAITING_TIMER, due, null, at.forkId(), null));
            }
        }
    }

    private void enterTask(Process process, Task task, ActiveToken at, RunCtx ctx, Deque<ActiveToken> queue) {
        switch (task) {
            case UserTask userTask -> enterUserTask(process, userTask, at, ctx);
            case ServiceTask svc -> {
                Map<String, Object> produced = serviceTaskHandler.execute(svc, Map.copyOf(ctx.variables));
                ctx.variables.putAll(produced);
                ctx.completedActivityIds.add(svc.id());
                advanceFrom(process, svc.id(), at.forkId(), ctx, queue);
            }
            case ScriptTask script -> {
                executeScript(script.script(), ctx.variables);
                ctx.completedActivityIds.add(script.id());
                advanceFrom(process, script.id(), at.forkId(), ctx, queue);
            }
            case GenericTask generic -> {
                ctx.completedActivityIds.add(generic.id());
                advanceFrom(process, generic.id(), at.forkId(), ctx, queue);
            }
        }
    }

    /** A user task is the only task flavor that genuinely waits, so it's the only one an
     * interrupting boundary timer can meaningfully race — every other task type completes
     * synchronously the instant its token arrives (see {@link Task}'s javadoc). */
    private void enterUserTask(Process process, UserTask userTask, ActiveToken at, RunCtx ctx) {
        BoundaryEvent timerBoundary = process.boundaryEventsFor(userTask.id()).stream()
                .filter(be -> be.timer() != null)
                .findFirst()
                .orElse(null);
        if (timerBoundary == null) {
            ctx.parked.add(new Token(at.tokenId(), userTask.id(), TokenStatus.WAITING_SIGNAL,
                    null, userTask.id(), at.forkId(), null));
            return;
        }
        String raceGroupId = newId("race");
        Instant due = timerBoundary.timer().resolve(clock.instant());
        ctx.parked.add(new Token(at.tokenId(), userTask.id(), TokenStatus.WAITING_SIGNAL,
                null, userTask.id(), at.forkId(), raceGroupId));
        ctx.parked.add(new Token(newId("tok"), timerBoundary.id(), TokenStatus.WAITING_TIMER,
                due, null, at.forkId(), raceGroupId));
    }

    private void dispatchGateway(Process process, Gateway gateway, ActiveToken at, RunCtx ctx, Deque<ActiveToken> queue) {
        List<SequenceFlow> outgoing = process.outgoing(gateway.id());
        List<SequenceFlow> incoming = process.incoming(gateway.id());

        switch (gateway) {
            case ExclusiveGateway eg -> routeExclusive(eg, outgoing, at, ctx, queue);
            case ParallelGateway pg -> {
                if (outgoing.size() > 1) {
                    fork(outgoing, at, ctx, queue);
                } else if (incoming.size() > 1) {
                    join(process, pg.id(), incoming.size(), at, ctx, queue);
                } else {
                    advanceFrom(process, pg.id(), at.forkId(), ctx, queue);
                }
            }
            case InclusiveGateway ig -> {
                if (outgoing.size() > 1) {
                    forkInclusive(ig, outgoing, at, ctx, queue);
                } else if (incoming.size() > 1) {
                    Integer expected = ctx.forkExpectedArrivals.get(at.forkId());
                    if (expected == null) {
                        throw new BpmnExecutionException(
                                "inclusive join " + ig.id() + " reached by a token with no matching open fork wave (forkId="
                                        + at.forkId() + ") — nested/non-flat fork structures are not supported");
                    }
                    join(process, ig.id(), expected, at, ctx, queue);
                } else {
                    advanceFrom(process, ig.id(), at.forkId(), ctx, queue);
                }
            }
        }
    }

    private void routeExclusive(ExclusiveGateway eg, List<SequenceFlow> outgoing, ActiveToken at, RunCtx ctx, Deque<ActiveToken> queue) {
        SequenceFlow chosen = null;
        for (SequenceFlow flow : outgoing) {
            if (flow.id().equals(eg.defaultFlowId())) {
                continue;
            }
            if (!flow.hasCondition() || ExpressionEvaluator.evaluateBoolean(flow.conditionExpression(), ctx.variables)) {
                chosen = flow;
                break;
            }
        }
        if (chosen == null && eg.defaultFlowId() != null) {
            chosen = outgoing.stream().filter(f -> f.id().equals(eg.defaultFlowId())).findFirst().orElse(null);
        }
        if (chosen == null) {
            throw new BpmnExecutionException("exclusive gateway " + eg.id() + ": no condition matched and no default flow declared");
        }
        queue.add(ActiveToken.fresh(newId("tok"), chosen.targetRef(), at.forkId()));
    }

    private void fork(List<SequenceFlow> outgoing, ActiveToken at, RunCtx ctx, Deque<ActiveToken> queue) {
        String forkId = newId("fork");
        ctx.forkExpectedArrivals.put(forkId, outgoing.size());
        for (SequenceFlow flow : outgoing) {
            queue.add(ActiveToken.fresh(newId("tok"), flow.targetRef(), forkId));
        }
    }

    private void forkInclusive(InclusiveGateway ig, List<SequenceFlow> outgoing, ActiveToken at, RunCtx ctx, Deque<ActiveToken> queue) {
        List<SequenceFlow> activated = new ArrayList<>();
        for (SequenceFlow flow : outgoing) {
            if (flow.id().equals(ig.defaultFlowId())) {
                continue;
            }
            if (!flow.hasCondition() || ExpressionEvaluator.evaluateBoolean(flow.conditionExpression(), ctx.variables)) {
                activated.add(flow);
            }
        }
        if (activated.isEmpty()) {
            SequenceFlow fallback = outgoing.stream().filter(f -> f.id().equals(ig.defaultFlowId())).findFirst()
                    .orElseThrow(() -> new BpmnExecutionException(
                            "inclusive gateway " + ig.id() + ": no condition matched and no default flow declared"));
            activated.add(fallback);
        }
        String forkId = newId("fork");
        ctx.forkExpectedArrivals.put(forkId, activated.size());
        for (SequenceFlow flow : activated) {
            queue.add(ActiveToken.fresh(newId("tok"), flow.targetRef(), forkId));
        }
    }

    /** Parks {@code at}'s token at the join node, then checks whether every branch of its fork
     * wave has now arrived (see the class javadoc for how "every branch" is determined for
     * parallel vs. inclusive joins). If not, the token stays parked — that's the whole mechanism
     * that lets a join "wait": arriving branches simply don't produce a continuing token until
     * the last one shows up. */
    private void join(Process process, String joinNodeId, int required, ActiveToken at, RunCtx ctx, Deque<ActiveToken> queue) {
        if (at.forkId() == null) {
            throw new BpmnExecutionException(
                    "join gateway " + joinNodeId + " reached by a token with no fork id — nested/non-flat join structures are not supported");
        }
        ctx.parked.add(new Token(at.tokenId(), joinNodeId, TokenStatus.PARKED_AT_JOIN, null, null, at.forkId(), null));
        long arrived = ctx.parked.stream()
                .filter(t -> t.status() == TokenStatus.PARKED_AT_JOIN
                        && t.nodeId().equals(joinNodeId)
                        && at.forkId().equals(t.forkId()))
                .count();
        if (arrived < required) {
            return;
        }
        ctx.parked.removeIf(t -> t.status() == TokenStatus.PARKED_AT_JOIN
                && t.nodeId().equals(joinNodeId)
                && at.forkId().equals(t.forkId()));
        ctx.forkExpectedArrivals.remove(at.forkId());
        advanceFrom(process, joinNodeId, null, ctx, queue);
    }

    private void advanceFrom(Process process, String nodeId, String forkId, RunCtx ctx, Deque<ActiveToken> queue) {
        SequenceFlow only = singleOutgoing(process, nodeId);
        queue.add(ActiveToken.fresh(newId("tok"), only.targetRef(), forkId));
    }

    private static SequenceFlow singleOutgoing(Process process, String nodeId) {
        List<SequenceFlow> outgoing = process.outgoing(nodeId);
        if (outgoing.size() != 1) {
            throw new BpmnExecutionException("node " + nodeId + " expected exactly one outgoing flow but has " + outgoing.size());
        }
        return outgoing.get(0);
    }

    /** Executes a {@code scriptTask} body: semicolon-separated {@code ident = expression;}
     * assignments (see {@link ScriptTask}'s javadoc). */
    private static void executeScript(String script, Map<String, Object> variables) {
        for (String statement : script.split(";")) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = indexOfTopLevelAssignment(trimmed);
            if (eq < 0) {
                throw new BpmnExecutionException("script statement is not an assignment: " + trimmed);
            }
            String ident = trimmed.substring(0, eq).trim();
            String expr = trimmed.substring(eq + 1).trim();
            variables.put(ident, ExpressionEvaluator.evaluate(expr, variables));
        }
    }

    private static int indexOfTopLevelAssignment(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '=') {
                continue;
            }
            char prev = i > 0 ? s.charAt(i - 1) : 0;
            char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
            if (prev == '=' || prev == '!' || prev == '<' || prev == '>' || next == '=') {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }


    /** Mutable working copy of a {@link ProcessState}, alive only for the duration of one {@link
     * #run} call; frozen back into an immutable {@link ProcessState} when the token queue drains. */
    private static final class RunCtx {
        final Map<String, Object> variables;
        final List<Token> parked;
        final Map<String, Integer> forkExpectedArrivals;
        final List<String> completedActivityIds;
        final List<String> compensatedActivityIds;

        RunCtx(ProcessState state) {
            this.variables = new LinkedHashMap<>(state.variables());
            this.parked = new ArrayList<>(state.tokens());
            this.forkExpectedArrivals = new LinkedHashMap<>(state.forkExpectedArrivals());
            this.completedActivityIds = new ArrayList<>(state.completedActivityIds());
            this.compensatedActivityIds = new ArrayList<>(state.compensatedActivityIds());
        }

        ProcessState toState() {
            return new ProcessState(variables, parked, forkExpectedArrivals, completedActivityIds, compensatedActivityIds);
        }
    }

    /** A token currently in motion within a single {@link #run} call — never persisted (contrast
     * with {@link Token}, which only represents blocked tokens). */
    private record ActiveToken(String tokenId, String nodeId, String forkId, boolean resumedCompletion) {
        static ActiveToken fresh(String tokenId, String nodeId, String forkId) {
            return new ActiveToken(tokenId, nodeId, forkId, false);
        }

        static ActiveToken resumed(String tokenId, String nodeId, String forkId) {
            return new ActiveToken(tokenId, nodeId, forkId, true);
        }
    }
}
