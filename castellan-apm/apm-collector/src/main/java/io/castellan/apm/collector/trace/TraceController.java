package io.castellan.apm.collector.trace;

import io.castellan.apm.collector.ingest.SpanRepository;
import io.castellan.apm.core.SpanData;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TraceController {

    private final SpanRepository repository;

    public TraceController(SpanRepository repository) {
        this.repository = repository;
    }

    /** Recently started traces, newest first — the entry point for browsing without already
     * knowing a trace id. */
    @GetMapping("/traces")
    public List<SpanRepository.TraceSummary> recent(@RequestParam(defaultValue = "20") int limit) {
        return repository.findRecentTraceSummaries(limit);
    }

    @GetMapping("/traces/{traceId}")
    public ResponseEntity<TraceView> get(@PathVariable String traceId) {
        List<SpanData> spans = repository.findByTraceId(traceId);
        if (spans.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(TraceAssembler.assemble(traceId, spans));
    }
}
