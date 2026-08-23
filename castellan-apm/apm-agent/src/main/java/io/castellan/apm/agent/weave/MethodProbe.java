package io.castellan.apm.agent.weave;

import java.util.Set;

/**
 * One method's shape as seen by the cheap {@link ProbeClassVisitor} pass: its identity (name +
 * descriptor — together the exact key {@link SpanWeavingClassVisitor} uses to recognize the same
 * method again during the full weaving pass), its access flags, and the set of annotation type
 * descriptors present on it (e.g. {@code "Lorg/springframework/web/bind/annotation/GetMapping;"}),
 * which is all {@link io.castellan.apm.agent.weave.mvc.SpringMvcInstrumentationRule} needs without
 * ever resolving the annotation's actual {@code Class} — see that rule's javadoc.
 */
public record MethodProbe(String name, String descriptor, int access, Set<String> annotationDescriptors) {

    /** The stable key {@link CastellanClassFileTransformer} uses to carry a decided plan from the probe pass to the weave pass. */
    public String key() {
        return name + descriptor;
    }
}
