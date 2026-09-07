package io.castellan.flow.engine;

/** BPMN XML fixtures for flow-engine's own tests — deliberately not shared with flow-bpmn's test
 * resources (a separate module's test-scope files aren't a clean cross-module dependency); these
 * mirror the same shapes flow-bpmn's own interpreter tests already prove correct at the
 * lower level, here exercised through the full persistence/versioning/timer stack instead. */
final class Fixtures {

    private Fixtures() {
    }

    /** A script task followed by a rule-set-invoking service task — used to prove the
     * rule-engine/BPMN integration point end to end. */
    static final String SEQUENTIAL_WITH_RULES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         id="d1" targetNamespace="io.castellan.flow.engine.test">
              <process id="sequential-process" name="Sequential">
                <startEvent id="start" />
                <sequenceFlow id="f1" sourceRef="start" targetRef="scoreTask" />
                <scriptTask id="scoreTask" name="Compute Score">
                  <script>score = amount * 2;</script>
                </scriptTask>
                <sequenceFlow id="f2" sourceRef="scoreTask" targetRef="reviewTask" />
                <serviceTask id="reviewTask" name="Rule Review" ruleSet="review-rules" />
                <sequenceFlow id="f3" sourceRef="reviewTask" targetRef="end" />
                <endEvent id="end" />
              </process>
            </definitions>
            """;

    /** Version 1 of a risk-routing process: anything over 10000 goes to high risk. */
    static final String EXCLUSIVE_V1 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         id="d2" targetNamespace="io.castellan.flow.engine.test">
              <process id="risk-process" name="Risk Routing V1">
                <startEvent id="start" />
                <sequenceFlow id="f1" sourceRef="start" targetRef="gw1" />
                <exclusiveGateway id="gw1" default="toLow" />
                <sequenceFlow id="toHigh" sourceRef="gw1" targetRef="highRisk">
                  <conditionExpression>amount > 10000</conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="toLow" sourceRef="gw1" targetRef="lowRisk" />
                <task id="highRisk" name="Escalate" />
                <task id="lowRisk" name="Auto Approve" />
                <sequenceFlow id="f2" sourceRef="highRisk" targetRef="end" />
                <sequenceFlow id="f3" sourceRef="lowRisk" targetRef="end" />
                <endEvent id="end" />
              </process>
            </definitions>
            """;

    /** Version 2 of the same process id: the threshold moved from 10000 to 500, and a brand new
     * "mediumRisk" branch was inserted — a genuinely different graph shape, not just a tweaked
     * condition, so a test can tell "which version actually executed" from the completed activity
     * ids alone. An instance already running against v1 must never see this shape. */
    static final String EXCLUSIVE_V2 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         id="d2" targetNamespace="io.castellan.flow.engine.test">
              <process id="risk-process" name="Risk Routing V2">
                <startEvent id="start" />
                <sequenceFlow id="f1" sourceRef="start" targetRef="gw1" />
                <exclusiveGateway id="gw1" default="toLow" />
                <sequenceFlow id="toHigh" sourceRef="gw1" targetRef="highRisk">
                  <conditionExpression>amount > 500</conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="toMedium" sourceRef="gw1" targetRef="mediumRisk">
                  <conditionExpression>amount > 100 &amp;&amp; amount &lt;= 500</conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="toLow" sourceRef="gw1" targetRef="lowRisk" />
                <task id="highRisk" name="Escalate" />
                <task id="mediumRisk" name="Manual Review" />
                <task id="lowRisk" name="Auto Approve" />
                <sequenceFlow id="f2" sourceRef="highRisk" targetRef="end" />
                <sequenceFlow id="f4" sourceRef="mediumRisk" targetRef="end" />
                <sequenceFlow id="f3" sourceRef="lowRisk" targetRef="end" />
                <endEvent id="end" />
              </process>
            </definitions>
            """;

    /** A user task with a 24-hour boundary timeout timer — used to prove timer persistence,
     * scheduler delivery, and restart-resumption. */
    static final String APPROVAL_WITH_TIMEOUT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         id="d4" targetNamespace="io.castellan.flow.engine.test">
              <process id="approval-process" name="Approval With Timeout">
                <startEvent id="start" />
                <sequenceFlow id="f1" sourceRef="start" targetRef="approve" />
                <userTask id="approve" name="Manager Approval" />
                <boundaryEvent id="timeoutBoundary" name="Approval Timeout" attachedToRef="approve">
                  <timerEventDefinition>
                    <timeDuration>PT24H</timeDuration>
                  </timerEventDefinition>
                </boundaryEvent>
                <sequenceFlow id="f2" sourceRef="approve" targetRef="approved" />
                <sequenceFlow id="f3" sourceRef="timeoutBoundary" targetRef="timedOut" />
                <task id="approved" name="Approved" />
                <task id="timedOut" name="Timed Out" />
                <sequenceFlow id="f4" sourceRef="approved" targetRef="end" />
                <sequenceFlow id="f5" sourceRef="timedOut" targetRef="end" />
                <endEvent id="end" />
              </process>
            </definitions>
            """;
}
