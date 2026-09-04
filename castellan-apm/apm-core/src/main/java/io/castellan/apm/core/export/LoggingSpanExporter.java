package io.castellan.apm.core.export;

import io.castellan.apm.core.SpanData;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback/dev exporter: logs each finished span at INFO. Used whenever no collector is configured
 * (standalone runs, {@link io.castellan.apm.core.AgentBridge}'s own default before {@code
 * CastellanAgent} has called {@code init(...)}, and the Definition-of-Done's manual
 * {@code -javaagent} smoke test, which deliberately runs without a collector process at all so
 * spans showing up in the console is the entire proof the weaving worked end to end).
 */
public final class LoggingSpanExporter implements SpanExporter {

    private static final Logger log = LoggerFactory.getLogger(LoggingSpanExporter.class);

    @Override
    public void export(List<SpanData> spans) {
        for (SpanData span : spans) {
            log.info(
                    "span traceId={} spanId={} parentSpanId={} name={} kind={} status={} durationMicros={} attributes={}{}",
                    span.traceId(),
                    span.spanId(),
                    span.parentSpanId(),
                    span.name(),
                    span.kind(),
                    span.status(),
                    span.durationNanos() / 1000,
                    span.attributes(),
                    span.errorMessage() == null ? "" : " error=" + span.errorMessage());
        }
    }
}
