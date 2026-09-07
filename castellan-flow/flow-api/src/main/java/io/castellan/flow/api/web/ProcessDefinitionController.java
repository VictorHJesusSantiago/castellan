package io.castellan.flow.api.web;

import io.castellan.flow.api.web.dto.DeployProcessRequest;
import io.castellan.flow.api.web.dto.ProcessDefinitionResponse;
import io.castellan.flow.engine.ProcessDefinitionRepository;
import io.castellan.flow.engine.ProcessEngine;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/process-definitions")
public class ProcessDefinitionController {

    private final ProcessEngine engine;
    private final ProcessDefinitionRepository definitions;

    public ProcessDefinitionController(ProcessEngine engine, ProcessDefinitionRepository definitions) {
        this.engine = engine;
        this.definitions = definitions;
    }

    /** Deploying always adds a new, higher version -- it never overwrites an existing one, and
     * any instance already running against an earlier version stays pinned to it (see {@link
     * ProcessEngine}'s own docs). */
    @PostMapping
    public ResponseEntity<ProcessDefinitionResponse> deploy(@Valid @RequestBody DeployProcessRequest request) {
        var record = engine.deployDefinition(request.processId(), request.bpmnXml());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProcessDefinitionResponse.of(record));
    }

    @GetMapping("/{processId}")
    public ResponseEntity<ProcessDefinitionResponse> latest(@PathVariable String processId) {
        return definitions.latest(processId)
                .map(ProcessDefinitionResponse::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{processId}/versions/{version}")
    public ResponseEntity<ProcessDefinitionResponse> version(@PathVariable String processId, @PathVariable int version) {
        return definitions.version(processId, version)
                .map(ProcessDefinitionResponse::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
