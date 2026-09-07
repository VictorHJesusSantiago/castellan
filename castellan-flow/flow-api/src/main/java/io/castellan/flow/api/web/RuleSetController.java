package io.castellan.flow.api.web;

import io.castellan.flow.api.web.dto.DeployRuleSetRequest;
import io.castellan.flow.api.web.dto.RuleDefinitionDto;
import io.castellan.flow.api.web.dto.RuleSetResponse;
import io.castellan.flow.engine.JsonRuleDefinition;
import io.castellan.flow.engine.RuleSetJdbcRegistry;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/rule-sets")
public class RuleSetController {

    private final RuleSetJdbcRegistry ruleSets;

    public RuleSetController(RuleSetJdbcRegistry ruleSets) {
        this.ruleSets = ruleSets;
    }

    @PostMapping
    public ResponseEntity<RuleSetResponse> deploy(@Valid @RequestBody DeployRuleSetRequest request) {
        List<JsonRuleDefinition> rules = request.rules().stream().map(RuleDefinitionDto::toDomain).toList();
        var record = ruleSets.deploy(request.name(), rules);
        return ResponseEntity.status(HttpStatus.CREATED).body(RuleSetResponse.of(record));
    }

    @GetMapping("/{name}")
    public ResponseEntity<RuleSetResponse> latest(@PathVariable String name) {
        return ruleSets.latest(name)
                .map(RuleSetResponse::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
