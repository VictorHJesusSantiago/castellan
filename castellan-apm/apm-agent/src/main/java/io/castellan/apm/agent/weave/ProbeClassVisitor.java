package io.castellan.apm.agent.weave;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Pass 1 of {@link CastellanClassFileTransformer}'s two-pass design: a cheap, read-only scan
 * ({@code ClassReader.accept(this, SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES)}) that answers "does this
 * class need weaving at all, and if so, exactly which methods, woven exactly how" — without ever
 * building a {@link org.objectweb.asm.ClassWriter} or touching a single instruction.
 *
 * <h2>Why two passes instead of deciding-and-weaving in one</h2>
 *
 * {@code ClassFileTransformer.transform} is invoked by the JVM for <em>every</em> class loaded in
 * the process — the transformer's job is to say "not interested" as cheaply as possible for the
 * overwhelming majority of them (every JDK class, every third-party class that is not a JDBC
 * driver, a {@code HttpURLConnection} subclass, or a Spring MVC handler). {@code SKIP_CODE} makes
 * this scan skip the most expensive part of parsing a class file (method bodies) entirely, so the
 * common "no" case costs roughly what reading the constant pool plus method signatures costs — far
 * cheaper than a full {@code ClassReader.accept} into a {@code ClassWriter(COMPUTE_FRAMES)}, which
 * this class's caller only pays for once a full pass has been decided to be worthwhile (see {@link
 * CastellanClassFileTransformer#transform}).
 *
 * <p>{@code SKIP_CODE} does <em>not</em> skip annotations — {@link #visitMethod} still receives
 * {@code visitAnnotation} callbacks for each method's annotations even with method bodies skipped,
 * which is exactly what {@link io.castellan.apm.agent.weave.mvc.SpringMvcInstrumentationRule}
 * needs and is the other reason this has to be a real two-pass visitor rather than a single
 * super-cheap "does the class name/interface list match" string check: Spring MVC's matching
 * signal (a {@code @GetMapping}-family annotation) is only visible per-method, and ASM only
 * delivers per-method annotation callbacks to the {@link MethodVisitor} returned from {@code
 * visitMethod} — which means every method of every non-trivially-rejected class must be visited at
 * least this far before a decision is possible.
 */
final class ProbeClassVisitor extends ClassVisitor {

    private final List<MethodProbe> methods = new ArrayList<>();
    private String internalName;
    private String superInternalName;
    private List<String> interfaceInternalNames = List.of();

    ProbeClassVisitor() {
        super(Opcodes.ASM9);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.internalName = name;
        this.superInternalName = superName;
        this.interfaceInternalNames = interfaces == null ? List.of() : List.of(interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        Set<String> annotationDescriptors = new LinkedHashSet<>();
        methods.add(new MethodProbe(name, descriptor, access, annotationDescriptors));
        return new AnnotationCollectingMethodVisitor(annotationDescriptors);
    }

    ClassContext toClassContext() {
        return new ClassContext(internalName, superInternalName, interfaceInternalNames);
    }

    List<MethodProbe> methods() {
        return methods;
    }

    /**
     * Records the descriptor of every annotation directly present on one method. The {@code
     * annotationDescriptors} set is the same mutable instance already stored in that method's
     * {@link MethodProbe} — this visitor's only job is to populate it as ASM calls {@code
     * visitAnnotation} for each one, which happens (per the class-file format) before {@code
     * visitCode}/{@code visitEnd}, so it is always fully populated by the time {@link
     * CastellanClassFileTransformer} inspects the finished {@link MethodProbe} list.
     */
    private static final class AnnotationCollectingMethodVisitor extends MethodVisitor {
        private final Set<String> annotationDescriptors;

        AnnotationCollectingMethodVisitor(Set<String> annotationDescriptors) {
            super(Opcodes.ASM9);
            this.annotationDescriptors = annotationDescriptors;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            annotationDescriptors.add(descriptor);
            return null;
        }
    }
}
