package io.castellan.flow.engine;

import io.castellan.bpmn.exec.BpmnExecutionException;
import io.castellan.bpmn.exec.ProcessInterpreter;
import io.castellan.bpmn.exec.ProcessState;
import io.castellan.bpmn.exec.ServiceTaskHandler;
import io.castellan.bpmn.exec.Signal;
import io.castellan.bpmn.model.Process;
import io.castellan.bpmn.parser.BpmnParser;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The orchestration facade tying persistence ({@link ProcessDefinitionRepository}, {@link
 * ProcessInstanceRepository}, {@link TimerRepository}) to interpretation ({@link
 * ProcessInterpreter}). Every method that touches a running instance re-parses the BPMN XML for
 * the *exact* {@code definition_version} that instance was started on (never "latest") — this is
 * what makes process versioning pin a running instance to its starting version: {@link
 * #deploy} can add v2, v3, ... freely, and every instance already running against v1 keeps
 * resolving v1 on every subsequent {@link #signal}/timer delivery, forever. This mirrors
 * flow-rules' {@code RuleSetRegistry.newSession(name, version)} pinning pattern exactly, just
 * backed by a table instead of an in-memory map.
 *
 * <p>Re-parsing the XML on every call (rather than caching the parsed {@link Process} graph) is a
 * deliberate simplicity choice: {@link BpmnParser} is a plain, cheap StAX walk over a
 * process-sized document, and re-parsing sidesteps ever needing a cache-invalidation story. A
 * production system evaluating this at very high instance-resume throughput would cache parsed
 * definitions keyed by (process_id, version) — immutable once deployed, so trivially cacheable —
 * but that's a valid future optimization, not attempted here.
 */
public final class ProcessEngine {

    private final ProcessDefinitionRepository definitions;
    private final ProcessInstanceRepository instances;
    private final TimerRepository timers;
    private final ProcessInterpreter interpreter;
    private final Clock clock;

    public ProcessEngine(ProcessDefinitionRepository definitions, ProcessInstanceRepository instances,
                          TimerRepository timers, ServiceTaskHandler serviceTaskHandler, Clock clock) {
        this.definitions = definitions;
        this.instances = instances;
        this.timers = timers;
        this.clock = clock;
        this.interpreter = new ProcessInterpreter(serviceTaskHandler, clock);
    }

    public ProcessDefinitionRecord deployDefinition(String processId, String bpmnXml) {
        Process parsed = new BpmnParser().parse(bpmnXml);
        return definitions.deploy(processId, parsed.name(), bpmnXml);
    }

    /** Starts a new instance against the latest deployed version of {@code processId}. */
    public ProcessInstanceRecord startInstance(String processId, Map<String, Object> variables) {
        ProcessDefinitionRecord definition = definitions.latest(processId)
                .orElseThrow(() -> new IllegalArgumentException("no process definition deployed under id: " + processId));
        return startInstance(definition, variables);
    }

    /** Starts a new instance pinned to a specific definition version (used by tests exercising
     * versioning directly; {@link #startInstance(String, Map)} is the normal entry point). */
    public ProcessInstanceRecord startInstance(String processId, int version, Map<String, Object> variables) {
        ProcessDefinitionRecord definition = definitions.version(processId, version)
                .orElseThrow(() -> new IllegalArgumentException("no such definition version: " + processId + " v" + version));
        return startInstance(definition, variables);
    }

    private ProcessInstanceRecord startInstance(ProcessDefinitionRecord definition, Map<String, Object> variables) {
        Process process = new BpmnParser().parse(definition.bpmnXml());
        ProcessState state = interpreter.start(process, variables);
        Instant now = clock.instant();
        ProcessInstanceRecord record = new ProcessInstanceRecord(newInstanceId(), definition.processId(),
                definition.version(), statusOf(state), state, now, now);
        instances.insert(record);
        timers.replaceForInstance(record.id(), state.tokens());
        return record;
    }

    public ProcessInstanceRecord getInstance(String instanceId) {
        return instances.find(instanceId).orElseThrow(() -> new NoSuchProcessInstanceException(instanceId));
    }

    /** Delivers an external signal (e.g. a completed {@code userTask}) to a specific waiting
     * token, identified by token id (see {@code GET /instances/{id}} for discovering it). */
    public ProcessInstanceRecord signal(String instanceId, String tokenId, Map<String, Object> variables) {
        return resume(instanceId, record -> new Signal.External(tokenId, variables));
    }

    /** Delivers a "timer fired" signal — called by {@link TimerScheduler}, not normally by API
     * callers directly. */
    public ProcessInstanceRecord deliverTimerSignal(String instanceId, String tokenId) {
        return resume(instanceId, record -> new Signal.TimerFired(tokenId));
    }

    public ProcessInstanceRecord compensate(String instanceId, String activityId) {
        ProcessInstanceRecord record = getInstance(instanceId);
        Process process = resolvePinnedProcess(record);
        ProcessState newState = interpreter.compensate(process, record.state(), activityId);
        return persist(record, newState);
    }

    private ProcessInstanceRecord resume(String instanceId, java.util.function.Function<ProcessInstanceRecord, Signal> signalOf) {
        ProcessInstanceRecord record = getInstance(instanceId);
        Process process = resolvePinnedProcess(record);
        ProcessState newState;
        try {
            newState = interpreter.resume(process, record.state(), signalOf.apply(record));
        } catch (BpmnExecutionException e) {
            throw new BpmnExecutionException("instance " + instanceId + ": " + e.getMessage());
        }
        return persist(record, newState);
    }

    private Process resolvePinnedProcess(ProcessInstanceRecord record) {
        ProcessDefinitionRecord definition = definitions.version(record.processId(), record.definitionVersion())
                .orElseThrow(() -> new IllegalStateException(
                        "instance " + record.id() + " is pinned to a definition version that no longer exists: "
                                + record.processId() + " v" + record.definitionVersion()));
        return new BpmnParser().parse(definition.bpmnXml());
    }

    private ProcessInstanceRecord persist(ProcessInstanceRecord old, ProcessState newState) {
        Instant now = clock.instant();
        InstanceStatus status = statusOf(newState);
        instances.updateState(old.id(), newState, status, now);
        timers.replaceForInstance(old.id(), newState.tokens());
        return new ProcessInstanceRecord(old.id(), old.processId(), old.definitionVersion(), status, newState, old.createdAt(), now);
    }

    private static InstanceStatus statusOf(ProcessState state) {
        return state.isTerminated() ? InstanceStatus.COMPLETED : InstanceStatus.RUNNING;
    }

    private static String newInstanceId() {
        return UUID.randomUUID().toString();
    }
}
