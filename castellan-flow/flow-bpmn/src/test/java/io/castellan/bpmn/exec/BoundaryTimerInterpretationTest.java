package io.castellan.bpmn.exec;

import io.castellan.bpmn.TestFixtures;
import io.castellan.bpmn.model.Process;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Walkthrough of {@code boundary-timer.bpmn}: start -> approve (userTask, racing a 24h boundary
 * timer) -> either {@code approved} (external signal wins) or {@code timedOut} (timer wins) -> end.
 */
class BoundaryTimerInterpretationTest {

    private final Process process = TestFixtures.load("boundary-timer.bpmn");
    private final ProcessInterpreter interpreter = new ProcessInterpreter();

    @Test
    void startingTheProcessParksTwoRacingTokensAtTheUserTaskAndItsBoundaryTimer() {
        ProcessState state = interpreter.start(process, Map.of());

        assertThat(state.tokens()).hasSize(2);
        Token signalToken = tokenAt(state, "approve");
        Token timerToken = tokenAt(state, "timeoutBoundary");
        assertThat(signalToken.status()).isEqualTo(TokenStatus.WAITING_SIGNAL);
        assertThat(timerToken.status()).isEqualTo(TokenStatus.WAITING_TIMER);
        assertThat(signalToken.raceGroupId()).isNotNull().isEqualTo(timerToken.raceGroupId());
    }

    @Test
    void externalApprovalCancelsTheRacingTimerAndTakesTheApprovedBranch() {
        ProcessState state = interpreter.start(process, Map.of());
        Token signalToken = tokenAt(state, "approve");

        ProcessState resumed = interpreter.resume(process, state,
                new Signal.External(signalToken.id(), Map.of("approver", "alice")));

        assertThat(resumed.isTerminated()).isTrue();
        assertThat(resumed.completedActivityIds()).containsExactly("approve", "approved");
        assertThat(resumed.variables().get("approver")).isEqualTo("alice");
    }

    @Test
    void timerFiringCancelsTheWaitingUserTaskAndTakesTheTimedOutBranch() {
        ProcessState state = interpreter.start(process, Map.of());
        Token timerToken = tokenAt(state, "timeoutBoundary");

        ProcessState resumed = interpreter.resume(process, state, new Signal.TimerFired(timerToken.id()));

        assertThat(resumed.isTerminated()).isTrue();
        assertThat(resumed.completedActivityIds()).containsExactly("timedOut");
    }

    /**
     * The pause-then-resume contract: interpret to a timer wait, tear the {@link ProcessState}
     * down into raw primitives (as flow-engine's JDBC layer would when writing rows / JSON), build
     * a *brand new* {@link ProcessState} purely from those primitives (simulating a read-back after
     * a restart), and confirm resuming from that reconstructed state continues correctly. This is
     * what proves the state is "plain data", not a disguised reference to live interpreter objects.
     */
    @Test
    void resumesCorrectlyFromAStateRebuiltPurelyFromExternalizedPrimitives() {
        ProcessState original = interpreter.start(process, Map.of("caseId", "CASE-1"));
        Token timerToken = tokenAt(original, "timeoutBoundary");

        List<Object[]> externalizedTokens = new ArrayList<>();
        for (Token t : original.tokens()) {
            externalizedTokens.add(new Object[] {
                    t.id(), t.nodeId(), t.status().name(),
                    t.timerDueAt() == null ? null : t.timerDueAt().toString(),
                    t.waitingSignalName(), t.forkId(), t.raceGroupId()
            });
        }
        Map<String, Object> externalizedVariables = Map.copyOf(original.variables());

        List<Token> rebuiltTokens = new ArrayList<>();
        for (Object[] row : externalizedTokens) {
            rebuiltTokens.add(new Token(
                    (String) row[0], (String) row[1], TokenStatus.valueOf((String) row[2]),
                    row[3] == null ? null : java.time.Instant.parse((String) row[3]),
                    (String) row[4], (String) row[5], (String) row[6]));
        }
        ProcessState rebuilt = new ProcessState(externalizedVariables, rebuiltTokens,
                original.forkExpectedArrivals(), original.completedActivityIds(), original.compensatedActivityIds());

        ProcessState resumed = interpreter.resume(process, rebuilt, new Signal.TimerFired(timerToken.id()));

        assertThat(resumed.isTerminated()).isTrue();
        assertThat(resumed.completedActivityIds()).containsExactly("timedOut");
        assertThat(resumed.variables().get("caseId")).isEqualTo("CASE-1");
    }

    @Test
    void resumingWithTheWrongSignalTypeIsRejected() {
        ProcessState state = interpreter.start(process, Map.of());
        Token timerToken = tokenAt(state, "timeoutBoundary");

        assertThatThrownBy(() -> interpreter.resume(process, state,
                new Signal.External(timerToken.id(), Map.of())))
                .isInstanceOf(BpmnExecutionException.class);
    }

    private static Token tokenAt(ProcessState state, String nodeId) {
        return state.tokens().stream().filter(t -> t.nodeId().equals(nodeId)).findFirst()
                .orElseThrow(() -> new AssertionError("no token parked at " + nodeId));
    }
}
