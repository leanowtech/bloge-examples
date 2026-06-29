package com.leanowtech.bloge.gateway.example;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for the browser-side custom graph composer.
 */
@RestController
@RequestMapping("/api/gateway/examples/compose")
public class DynamicGatewayComposerController {

    private final DynamicGatewayComposerService composerService;

    /**
     * Creates a dynamic composer controller.
     *
     * @param composerService dynamic compilation and execution service
     */
    public DynamicGatewayComposerController(DynamicGatewayComposerService composerService) {
        this.composerService = composerService;
    }

    /**
     * Compiles and runs one browser-submitted gateway graph.
     *
     * @param request DSL source, context, and optional output-node hint
     * @return compile diagnostics, generated layout, decision table metadata, and output
     */
    @PostMapping("/run")
    public DynamicGraphRunResponse run(@RequestBody DynamicGraphRunRequest request) {
        return composerService.run(request);
    }
}
