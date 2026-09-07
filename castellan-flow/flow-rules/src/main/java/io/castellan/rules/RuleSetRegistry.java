package io.castellan.rules;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deploys and resolves versioned {@link RuleSet}s, and hands out fresh {@link RuleEngine} sessions
 * against a specific (or the latest) version.
 *
 * <p>Deploying under a name that already has a version never overwrites it — it always appends a
 * new, higher version number, and every earlier version remains resolvable by number forever
 * (until process restart; this registry is in-memory — {@code flow-engine} is what makes rule set
 * deployments durable, the same way it makes BPMN process definition deployments durable). A
 * caller that captured a specific version number (e.g. a BPMN service task that resolved "the
 * fraud-rules rule set, version 3" the first time it ran) keeps evaluating against that exact
 * {@link Rule} list forever, regardless of how many newer versions get deployed later — deploying
 * v4 has zero effect on anyone still holding v3.
 */
public final class RuleSetRegistry {

    private final Map<String, NavigableMap<Integer, RuleSet>> versionsByName = new ConcurrentHashMap<>();

    public synchronized RuleSet deploy(String name, List<Rule> rules) {
        NavigableMap<Integer, RuleSet> byVersion =
                versionsByName.computeIfAbsent(name, k -> new TreeMap<>());
        int nextVersion = byVersion.isEmpty() ? 1 : byVersion.lastKey() + 1;
        RuleSet ruleSet = new RuleSet(name, nextVersion, rules);
        byVersion.put(nextVersion, ruleSet);
        return ruleSet;
    }

    public Optional<RuleSet> latest(String name) {
        NavigableMap<Integer, RuleSet> byVersion = versionsByName.get(name);
        if (byVersion == null || byVersion.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(byVersion.lastEntry().getValue());
    }

    public Optional<RuleSet> version(String name, int version) {
        NavigableMap<Integer, RuleSet> byVersion = versionsByName.get(name);
        if (byVersion == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byVersion.get(version));
    }

    public RuleEngine newSession(String name) {
        RuleSet ruleSet = latest(name)
                .orElseThrow(() -> new IllegalArgumentException("no rule set deployed under name: " + name));
        return new RuleEngine(ruleSet);
    }

    public RuleEngine newSession(String name, int version) {
        RuleSet ruleSet = version(name, version)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no such rule set version: " + name + " v" + version));
        return new RuleEngine(ruleSet);
    }
}
