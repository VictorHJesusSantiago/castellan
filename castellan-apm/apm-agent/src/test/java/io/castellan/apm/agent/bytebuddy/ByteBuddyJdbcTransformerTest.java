package io.castellan.apm.agent.bytebuddy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.jdbcapp.FakeJdbcStatement;
import com.example.jdbcapp.FakeJdbcStatementTarget;
import io.castellan.apm.agent.weave.CapturingExporter;
import io.castellan.apm.core.SpanData;
import io.castellan.apm.core.SpanKind;
import io.castellan.apm.core.SpanStatus;
import java.lang.instrument.Instrumentation;
import java.util.List;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The Byte Buddy comparison path, exercised through a <em>real</em> {@code -javaagent}-style
 * attach — {@link ByteBuddyAgent#install()} self-attaches to the currently running test JVM and
 * hands back a real {@link Instrumentation}, which is exactly what {@code CastellanAgent.premain}
 * would receive from the JVM itself. This is the "strong addition" beyond the required minimum
 * (direct {@code transform()} calls, used throughout the {@code weave} package's tests): it proves
 * {@link ByteBuddyJdbcTransformer#install} retransforms an *already-loaded* class correctly, not
 * just a freshly-defined one.
 */
class ByteBuddyJdbcTransformerTest {

    private ResettableClassFileTransformer installedTransformer;

    @AfterEach
    void uninstall() {
        if (installedTransformer != null) {
            installedTransformer.reset(ByteBuddyAgent.install(), AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
        }
    }

    @Test
    void retransformingAnAlreadyLoadedClassWeavesItInPlace() throws Exception {
        Class.forName(FakeJdbcStatementTarget.class.getName());

        CapturingExporter exporter = new CapturingExporter().installAsActiveTracer();
        Instrumentation instrumentation = ByteBuddyAgent.install();
        installedTransformer = ByteBuddyJdbcTransformer.install(instrumentation, List.of("com.example.jdbcapp.FakeJdbcStatement"));

        FakeJdbcStatement instance = new FakeJdbcStatementTarget();
        String result = instance.execute("SELECT 1");

        assertThat(result).isEqualTo("executed:SELECT 1");
        assertThat(exporter.exported).hasSize(1);
        SpanData span = exporter.exported.get(0);
        assertThat(span.kind()).isEqualTo(SpanKind.CLIENT);
        assertThat(span.name()).contains("execute");
        assertThat(span.status()).isEqualTo(SpanStatus.OK);
        assertThat(span.attributes()).containsEntry("db.statement", "SELECT 1");
    }

    @Test
    void retransformedMethodStillReportsAnErrorSpanWhenItThrows() throws Exception {
        Class.forName(FakeJdbcStatementTarget.class.getName());
        CapturingExporter exporter = new CapturingExporter().installAsActiveTracer();
        Instrumentation instrumentation = ByteBuddyAgent.install();
        installedTransformer = ByteBuddyJdbcTransformer.install(instrumentation, List.of("com.example.jdbcapp.FakeJdbcStatement"));

        FakeJdbcStatement instance = new FakeJdbcStatementTarget();

        assertThatThrownBy(() -> instance.executeUpdate("DELETE FROM x")).isInstanceOf(IllegalStateException.class);

        assertThat(exporter.exported).hasSize(1);
        assertThat(exporter.exported.get(0).status()).isEqualTo(SpanStatus.ERROR);
    }
}
