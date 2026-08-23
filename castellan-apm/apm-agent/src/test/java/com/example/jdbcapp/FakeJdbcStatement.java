package com.example.jdbcapp;

/**
 * A deliberately tiny stand-in for {@code java.sql.Statement}/{@code PreparedStatement} — see
 * {@code io.castellan.apm.agent.weave.jdbc.JdbcInstrumentationRule}'s javadoc for why the rule's
 * target-interface set is a constructor parameter and tests inject a small stand-in like this one
 * rather than hand-implementing the ~70-method real JDBC interfaces. Lives outside the {@code
 * io.castellan.apm} namespace deliberately, like every other fixture in this test tree — it stands
 * in for "a target application's own code," and {@link
 * io.castellan.apm.agent.weave.CastellanClassFileTransformer} itself skips the agent's own
 * namespace, so a fixture placed under {@code io.castellan.apm} would never even reach the
 * transformer. {@code JdbcInstrumentationRuleTest} configures the rule with this interface's
 * internal name instead of {@code java/sql/Statement}, exercising the exact same matching and
 * weaving code path production uses.
 */
public interface FakeJdbcStatement {

    String execute(String sql);

    int executeUpdate(String sql);
}
