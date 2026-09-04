package io.castellan.apm.collector.servicemap;

import java.util.List;

public record ServiceMapView(List<ServiceMapNode> nodes, List<ServiceMapEdge> edges) {

    public ServiceMapView {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
