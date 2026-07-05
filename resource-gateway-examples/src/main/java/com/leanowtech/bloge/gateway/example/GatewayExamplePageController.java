package com.leanowtech.bloge.gateway.example;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves browser entry points at clean URLs while the concrete assets stay under static resources.
 */
@Controller
public class GatewayExamplePageController {

    /**
     * Forwards the clean showcase route to the static browser artifact.
     *
     * @return static resource forward target
     */
    @GetMapping({"/examples/gateway", "/examples/gateway/"})
    public String gatewayShowcase() {
        return "forward:/examples/gateway/index.html";
    }

    /**
     * Forwards the React authoring workspace route to its Vite-built SPA entry point.
     *
     * @return static resource forward target
     */
    @GetMapping({"/author", "/author/"})
    public String authorCanvas() {
        return "forward:/author/index.html";
    }

    /**
     * Forwards the React showcase route to its Vite-built SPA entry point.
     *
     * @return static resource forward target
     */
    @GetMapping({"/showcase", "/showcase/"})
    public String reactShowcase() {
        return "forward:/showcase/index.html";
    }
}
