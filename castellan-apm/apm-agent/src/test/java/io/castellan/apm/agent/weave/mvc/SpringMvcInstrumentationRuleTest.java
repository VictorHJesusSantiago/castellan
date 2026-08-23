package io.castellan.apm.agent.weave.mvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.webapp.FakeOrderController;
import com.example.webapp.OrderApi;
import io.castellan.apm.agent.weave.CapturingExporter;
import io.castellan.apm.agent.weave.CastellanClassFileTransformer;
import io.castellan.apm.agent.weave.WeavingTestSupport;
import io.castellan.apm.core.SpanData;
import io.castellan.apm.core.SpanKind;
import io.castellan.apm.core.SpanStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Spring MVC handler weaving, driven by annotation *presence* detected during the probe pass (see
 * {@link SpringMvcInstrumentationRule}'s javadoc for exactly which six annotation forms are
 * covered, and the stated cuts — no route-value extraction, no class-level {@code @RequestMapping}
 * composition). {@link com.example.webapp.FakeOrderController} carries three genuinely different
 * shapes in one class: a mapped handler with no {@code HttpServletRequest} parameter, one that has
 * one, and a plain method that must be left completely untouched.
 */
class SpringMvcInstrumentationRuleTest {

    @Test
    void mappedHandlerWithNoServletRequestParameterStartsAFreshServerSpan() throws Exception {
        CapturingExporter exporter = new CapturingExporter().installAsActiveTracer();
        OrderApi controller = wovenController();

        String result = controller.getOrder("42");

        assertThat(result).isEqualTo("order:42");
        assertThat(exporter.exported).hasSize(1);
        SpanData span = exporter.exported.get(0);
        assertThat(span.kind()).isEqualTo(SpanKind.SERVER);
        assertThat(span.name()).isEqualTo("FakeOrderController.getOrder");
        assertThat(span.status()).isEqualTo(SpanStatus.OK);
        assertThat(span.parentSpanId()).isNull();
    }

    @Test
    void mappedHandlerWithAServletRequestParameterContinuesTheInboundTrace() throws Exception {
        CapturingExporter exporter = new CapturingExporter().installAsActiveTracer();
        OrderApi controller = wovenController();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("traceparent")).thenReturn("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        String result = controller.createOrderWithRequest(request);

        assertThat(result).contains("created");
        assertThat(exporter.exported).hasSize(1);
        SpanData span = exporter.exported.get(0);
        assertThat(span.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(span.parentSpanId()).isEqualTo("00f067aa0ba902b7");
    }

    @Test
    void anUnannotatedMethodIsNeverWovenEvenThoughOtherMethodsInTheSameClassAre() throws Exception {
        CapturingExporter exporter = new CapturingExporter().installAsActiveTracer();
        OrderApi controller = wovenController();

        String result = controller.notMapped("hello");

        assertThat(result).isEqualTo("plain:hello");
        assertThat(exporter.exported).isEmpty();
    }

    @Test
    void aHandlerThatThrowsStillReportsAnErrorSpan() throws Exception {
        CapturingExporter exporter = new CapturingExporter().installAsActiveTracer();
        OrderApi controller = wovenController();

        assertThatThrownBy(controller::broken).isInstanceOf(IllegalStateException.class).hasMessage("handler failed");

        assertThat(exporter.exported).hasSize(1);
        SpanData span = exporter.exported.get(0);
        assertThat(span.status()).isEqualTo(SpanStatus.ERROR);
        assertThat(span.errorMessage()).contains("handler failed");
    }

    private static OrderApi wovenController() throws ReflectiveOperationException {
        CastellanClassFileTransformer transformer = new CastellanClassFileTransformer(List.of(new SpringMvcInstrumentationRule()));
        byte[] original = WeavingTestSupport.readClassBytes(FakeOrderController.class);

        byte[] woven = transformer.transform(FakeOrderController.class.getClassLoader(),
                "com/example/webapp/FakeOrderController", null, null, original);
        assertThat(woven).as("transform() must actually produce woven bytecode for a class with mapped methods").isNotNull();

        Class<?> wovenClass = WeavingTestSupport.defineInFreshClassLoader(FakeOrderController.class, woven);
        return (OrderApi) wovenClass.getDeclaredConstructor().newInstance();
    }
}
