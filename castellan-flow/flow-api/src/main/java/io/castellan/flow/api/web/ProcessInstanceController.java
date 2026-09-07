package io.castellan.flow.api.web;

import io.castellan.flow.api.web.dto.CompensateRequest;
import io.castellan.flow.api.web.dto.ProcessInstanceResponse;
import io.castellan.flow.api.web.dto.SignalRequest;
import io.castellan.flow.api.web.dto.StartInstanceRequest;
import io.castellan.flow.engine.ProcessEngine;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ProcessInstanceController {

    private final ProcessEngine engine;

    public ProcessInstanceController(ProcessEngine engine) {
        this.engine = engine;
    }

    /** Starts a new instance against the latest deployed version of {@code processId}, or a
     * specific one if {@code version} is given (mirrors {@link ProcessEngine}'s two overloads). */
    @PostMapping("/process-definitions/{processId}/instances")
    public ResponseEntity<ProcessInstanceResponse> start(
            @PathVariable String processId,
            @RequestParam(required = false) Integer version,
            @RequestBody(required = false) StartInstanceRequest request) {
        Map<String, Object> variables = request == null ? Map.of() : request.variables();
        var record = version == null
                ? engine.startInstance(processId, variables)
                : engine.startInstance(processId, version, variables);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProcessInstanceResponse.of(record));
    }

    @GetMapping("/instances/{id}")
    public ProcessInstanceResponse get(@PathVariable String id) {
        return ProcessInstanceResponse.of(engine.getInstance(id));
    }

    /** Delivers an external signal to a specific waiting token -- see {@code tokens} in a prior
     * {@link #get} response for its id. */
    @PostMapping("/instances/{id}/signal")
    public ProcessInstanceResponse signal(@PathVariable String id, @Valid @RequestBody SignalRequest request) {
        return ProcessInstanceResponse.of(engine.signal(id, request.tokenId(), request.variables()));
    }

    @PostMapping("/instances/{id}/compensate")
    public ProcessInstanceResponse compensate(@PathVariable String id, @Valid @RequestBody CompensateRequest request) {
        return ProcessInstanceResponse.of(engine.compensate(id, request.activityId()));
    }
}
