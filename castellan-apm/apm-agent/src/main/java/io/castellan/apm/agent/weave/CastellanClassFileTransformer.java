package io.castellan.apm.agent.weave;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ASM-based {@link ClassFileTransformer} — the project's primary, default instrumentation
 * path (see {@code io.castellan.apm.agent.bytebuddy.ByteBuddyJdbcTransformer} for the secondary,
 * comparison implementation). Runs every class loaded in the JVM through a cheap probe pass ({@link
 * ProbeClassVisitor}) and only pays for a full {@code ClassWriter(COMPUTE_FRAMES)} rewrite ({@link
 * SpanWeavingClassVisitor}) on the classes that pass at least one {@link MethodInstrumentationRule}.
 *
 * <p>This class has no dependency on which rules are active — it is handed a {@link List} of them
 * (JDBC, outbound HTTP, Spring MVC, or any subset, or a test-only stand-in) and treats "first rule
 * whose {@link MethodInstrumentationRule#appliesToClass} accepts, and whose {@link
 * MethodInstrumentationRule#planFor} returns a plan for a given method, wins" uniformly.
 */
public final class CastellanClassFileTransformer implements ClassFileTransformer {

    private static final Logger log = LoggerFactory.getLogger(CastellanClassFileTransformer.class);

    /**
     * Class-name prefixes never worth probing: JDK/agent-library internals that are never
     * themselves a JDBC driver, a {@code HttpURLConnection} subclass, or a Spring MVC handler, and
     * where a mistake would risk destabilizing the JVM's own bootstrapping. The one deliberate
     * carve-out is the JDK's actual {@code HttpURLConnection} implementation classes, which *do*
     * live under {@code sun/net/www/protocol/...} — a blanket {@code sun/} exclusion would silently
     * defeat real-world outbound HTTP instrumentation entirely (see this module's README-equivalent
     * discussion in the final report: this carve-out is documented but not exercised by a unit test,
     * since exercising it for real would require an actual socket connection, not a direct {@code
     * transform()} call).
     */
    private static final List<String> SKIP_PREFIXES = List.of(
            "io/castellan/apm/", "java/", "jdk/", "com/sun/", "javax/", "jakarta/",
            "org/objectweb/asm/", "net/bytebuddy/", "org/slf4j/");

    private static final List<String> RETAIN_DESPITE_SKIP_PREFIX = List.of(
            "sun/net/www/protocol/http/HttpURLConnection", "sun/net/www/protocol/https/HttpsURLConnectionImpl");

    private final List<MethodInstrumentationRule> rules;

    public CastellanClassFileTransformer(List<MethodInstrumentationRule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    public byte[] transform(ClassLoader loader, String internalClassName, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (internalClassName == null || shouldSkip(internalClassName)) {
            return null;
        }
        try {
            return doTransform(loader, classfileBuffer);
        } catch (RuntimeException weavingFailure) {
            log.warn("Castellan APM: failed to weave {}, leaving it unmodified: {}", internalClassName, weavingFailure.toString());
            return null;
        }
    }

    private byte[] doTransform(ClassLoader loader, byte[] classfileBuffer) {
        ClassReader probeReader = new ClassReader(classfileBuffer);
        ProbeClassVisitor probe = new ProbeClassVisitor();
        probeReader.accept(probe, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        ClassContext classContext = probe.toClassContext();

        Map<String, MethodWeavingPlan> plans = decidePlans(classContext, probe.methods());
        if (plans.isEmpty()) {
            return null;
        }

        ClassReader fullReader = new ClassReader(classfileBuffer);
        ClassWriter writer = new TargetLoaderAwareClassWriter(fullReader, ClassWriter.COMPUTE_FRAMES, loader);
        SpanWeavingClassVisitor classVisitor = new SpanWeavingClassVisitor(Opcodes.ASM9, writer, plans);
        fullReader.accept(classVisitor, ClassReader.SKIP_FRAMES);
        log.debug("Castellan APM: wove {} method(s) in {}", plans.size(), classContext.internalName());
        return writer.toByteArray();
    }

    private Map<String, MethodWeavingPlan> decidePlans(ClassContext classContext, List<MethodProbe> methods) {
        Map<String, MethodWeavingPlan> plans = new HashMap<>();
        List<MethodInstrumentationRule> applicableRules = rules.stream().filter(r -> r.appliesToClass(classContext)).toList();
        if (applicableRules.isEmpty()) {
            return plans;
        }
        for (MethodProbe method : methods) {
            for (MethodInstrumentationRule rule : applicableRules) {
                Optional<MethodWeavingPlan> plan = rule.planFor(classContext, method);
                if (plan.isPresent()) {
                    plans.put(method.key(), plan.get());
                    break;
                }
            }
        }
        return plans;
    }

    private static boolean shouldSkip(String internalClassName) {
        for (String retain : RETAIN_DESPITE_SKIP_PREFIX) {
            if (internalClassName.equals(retain)) {
                return false;
            }
        }
        for (String prefix : SKIP_PREFIXES) {
            if (internalClassName.startsWith(prefix)) {
                return true;
            }
        }
        return internalClassName.contains("$$");
    }
}
