package io.castellan.apm.collector.servicemap;

import io.castellan.apm.collector.ingest.SpanRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.TreeSet;

/**
 * Derives a service map purely from span data — there is no separate "service registry" or
 * "service.name" attribute anywhere in this project's tracing model (see
 * {@link io.castellan.apm.core.SpanKind}'s own class docs, which anticipate exactly this class). A
 * "service" here is really an <em>operation name</em> (e.g. {@code "HTTP FooConnection.connect"},
 * {@code "SimpleClassName.methodName"}) — an honest simplification relative to a real APM's
 * per-process service identity, not a hidden gap: {@code apm-agent} never stamps a service name on
 * any span, so there is nothing truer to derive this from.
 *
 * <p>An edge exists between a {@code CLIENT} span's name and a {@code SERVER} span's name whenever
 * the {@code SERVER} span's {@code parentSpanId} is that exact {@code CLIENT} span's id within the
 * same trace — the one link {@code apm-agent}'s outbound-HTTP/inbound-MVC instrumentation actually
 * wires end to end (see {@code SpringMvcInstrumentationRule}'s docs on how narrow real inbound
 * propagation is in practice: only handlers that take an explicit {@code HttpServletRequest}
 * parameter continue an inbound trace at all).
 */
@Service
public class ServiceMapService {

    private final SpanRepository repository;

    public ServiceMapService(SpanRepository repository) {
        this.repository = repository;
    }

    public ServiceMapView build() {
        List<SpanRepository.ServiceCallEdge> raw = repository.findClientServerEdges();
        List<ServiceMapEdge> edges = raw.stream()
                .map(e -> new ServiceMapEdge(e.callerName(), e.calleeName(), e.callCount()))
                .toList();

        TreeSet<String> names = new TreeSet<>();
        for (ServiceMapEdge edge : edges) {
            names.add(edge.caller());
            names.add(edge.callee());
        }
        List<ServiceMapNode> nodes = names.stream().map(ServiceMapNode::new).toList();

        return new ServiceMapView(nodes, edges);
    }
}
