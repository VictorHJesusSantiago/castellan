package io.castellan.apm.collector.ingest;

import io.castellan.apm.core.SpanData;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The endpoint {@code apm-core}'s {@code HttpSpanExporter} POSTs finished-span batches to. */
@RestController
public class SpanIngestController {

    private final SpanRepository repository;

    public SpanIngestController(SpanRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/spans")
    public ResponseEntity<Void> ingest(@RequestBody List<SpanData> spans) {
        repository.saveAll(spans);
        return ResponseEntity.accepted().build();
    }
}
