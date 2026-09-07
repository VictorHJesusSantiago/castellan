package io.castellan.rules;

import java.util.List;

/**
 * A named, versioned, immutable collection of compiled {@link Rule}s. Mirrors the versioning
 * model {@code flow-engine} needs for BPMN process definitions: a rule set is deployed under a
 * name, each deployment mints a new integer version, and callers that resolved a specific version
 * (typically: a process instance, at the moment it first invoked this rule set) stay pinned to
 * that exact {@link Rule} list forever, even after a newer version is deployed under the same
 * name. See {@link RuleSetRegistry} for the deploy/resolve API that provides this.
 */
public final class RuleSet {

    private final String name;
    private final int version;
    private final List<Rule> rules;

    RuleSet(String name, int version, List<Rule> rules) {
        this.name = name;
        this.version = version;
        this.rules = List.copyOf(rules);
    }

    public String name() {
        return name;
    }

    public int version() {
        return version;
    }

    public List<Rule> rules() {
        return rules;
    }

    @Override
    public String toString() {
        return "RuleSet[" + name + " v" + version + ", " + rules.size() + " rules]";
    }
}
