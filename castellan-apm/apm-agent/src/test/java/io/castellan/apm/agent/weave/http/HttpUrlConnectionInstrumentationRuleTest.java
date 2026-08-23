package io.castellan.apm.agent.weave.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.httpapp.FakeHttpUrlConnection;
import io.castellan.apm.agent.weave.CapturingExporter;
import io.castellan.apm.agent.weave.CastellanClassFileTransformer;
import io.castellan.apm.agent.weave.WeavingTestSupport;
import io.castellan.apm.core.AgentBridge;
import io.castellan.apm.core.SpanData;
import io.castellan.apm.core.SpanKind;
import io.castellan.apm.core.SpanStatus;
import io.castellan.apm.core.TraceParent;
import io.castellan.apm.core.Tracer;
import io.castellan.apm.core.export.SpanExporter;
import io.castellan.apm.core.sampling.AdaptiveSampler;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Outbound {@code HttpURLConnection.connect()} weaving: a {@code CLIENT} span opens before the
 * original {@code connect()} body runs, and — per W3C Trace Context — the header injected carries
 * <em>that new span's own identity</em> (not some caller's), since it is the span the downstream
 * {@code SERVER} span should parent to. {@link #wovenClassInstance} loads the woven bytecode into a
 * fresh {@link ClassLoader}, exactly like {@code JdbcInstrumentationRuleTest}; unlike the JDBC
 * fixture (matched via a shared interface), this fixture *is* the concrete class being redefined,
 * so the test interacts with it through the shared JDK supertype {@link HttpURLConnection} plus
 * reflection for the fixture's own recording fields, rather than an unsafe direct cast.
 */
class HttpUrlConnectionInstrumentationRuleTest {

    @Test
    void sampledConnectInjectsTheJustOpenedSpansOwnTraceparentAndReportsAnOkSpan() throws Exception {
        CapturingExporter exporter = new CapturingExporter().installAsActiveTracer();
        HttpURLConnection connection = wovenClassInstance();

        connection.connect();

        assertThat(fieldValue(connection, "connectCalled", Boolean.class)).isTrue();
        assertThat(exporter.exported).hasSize(1);
        SpanData span = exporter.exported.get(0);
        assertThat(span.kind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.name()).isEqualTo("HTTP FakeHttpUrlConnection.connect");
        assertThat(span.status()).isEqualTo(SpanStatus.OK);

        Map<String, String> requestProperties = fieldValue(connection, "capturedRequestProperties", Map.class);
        String expectedHeader = TraceParent.sampled(span.traceId(), span.spanId()).format();
        assertThat(requestProperties).containsEntry("traceparent", expectedHeader);
    }

    @Test
    void connectThatThrowsStillInjectsTheHeaderFirstAndReportsAnErrorSpan() throws Exception {
        CapturingExporter exporter = new CapturingExporter().installAsActiveTracer();
        HttpURLConnection connection = wovenClassInstance();
        setField(connection, "throwOnConnect", true);

        assertThatThrownBy(connection::connect).isInstanceOf(IllegalStateException.class).hasMessage("connection refused");

        assertThat(exporter.exported).hasSize(1);
        SpanData span = exporter.exported.get(0);
        assertThat(span.status()).isEqualTo(SpanStatus.ERROR);
        assertThat(span.errorMessage()).contains("IllegalStateException").contains("connection refused");

        Map<String, String> requestProperties = fieldValue(connection, "capturedRequestProperties", Map.class);
        assertThat(requestProperties).containsKey("traceparent");
    }

    @Test
    void aSpanDroppedBySamplingInjectsNoHeaderAtAll() throws Exception {
        SpanExporter exporterThatMustNotBeCalled = spans -> {
            throw new AssertionError("a dropped span must never be exported");
        };
        AgentBridge.init(new Tracer(AdaptiveSampler.withBudget(0.0000001), exporterThatMustNotBeCalled));
        HttpURLConnection connection = wovenClassInstance();

        connection.connect();

        assertThat(fieldValue(connection, "connectCalled", Boolean.class)).isTrue();
        Map<String, String> requestProperties = fieldValue(connection, "capturedRequestProperties", Map.class);
        assertThat(requestProperties).doesNotContainKey("traceparent");
    }

    private static HttpURLConnection wovenClassInstance() throws Exception {
        CastellanClassFileTransformer transformer =
                new CastellanClassFileTransformer(List.of(new HttpUrlConnectionInstrumentationRule()));
        byte[] original = WeavingTestSupport.readClassBytes(FakeHttpUrlConnection.class);

        byte[] woven = transformer.transform(FakeHttpUrlConnection.class.getClassLoader(),
                "com/example/httpapp/FakeHttpUrlConnection", null, null, original);
        assertThat(woven).as("transform() must actually produce woven bytecode for a matching class").isNotNull();

        Class<?> wovenClass = WeavingTestSupport.defineInFreshClassLoader(FakeHttpUrlConnection.class, woven);
        URL url = URI.create("http://example.invalid/orders").toURL();
        return (HttpURLConnection) wovenClass.getDeclaredConstructor(URL.class).newInstance(url);
    }

    @SuppressWarnings("unchecked")
    private static <T> T fieldValue(Object instance, String fieldName, Class<T> type) throws ReflectiveOperationException {
        Field field = instance.getClass().getField(fieldName);
        return (T) field.get(instance);
    }

    private static void setField(Object instance, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = instance.getClass().getField(fieldName);
        field.set(instance, value);
    }
}
