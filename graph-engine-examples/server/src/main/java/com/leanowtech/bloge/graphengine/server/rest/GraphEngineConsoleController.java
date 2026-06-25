package com.leanowtech.bloge.graphengine.server.rest;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the graph-engine browser console entry point.
 *
 * <p>The console is a static, read-mostly example UI that consumes the existing
 * REST control-plane APIs. It does not introduce a second authoring or runtime
 * model; all graph semantics still come from stored DSL, compiled metadata, and
 * the runtime projection endpoints.</p>
 */
@Controller
public class GraphEngineConsoleController {

    /**
     * Forwards clean console routes to the packaged static UI.
     *
     * @return static resource forward target
     */
    @GetMapping({
            "/console",
            "/console/",
            "/console/graphs",
            "/console/instances",
            "/console/deployments",
            "/console/operators",
            "/console/authoring",
            "/console/tasks"
    })
    public String console() {
        return "forward:/console/index.html";
    }
}
