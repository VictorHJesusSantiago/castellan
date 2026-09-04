package io.castellan.apm.collector.servicemap;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ServiceMapController {

    private final ServiceMapService service;

    public ServiceMapController(ServiceMapService service) {
        this.service = service;
    }

    @GetMapping("/service-map")
    public ServiceMapView get() {
        return service.build();
    }
}
