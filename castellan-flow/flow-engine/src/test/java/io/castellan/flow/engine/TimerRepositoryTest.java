package io.castellan.flow.engine;

import io.castellan.bpmn.exec.Token;
import io.castellan.bpmn.exec.TokenStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimerRepositoryTest {

    private TimerRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TimerRepository(TestDb.jdbcTemplate(TestDb.inMemory()));
    }

    @Test
    void onlyWaitingTimerTokensAreStoredAsTimers() {
        Token waitingTimer = new Token("tok-1", "boundary", TokenStatus.WAITING_TIMER,
                Instant.parse("2026-01-02T00:00:00Z"), null, null, null);
        Token waitingSignal = new Token("tok-2", "approve", TokenStatus.WAITING_SIGNAL, null, "approve", null, null);
        Token parkedAtJoin = new Token("tok-3", "join1", TokenStatus.PARKED_AT_JOIN, null, null, "fork-1", null);

        repository.replaceForInstance("inst-1", List.of(waitingTimer, waitingSignal, parkedAtJoin));

        List<TimerRepository.DueTimer> due = repository.findDue(Instant.parse("2026-01-03T00:00:00Z"));
        assertThat(due).hasSize(1);
        assertThat(due.get(0).instanceId()).isEqualTo("inst-1");
        assertThat(due.get(0).tokenId()).isEqualTo("tok-1");
    }

    @Test
    void findDueOnlyReturnsTimersAtOrBeforeNow() {
        Token pastDue = new Token("tok-past", "n", TokenStatus.WAITING_TIMER, Instant.parse("2026-01-01T00:00:00Z"), null, null, null);
        Token future = new Token("tok-future", "n", TokenStatus.WAITING_TIMER, Instant.parse("2099-01-01T00:00:00Z"), null, null, null);
        repository.replaceForInstance("inst-1", List.of(pastDue, future));

        List<TimerRepository.DueTimer> due = repository.findDue(Instant.parse("2026-06-01T00:00:00Z"));

        assertThat(due).extracting(TimerRepository.DueTimer::tokenId).containsExactly("tok-past");
    }

    @Test
    void replaceForInstanceWhollyReplacesPreviousTimers() {
        Token first = new Token("tok-1", "n", TokenStatus.WAITING_TIMER, Instant.parse("2026-01-01T00:00:00Z"), null, null, null);
        repository.replaceForInstance("inst-1", List.of(first));
        assertThat(repository.findDue(Instant.parse("2027-01-01T00:00:00Z"))).hasSize(1);

        repository.replaceForInstance("inst-1", List.of());

        assertThat(repository.findDue(Instant.parse("2027-01-01T00:00:00Z"))).isEmpty();
    }

    @Test
    void timersFromDifferentInstancesDoNotInterfere() {
        Token a = new Token("tok-a", "n", TokenStatus.WAITING_TIMER, Instant.parse("2026-01-01T00:00:00Z"), null, null, null);
        Token b = new Token("tok-b", "n", TokenStatus.WAITING_TIMER, Instant.parse("2026-01-01T00:00:00Z"), null, null, null);
        repository.replaceForInstance("inst-a", List.of(a));
        repository.replaceForInstance("inst-b", List.of(b));

        repository.replaceForInstance("inst-a", List.of());

        List<TimerRepository.DueTimer> due = repository.findDue(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(due).extracting(TimerRepository.DueTimer::instanceId).containsExactly("inst-b");
    }

    @Test
    void dueTimersAreOrderedByDueAt() {
        Token later = new Token("tok-later", "n", TokenStatus.WAITING_TIMER, Instant.parse("2026-01-02T00:00:00Z"), null, null, null);
        Token earlier = new Token("tok-earlier", "n", TokenStatus.WAITING_TIMER, Instant.parse("2026-01-01T00:00:00Z"), null, null, null);
        repository.replaceForInstance("inst-1", List.of(later, earlier));

        List<TimerRepository.DueTimer> due = repository.findDue(Instant.parse("2027-01-01T00:00:00Z"));

        assertThat(due).extracting(TimerRepository.DueTimer::tokenId).containsExactly("tok-earlier", "tok-later");
    }
}
