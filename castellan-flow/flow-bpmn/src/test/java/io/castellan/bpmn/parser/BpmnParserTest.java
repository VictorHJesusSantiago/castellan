package io.castellan.bpmn.parser;

import io.castellan.bpmn.TestFixtures;
import io.castellan.bpmn.model.BoundaryEvent;
import io.castellan.bpmn.model.ExclusiveGateway;
import io.castellan.bpmn.model.InclusiveGateway;
import io.castellan.bpmn.model.ParallelGateway;
import io.castellan.bpmn.model.Process;
import io.castellan.bpmn.model.ScriptTask;
import io.castellan.bpmn.model.SequenceFlow;
import io.castellan.bpmn.model.ServiceTask;
import io.castellan.bpmn.model.UserTask;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BpmnParserTest {

    @Test
    void parsesSequentialProcessNodesAndFlows() {
        Process process = TestFixtures.load("sequential.bpmn");

        assertThat(process.id()).isEqualTo("sequential-process");
        assertThat(process.nodes()).hasSize(4);
        assertThat(process.node("start")).isNotNull();
        assertThat(process.node("scoreTask")).isInstanceOf(ScriptTask.class);
        assertThat(((ScriptTask) process.node("scoreTask")).script()).contains("score = amount * 2");
        assertThat(process.node("reviewTask")).isInstanceOf(ServiceTask.class);
        assertThat(((ServiceTask) process.node("reviewTask")).ruleSetName()).isEqualTo("review-rules");

        assertThat(process.outgoing("start")).extracting(SequenceFlow::targetRef).containsExactly("scoreTask");
        assertThat(process.incoming("end")).extracting(SequenceFlow::sourceRef).containsExactly("reviewTask");
    }

    @Test
    void parsesExclusiveGatewayWithConditionsAndDefaultFlow() {
        Process process = TestFixtures.load("exclusive-gateway.bpmn");

        ExclusiveGateway gw = (ExclusiveGateway) process.node("gw1");
        assertThat(gw.defaultFlowId()).isEqualTo("toLowRisk");
        assertThat(process.outgoing("gw1")).hasSize(3);
        SequenceFlow highRisk = process.outgoing("gw1").stream()
                .filter(f -> f.id().equals("toHighRisk")).findFirst().orElseThrow();
        assertThat(highRisk.conditionExpression()).isEqualTo("amount > 10000");
    }

    @Test
    void parsesParallelGatewayForkAndJoin() {
        Process process = TestFixtures.load("parallel-fork-join.bpmn");

        assertThat(process.node("fork")).isInstanceOf(ParallelGateway.class);
        assertThat(process.outgoing("fork")).hasSize(2);
        assertThat(process.incoming("join")).hasSize(2);
    }

    @Test
    void parsesInclusiveGatewayWithDefaultFlow() {
        Process process = TestFixtures.load("inclusive-gateway.bpmn");

        InclusiveGateway fork = (InclusiveGateway) process.node("fork");
        assertThat(fork.defaultFlowId()).isEqualTo("toStandardShipping");
        assertThat(process.node("join")).isInstanceOf(InclusiveGateway.class);
    }

    @Test
    void parsesBoundaryTimerEventAttachedToUserTask() {
        Process process = TestFixtures.load("boundary-timer.bpmn");

        assertThat(process.node("approve")).isInstanceOf(UserTask.class);
        BoundaryEvent boundary = (BoundaryEvent) process.node("timeoutBoundary");
        assertThat(boundary.attachedToActivityId()).isEqualTo("approve");
        assertThat(boundary.timer()).isNotNull();
        assertThat(boundary.timer().duration()).isEqualTo(java.time.Duration.ofHours(24));
        assertThat(boundary.isCompensation()).isFalse();
        assertThat(process.boundaryEventsFor("approve")).containsExactly(boundary);
    }

    @Test
    void parsesCompensationBoundaryEventAndAssociation() {
        Process process = TestFixtures.load("compensation.bpmn");

        BoundaryEvent boundary = (BoundaryEvent) process.node("reserveHotelCompensation");
        assertThat(boundary.isCompensation()).isTrue();
        assertThat(boundary.timer()).isNull();
        assertThat(process.associations()).hasSize(1);
        assertThat(process.associations().get(0).sourceRef()).isEqualTo("reserveHotelCompensation");
        assertThat(process.associations().get(0).targetRef()).isEqualTo("cancelHotel");
    }

    @Test
    void rejectsXmlWithNoProcessElement() {
        assertThatThrownBy(() -> new BpmnParser().parse("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"/>"))
                .isInstanceOf(BpmnParseException.class)
                .hasMessageContaining("no <process>");
    }

    @Test
    void rejectsSequenceFlowMissingRequiredAttribute() {
        String xml = """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="p">
                    <startEvent id="s"/>
                    <sequenceFlow id="f1" sourceRef="s"/>
                  </process>
                </definitions>
                """;
        assertThatThrownBy(() -> new BpmnParser().parse(xml))
                .isInstanceOf(BpmnParseException.class)
                .hasMessageContaining("targetRef");
    }
}
