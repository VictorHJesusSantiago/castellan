package io.castellan.apm.agent;

import io.castellan.apm.core.Tracer;
import io.castellan.apm.core.export.HttpSpanExporter;
import io.castellan.apm.core.export.LoggingSpanExporter;
import io.castellan.apm.core.export.SpanExporter;
import io.castellan.apm.core.sampling.AdaptiveSampler;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses the {@code -javaagent:apm-agent.jar=key=value,key2=value2} argument string {@code
 * premain} receives verbatim from the JVM, and builds the {@link Tracer} {@code
 * io.castellan.apm.core.AgentBridge} is initialized with.
 *
 * <h2>Recognized keys</h2>
 * <ul>
 *   <li>{@code collectorUrl} — the {@code apm-collector} {@code POST /spans} endpoint. If absent,
 *       spans are logged locally via {@link LoggingSpanExporter} instead of exported over HTTP —
 *       deliberately the same fallback {@code AgentBridge}'s own default {@link Tracer} uses, so
 *       "no collector configured" behaves identically whether or not an agent ever attached at
 *       all.</li>
 *   <li>{@code sampleRate} — the {@link AdaptiveSampler} budget, in spans/second. Malformed or
 *       missing values fall back to {@link #DEFAULT_SAMPLE_BUDGET}.</li>
 *   <li>{@code transformer} — {@code "bytebuddy"} selects the secondary Byte Buddy JDBC-only
 *       transformer for comparison; anything else (including absence) selects the primary ASM
 *       transformer, which is the default.</li>
 * </ul>
 */
public record AgentConfig(String collectorUrl, double sampleBudget, boolean useByteBuddy) {

    private static final double DEFAULT_SAMPLE_BUDGET = 1000.0;

    public static AgentConfig parse(String agentArgs) {
        Map<String, String> keyValues = new HashMap<>();
        if (agentArgs != null && !agentArgs.isBlank()) {
            for (String pair : agentArgs.split(",")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    keyValues.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
                }
            }
        }
        String collectorUrl = keyValues.get("collectorUrl");
        double sampleBudget = parseSampleBudget(keyValues.get("sampleRate"));
        boolean useByteBuddy = "bytebuddy".equalsIgnoreCase(keyValues.get("transformer"));
        return new AgentConfig(collectorUrl, sampleBudget, useByteBuddy);
    }

    private static double parseSampleBudget(String value) {
        if (value == null) {
            return DEFAULT_SAMPLE_BUDGET;
        }
        try {
            double parsed = Double.parseDouble(value);
            return parsed > 0 ? parsed : DEFAULT_SAMPLE_BUDGET;
        } catch (NumberFormatException malformed) {
            return DEFAULT_SAMPLE_BUDGET;
        }
    }

    public Tracer buildTracer() {
        SpanExporter exporter = (collectorUrl == null || collectorUrl.isBlank())
                ? new LoggingSpanExporter()
                : new HttpSpanExporter(collectorUrl);
        return new Tracer(AdaptiveSampler.withBudget(sampleBudget), exporter);
    }
}
