package io.castellan.bpmn.exec;

import io.castellan.bpmn.TestFixtures;
import io.castellan.bpmn.model.Process;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Walkthrough of {@code parallel-fork-join.bpmn}: start -> fork (parallel) -> {creditCheck,
 * fraudCheck} concurrently -> join (parallel) -> end. Asserts the join produces exactly one
 * continuing token only once *both* branches have completed — not once per arrival.
 */
class ParallelForkJoinInterpretationTest {

    @Test
    void joinWaitsForBothBranchesBeforeProducingOneContinuingToken() {
        Process process = TestFixtures.load("parallel-fork-join.bpmn");
        ProcessInterpreter interpreter = new ProcessInterpreter();

        ProcessState state = interpreter.start(process, Map.of());

        assertThat(state.completedActivityIds()).containsExactlyInAnyOrder("creditCheck", "fraudCheck");
        assertThat(state.isTerminated()).isTrue();
        assertThat(state.forkExpectedArrivals()).isEmpty();
        assertThat(state.tokens()).isEmpty();
    }
}
