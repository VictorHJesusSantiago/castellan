package io.castellan.bpmn.exec;

import io.castellan.bpmn.TestFixtures;
import io.castellan.bpmn.model.Process;
import io.castellan.bpmn.model.ServiceTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompensationInterpretationTest {

    @Test
    void compensateInvokesTheAssociatedHandlerForACompletedActivity() {
        Process process = TestFixtures.load("compensation.bpmn");
        List<String> invocations = new ArrayList<>();
        ServiceTaskHandler handler = (ServiceTask task, Map<String, Object> vars) -> {
            invocations.add(task.id());
            return Map.of();
        };
        ProcessInterpreter interpreter = new ProcessInterpreter(handler);

        ProcessState state = interpreter.start(process, Map.of());
        assertThat(state.isTerminated()).isTrue();
        assertThat(state.completedActivityIds()).containsExactly("reserveHotel");
        assertThat(invocations).containsExactly("reserveHotel");

        ProcessState compensated = interpreter.compensate(process, state, "reserveHotel");

        assertThat(invocations).containsExactly("reserveHotel", "cancelHotel");
        assertThat(compensated.compensatedActivityIds()).containsExactly("reserveHotel");
    }

    @Test
    void rejectsCompensatingAnActivityThatNeverCompleted() {
        Process process = TestFixtures.load("compensation.bpmn");
        ProcessInterpreter interpreter = new ProcessInterpreter();
        ProcessState fresh = new ProcessState(Map.of(), List.of(), Map.of(), List.of(), List.of());

        assertThatThrownBy(() -> interpreter.compensate(process, fresh, "reserveHotel"))
                .isInstanceOf(BpmnExecutionException.class)
                .hasMessageContaining("hasn't completed");
    }
}
