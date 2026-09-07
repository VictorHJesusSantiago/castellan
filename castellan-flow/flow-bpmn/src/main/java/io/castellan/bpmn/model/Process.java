package io.castellan.bpmn.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed BPMN process: nodes, sequence flows, and compensation associations, plus the
 * incoming/outgoing indices the interpreter needs on every step (computed once here rather than
 * scanned from the flat flow list on every token move).
 */
public final class Process {

    private final String id;
    private final String name;
    private final Map<String, FlowNode> nodesById;
    private final List<SequenceFlow> flows;
    private final List<Association> associations;
    private final Map<String, List<SequenceFlow>> outgoingByNode;
    private final Map<String, List<SequenceFlow>> incomingByNode;

    public Process(String id, String name, List<FlowNode> nodes, List<SequenceFlow> flows,
                    List<Association> associations) {
        this.id = id;
        this.name = name;
        this.nodesById = new LinkedHashMap<>();
        for (FlowNode node : nodes) {
            if (nodesById.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("duplicate flow node id: " + node.id());
            }
        }
        this.flows = List.copyOf(flows);
        this.associations = List.copyOf(associations);

        this.outgoingByNode = new LinkedHashMap<>();
        this.incomingByNode = new LinkedHashMap<>();
        for (SequenceFlow flow : this.flows) {
            outgoingByNode.computeIfAbsent(flow.sourceRef(), k -> new ArrayList<>()).add(flow);
            incomingByNode.computeIfAbsent(flow.targetRef(), k -> new ArrayList<>()).add(flow);
        }
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public FlowNode node(String nodeId) {
        FlowNode node = nodesById.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("no such flow node: " + nodeId);
        }
        return node;
    }

    public List<FlowNode> nodes() {
        return List.copyOf(nodesById.values());
    }

    public List<SequenceFlow> outgoing(String nodeId) {
        return outgoingByNode.getOrDefault(nodeId, List.of());
    }

    public List<SequenceFlow> incoming(String nodeId) {
        return incomingByNode.getOrDefault(nodeId, List.of());
    }

    public List<Association> associations() {
        return associations;
    }

    /** The single {@link StartEvent} in this process. BPMN allows multiple start events (one per
     * trigger type); this module supports exactly one, since flow-engine always starts an
     * instance the same way (an API call), so multiple start triggers have no meaning here. */
    public StartEvent startEvent() {
        for (FlowNode node : nodesById.values()) {
            if (node instanceof StartEvent start) {
                return start;
            }
        }
        throw new IllegalStateException("process " + id + " has no start event");
    }

    /** All boundary events attached to the given activity id. */
    public List<BoundaryEvent> boundaryEventsFor(String activityId) {
        List<BoundaryEvent> result = new ArrayList<>();
        for (FlowNode node : nodesById.values()) {
            if (node instanceof BoundaryEvent be && be.attachedToActivityId().equals(activityId)) {
                result.add(be);
            }
        }
        return result;
    }
}
