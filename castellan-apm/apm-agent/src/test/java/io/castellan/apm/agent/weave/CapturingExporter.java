package io.castellan.apm.agent.weave;

import io.castellan.apm.core.AgentBridge;
import io.castellan.apm.core.SpanData;
import io.castellan.apm.core.Tracer;
import io.castellan.apm.core.export.SpanExporter;
import io.castellan.apm.core.sampling.AdaptiveSampler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link SpanExporter} that just remembers every {@link SpanData} it was handed, so a test can
 * assert on exactly what the woven bytecode reported to {@link AgentBridge} — installed via {@link
 * #installAsActiveTracer()}, which replaces {@code AgentBridge}'s process-wide {@link Tracer} for
 * the duration of the calling test. Samples everything (an unbounded budget), matching {@code
 * AgentBridge}'s own default-tracer rationale: a test asserting on span content must never have the
 * sampler silently drop the very span it is trying to observe.
 */
public final class CapturingExporter implements SpanExporter {

    public final List<SpanData> exported = new CopyOnWriteArrayList<>();

    @Override
    public void export(List<SpanData> spans) {
        exported.addAll(spans);
    }

    public CapturingExporter installAsActiveTracer() {
        AgentBridge.init(new Tracer(AdaptiveSampler.withBudget(Double.MAX_VALUE), this));
        return this;
    }
}
