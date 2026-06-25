package com.leanowtech.bloge.gateway.example;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Resource Gateway Showcase entry point at a clean example URL.
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
}
