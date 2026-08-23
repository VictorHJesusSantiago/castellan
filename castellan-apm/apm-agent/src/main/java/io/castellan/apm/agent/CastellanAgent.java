package io.castellan.apm.agent;

import io.castellan.apm.agent.bytebuddy.ByteBuddyJdbcTransformer;
import io.castellan.apm.agent.weave.CastellanClassFileTransformer;
import io.castellan.apm.agent.weave.MethodInstrumentationRule;
import io.castellan.apm.agent.weave.http.HttpUrlConnectionInstrumentationRule;
import io.castellan.apm.agent.weave.jdbc.JdbcInstrumentationRule;
import io.castellan.apm.agent.weave.mvc.SpringMvcInstrumentationRule;
import io.castellan.apm.core.AgentBridge;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.HttpURLConnection;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code -javaagent} entry point (matches {@code apm-agent/pom.xml}'s {@code Premain-Class}
 * manifest entry). Three jobs, in order: (1) wire {@code apm-core}'s {@link AgentBridge} to a
 * configured {@link io.castellan.apm.core.Tracer} so every already-woven call site anywhere in the
 * JVM has somewhere real to report to before any class is transformed; (2) register the class
 * transformer (ASM by default, Byte Buddy if {@code transformer=bytebuddy} was passed); (3) ask the
 * JVM to retransform classes that loaded before this agent got a chance to see them at load time —
 * relevant because {@code premain} runs before {@code main}, but not necessarily before every
 * bootstrap/JDK class the target application will eventually use has already loaded.
 */
public final class CastellanAgent {

    private static final Logger log = LoggerFactory.getLogger(CastellanAgent.class);

    private CastellanAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        AgentConfig config = AgentConfig.parse(agentArgs);
        AgentBridge.init(config.buildTracer());

        if (config.useByteBuddy()) {
            ByteBuddyJdbcTransformer.install(inst);
            log.info("Castellan APM agent attached: transformer=bytebuddy(jdbc-only) collectorUrl={} sampleBudget={}/s",
                    config.collectorUrl(), config.sampleBudget());
            return;
        }

        List<MethodInstrumentationRule> rules = List.of(
                new JdbcInstrumentationRule(), new HttpUrlConnectionInstrumentationRule(), new SpringMvcInstrumentationRule());
        CastellanClassFileTransformer transformer = new CastellanClassFileTransformer(rules);
        inst.addTransformer(transformer, true);
        retransformAlreadyLoadedClasses(inst);
        log.info("Castellan APM agent attached: transformer=asm collectorUrl={} sampleBudget={}/s",
                config.collectorUrl(), config.sampleBudget());
    }

    /**
     * Catches classes that were already loaded (typically JDBC driver classes and {@code
     * HttpURLConnection} subclasses pulled in during early JVM/classloader bootstrap) before {@link
     * #premain} registered the transformer above — the manifest's {@code Can-Retransform-Classes:
     * true} entry is what makes this legal at all. Uses real reflection ({@code
     * Class.isAssignableFrom}) rather than the transformer's own direct-implements-only check
     * ({@code JdbcInstrumentationRule}/{@code HttpUrlConnectionInstrumentationRule}'s stated
     * limitation) precisely because reflection on an already-loaded {@link Class} can walk the
     * *actual*, fully-resolved type hierarchy for free — there is no reason to settle for the
     * weaker, ASM-bytecode-level check here. Spring MVC controllers are deliberately not scanned
     * for retransformation: application classes load after the agent attaches in the overwhelming
     * majority of real deployments, so they are caught by the normal load-time transformer path
     * instead.
     */
    private static void retransformAlreadyLoadedClasses(Instrumentation inst) {
        if (!inst.isRetransformClassesSupported()) {
            log.warn("Castellan APM: JVM does not support class retransformation; already-loaded classes will not be instrumented");
            return;
        }
        for (Class<?> loadedClass : inst.getAllLoadedClasses()) {
            if (!inst.isModifiableClass(loadedClass) || !looksInstrumentable(loadedClass)) {
                continue;
            }
            try {
                inst.retransformClasses(loadedClass);
            } catch (UnmodifiableClassException | RuntimeException retransformFailure) {
                log.debug("Castellan APM: could not retransform already-loaded class {}: {}",
                        loadedClass.getName(), retransformFailure.toString());
            }
        }
    }

    private static boolean looksInstrumentable(Class<?> loadedClass) {
        return Statement.class.isAssignableFrom(loadedClass)
                || Connection.class.isAssignableFrom(loadedClass)
                || HttpURLConnection.class.isAssignableFrom(loadedClass);
    }
}
