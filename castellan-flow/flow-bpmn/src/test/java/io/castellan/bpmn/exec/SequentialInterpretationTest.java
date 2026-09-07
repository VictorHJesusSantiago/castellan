package io.castellan.bpmn.exec;

import io.castellan.bpmn.TestFixtures;
import io.castellan.bpmn.model.Process;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SequentialInterpretationTest {

    @Test
    void runsStartScriptServiceEndWithoutStopping() {
        Process process = TestFixtures.load("sequential.bpmn");
        ProcessInterpreter interpreter = new ProcessInterpreter();

        ProcessState state = interpreter.start(process, Map.of("amount", 50.0));

        assertThat(state.isTerminated()).isTrue();
        assertThat(state.variables().get("score")).isEqualTo(100.0);
        assertThat(state.completedActivityIds()).containsExactly("scoreTask", "reviewTask");
    }

    @Test
    void serviceTaskInvokesRegisteredHandlerWithRuleSetName() {
        Process process = TestFixtures.load("sequential.bpmn");
        java.util.List<String> invokedRuleSets = new java.util.ArrayList<>();
        ServiceTaskHandler handler = (task, vars) -> {
            invokedRuleSets.add(task.ruleSetName());
            return Map.of("decision", "APPROVED");
        };
        ProcessInterpreter interpreter = new ProcessInterpreter(handler);

        ProcessState state = interpreter.start(process, Map.of("amount", 10.0));

        assertThat(invokedRuleSets).containsExactly("review-rules");
        assertThat(state.variables().get("decision")).isEqualTo("APPROVED");
    }
}
