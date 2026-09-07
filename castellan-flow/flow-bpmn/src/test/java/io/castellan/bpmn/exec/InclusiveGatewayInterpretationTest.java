package io.castellan.bpmn.exec;

import io.castellan.bpmn.TestFixtures;
import io.castellan.bpmn.model.Process;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InclusiveGatewayInterpretationTest {

    private final Process process = TestFixtures.load("inclusive-gateway.bpmn");
    private final ProcessInterpreter interpreter = new ProcessInterpreter();

    @Test
    void activatesOnlyTheBranchesWhoseConditionsAreTrue() {
        ProcessState state = interpreter.start(process, Map.of("giftWrap", true, "express", false));

        assertThat(state.completedActivityIds()).containsExactly("giftWrap");
        assertThat(state.isTerminated()).isTrue();
    }

    @Test
    void activatesMultipleTrueBranchesAndJoinWaitsForAllOfThem() {
        ProcessState state = interpreter.start(process, Map.of("giftWrap", true, "express", true));

        assertThat(state.completedActivityIds()).containsExactlyInAnyOrder("giftWrap", "expressShipping");
        assertThat(state.isTerminated()).isTrue();
    }

    @Test
    void fallsBackToDefaultFlowWhenNoConditionMatches() {
        ProcessState state = interpreter.start(process, Map.of("giftWrap", false, "express", false));

        assertThat(state.completedActivityIds()).containsExactly("standardShipping");
        assertThat(state.isTerminated()).isTrue();
    }
}
