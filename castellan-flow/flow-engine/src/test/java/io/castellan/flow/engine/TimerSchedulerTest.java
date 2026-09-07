package io.castellan.flow.engine;

import io.castellan.bpmn.exec.ServiceTaskHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TimerSchedulerTest {

    private TimerScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void pollOnceDeliversEveryDueTimerAndReturnsHowManyFired() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock clock = Clock.fixed(now.get(), ZoneOffset.UTC);

        DataSource dataSource = TestDb.inMemory();
        JdbcTemplate jdbc = TestDb.jdbcTemplate(dataSource);
        ProcessDefinitionRepository definitions = new ProcessDefinitionRepository(jdbc, clock);
        ProcessInstanceRepository instances = new ProcessInstanceRepository(jdbc);
        TimerRepository timers = new TimerRepository(jdbc);
        ProcessEngine engine = new ProcessEngine(definitions, instances, timers, ServiceTaskHandler.NO_OP, clock);
        engine.deployDefinition("approval-process", Fixtures.APPROVAL_WITH_TIMEOUT);

        ProcessInstanceRecord started = engine.startInstance("approval-process", Map.of());
        assertThat(started.status()).isEqualTo(InstanceStatus.RUNNING);
        scheduler = new TimerScheduler(timers, engine, clock);
        assertThat(scheduler.pollOnce()).isZero();

        Clock later = Clock.fixed(now.get().plus(Duration.ofHours(25)), ZoneOffset.UTC);
        TimerScheduler laterScheduler = new TimerScheduler(timers, engine, later);
        int fired = laterScheduler.pollOnce();
        laterScheduler.shutdown();

        assertThat(fired).isEqualTo(1);
        ProcessInstanceRecord resumed = engine.getInstance(started.id());
        assertThat(resumed.status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(resumed.state().completedActivityIds()).contains("timedOut");
    }

    @Test
    void startedSchedulerFiresOnItsOwnBackgroundThreadWithoutAManualPollOnceCall() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        DataSource dataSource = TestDb.inMemory();
        JdbcTemplate jdbc = TestDb.jdbcTemplate(dataSource);
        ProcessDefinitionRepository definitions = new ProcessDefinitionRepository(jdbc, clock);
        ProcessInstanceRepository instances = new ProcessInstanceRepository(jdbc);
        TimerRepository timers = new TimerRepository(jdbc);
        Clock alreadyLate = Clock.fixed(clock.instant().plus(Duration.ofHours(25)), ZoneOffset.UTC);
        ProcessEngine engine = new ProcessEngine(definitions, instances, timers, ServiceTaskHandler.NO_OP, clock);
        engine.deployDefinition("approval-process", Fixtures.APPROVAL_WITH_TIMEOUT);
        ProcessInstanceRecord started = engine.startInstance("approval-process", Map.of());

        scheduler = new TimerScheduler(timers, engine, alreadyLate);
        scheduler.start(Duration.ofMillis(20));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(engine.getInstance(started.id()).status()).isEqualTo(InstanceStatus.COMPLETED));
    }
}
