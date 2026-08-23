package io.castellan.apm.agent.weave;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * A {@link ClassWriter} configured with {@code COMPUTE_FRAMES} whose {@link
 * #getCommonSuperClass(String, String)} resolves against the <em>target class's own</em> {@link
 * ClassLoader} rather than {@code ClassWriter}'s default of the calling code's classloader.
 *
 * <h2>The hazard this fixes</h2>
 *
 * {@code COMPUTE_FRAMES} needs to compute, at every control-flow merge point in the bytecode being
 * written, the nearest common supertype of whatever types can reach that point. ASM's default
 * {@code getCommonSuperClass} does this via {@code Class.forName(name, false, classLoaderOfThis)}
 * — i.e. it resolves using <em>the agent's own classloader</em>. That is exactly backwards for a
 * java agent: agent classes are typically appended to the bootstrap/system class path (see {@link
 * io.castellan.apm.agent.CastellanAgent}) precisely so every application class can reach {@code
 * AgentBridge}, which means the agent's classloader sits <em>above</em> the target application's
 * classloader in the delegation chain — it frequently cannot see application classes at all, and
 * {@code getCommonSuperClass} would throw a {@code TypeNotPresentException} while weaving
 * perfectly ordinary application bytecode that never even touches our injected code (any merge
 * point in the *original* method body, e.g. a ternary or multi-catch producing two different
 * application types, triggers this — not just our injected try/catch).
 *
 * <p>This class asks the classloader of the class actually being transformed instead — passed
 * through from {@link CastellanClassFileTransformer#transform}, which is exactly the classloader
 * that successfully loaded this class file in the first place, so it can resolve every type that
 * file's bytecode can reference. When even that fails (a type resolvable neither there — an
 * unusual but not impossible situation, e.g. a merge with an array type of a not-yet-fully-defined
 * class during the class's own static initialization) this falls back to {@code java/lang/Object}:
 * always a structurally valid (if maximally conservative) common supertype, so the class file still
 * verifies — it costs the verifier some type-narrowing precision at that one join point, not
 * correctness.
 */
final class TargetLoaderAwareClassWriter extends ClassWriter {

    private final ClassLoader targetLoader;

    TargetLoaderAwareClassWriter(ClassReader reader, int flags, ClassLoader targetLoader) {
        super(reader, flags);
        this.targetLoader = targetLoader;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        try {
            Class<?> class1 = Class.forName(type1.replace('/', '.'), false, targetLoader);
            Class<?> class2 = Class.forName(type2.replace('/', '.'), false, targetLoader);
            if (class1.isAssignableFrom(class2)) {
                return type1;
            }
            if (class2.isAssignableFrom(class1)) {
                return type2;
            }
            if (class1.isInterface() || class2.isInterface()) {
                return "java/lang/Object";
            }
            Class<?> candidate = class1;
            do {
                candidate = candidate.getSuperclass();
            } while (candidate != null && !candidate.isAssignableFrom(class2));
            return candidate == null ? "java/lang/Object" : candidate.getName().replace('.', '/');
        } catch (ClassNotFoundException | LinkageError unresolvable) {
            return "java/lang/Object";
        }
    }
}
