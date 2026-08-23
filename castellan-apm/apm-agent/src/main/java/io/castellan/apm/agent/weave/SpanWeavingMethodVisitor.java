package io.castellan.apm.agent.weave;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

/**
 * Emits the bytecode for exactly one woven method: start a span in the prologue, close it on every
 * exit path, and do it with real try/finally semantics (a span must close even when the original
 * method throws — not "usually", the actual bytecode must guarantee it). Every call target is one
 * of {@code io.castellan.apm.core.AgentBridge}'s five static methods — this class never inlines
 * span-model logic, per {@code AgentBridge}'s own class javadoc.
 *
 * <h2>Why {@link AdviceAdapter} alone is not enough</h2>
 *
 * {@code AdviceAdapter.onMethodExit(int opcode)} is called before every explicit {@code xRETURN}/
 * {@code RETURN}/{@code ATHROW} instruction <em>already present in the method's own bytecode</em>.
 * That covers a normal return and an explicit {@code throw} statement written directly in the
 * instrumented method — but it does <b>not</b> cover the far more common case of an exception
 * thrown by code the method <em>calls</em> (a JDBC driver's own {@code SQLException}, for example):
 * that exception propagates via the JVM's exception-dispatch mechanism, which never executes an
 * {@code ATHROW} instruction inside <em>this</em> method's bytecode at all. Relying on {@code
 * onMethodExit} alone would silently leak every span whose method threw indirectly — exactly the
 * "closes... but only usually" bug this class exists to not have. The fix is the same one {@code
 * javac} itself uses to compile a real {@code try/finally}: wrap the <em>entire</em> original
 * method body in a synthetic exception handler for {@code java/lang/Throwable} that calls {@code
 * AgentBridge.end(span, throwable)} and rethrows, <em>in addition to</em> using {@code
 * onMethodExit} to inline the same "close normally" call before every explicit return. Together
 * they reproduce exactly what {@code javac} emits for a hand-written {@code try { ... } finally {
 * end(span, null-or-caught); }} — because that is, bytecode-for-bytecode, what this is.
 *
 * <h2>Why the synthetic exception-table entry is registered last, not first</h2>
 *
 * The JVM searches a method's exception table in declaration order and uses the <em>first</em>
 * matching entry — which is how nested {@code try/catch} blocks in the original source correctly
 * take priority over an outer one. Our synthetic handler catches {@code java/lang/Throwable}, i.e.
 * it matches everything; if it were declared <em>before</em> the original method body's own {@code
 * visitTryCatchBlock} calls (which arrive, unmodified, as this visitor passes them through to the
 * delegate during body visitation), it would shadow every one of the original method's own
 * handlers, silently changing the instrumented method's exception-handling behavior — a
 * correctness bug, not a performance one. This class instead calls {@code visitTryCatchBlock} for
 * its own synthetic block from {@link #visitMaxs}, i.e. only after the entire original body
 * (including all of its own, already-forwarded, {@code visitTryCatchBlock} calls) has been
 * visited — so in the finished exception table, every original handler is checked first and ours
 * is the catch-all of last resort, exactly matching what {@code javac} itself would emit if the
 * original method's own {@code try/finally} were the outermost block instead of a synthetic one.
 *
 * <h2>Why {@code COMPUTE_FRAMES} is required for this specific transformation</h2>
 *
 * Introducing a brand-new exception handler creates a brand-new control-flow merge point (the
 * handler's entry, where the JVM verifier must know the exact operand-stack/local-variable state
 * regardless of which instruction inside the protected region threw) that the original bytecode
 * never had a stack-map frame for. Computing that frame requires knowing the merged type at that
 * point, which for our injected code is always trivial — see {@code AgentBridge}'s class javadoc
 * for why the span-token local is typed {@code java/lang/Object} rather than {@code Span}, which
 * is precisely so this frame computation never needs to resolve an application/agent-classloader
 * type-hierarchy question at all. See {@link CastellanClassFileTransformer}'s custom {@code
 * ClassWriter} for the other half of the {@code COMPUTE_FRAMES} story: resolving common
 * supertypes for the <em>original</em> method body's own merge points, which does require
 * classloader awareness.
 */
final class SpanWeavingMethodVisitor extends AdviceAdapter {

