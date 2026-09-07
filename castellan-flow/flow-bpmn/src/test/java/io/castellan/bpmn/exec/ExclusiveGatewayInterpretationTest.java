package io.castellan.bpmn.exec;

import io.castellan.bpmn.TestFixtures;
import io.castellan.bpmn.model.Process;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExclusiveGatewayInterpretationTest {

    private final Process process = TestFixtures.load("exclusive-gateway.bpmn");
    private final ProcessInterpreter interpreter = new ProcessInterpreter();

    @Test
    void routesHighRiskBranchWhenAmountExceedsTenThousand() {
        ProcessState state = interpreter.start(process, Map.of("amount", 20000.0));
        assertThat(state.completedActivityIds()).containsExactly("highRiskTask");
        assertThat(state.isTerminated()).isTrue();
    }

    @Test
    void routesMediumRiskBranchInBetween() {
        ProcessState state = interpreter.start(process, Map.of("amount", 5000.0));
        assertThat(state.completedActivityIds()).containsExactly("mediumRiskTask");
    }

    @Test
    void routesDefaultFlowWhenNoConditionMatches() {
        ProcessState state = interpreter.start(process, Map.of("amount", 10.0));
        assertThat(state.completedActivityIds()).containsExactly("lowRiskTask");
    }
}
