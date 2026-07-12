package com.leanowtech.bloge.gateway.integration;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only governance feedback endpoint consumed by the visual authoring surface. */
@RestController
@RequestMapping("/api/visual/governance-gates")
public class GovernanceGateAuthoringController {
    private final ToolStudioIntegrationService service;

    public GovernanceGateAuthoringController(ToolStudioIntegrationService service) {
        this.service = service;
    }

    @GetMapping("/drafts/{draftId}")
    public GovernanceGateView forDraft(@PathVariable String draftId) {
        return service.authoringGovernanceGate(draftId);
    }
}
