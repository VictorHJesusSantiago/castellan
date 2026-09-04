package io.castellan.apm.core.export;

import io.castellan.apm.core.SpanData;
import java.util.List;

/**
 * Where finished spans go. {@link io.castellan.apm.core.Tracer#endSpan} calls {@link #export}
 * once per finished span (as a singleton list) — any batching (e.g. {@link HttpSpanExporter}
 * buffering many single-span calls into one larger HTTP POST) is this interface's implementation's
 * own concern, not the tracer's, so the tracer stays free of I/O and export-policy decisions.
 */
public interface SpanExporter {

    void export(List<SpanData> spans);
}
