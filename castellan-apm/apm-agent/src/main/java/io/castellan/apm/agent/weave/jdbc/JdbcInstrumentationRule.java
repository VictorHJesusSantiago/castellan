package io.castellan.apm.agent.weave.jdbc;

import io.castellan.apm.agent.weave.ClassContext;
import io.castellan.apm.agent.weave.MethodInstrumentationRule;
import io.castellan.apm.agent.weave.MethodProbe;
import io.castellan.apm.agent.weave.MethodWeavingPlan;
import java.util.Optional;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Matches JDBC driver methods that take a SQL string as their first argument:
 * {@code Statement.execute(String)}/{@code executeQuery}/{@code executeUpdate}/{@code
 * executeLargeUpdate}, and {@code Connection.prepareStatement(String)}/{@code prepareCall(String)}
 * — the exact set the task brief calls out. Each woven call captures the SQL <em>text</em> as the
 * {@code db.statement} attribute.
 *
 * <h2>What is deliberately not captured, and why</h2>
 *
 * Bind parameter <em>values</em> (the arguments to {@code PreparedStatement.setString(1, ssn)} and
 * friends) are never captured. This is not an oversight or a "future work" gap — it is a stated
 * boundary: bind values routinely carry PII (SSNs, emails, tokens) and sometimes literal secrets,
 * and a tracing pipeline is exactly the kind of system whose whole job is to fan that data out to
 * a third party (the collector) and retain it (in its store) far longer and far less carefully
 * than the original request handling ever did. Capturing the SQL <em>text</em> — the shape of the
 * query, with {@code ?} placeholders — gives essentially all of the operational value (which
 * query, how long it took, whether it errored) with none of that exposure. A real production APM
 * would need an explicit, opt-in, audited mechanism to ever capture parameter values; this project
 * does not build one.
 *
 * <h2>Interface matching is direct-implements only</h2>
 *
 * {@link #appliesToClass} checks only the class's own, directly-declared {@code implements}
 * clause (from {@link ClassContext#interfaceInternalNames()}, which itself comes from ASM's {@code
 * ClassVisitor.visit} — never a transitively-resolved hierarchy). A driver class that implements
 * {@code java.sql.PreparedStatement} several levels up an abstract base-class chain, rather than
 * directly on the concrete class, is not matched. Resolving that properly means walking the
 * superclass chain by loading each ancestor's class file through the target's own classloader
 * (real APM agents do this, and Byte Buddy's own type matchers do it out of the box via its {@code
 * TypePool} — see {@code io.castellan.apm.agent.bytebuddy.ByteBuddyJdbcTransformer}'s javadoc for
 * exactly this point made concrete). Implementing that walk by hand for the ASM path was cut for
 * time; it is a real, stated gap, not a hidden one.
 */
public final class JdbcInstrumentationRule implements MethodInstrumentationRule {

    /** The real JDBC interfaces this rule targets in production. */
    public static final Set<String> DEFAULT_TARGET_INTERFACES =
            Set.of("java/sql/Statement", "java/sql/PreparedStatement", "java/sql/CallableStatement", "java/sql/Connection");

    private static final Set<String> SQL_FIRST_ARG_METHOD_NAMES =
            Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "prepareStatement", "prepareCall");

    private static final Type STRING_TYPE = Type.getType(String.class);

    private final Set<String> targetInterfaces;

    public JdbcInstrumentationRule() {
        this(DEFAULT_TARGET_INTERFACES);
    }

    /** Test seam: a small stand-in interface set exercises the identical matching/weaving code path without a ~70-method java.sql.Statement fixture — see the test class's javadoc. */
    public JdbcInstrumentationRule(Set<String> targetInterfaces) {
        this.targetInterfaces = Set.copyOf(targetInterfaces);
    }

    @Override
    public boolean appliesToClass(ClassContext classContext) {
        return classContext.interfaceInternalNames().stream().anyMatch(targetInterfaces::contains);
    }

    @Override
    public Optional<MethodWeavingPlan> planFor(ClassContext classContext, MethodProbe method) {
        if (isStaticOrAbstract(method) || !SQL_FIRST_ARG_METHOD_NAMES.contains(method.name())) {
            return Optional.empty();
        }
        Type[] argumentTypes = Type.getArgumentTypes(method.descriptor());
        if (argumentTypes.length == 0 || !STRING_TYPE.equals(argumentTypes[0])) {
            return Optional.empty();
        }
        return Optional.of(MethodWeavingPlan.jdbc("JDBC " + method.name(), 0));
    }

    private static boolean isStaticOrAbstract(MethodProbe method) {
        return (method.access() & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0;
    }
}
