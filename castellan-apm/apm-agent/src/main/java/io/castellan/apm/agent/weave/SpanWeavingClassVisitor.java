package io.castellan.apm.agent.weave;

import java.util.Map;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

/**
 * Pass 2 of {@link CastellanClassFileTransformer}: given the {@link MethodWeavingPlan}s already
 * decided by pass 1 (keyed by {@link MethodProbe#key()}), wraps exactly the matched methods'
 * visitors with {@link SpanWeavingMethodVisitor} and leaves every other method — the vast majority,
 * for any real class — completely untouched by delegating straight to the superclass.
 */
final class SpanWeavingClassVisitor extends ClassVisitor {

    private final Map<String, MethodWeavingPlan> plansByMethodKey;

    SpanWeavingClassVisitor(int api, ClassVisitor classVisitor, Map<String, MethodWeavingPlan> plansByMethodKey) {
        super(api, classVisitor);
        this.plansByMethodKey = plansByMethodKey;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
        MethodWeavingPlan plan = plansByMethodKey.get(name + descriptor);
        if (plan == null || delegate == null) {
            return delegate;
        }
        return new SpanWeavingMethodVisitor(api, delegate, access, name, descriptor, plan);
    }
}
