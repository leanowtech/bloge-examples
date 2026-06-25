package com.leanowtech.bloge.gateway.example;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API that powers the Resource Gateway Showcase browser UI.
 *
 * <p>The endpoints are read-only and example-oriented. They expose scenario
 * metadata and seeded visual layouts while preserving the existing public
 * gateway execution endpoints as the only way the browser actually runs a
 * graph.</p>
 */
@RestController
@RequestMapping("/api/gateway/examples")
public class GatewayExampleController {

    private final GatewayExampleCatalog catalog;

    /**
     * Creates a controller backed by the built-in example catalog.
     *
     * @param catalog scenario catalog
     */
    public GatewayExampleController(GatewayExampleCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * Lists all resource-gateway scenarios available in the showcase.
     *
     * @return scenario metadata in showcase order
     */
    @GetMapping("/scenarios")
    public List<GatewayExampleScenario> scenarios() {
        return catalog.scenarios();
    }

    /**
     * Loads one resource-gateway scenario.
     *
     * @param graphName graph name such as {@code userDashboard}
     * @return scenario metadata
     */
    @GetMapping("/scenarios/{graphName}")
    public GatewayExampleScenario scenario(@PathVariable String graphName) {
        return catalog.scenario(graphName)
                .orElseThrow(() -> new ScenarioNotFoundException(graphName));
    }

    /**
     * Loads the visual layout for one resource-gateway scenario.
     *
     * @param graphName graph name such as {@code userDashboard}
     * @return seeded visual layout
     */
    @GetMapping("/scenarios/{graphName}/diagram")
    public ExampleVisualLayout diagram(@PathVariable String graphName) {
        return catalog.diagram(graphName)
                .orElseThrow(() -> new ScenarioNotFoundException(graphName));
    }

    /**
     * 404 exception for unknown example scenarios.
     */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    private static final class ScenarioNotFoundException extends RuntimeException {
        private ScenarioNotFoundException(String graphName) {
            super("Gateway example scenario not found: " + graphName);
        }
    }
}
