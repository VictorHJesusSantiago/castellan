package io.castellan.flow.engine;

import io.castellan.bpmn.exec.ServiceTaskHandler;
import io.castellan.bpmn.exec.TokenStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The actual proof of this module's central durability claim: a process instance parked waiting
 * on a timer, persisted by one set of engine/repository/scheduler objects, is picked back up and
 * correctly resumed by a completely independent, freshly constructed set of the same classes
 * pointed at the same on-disk H2 file — never touching any object, cache, or field the first set
 * created. If flow-engine's persistence had a bug where some part of "what to do next" secretly
 * lived in Java heap state rather than in {@code process_instances}/{@code timers} rows, this is
 * the test that would catch it: the "restarted" side has no way to cheat by reusing anything from
 * before.
 */
class RestartResumptionTest {

    @Test
    void aTimerPersistedBeforeARestartStillFiresAndResumesCorrectlyAfterOne(@TempDir Path dir) {
        Clock startClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

        String instanceId;
        {
            DataSource dataSource = TestDb.file(dir);
            JdbcTemplate jdbc = TestDb.jdbcTemplate(dataSource);
            ProcessDefinitionRepository definitions = new ProcessDefinitionRepository(jdbc, startClock);
            ProcessInstanceRepository instances = new ProcessInstanceRepository(jdbc);
            TimerRepository timers = new TimerRepository(jdbc);
            ProcessEngine engine = new ProcessEngine(definitions, instances, timers, ServiceTaskHandler.NO_OP, startClock);

            engine.deployDefinition("approval-process", Fixtures.APPROVAL_WITH_TIMEOUT);
            ProcessInstanceRecord started = engine.startInstance("approval-process", Map.of());
            assertThat(started.status()).isEqualTo(InstanceStatus.RUNNING);
            assertThat(started.state().tokens()).anyMatch(t -> t.status() == TokenStatus.WAITING_TIMER);
            instanceId = started.id();
        }

        DataSource restartedDataSource = TestDb.file(dir);
        JdbcTemplate restartedJdbc = TestDb.jdbcTemplate(restartedDataSource);
        ProcessDefinitionRepository restartedDefinitions = new ProcessDefinitionRepository(restartedJdbc, startClock);
        ProcessInstanceRepository restartedInstances = new ProcessInstanceRepository(restartedJdbc);
        TimerRepository restartedTimers = new TimerRepository(restartedJdbc);
        Clock afterRestartClock = Clock.fixed(startClock.instant().plus(Duration.ofHours(25)), ZoneOffset.UTC);
        ProcessEngine restartedEngine = new ProcessEngine(
                restartedDefinitions, restartedInstances, restartedTimers, ServiceTaskHandler.NO_OP, afterRestartClock);

        ProcessInstanceRecord rehydrated = restartedEngine.getInstance(instanceId);
        assertThat(rehydrated.status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(rehydrated.definitionVersion()).isEqualTo(1);

        TimerScheduler restartedScheduler = new TimerScheduler(restartedTimers, restartedEngine, afterRestartClock);
        try {
            int fired = restartedScheduler.pollOnce();

            assertThat(fired).isEqualTo(1);
            ProcessInstanceRecord finished = restartedEngine.getInstance(instanceId);
            assertThat(finished.status()).isEqualTo(InstanceStatus.COMPLETED);
            assertThat(finished.state().completedActivityIds()).contains("timedOut");
        } finally {
            restartedScheduler.shutdown();
        }
    }

    @Test
    void processDefinitionsAndDeployedRuleSetsAlsoSurviveAcrossTheSameRestartSimulation(@TempDir Path dir) {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        {
            JdbcTemplate jdbc = TestDb.jdbcTemplate(TestDb.file(dir));
            new ProcessDefinitionRepository(jdbc, clock).deploy("risk-process", "Risk", Fixtures.EXCLUSIVE_V1);
            new RuleSetJdbcRegistry(jdbc, clock).deploy("review-rules", java.util.List.of(
                    new JsonRuleDefinition("r1", 0, null, Map.of("approved", "true"))));
        }

        JdbcTemplate restartedJdbc = TestDb.jdbcTemplate(TestDb.file(dir));
        ProcessDefinitionRecord definition = new ProcessDefinitionRepository(restartedJdbc, clock)
                .latest("risk-process").orElseThrow();
        RuleSetDefinitionRecord ruleSet = new RuleSetJdbcRegistry(restartedJdbc, clock)
                .latest("review-rules").orElseThrow();

        assertThat(definition.version()).isEqualTo(1);
        assertThat(definition.bpmnXml()).isEqualTo(Fixtures.EXCLUSIVE_V1);
        assertThat(ruleSet.rules()).hasSize(1);
        assertThat(ruleSet.rules().get(0).name()).isEqualTo("r1");
    }
}
