package com.leanowtech.bloge.gateway.visual.connection;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public API for interactive visual canvas connection checks.
 */
@RestController
@RequestMapping("/api/visual/connections")
public class VisualConnectionController {

    private final VisualConnectionCheckService service;

    /**
     * @param service connection check service
     */
    public VisualConnectionController(VisualConnectionCheckService service) {
        this.service = service;
    }

    /**
     * Checks whether a proposed connection can be applied to the current draft.
     *
     * @param request connection check request
     * @return connection check result
     */
    @PostMapping("/check")
    public VisualConnectionCheckResult check(@RequestBody VisualConnectionCheckRequest request) {
        return service.check(request);
    }
}
