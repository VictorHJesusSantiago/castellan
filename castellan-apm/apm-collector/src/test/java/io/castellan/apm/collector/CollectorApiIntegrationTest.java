package io.castellan.apm.collector;

import io.castellan.apm.collector.ingest.SpanRepository;
import io.castellan.apm.collector.latency.LatencyPercentiles;
import io.castellan.apm.collector.servicemap.ServiceMapEdge;
import io.castellan.apm.collector.servicemap.ServiceMapView;
import io.castellan.apm.collector.trace.SpanNode;
import io.castellan.apm.collector.trace.TraceView;
import io.castellan.apm.core.SpanData;
import io.castellan.apm.core.SpanKind;
import io.castellan.apm.core.SpanStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests over real HTTP, against the real Spring context wired to a real (Flyway-
 * migrated, embedded) H2 database -- see {@code src/test/resources/application.yml}. No mocks:
 * every span this suite posts is a real {@link SpanData} record, and every assertion reads back
 * whatever {@code POST /spans} actually persisted through the real query endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CollectorApiIntegrationTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static SpanData span(String traceId, String spanId, String parentSpanId, String name, SpanKind kind, long startMillis) {
        return new SpanData(traceId, spanId, parentSpanId, name, kind, SpanStatus.OK, null, null,
                startMillis, startMillis + 5, 5_000_000L, Map.of());
    }

    private void postSpans(List<SpanData> spans) {
        ResponseEntity<Void> response = rest.postForEntity(url("/spans"), spans, Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void postingATraceThenFetchingItReturnsTheReassembledParentChildTree() {
        String traceId = UUID.randomUUID().toString();
        SpanData root = span(traceId, "s-a", null, "OrderService.handle", SpanKind.SERVER, 1000);
        SpanData client = span(traceId, "s-b", "s-a", "HTTP PaymentClient.connect", SpanKind.CLIENT, 1010);
        SpanData callee = span(traceId, "s-c", "s-b", "PaymentService.charge", SpanKind.SERVER, 1020);
        postSpans(List.of(root, client, callee));

        ResponseEntity<TraceView> response = rest.getForEntity(url("/traces/" + traceId), TraceView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TraceView view = response.getBody();
        assertThat(view.traceId()).isEqualTo(traceId);
        assertThat(view.spanCount()).isEqualTo(3);
        assertThat(view.roots()).hasSize(1);
        SpanNode rootNode = view.roots().get(0);
        assertThat(rootNode.span().spanId()).isEqualTo("s-a");
        assertThat(rootNode.children()).hasSize(1);
        SpanNode clientNode = rootNode.children().get(0);
        assertThat(clientNode.span().spanId()).isEqualTo("s-b");
        assertThat(clientNode.children()).hasSize(1);
        assertThat(clientNode.children().get(0).span().spanId()).isEqualTo("s-c");
    }

    @Test
    void aSpanWhoseParentWasNeverIngestedBecomesItsOwnRootInsteadOfVanishing() {
        String traceId = UUID.randomUUID().toString();
        SpanData orphan = span(traceId, "s-orphan", "s-never-exported", "JDBC executeQuery", SpanKind.CLIENT, 2000);
        postSpans(List.of(orphan));

        TraceView view = rest.getForEntity(url("/traces/" + traceId), TraceView.class).getBody();

        assertThat(view.roots()).hasSize(1);
        assertThat(view.roots().get(0).span().spanId()).isEqualTo("s-orphan");
        assertThat(view.roots().get(0).children()).isEmpty();
    }

    @Test
    void reingestingTheSameSpanIdIsIdempotentAndDoesNotDuplicateIt() {
        String traceId = UUID.randomUUID().toString();
        SpanData s = span(traceId, "s-dup", null, "Retryable.op", SpanKind.INTERNAL, 3000);
        postSpans(List.of(s));
        postSpans(List.of(s));

        TraceView view = rest.getForEntity(url("/traces/" + traceId), TraceView.class).getBody();
        assertThat(view.spanCount()).isEqualTo(1);
    }

    @Test
    void fetchingAnUnknownTraceReturns404() {
        ResponseEntity<TraceView> response = rest.getForEntity(url("/traces/does-not-exist"), TraceView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void recentTracesListsNewlyPostedTracesWithASummary() {
        String traceId = UUID.randomUUID().toString();
        SpanData root = span(traceId, "s-recent-root", null, "InventoryService.reserve", SpanKind.SERVER, 4000);
        SpanData child = span(traceId, "s-recent-child", "s-recent-root", "JDBC prepareStatement", SpanKind.CLIENT, 4005);
        postSpans(List.of(root, child));

        ResponseEntity<SpanRepository.TraceSummary[]> response =
                rest.getForEntity(url("/traces?limit=50"), SpanRepository.TraceSummary[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .anySatisfy(s -> {
                    assertThat(s.traceId()).isEqualTo(traceId);
                    assertThat(s.rootName()).isEqualTo("InventoryService.reserve");
                    assertThat(s.spanCount()).isEqualTo(2);
                });
    }

    @Test
    void serviceMapDerivesAnEdgeFromAClientSpanToItsChildServerSpan() {
        String traceId = UUID.randomUUID().toString();
        SpanData caller = span(traceId, "s-map-a", null, "CheckoutService.submit", SpanKind.SERVER, 5000);
        SpanData outbound = span(traceId, "s-map-b", "s-map-a", "HTTP ShippingClient.connect", SpanKind.CLIENT, 5005);
        SpanData callee = span(traceId, "s-map-c", "s-map-b", "ShippingService.schedule", SpanKind.SERVER, 5010);
        postSpans(List.of(caller, outbound, callee));

        ResponseEntity<ServiceMapView> response = rest.getForEntity(url("/service-map"), ServiceMapView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ServiceMapView view = response.getBody();
        assertThat(view.edges()).contains(new ServiceMapEdge("HTTP ShippingClient.connect", "ShippingService.schedule", 1));
        assertThat(view.nodes()).extracting(n -> n.name())
                .contains("HTTP ShippingClient.connect", "ShippingService.schedule");
    }

    @Test
    void latencyPercentilesAreComputedByNearestRankOverRecentDurations() {
        String operationName = "PricingEngine.compute-" + UUID.randomUUID();
        String traceId = UUID.randomUUID().toString();
        for (int i = 1; i <= 10; i++) {
            long durationNanos = i * 100_000_000L;
            SpanData s = new SpanData(traceId, "s-lat-" + i, null, operationName, SpanKind.INTERNAL,
                    SpanStatus.OK, null, null, 6000, 6000 + i, durationNanos, Map.of());
            postSpans(List.of(s));
        }

        ResponseEntity<LatencyPercentiles> response =
                rest.getForEntity(url("/operations/" + operationName + "/latency"), LatencyPercentiles.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        LatencyPercentiles percentiles = response.getBody();
        assertThat(percentiles.sampleCount()).isEqualTo(10);
        assertThat(percentiles.p50Millis()).isEqualTo(500.0);
        assertThat(percentiles.p95Millis()).isEqualTo(1000.0);
        assertThat(percentiles.p99Millis()).isEqualTo(1000.0);
    }

    @Test
    void latencyForAnOperationThatWasNeverRecordedReportsZeroSamplesRatherThanFailing() {
        ResponseEntity<LatencyPercentiles> response =
                rest.getForEntity(url("/operations/never-called-operation/latency"), LatencyPercentiles.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().sampleCount()).isZero();
        assertThat(response.getBody().p50Millis()).isZero();
    }

    @Test
    void anErrorSpanRoundTripsItsStatusAndErrorDetails() {
        String traceId = UUID.randomUUID().toString();
        SpanData failed = new SpanData(traceId, "s-err", null, "Risky.op", SpanKind.INTERNAL,
                SpanStatus.ERROR, "java.lang.RuntimeException: boom", "java.lang.RuntimeException: boom\n\tat ...",
                7000, 7005, 5_000_000L, Map.of("db.statement", "SELECT 1"));
        postSpans(List.of(failed));

        TraceView view = rest.getForEntity(url("/traces/" + traceId), TraceView.class).getBody();

        SpanData roundTripped = view.roots().get(0).span();
        assertThat(roundTripped.status()).isEqualTo(SpanStatus.ERROR);
        assertThat(roundTripped.errorMessage()).isEqualTo("java.lang.RuntimeException: boom");
        assertThat(roundTripped.attributes()).containsEntry("db.statement", "SELECT 1");
    }
}
