package io.castellan.flow.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessDefinitionRepositoryTest {

    private ProcessDefinitionRepository repository;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        repository = new ProcessDefinitionRepository(TestDb.jdbcTemplate(TestDb.inMemory()), clock);
    }

    @Test
    void firstDeployIsVersion1() {
        ProcessDefinitionRecord record = repository.deploy("proc-a", "Process A", "<xml/>");

        assertThat(record.version()).isEqualTo(1);
    }

    @Test
    void redeployingTheSameProcessIdNeverOverwritesAlwaysIncrementsVersion() {
        repository.deploy("proc-a", "Process A", "<xml v='1'/>");
        ProcessDefinitionRecord v2 = repository.deploy("proc-a", "Process A", "<xml v='2'/>");
        ProcessDefinitionRecord v3 = repository.deploy("proc-a", "Process A", "<xml v='3'/>");

        assertThat(v2.version()).isEqualTo(2);
        assertThat(v3.version()).isEqualTo(3);
        assertThat(repository.version("proc-a", 1)).isPresent();
        assertThat(repository.version("proc-a", 1).get().bpmnXml()).isEqualTo("<xml v='1'/>");
    }

    @Test
    void latestReturnsTheHighestVersionEvenAfterSeveralDeploys() {
        repository.deploy("proc-a", "Process A", "<xml v='1'/>");
        repository.deploy("proc-a", "Process A", "<xml v='2'/>");
        repository.deploy("proc-a", "Process A", "<xml v='3'/>");

        assertThat(repository.latest("proc-a")).isPresent();
        assertThat(repository.latest("proc-a").get().version()).isEqualTo(3);
        assertThat(repository.latest("proc-a").get().bpmnXml()).isEqualTo("<xml v='3'/>");
    }

    @Test
    void differentProcessIdsVersionIndependently() {
        repository.deploy("proc-a", "A", "<a/>");
        repository.deploy("proc-a", "A", "<a2/>");
        repository.deploy("proc-b", "B", "<b/>");

        assertThat(repository.latest("proc-a").get().version()).isEqualTo(2);
        assertThat(repository.latest("proc-b").get().version()).isEqualTo(1);
    }

    @Test
    void unknownProcessIdIsEmpty() {
        assertThat(repository.latest("does-not-exist")).isEmpty();
        assertThat(repository.version("does-not-exist", 1)).isEmpty();
    }
}
