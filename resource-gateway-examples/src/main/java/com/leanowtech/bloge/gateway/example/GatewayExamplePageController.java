package com.leanowtech.bloge.gateway.example;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves browser entry points at clean URLs while the concrete assets stay under static resources.
 */
@Controller
public class GatewayExamplePageController {

    /**
     * Preserves the workspace directory in the browser URL so relative Vite assets resolve correctly.
     *
     * @return canonical workspace redirect target
     */
    @GetMapping("/capabilities")
    public String capabilityStudioRedirect() {
        return "redirect:/capabilities/";
    }

    /**
     * Selects the packaged React Launcher for the opted-in 1.3.0 spine coordinate while
     * retaining Capability Studio as the default root workspace for legacy callers.
     *
     * @param spine optional launcher generation selector; only {@code v1} opts in
     * @return the Launcher static entry point or the legacy Capability Studio redirect
     */
    @GetMapping("/")
    public String rootWorkspace(@RequestParam(name = "spine", required = false) String spine) {
        return "v1".equals(spine) ? "forward:/index.html" : "redirect:/capabilities/";
    }

    /**
     * Serves Capability Studio as the default product workspace.
     *
     * @return static resource forward target
     */
    @GetMapping("/capabilities/")
    public String capabilityStudio() {
        return "forward:/capabilities/index.html";
    }

    /**
     * Serves Business Mirror as the default product workspace at its canonical URL.
     *
     * @return static resource forward target
     */
    @GetMapping({"/business-mirror", "/business-mirror/"})
    public String businessMirrorWorkspace() {
        return "forward:/business-mirror/index.html";
    }

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
     * Forwards the first-class Correctness Studio route to the shared Vite-built SPA.
     *
     * @return static resource forward target
     */
    @GetMapping({"/correctness", "/correctness/"})
    public String correctnessStudio() {
        return "forward:/correctness/index.html";
    }

    /**
     * Forwards the progressive operator-library Workbench route to the shared Vite SPA.
     *
     * @return static resource forward target
     */
    @GetMapping({"/libraries", "/libraries/"})
    public String libraryWorkbench() {
        return "forward:/libraries/index.html";
    }

    /**
     * Forwards the Scenario Owner workbench route to the shared Vite-built SPA.
     *
     * @return static resource forward target
     */
    @GetMapping({"/rehearsals", "/rehearsals/"})
    public String rehearsalWorkbench() {
        return "forward:/rehearsals/index.html";
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
