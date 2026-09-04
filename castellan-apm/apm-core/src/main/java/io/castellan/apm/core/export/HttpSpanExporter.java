package io.castellan.apm.core.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.castellan.apm.core.SpanData;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POSTs batches of finished spans, as JSON, to {@code apm-collector}'s {@code POST /spans}
 * endpoint — using only {@link HttpClient} (JDK 11+ built-in), per the project brief's explicit
 * "no new HTTP client dependency" constraint.
 *
 * <h2>Batching policy</h2>
 *
 * Spans arrive one at a time (one {@link #export} call per finished span, from {@link
 * io.castellan.apm.core.Tracer#endSpan}) but are shipped in batches, flushed on whichever of two
 * triggers fires first:
 * <ul>
 *   <li><b>Size</b> — the buffer reaches {@code maxBatchSize}, so a burst of spans does not sit in
 *       memory indefinitely and each HTTP POST stays a bounded size.</li>
 *   <li><b>Time</b> — a background timer fires every {@code flushInterval}, so a quiet period does
 *       not leave a handful of spans buffered forever with nothing to trigger their send.</li>
 * </ul>
 * Every flush is dispatched via {@link HttpClient#sendAsync} — {@link #export} must never block an
 * application thread on network I/O, since it is called synchronously from inside instrumented
 * application code.
 */
public final class HttpSpanExporter implements SpanExporter, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpSpanExporter.class);

    private final URI endpoint;
    private final int maxBatchSize;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService flusher;

    private final Object lock = new Object();
    private List<SpanData> buffer = new ArrayList<>();

    public HttpSpanExporter(String collectorSpansUrl) {
        this(collectorSpansUrl, 100, Duration.ofSeconds(2));
    }

    public HttpSpanExporter(String collectorSpansUrl, int maxBatchSize, Duration flushInterval) {
        this.endpoint = URI.create(Objects.requireNonNull(collectorSpansUrl, "collectorSpansUrl"));
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("maxBatchSize must be >= 1");
        }
        this.maxBatchSize = maxBatchSize;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.flusher = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
        this.flusher.scheduleAtFixedRate(
                this::flush, flushInterval.toMillis(), flushInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread t = new Thread(runnable, "castellan-apm-exporter");
            t.setDaemon(true);
            return t;
        };
    }

    @Override
    public void export(List<SpanData> spans) {
        List<SpanData> toFlush = null;
        synchronized (lock) {
            buffer.addAll(spans);
            if (buffer.size() >= maxBatchSize) {
                toFlush = buffer;
                buffer = new ArrayList<>();
            }
        }
        if (toFlush != null) {
            send(toFlush);
        }
    }

    /** Flushes whatever is currently buffered, even below {@code maxBatchSize} — the timer-driven trigger. */
    public void flush() {
        List<SpanData> toFlush = null;
        synchronized (lock) {
            if (!buffer.isEmpty()) {
                toFlush = buffer;
                buffer = new ArrayList<>();
            }
        }
        if (toFlush != null) {
            send(toFlush);
        }
    }

    private void send(List<SpanData> spans) {
        try {
            String json = mapper.writeValueAsString(spans);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            log.warn("failed to export {} span(s) to {}: {}", spans.size(), endpoint, error.toString());
                        } else if (response.statusCode() >= 300) {
                            log.warn("collector rejected {} span(s): HTTP {}", spans.size(), response.statusCode());
                        }
                    });
        } catch (IOException serializationFailure) {
            log.warn("failed to serialize {} span(s) for export: {}", spans.size(), serializationFailure.toString());
        }
    }

    @Override
    public void close() {
        flush();
        flusher.shutdown();
    }
}
