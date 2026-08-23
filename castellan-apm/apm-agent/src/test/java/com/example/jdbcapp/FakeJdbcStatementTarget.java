package com.example.jdbcapp;

/** {@code execute} returns normally; {@code executeUpdate} always throws — exercises both exit paths {@link io.castellan.apm.agent.weave.SpanWeavingMethodVisitor} must close a span on. */
public final class FakeJdbcStatementTarget implements FakeJdbcStatement {

    @Override
    public String execute(String sql) {
        return "executed:" + sql;
    }

    @Override
    public int executeUpdate(String sql) {
        throw new IllegalStateException("boom: " + sql);
    }
}
