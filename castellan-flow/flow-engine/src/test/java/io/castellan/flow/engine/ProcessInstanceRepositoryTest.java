package io.castellan.flow.engine;

import io.castellan.bpmn.exec.ProcessState;
import io.castellan.bpmn.exec.Token;
import io.castellan.bpmn.exec.TokenStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessInstanceRepositoryTest {

    private ProcessInstanceRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ProcessInstanceRepository(TestDb.jdbcTemplate(TestDb.inMemory()));
    }

    @Test
    void insertThenFindRoundTripsTheFullProcessStateIncludingTokensAndForkBookkeeping() {
        Token waiting = new Token("tok-1", "approve", TokenStatus.WAITING_SIGNAL, null, "approve", "fork-7", null);
        ProcessState state = new ProcessState(
                Map.of("amount", 500, "flag", true),
                List.of(waiting),
                Map.of("fork-7", 2),
                List.of("scoreTask", "reviewTask"),
                List.of("someActivity"));
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ProcessInstanceRecord record = new ProcessInstanceRecord(
                "inst-1", "proc-a", 3, InstanceStatus.RUNNING, state, now, now);

        repository.insert(record);
        ProcessInstanceRecord found = repository.find("inst-1").orElseThrow();

        assertThat(found.id()).isEqualTo("inst-1");
        assertThat(found.processId()).isEqualTo("proc-a");
        assertThat(found.definitionVersion()).isEqualTo(3);
        assertThat(found.status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(found.state().variables()).isEqualTo(Map.of("amount", 500, "flag", true));
        assertThat(found.state().tokens()).containsExactly(waiting);
        assertThat(found.state().forkExpectedArrivals()).isEqualTo(Map.of("fork-7", 2));
        assertThat(found.state().completedActivityIds()).containsExactly("scoreTask", "reviewTask");
        assertThat(found.state().compensatedActivityIds()).containsExactly("someActivity");
    }

    @Test
    void updateStateChangesStatusAndStateAndBumpsUpdatedAt() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        ProcessState initial = new ProcessState(Map.of(), List.of(), Map.of(), List.of(), List.of());
        repository.insert(new ProcessInstanceRecord("inst-1", "proc-a", 1, InstanceStatus.RUNNING, initial, created, created));

        ProcessState terminated = new ProcessState(Map.of("done", true), List.of(), Map.of(), List.of("t1"), List.of());
        Instant later = Instant.parse("2026-01-01T01:00:00Z");
        repository.updateState("inst-1", terminated, InstanceStatus.COMPLETED, later);

        ProcessInstanceRecord found = repository.find("inst-1").orElseThrow();
        assertThat(found.status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(found.state().variables()).isEqualTo(Map.of("done", true));
        assertThat(found.createdAt()).isEqualTo(created);
        assertThat(found.updatedAt()).isEqualTo(later);
    }

    @Test
    void updateStateOnAnUnknownInstanceThrows() {
        ProcessState state = new ProcessState(Map.of(), List.of(), Map.of(), List.of(), List.of());

        assertThatThrownBy(() -> repository.updateState("ghost", state, InstanceStatus.COMPLETED, Instant.now()))
                .isInstanceOf(NoSuchProcessInstanceException.class);
    }

    @Test
    void findOnAnUnknownInstanceIsEmpty() {
        assertThat(repository.find("ghost")).isEmpty();
    }
}
