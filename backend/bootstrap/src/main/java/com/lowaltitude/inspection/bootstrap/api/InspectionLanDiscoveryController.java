package com.lowaltitude.inspection.bootstrap.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class InspectionLanDiscoveryController {

    @GetMapping("/api/v1/inspection/discovery")
    public Map<String, String> identify() {
        return Map.of(
                "service", "low-altitude-inspection",
                "contract", "inspection-lan-discovery-v1"
        );
    }
}
