package io.castellan.apm.agent.weave.mvc;

import io.castellan.apm.agent.weave.ClassContext;
import io.castellan.apm.agent.weave.MethodInstrumentationRule;
import io.castellan.apm.agent.weave.MethodProbe;
import io.castellan.apm.agent.weave.MethodWeavingPlan;
import java.util.Optional;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Matches methods carrying one of Spring MVC's six request-mapping annotations, decided purely
 * from the annotation's type descriptor as seen by ASM's {@code visitAnnotation} callback during
 * the probe pass ({@link io.castellan.apm.agent.weave.ProbeClassVisitor}) — this class never
 * resolves {@code org.springframework.web.bind.annotation.GetMapping} as an actual {@code Class}
 * (and {@code apm-agent} deliberately has no compile-time dependency on Spring at all), which is
 * exactly the point of annotation matching at the bytecode level: it works identically whether or
 * not Spring is even on the JVM's boot search path, since it never needs the annotation class
 * itself to be loadable.
 *
 * <h2>Annotation forms covered</h2>
 *
 * Exactly the method-level presence of one of:
 * {@code @RequestMapping}, {@code @GetMapping}, {@code @PostMapping}, {@code @PutMapping},
 * {@code @DeleteMapping}, {@code @PatchMapping} (all from {@code
 * org.springframework.web.bind.annotation}). <b>Not</b> covered, as stated scope cuts:
 * <ul>
 *   <li>Class-level {@code @RequestMapping} composed with a method-level mapping (Spring's actual
 *       routing combines a controller-level base path with each handler's own path) — this rule
 *       only inspects the method's own annotations, never the enclosing class's, so it cannot see
 *       a handler that is mapped purely via an inherited/class-level annotation with no method-level
 *       annotation of its own (extremely rare in practice, but real).</li>
 *   <li>The mapping's {@code value}/{@code path} element (the actual route, e.g. {@code
 *       "/orders/{id}"}) is not read — the probe pass's annotation visitor returns {@code null}
 *       from {@code visitAnnotation}, so ASM never descends into the annotation's own element
 *       values. The span name is therefore {@code
 *       "SimpleClassName.methodName"}, not the HTTP route. Reading the route would mean returning a
 *       real {@code AnnotationVisitor} that captures the {@code value}/{@code path} array's first
 *       element during the probe pass and threading it through to here — mechanically similar to
 *       what is already done for annotation *presence*, cut for time rather than for any structural
 *       reason.</li>
 *   <li>Meta-annotations (a custom {@code @GetMapping}-annotated annotation, Spring's own supported
 *       "composed annotation" pattern) are not resolved — only the six literal annotation types
 *       above are matched, not anything annotated with one of them.</li>
 * </ul>
 *
 * <h2>Inbound trace continuation</h2>
 *
 * If the handler method's own parameter list includes a {@code jakarta.servlet.http.HttpServletRequest}
 * parameter, its {@code traceparent} header is read and used to continue the caller's trace (see
 * {@link MethodWeavingPlan#httpServletRequestArgIndex()}). Most Spring MVC handlers do not take an
 * explicit {@code HttpServletRequest} parameter — Spring resolves path variables, query params, and
 * request bodies directly as method arguments — so this is the common case for real controllers:
 * a fresh trace is started, with no inbound propagation. Full inbound propagation for the common
 * case would require intercepting Spring's {@code DispatcherServlet}/{@code HandlerAdapter} layer
 * instead, which does see the raw request; that is a different (and larger) instrumentation target
 * than "the handler method itself" and is out of scope here.
 */
public final class SpringMvcInstrumentationRule implements MethodInstrumentationRule {

    public static final Set<String> MAPPING_ANNOTATION_DESCRIPTORS = Set.of(
            "Lorg/springframework/web/bind/annotation/RequestMapping;",
            "Lorg/springframework/web/bind/annotation/GetMapping;",
            "Lorg/springframework/web/bind/annotation/PostMapping;",
            "Lorg/springframework/web/bind/annotation/PutMapping;",
            "Lorg/springframework/web/bind/annotation/DeleteMapping;",
            "Lorg/springframework/web/bind/annotation/PatchMapping;");

    private static final Type HTTP_SERVLET_REQUEST_TYPE = Type.getObjectType("jakarta/servlet/http/HttpServletRequest");

    @Override
    public boolean appliesToClass(ClassContext classContext) {
        return true;
    }

    @Override
    public Optional<MethodWeavingPlan> planFor(ClassContext classContext, MethodProbe method) {
        boolean isMapped = method.annotationDescriptors().stream().anyMatch(MAPPING_ANNOTATION_DESCRIPTORS::contains);
        if (!isMapped || (method.access() & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) {
            return Optional.empty();
        }
        int requestArgIndex = findHttpServletRequestArgIndex(method.descriptor());
        String spanName = classContext.simpleName() + "." + method.name();
        return Optional.of(MethodWeavingPlan.serverHandler(spanName, requestArgIndex));
    }

    private static int findHttpServletRequestArgIndex(String descriptor) {
        Type[] argumentTypes = Type.getArgumentTypes(descriptor);
        for (int i = 0; i < argumentTypes.length; i++) {
            if (HTTP_SERVLET_REQUEST_TYPE.equals(argumentTypes[i])) {
                return i;
            }
        }
        return -1;
    }
}
