package io.castellan.apm.collector.latency;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LatencyController {

    private final LatencyService service;

    public LatencyController(LatencyService service) {
        this.service = service;
    }

    @GetMapping("/operations/{name}/latency")
    public LatencyPercentiles latency(@PathVariable String name, @RequestParam(defaultValue = "1000") int sampleLimit) {
        return service.forOperation(name, sampleLimit);
    }
}
