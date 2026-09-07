package io.castellan.bpmn.model;

/**
 * A {@code <scriptTask>}. The script body (the element's text content) is interpreted as
 * semicolon-separated variable assignments, {@code ident = expression;}, evaluated with {@link
 * io.castellan.bpmn.expr.ExpressionEvaluator} against the current process variables — the same
 * expression subset used for sequence-flow conditions (see that class's javadoc for exactly what
 * it supports). This is a deliberately narrow stand-in for a real scripting language (no
 * loops/branches/function calls): enough to let a fixture compute a derived variable without
 * pulling in a JS/Groovy engine.
 */
public final class ScriptTask extends Task {

    private final String script;

    public ScriptTask(String id, String name, String script) {
        super(id, name);
        this.script = script == null ? "" : script;
    }

    public String script() {
        return script;
    }
}
