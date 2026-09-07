package io.castellan.bpmn.parser;

import io.castellan.bpmn.model.Association;
import io.castellan.bpmn.model.BoundaryEvent;
import io.castellan.bpmn.model.EndEvent;
import io.castellan.bpmn.model.ExclusiveGateway;
import io.castellan.bpmn.model.FlowNode;
import io.castellan.bpmn.model.GenericTask;
import io.castellan.bpmn.model.InclusiveGateway;
import io.castellan.bpmn.model.IntermediateCatchEvent;
import io.castellan.bpmn.model.ParallelGateway;
import io.castellan.bpmn.model.Process;
import io.castellan.bpmn.model.ScriptTask;
import io.castellan.bpmn.model.SequenceFlow;
import io.castellan.bpmn.model.ServiceTask;
import io.castellan.bpmn.model.StartEvent;
import io.castellan.bpmn.model.TimerDefinition;
import io.castellan.bpmn.model.UserTask;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses BPMN 2.0 XML (namespace {@code http://www.omg.org/spec/BPMN/20100524/MODEL}) into the
 * typed {@link Process} graph, using the JDK's own StAX cursor API — no external XML-binding
 * framework, and no DOM tree built and thrown away. Namespace prefixes on BPMN elements
 * themselves are ignored (element/attribute *local names* are matched, not qualified names), so
 * both {@code <bpmn:task>} and unprefixed {@code <task>} parse identically — real BPMN tools vary
 * on this, and there's no behavioral difference worth rejecting one form for.
 *
 * <p><b>Covered elements:</b> {@code process}, {@code startEvent}, {@code endEvent}, {@code task},
 * {@code serviceTask}, {@code userTask}, {@code scriptTask}, {@code exclusiveGateway},
 * {@code parallelGateway}, {@code inclusiveGateway}, {@code boundaryEvent} (with
 * {@code timerEventDefinition} or {@code compensateEventDefinition}), {@code intermediateCatchEvent}
 * (with {@code timerEventDefinition}), {@code sequenceFlow} (with {@code conditionExpression}),
 * {@code association}. Everything else (sub-processes, message/signal events, data objects,
 * lanes, {@code documentation}, {@code extensionElements}) is structurally skipped, not an error —
 * a diagram using them still parses, just without that element having any effect.
 *
 * <p><b>Vendor extension:</b> a {@code serviceTask} names the rule set it invokes via a bare
 * {@code ruleSet} attribute (namespace-agnostic lookup, so both {@code ruleSet="x"} and
 * {@code flow:ruleSet="x"} work) — see {@link ServiceTask}'s javadoc.
 */
public final class BpmnParser {

    private static final String NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    public Process parse(String xml) {
        return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    public Process parse(InputStream input) {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            try {
                return parseDocument(reader);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new BpmnParseException("malformed BPMN XML: " + e.getMessage(), e);
        }
    }

    public Process parse(Reader reader) {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        try {
            XMLStreamReader xmlReader = factory.createXMLStreamReader(reader);
            try {
                return parseDocument(xmlReader);
            } finally {
                xmlReader.close();
            }
        } catch (XMLStreamException e) {
            throw new BpmnParseException("malformed BPMN XML: " + e.getMessage(), e);
        }
    }

    private Process parseDocument(XMLStreamReader r) throws XMLStreamException {
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT && "process".equals(r.getLocalName())) {
                return parseProcess(r);
            }
        }
        throw new BpmnParseException("no <process> element found");
    }

    private Process parseProcess(XMLStreamReader r) throws XMLStreamException {
        String id = require(attr(r, "id"), "process/@id");
        String name = attr(r, "name");

        List<FlowNode> nodes = new ArrayList<>();
        List<SequenceFlow> flows = new ArrayList<>();
        List<Association> associations = new ArrayList<>();

        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.END_ELEMENT && "process".equals(r.getLocalName())) {
                break;
            }
            if (event != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            switch (r.getLocalName()) {
                case "startEvent" -> nodes.add(new StartEvent(require(attr(r, "id"), "id"), attr(r, "name")));
                case "endEvent" -> nodes.add(parseSkippingChildren(r, "endEvent",
                        new EndEvent(require(attr(r, "id"), "id"), attr(r, "name"))));
                case "task", "manualTask" -> nodes.add(parseSkippingChildren(r, r.getLocalName(),
                        new GenericTask(require(attr(r, "id"), "id"), attr(r, "name"))));
                case "userTask" -> nodes.add(parseSkippingChildren(r, "userTask",
                        new UserTask(require(attr(r, "id"), "id"), attr(r, "name"))));
                case "serviceTask" -> nodes.add(parseSkippingChildren(r, "serviceTask",
                        new ServiceTask(require(attr(r, "id"), "id"), attr(r, "name"), attrByLocalName(r, "ruleSet"))));
                case "scriptTask" -> nodes.add(parseScriptTask(r));
                case "exclusiveGateway" -> nodes.add(parseSkippingChildren(r, "exclusiveGateway",
                        new ExclusiveGateway(require(attr(r, "id"), "id"), attr(r, "name"), attr(r, "default"))));
                case "parallelGateway" -> nodes.add(parseSkippingChildren(r, "parallelGateway",
                        new ParallelGateway(require(attr(r, "id"), "id"), attr(r, "name"))));
                case "inclusiveGateway" -> nodes.add(parseSkippingChildren(r, "inclusiveGateway",
                        new InclusiveGateway(require(attr(r, "id"), "id"), attr(r, "name"), attr(r, "default"))));
                case "boundaryEvent" -> nodes.add(parseBoundaryEvent(r));
                case "intermediateCatchEvent" -> nodes.add(parseIntermediateCatchEvent(r));
                case "sequenceFlow" -> flows.add(parseSequenceFlow(r));
                case "association" -> associations.add(new Association(
                        attr(r, "id"), require(attr(r, "sourceRef"), "association/@sourceRef"),
                        require(attr(r, "targetRef"), "association/@targetRef")));
                default -> skipElement(r);
            }
        }
        return new Process(id, name, nodes, flows, associations);
    }

    private <T extends FlowNode> T parseSkippingChildren(XMLStreamReader r, String elementName, T node) throws XMLStreamException {
        skipToEndOf(r, elementName);
        return node;
    }

    private FlowNode parseScriptTask(XMLStreamReader r) throws XMLStreamException {
        String id = require(attr(r, "id"), "id");
        String name = attr(r, "name");
        StringBuilder script = new StringBuilder();
        while (true) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT && "script".equals(r.getLocalName())) {
                script.append(r.getElementText());
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                skipElement(r);
            } else if (event == XMLStreamConstants.END_ELEMENT && "scriptTask".equals(r.getLocalName())) {
                break;
            }
        }
        return new ScriptTask(id, name, script.toString());
    }

    private FlowNode parseBoundaryEvent(XMLStreamReader r) throws XMLStreamException {
        String id = require(attr(r, "id"), "id");
        String name = attr(r, "name");
        String attachedTo = require(attr(r, "attachedToRef"), "boundaryEvent/@attachedToRef");
        TimerDefinition timer = null;
        boolean compensation = false;
        while (true) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "timerEventDefinition" -> timer = parseTimerEventDefinition(r);
                    case "compensateEventDefinition" -> {
                        compensation = true;
                        skipElement(r);
                    }
                    default -> skipElement(r);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "boundaryEvent".equals(r.getLocalName())) {
                break;
            }
        }
        return new BoundaryEvent(id, name, attachedTo, timer, compensation);
    }

    private FlowNode parseIntermediateCatchEvent(XMLStreamReader r) throws XMLStreamException {
        String id = require(attr(r, "id"), "id");
        String name = attr(r, "name");
        TimerDefinition timer = null;
        while (true) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if ("timerEventDefinition".equals(r.getLocalName())) {
                    timer = parseTimerEventDefinition(r);
                } else {
                    skipElement(r);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "intermediateCatchEvent".equals(r.getLocalName())) {
                break;
            }
        }
        if (timer == null) {
            throw new BpmnParseException("intermediateCatchEvent " + id + " has no supported event definition (only timer is implemented)");
        }
        return new IntermediateCatchEvent(id, name, timer);
    }

    private TimerDefinition parseTimerEventDefinition(XMLStreamReader r) throws XMLStreamException {
        Duration duration = null;
        Instant date = null;
        while (true) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "timeDuration" -> duration = parseDuration(r.getElementText().trim());
                    case "timeDate" -> date = parseInstant(r.getElementText().trim());
                    default -> skipElement(r);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "timerEventDefinition".equals(r.getLocalName())) {
                break;
            }
        }
        if (duration == null && date == null) {
            return null;
        }
        return duration != null ? TimerDefinition.ofDuration(duration) : TimerDefinition.ofDate(date);
    }

    private SequenceFlow parseSequenceFlow(XMLStreamReader r) throws XMLStreamException {
        String id = require(attr(r, "id"), "id");
        String source = require(attr(r, "sourceRef"), "sequenceFlow/@sourceRef");
        String target = require(attr(r, "targetRef"), "sequenceFlow/@targetRef");
        String condition = null;
        while (true) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if ("conditionExpression".equals(r.getLocalName())) {
                    condition = r.getElementText().trim();
                } else {
                    skipElement(r);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && "sequenceFlow".equals(r.getLocalName())) {
                break;
            }
        }
        return new SequenceFlow(id, source, target, condition);
    }

    private static Duration parseDuration(String text) {
        try {
            return Duration.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            throw new BpmnParseException("invalid ISO-8601 duration in timeDuration: " + text, e);
        }
    }

    private static Instant parseInstant(String text) {
        try {
            return Instant.parse(text);
        } catch (java.time.format.DateTimeParseException e) {
            throw new BpmnParseException("invalid ISO-8601 instant in timeDate: " + text, e);
        }
    }

    /** Consumes events until (and including) the matching end tag for the element the reader is
     * currently inside of — used for element types whose children carry no information this
     * parser needs, so their subtree is discarded wholesale rather than event-by-event. */
    private static void skipToEndOf(XMLStreamReader r, String elementName) throws XMLStreamException {
        while (true) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                skipElement(r);
            } else if (event == XMLStreamConstants.END_ELEMENT && elementName.equals(r.getLocalName())) {
                return;
            }
        }
    }

    /** Consumes a single element's subtree; must be called with the reader positioned at that
     * element's START_ELEMENT event, and leaves it positioned at the matching END_ELEMENT. */
    private static void skipElement(XMLStreamReader r) throws XMLStreamException {
        int depth = 1;
        while (depth > 0) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
    }

    private static String attr(XMLStreamReader r, String localName) {
        return r.getAttributeValue(null, localName);
    }

    /** Namespace-agnostic attribute lookup, used only for the {@code ruleSet} vendor extension so
     * both a bare and a prefixed form are accepted (see class javadoc). */
    private static String attrByLocalName(XMLStreamReader r, String localName) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (r.getAttributeLocalName(i).equals(localName)) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }

    private static String require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new BpmnParseException("missing required attribute: " + what);
        }
        return value;
    }
}
