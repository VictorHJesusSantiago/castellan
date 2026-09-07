package io.castellan.flow.api;

import io.castellan.flow.api.web.dto.CompensateRequest;
import io.castellan.flow.api.web.dto.DeployProcessRequest;
import io.castellan.flow.api.web.dto.DeployRuleSetRequest;
import io.castellan.flow.api.web.dto.ErrorResponse;
import io.castellan.flow.api.web.dto.ProcessDefinitionResponse;
import io.castellan.flow.api.web.dto.ProcessInstanceResponse;
import io.castellan.flow.api.web.dto.RuleDefinitionDto;
import io.castellan.flow.api.web.dto.RuleSetResponse;
import io.castellan.flow.api.web.dto.SignalRequest;
import io.castellan.flow.api.web.dto.StartInstanceRequest;
import io.castellan.flow.api.web.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests over real HTTP, against the real Spring context (real embedded H2, real
 * flow-engine repositories, real background {@code TimerSchedulerLifecycle} poller -- see {@code
 * src/test/resources/application.yml}'s 100ms poll interval). No mocks: every process definition
 * this suite deploys is real BPMN XML, parsed and interpreted by the genuine {@code flow-bpmn}
 * engine underneath.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FlowApiIntegrationTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static final String SIMPLE_PROCESS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         id="d1" targetNamespace="io.castellan.flow.api.test">
              <process id="simple-process" name="Simple">
                <startEvent id="start" />
                <sequenceFlow id="f1" sourceRef="start" targetRef="doWork" />
                <task id="doWork" name="Do Work" />
                <sequenceFlow id="f2" sourceRef="doWork" targetRef="end" />
                <endEvent id="end" />
              </process>
            </definitions>
            """;

    private static String exclusiveProcess(int threshold) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             id="d2" targetNamespace="io.castellan.flow.api.test">
                  <process id="risk-process" name="Risk">
                    <startEvent id="start" />
                    <sequenceFlow id="f1" sourceRef="start" targetRef="gw1" />
                    <exclusiveGateway id="gw1" default="toLow" />
                    <sequenceFlow id="toHigh" sourceRef="gw1" targetRef="highRisk">
                      <conditionExpression>amount > %d</conditionExpression>
                    </sequenceFlow>
                    <sequenceFlow id="toLow" sourceRef="gw1" targetRef="lowRisk" />
                    <task id="highRisk" name="Escalate" />
                    <task id="lowRisk" name="Auto Approve" />
                    <sequenceFlow id="f2" sourceRef="highRisk" targetRef="end" />
                    <sequenceFlow id="f3" sourceRef="lowRisk" targetRef="end" />
                    <endEvent id="end" />
                  </process>
                </definitions>
                """.formatted(threshold);
    }

    private static final String APPROVAL_PROCESS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         id="d3" targetNamespace="io.castellan.flow.api.test">
              <process id="approval-process" name="Approval">
                <startEvent id="start" />
                <sequenceFlow id="f1" sourceRef="start" targetRef="approve" />
                <userTask id="approve" name="Manager Approval" />
                <boundaryEvent id="timeoutBoundary" attachedToRef="approve">
                  <timerEventDefinition>
                    <timeDuration>PT2S</timeDuration>
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

    private static final String BOOKING_WITH_COMPENSATION = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         id="d4" targetNamespace="io.castellan.flow.api.test">
              <process id="booking-process" name="Booking">
                <startEvent id="start" />
                <sequenceFlow id="f1" sourceRef="start" targetRef="reserveHotel" />
                <serviceTask id="reserveHotel" name="Reserve Hotel" />
                <boundaryEvent id="reserveHotelCompensation" attachedToRef="reserveHotel">
                  <compensateEventDefinition />
                </boundaryEvent>
                <association id="assoc1" sourceRef="reserveHotelCompensation" targetRef="cancelHotel" />
                <serviceTask id="cancelHotel" name="Cancel Hotel Reservation" />
                <sequenceFlow id="f2" sourceRef="reserveHotel" targetRef="end" />
                <endEvent id="end" />
              </process>
            </definitions>
            """;

    private ProcessDefinitionResponse deploy(String processId, String bpmnXml) {
        ResponseEntity<ProcessDefinitionResponse> response = rest.postForEntity(
                url("/process-definitions"), new DeployProcessRequest(processId, bpmnXml), ProcessDefinitionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ProcessInstanceResponse start(String processId, Map<String, Object> variables) {
        ResponseEntity<ProcessInstanceResponse> response = rest.postForEntity(
                url("/process-definitions/" + processId + "/instances"), new StartInstanceRequest(variables), ProcessInstanceResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    @Test
    void deployingAProcessDefinitionThenFetchingItByLatestAndByVersionReturnsTheSameShape() {
        ProcessDefinitionResponse deployed = deploy("simple-process-" + Instant.now().toEpochMilli(), SIMPLE_PROCESS);
        assertThat(deployed.version()).isEqualTo(1);
        assertThat(deployed.name()).isEqualTo("Simple");

        ProcessDefinitionResponse latest = rest.getForEntity(
                url("/process-definitions/" + deployed.processId()), ProcessDefinitionResponse.class).getBody();
        assertThat(latest.processId()).isEqualTo(deployed.processId());
        assertThat(latest.version()).isEqualTo(deployed.version());
        assertThat(latest.name()).isEqualTo(deployed.name());
        assertThat(latest.deployedAt()).isCloseTo(deployed.deployedAt(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));

        ProcessDefinitionResponse byVersion = rest.getForEntity(
                url("/process-definitions/" + deployed.processId() + "/versions/1"), ProcessDefinitionResponse.class).getBody();
        assertThat(byVersion.processId()).isEqualTo(deployed.processId());
        assertThat(byVersion.version()).isEqualTo(deployed.version());
        assertThat(byVersion.name()).isEqualTo(deployed.name());
    }

    @Test
    void fetchingAnUndeployedProcessDefinitionReturns404() {
        ResponseEntity<ErrorResponse> response = rest.getForEntity(
                url("/process-definitions/no-such-process-ever"), ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aSimpleProcessWithNoWaitingPointsCompletesSynchronouslyOnStart() {
        String processId = "simple-" + Instant.now().toEpochMilli();
        deploy(processId, SIMPLE_PROCESS);

        ProcessInstanceResponse instance = start(processId, Map.of());

        assertThat(instance.status()).isEqualTo("COMPLETED");
        assertThat(instance.completedActivityIds()).containsExactly("doWork");
        assertThat(instance.tokens()).isEmpty();
    }

    @Test
    void startingAnInstanceOfAnUndeployedProcessReturns400() {
        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                url("/process-definitions/never-deployed/instances"), new StartInstanceRequest(Map.of()), ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void fetchingAnUnknownInstanceReturns404() {
        ResponseEntity<ErrorResponse> response = rest.getForEntity(url("/instances/does-not-exist"), ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anInFlightInstanceStaysPinnedToItsStartingVersionAfterANewerVersionDeploys() {
        String processId = "risk-" + Instant.now().toEpochMilli();
        deploy(processId, exclusiveProcess(10_000));

        ResponseEntity<ProcessInstanceResponse> pinned = rest.postForEntity(
                url("/process-definitions/" + processId + "/instances?version=1"),
                new StartInstanceRequest(Map.of("amount", 200)), ProcessInstanceResponse.class);
        assertThat(pinned.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(pinned.getBody().definitionVersion()).isEqualTo(1);
        assertThat(pinned.getBody().completedActivityIds()).containsExactly("lowRisk");

        deploy(processId, exclusiveProcess(100));

        ProcessInstanceResponse reFetched = rest.getForEntity(
                url("/instances/" + pinned.getBody().id()), ProcessInstanceResponse.class).getBody();
        assertThat(reFetched.definitionVersion()).isEqualTo(1);
        assertThat(reFetched.completedActivityIds()).containsExactly("lowRisk");

        ProcessInstanceResponse freshInstance = start(processId, Map.of("amount", 200));
        assertThat(freshInstance.definitionVersion()).isEqualTo(2);
        assertThat(freshInstance.completedActivityIds()).containsExactly("highRisk");
    }

    @Test
    void userTaskParksAndExplicitSignalResumesToCompletion() {
        String processId = "approval-" + Instant.now().toEpochMilli();
        deploy(processId, APPROVAL_PROCESS);

        ProcessInstanceResponse started = start(processId, Map.of());
        assertThat(started.status()).isEqualTo("RUNNING");
        assertThat(started.tokens()).hasSize(2);

        TokenResponse signalToken = started.tokens().stream()
                .filter(t -> "WAITING_SIGNAL".equals(t.status()))
                .findFirst().orElseThrow();

        ResponseEntity<ProcessInstanceResponse> resumed = rest.postForEntity(
                url("/instances/" + started.id() + "/signal"),
                new SignalRequest(signalToken.id(), Map.of()), ProcessInstanceResponse.class);

        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resumed.getBody().status()).isEqualTo("COMPLETED");
        assertThat(resumed.getBody().completedActivityIds()).contains("approve", "approved");
        assertThat(resumed.getBody().tokens()).isEmpty();
    }

    @Test
    void signalingWithAnUnknownTokenIdReturns422() {
        String processId = "approval-bad-token-" + Instant.now().toEpochMilli();
        deploy(processId, APPROVAL_PROCESS);
        ProcessInstanceResponse started = start(processId, Map.of());

        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                url("/instances/" + started.id() + "/signal"),
                new SignalRequest("not-a-real-token-id", Map.of()), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aBackgroundTimerAutomaticallyFiresAndCompletesTheInstanceWithoutAnyExplicitApiCall() throws InterruptedException {
        String processId = "approval-timer-" + Instant.now().toEpochMilli();
        deploy(processId, APPROVAL_PROCESS);
        ProcessInstanceResponse started = start(processId, Map.of());
        assertThat(started.status()).isEqualTo("RUNNING");

        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        ProcessInstanceResponse finalState = null;
        while (Instant.now().isBefore(deadline)) {
            finalState = rest.getForEntity(url("/instances/" + started.id()), ProcessInstanceResponse.class).getBody();
            if ("COMPLETED".equals(finalState.status())) {
                break;
            }
            Thread.sleep(50);
        }

        assertThat(finalState).isNotNull();
        assertThat(finalState.status()).isEqualTo("COMPLETED");
        assertThat(finalState.completedActivityIds()).contains("timedOut");
    }

    @Test
    void compensateEndpointRunsTheAssociatedCompensationHandler() {
        String processId = "booking-" + Instant.now().toEpochMilli();
        deploy(processId, BOOKING_WITH_COMPENSATION);
        ProcessInstanceResponse started = start(processId, Map.of());
        assertThat(started.status()).isEqualTo("COMPLETED");
        assertThat(started.completedActivityIds()).containsExactly("reserveHotel");

        ResponseEntity<ProcessInstanceResponse> compensated = rest.postForEntity(
                url("/instances/" + started.id() + "/compensate"),
                new CompensateRequest("reserveHotel"), ProcessInstanceResponse.class);

        assertThat(compensated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(compensated.getBody().compensatedActivityIds()).contains("reserveHotel");
    }

    @Test
    void deployingAndFetchingARuleSetRoundTripsItsRules() {
        String ruleSetName = "review-rules-" + Instant.now().toEpochMilli();
        var rule = new RuleDefinitionDto("flag-high", 0, "score > 500", Map.of("approved", "false"));

        ResponseEntity<RuleSetResponse> deployResponse = rest.postForEntity(
                url("/rule-sets"), new DeployRuleSetRequest(ruleSetName, List.of(rule)), RuleSetResponse.class);
        assertThat(deployResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(deployResponse.getBody().version()).isEqualTo(1);

        ResponseEntity<RuleSetResponse> fetched = rest.getForEntity(
                url("/rule-sets/" + ruleSetName), RuleSetResponse.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().rules()).extracting(RuleDefinitionDto::name).containsExactly("flag-high");
    }

    @Test
    void deployingAProcessWithABlankProcessIdIsRejectedWithValidationDetails() {
        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                url("/process-definitions"), new DeployProcessRequest("", SIMPLE_PROCESS), ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("processId");
    }

    @Test
    void deployingMalformedBpmnXmlIsRejected() {
        ResponseEntity<ErrorResponse> response = rest.postForEntity(
                url("/process-definitions"), new DeployProcessRequest("bad-xml-process", "not xml at all"), ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void malformedJsonBodyReturns400() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>("{not valid json", headers);

        ResponseEntity<ErrorResponse> response = rest.exchange(
                url("/process-definitions"), HttpMethod.POST, entity, ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
