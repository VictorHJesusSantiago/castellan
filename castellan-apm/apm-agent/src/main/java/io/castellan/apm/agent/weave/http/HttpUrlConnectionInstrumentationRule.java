package io.castellan.apm.agent.weave.http;

import io.castellan.apm.agent.weave.ClassContext;
import io.castellan.apm.agent.weave.MethodInstrumentationRule;
import io.castellan.apm.agent.weave.MethodProbe;
import io.castellan.apm.agent.weave.MethodWeavingPlan;
import java.util.Optional;
import org.objectweb.asm.Opcodes;

/**
 * Matches {@code connect()} on direct subclasses of {@code java.net.HttpURLConnection}, wrapping
 * it with a {@code CLIENT} span and — before the original body runs — injecting the outbound W3C
 * {@code traceparent} header via {@code setRequestProperty}.
 *
 * <h2>Why {@code connect()} is the injection point, not {@code getResponseCode()}/{@code getInputStream()}</h2>
 *
 * {@code HttpURLConnection}'s documented contract is that request properties (headers) must be set
 * <em>before</em> the connection is established, and {@code connect()} is the method that
 * establishes it. Injecting our header-setting code as the very first instructions of the woven
 * {@code connect()} body — before the original implementation runs — is therefore not just a
 * convenient single choke point, it is the last legally-correct moment to set a header at all: any
 * later injection point (e.g. wrapping {@code getResponseCode()}, which most applications call
 * right after {@code connect()} and which is often even where the connection is implicitly
 * established if {@code connect()} was never called explicitly) risks setting the header after the
 * request has already gone out.
 *
 * <h2>Scope actually covered — and what is deliberately cut</h2>
 *
 * Only {@code java.net.HttpURLConnection} (and, by the same code path, {@code
 * javax.net.ssl.HttpsURLConnection}, which extends it) is instrumented. {@code
 * java.net.http.HttpClient} (JDK 11+) is explicitly <b>not</b> instrumented: its actual
 * implementation is a JDK-internal class ({@code jdk.internal.net.http.HttpClientImpl}) that this
 * class-body-weaving strategy cannot reach without deep module-system workarounds that would be a
 * different, much larger project (call-site rewriting at every caller instead of weaving the
 * implementation class itself — the same category of extra machinery Byte Buddy's own advice on
 * "instrumenting sealed/JDK-internal types" documents as needed). This is a stated cut, not a
 * silent gap: an application built on {@code HttpClient} instead of {@code HttpURLConnection} gets
 * no outbound propagation from this agent.
 *
 * <p>Superclass matching, like {@link JdbcInstrumentationRule} interface matching, is direct-only
 * (exactly one level: {@link ClassContext#superInternalName()}), not a walk up the full hierarchy.
 */
public final class HttpUrlConnectionInstrumentationRule implements MethodInstrumentationRule {

    /** The real JDK superclass this rule targets in production. */
    public static final String DEFAULT_TARGET_SUPERCLASS = "java/net/HttpURLConnection";

    private final String targetSuperclass;

    public HttpUrlConnectionInstrumentationRule() {
        this(DEFAULT_TARGET_SUPERCLASS);
    }

    /** Test seam: lets a test target a small stand-in superclass instead of the real (abstract-but-still-sizable) JDK type. */
    public HttpUrlConnectionInstrumentationRule(String targetSuperclass) {
        this.targetSuperclass = targetSuperclass;
    }

    @Override
    public boolean appliesToClass(ClassContext classContext) {
        return targetSuperclass.equals(classContext.superInternalName());
    }

    @Override
    public Optional<MethodWeavingPlan> planFor(ClassContext classContext, MethodProbe method) {
        boolean isInstanceConnectNoArgs = "connect".equals(method.name())
                && "()V".equals(method.descriptor())
                && (method.access() & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0;
        if (!isInstanceConnectNoArgs) {
            return Optional.empty();
        }
        return Optional.of(MethodWeavingPlan.outboundHttp("HTTP " + classContext.simpleName() + ".connect"));
    }
}