    private static final Type AGENT_BRIDGE_TYPE = Type.getObjectType("io/castellan/apm/core/AgentBridge");
    private static final Type OBJECT_TYPE = Type.getType(Object.class);
    private static final Type STRING_TYPE = Type.getType(String.class);
    private static final Type THROWABLE_TYPE = Type.getType(Throwable.class);
    private static final Type HTTP_SERVLET_REQUEST_TYPE = Type.getObjectType("jakarta/servlet/http/HttpServletRequest");
    private static final Type HTTP_URL_CONNECTION_TYPE = Type.getObjectType("java/net/HttpURLConnection");

    private static final Method START = Method.getMethod("Object start(String, String)");
    private static final Method START_WITH_REMOTE_PARENT = Method.getMethod("Object startWithRemoteParent(String, String, String)");
    private static final Method SET_ATTRIBUTE = Method.getMethod("void setAttribute(Object, String, String)");
    private static final Method CURRENT_TRACEPARENT = Method.getMethod("String currentTraceparent()");
    private static final Method END = Method.getMethod("void end(Object, Throwable)");
    private static final Method GET_HEADER = Method.getMethod("String getHeader(String)");
    private static final Method SET_REQUEST_PROPERTY = Method.getMethod("void setRequestProperty(String, String)");

    private final MethodWeavingPlan plan;
    private final Label tryStart = new Label();
    private final Label tryEnd = new Label();
    private final Label handler = new Label();
    private int spanLocal = -1;

    SpanWeavingMethodVisitor(int api, org.objectweb.asm.MethodVisitor mv, int access, String name, String descriptor, MethodWeavingPlan plan) {
        super(api, mv, access, name, descriptor);
        this.plan = plan;
    }

    @Override
    protected void onMethodEnter() {
        int headerLocal = -1;
        if (plan.httpServletRequestArgIndex() >= 0) {
            headerLocal = newLocal(STRING_TYPE);
            loadArg(plan.httpServletRequestArgIndex());
            push("traceparent");
            invokeInterface(HTTP_SERVLET_REQUEST_TYPE, GET_HEADER);
            storeLocal(headerLocal);
        }

        push(plan.spanKind().name());
        push(plan.spanName());
        if (headerLocal >= 0) {
            loadLocal(headerLocal);
            invokeStatic(AGENT_BRIDGE_TYPE, START_WITH_REMOTE_PARENT);
        } else {
            invokeStatic(AGENT_BRIDGE_TYPE, START);
        }
        spanLocal = newLocal(OBJECT_TYPE);
        storeLocal(spanLocal);

        for (MethodWeavingPlan.AttributeCapture capture : plan.attributeCaptures()) {
            loadLocal(spanLocal);
            push(capture.attributeKey());
            loadArg(capture.argIndex());
            invokeStatic(AGENT_BRIDGE_TYPE, SET_ATTRIBUTE);
        }

        if (plan.injectOutboundTraceparentHeader()) {
            int traceparentLocal = newLocal(STRING_TYPE);
            invokeStatic(AGENT_BRIDGE_TYPE, CURRENT_TRACEPARENT);
            storeLocal(traceparentLocal);
            loadLocal(traceparentLocal);
            Label skip = new Label();
            ifNull(skip);
            loadThis();
            push("traceparent");
            loadLocal(traceparentLocal);
            invokeVirtual(HTTP_URL_CONNECTION_TYPE, SET_REQUEST_PROPERTY);
            mark(skip);
        }

        // Everything above must run before the protected region begins: the try block covers only
        visitLabel(tryStart);
    }

    @Override
    protected void onMethodExit(int opcode) {
        if (opcode == ATHROW) {
            return;
        }
        loadLocal(spanLocal);
        visitInsn(Opcodes.ACONST_NULL);
        invokeStatic(AGENT_BRIDGE_TYPE, END);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        visitLabel(tryEnd);
        visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
        visitLabel(handler);
        int exceptionLocal = newLocal(THROWABLE_TYPE);
        storeLocal(exceptionLocal);
        loadLocal(spanLocal);
        loadLocal(exceptionLocal);
        invokeStatic(AGENT_BRIDGE_TYPE, END);
        loadLocal(exceptionLocal);
        visitInsn(Opcodes.ATHROW);
        super.visitMaxs(maxStack, maxLocals);
    }
}
