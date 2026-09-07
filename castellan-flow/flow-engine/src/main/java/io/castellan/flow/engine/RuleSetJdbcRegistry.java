package io.castellan.flow.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JDBC-backed, versioned storage for JSON-declared rule sets — see {@link JsonRuleDefinition}
 * for the "why data, not compiled Rule objects" reasoning. Versioning semantics mirror {@link
 * ProcessDefinitionRepository} exactly. */
public final class RuleSetJdbcRegistry {

    private static final TypeReference<List<JsonRuleDefinition>> RULE_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RuleSetJdbcRegistry(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public synchronized RuleSetDefinitionRecord deploy(String name, List<JsonRuleDefinition> rules) {
        Integer maxVersion = jdbc.queryForObject(
                "SELECT MAX(version) FROM rule_sets WHERE name = ?", Integer.class, name);
        int nextVersion = (maxVersion == null ? 0 : maxVersion) + 1;
        Instant now = clock.instant();
        jdbc.update("INSERT INTO rule_sets (name, version, rules_json, deployed_at) VALUES (?,?,?,?)",
                name, nextVersion, writeRules(rules), Timestamp.from(now));
        return new RuleSetDefinitionRecord(name, nextVersion, rules, now);
    }

    public Optional<RuleSetDefinitionRecord> latest(String name) {
        return jdbc.query("SELECT name, version, rules_json, deployed_at FROM rule_sets "
                        + "WHERE name = ? ORDER BY version DESC LIMIT 1",
                RuleSetJdbcRegistry::map, name).stream().findFirst();
    }

    public Optional<RuleSetDefinitionRecord> version(String name, int version) {
        return jdbc.query("SELECT name, version, rules_json, deployed_at FROM rule_sets WHERE name = ? AND version = ?",
                RuleSetJdbcRegistry::map, name, version).stream().findFirst();
    }

    private static RuleSetDefinitionRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new RuleSetDefinitionRecord(rs.getString("name"), rs.getInt("version"),
                readRules(rs.getString("rules_json")), rs.getTimestamp("deployed_at").toInstant());
    }

    private static String writeRules(List<JsonRuleDefinition> rules) {
        try {
            return JsonSupport.MAPPER.writeValueAsString(rules);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize rule set", e);
        }
    }

    private static List<JsonRuleDefinition> readRules(String json) {
        try {
            return JsonSupport.MAPPER.readValue(json, RULE_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize rule set", e);
        }
    }
}
