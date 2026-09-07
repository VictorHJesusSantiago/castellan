package io.castellan.flow.engine;

import io.castellan.bpmn.exec.TokenStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessEngineTest {

    private Clock clock;
    private ProcessDefinitionRepository definitions;
    private ProcessInstanceRepository instances;
    private TimerRepository timers;
    private RuleSetJdbcRegistry ruleSets;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        DataSource dataSource = TestDb.inMemory();
        JdbcTemplate jdbc = TestDb.jdbcTemplate(dataSource);
        definitions = new ProcessDefinitionRepository(jdbc, clock);
        instances = new ProcessInstanceRepository(jdbc);
        timers = new TimerRepository(jdbc);
        ruleSets = new RuleSetJdbcRegistry(jdbc, clock);
    }

    private ProcessEngine engineWithRules() {
        return new ProcessEngine(definitions, instances, timers, new RuleServiceTaskHandler(ruleSets), clock);
    }

    private ProcessEngine engineNoOp() {
        return new ProcessEngine(definitions, instances, timers, io.castellan.bpmn.exec.ServiceTaskHandler.NO_OP, clock);
    }

    @Test
    void sequentialProcessWithNoWaitingPointsRunsToCompletionSynchronously() {
        ProcessEngine engine = engineNoOp();
        engine.deployDefinition("risk-process", Fixtures.EXCLUSIVE_V1);

        ProcessInstanceRecord record = engine.startInstance("risk-process", Map.of("amount", 50));

        assertThat(record.status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(record.state().completedActivityIds()).containsExactly("lowRisk");
    }

    @Test
    void exclusiveGatewayRoutesOnVariablesAtStartTime() {
        ProcessEngine engine = engineNoOp();
        engine.deployDefinition("risk-process", Fixtures.EXCLUSIVE_V1);

        ProcessInstanceRecord highRisk = engine.startInstance("risk-process", Map.of("amount", 50_000));
        ProcessInstanceRecord lowRisk = engine.startInstance("risk-process", Map.of("amount", 5));

        assertThat(highRisk.state().completedActivityIds()).containsExactly("highRisk");
        assertThat(lowRisk.state().completedActivityIds()).containsExactly("lowRisk");
    }

    @Test
    void ruleServiceTaskFiresARealRuleSetAndMergesItsOutcomeIntoProcessVariables() {
        ruleSets.deploy("review-rules", List.of(
                new JsonRuleDefinition("flag-high-score", 0, "score > 500", Map.of("approved", "false")),
                new JsonRuleDefinition("approve-low-score", 0, "score <= 500", Map.of("approved", "true"))
        ));
        ProcessEngine engine = engineWithRules();
        engine.deployDefinition("sequential-process", Fixtures.SEQUENTIAL_WITH_RULES);

        ProcessInstanceRecord highScore = engine.startInstance("sequential-process", Map.of("amount", 1000));
        ProcessInstanceRecord lowScore = engine.startInstance("sequential-process", Map.of("amount", 10));

        assertThat(highScore.status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(highScore.state().variables()).containsEntry("approved", false);
        assertThat(lowScore.state().variables()).containsEntry("approved", true);
    }

    @Test
    void userTaskParksAndSignalResumesToCompletion() {
        ProcessEngine engine = engineNoOp();
        engine.deployDefinition("approval-process", Fixtures.APPROVAL_WITH_TIMEOUT);

        ProcessInstanceRecord started = engine.startInstance("approval-process", Map.of());
        assertThat(started.status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(started.state().tokens()).hasSize(2);

        String signalTokenId = started.state().tokens().stream()
                .filter(t -> t.status() == TokenStatus.WAITING_SIGNAL)
                .findFirst().orElseThrow().id();

        ProcessInstanceRecord resumed = engine.signal(started.id(), signalTokenId, Map.of());

        assertThat(resumed.status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(resumed.state().completedActivityIds()).contains("approve", "approved");
        assertThat(resumed.state().tokens()).isEmpty();
    }

    @Test
    void deliverTimerSignalResumesAWaitingToken() {
        ProcessEngine engine = engineNoOp();
        engine.deployDefinition("approval-process", Fixtures.APPROVAL_WITH_TIMEOUT);
        ProcessInstanceRecord started = engine.startInstance("approval-process", Map.of());
        String timerTokenId = started.state().tokens().stream()
                .filter(t -> t.status() == TokenStatus.WAITING_TIMER)
                .findFirst().orElseThrow().id();

        ProcessInstanceRecord resumed = engine.deliverTimerSignal(started.id(), timerTokenId);

        assertThat(resumed.status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(resumed.state().completedActivityIds()).contains("timedOut");
    }

    @Test
    void anInFlightInstanceStaysPinnedToItsStartingVersionEvenAfterANewerVersionDeploys() {
        ProcessEngine engine = engineNoOp();
        engine.deployDefinition("risk-process", Fixtures.EXCLUSIVE_V1);
        ProcessInstanceRecord instance = engine.startInstance("risk-process", Map.of("amount", 200));
        assertThat(instance.status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(instance.state().completedActivityIds()).containsExactly("lowRisk");

        engine.deployDefinition("risk-process", Fixtures.EXCLUSIVE_V2);

        ProcessInstanceRecord reFetched = engine.getInstance(instance.id());
        assertThat(reFetched.definitionVersion()).isEqualTo(1);
        assertThat(reFetched.state().completedActivityIds()).containsExactly("lowRisk");

        ProcessInstanceRecord newInstance = engine.startInstance("risk-process", Map.of("amount", 200));
        assertThat(newInstance.definitionVersion()).isEqualTo(2);
        assertThat(newInstance.state().completedActivityIds()).containsExactly("mediumRisk");
    }

    @Test
    void startInstanceOnAnExplicitVersionPinsToExactlyThatVersion() {
        ProcessEngine engine = engineNoOp();
        engine.deployDefinition("risk-process", Fixtures.EXCLUSIVE_V1);
        engine.deployDefinition("risk-process", Fixtures.EXCLUSIVE_V2);

        ProcessInstanceRecord pinnedToV1 = engine.startInstance("risk-process", 1, Map.of("amount", 200));

        assertThat(pinnedToV1.definitionVersion()).isEqualTo(1);
        assertThat(pinnedToV1.state().completedActivityIds()).containsExactly("lowRisk");
    }

    @Test
    void startingAnUndeployedProcessIdThrows() {
        ProcessEngine engine = engineNoOp();

        assertThatThrownBy(() -> engine.startInstance("no-such-process", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gettingAnUnknownInstanceThrows() {
        ProcessEngine engine = engineNoOp();

        assertThatThrownBy(() -> engine.getInstance("ghost"))
                .isInstanceOf(NoSuchProcessInstanceException.class);
    }
}
