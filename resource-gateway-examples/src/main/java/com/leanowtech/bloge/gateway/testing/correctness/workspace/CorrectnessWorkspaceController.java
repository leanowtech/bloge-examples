package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated metadata-only BFF for the Correctness Studio authoring workspace. */
@RestController
@ConditionalOnBean(CorrectnessWorkspaceQuery.class)
@RequestMapping("/api/visual/correctness-workspaces")
public final class CorrectnessWorkspaceController {

    private final CorrectnessWorkspaceQuery query;
    private final IntegrationRequestAuthenticator authenticator;

    public CorrectnessWorkspaceController(
            CorrectnessWorkspaceQuery query,
            IntegrationRequestAuthenticator authenticator
    ) {
        this.query = query;
        this.authenticator = authenticator;
    }

    @GetMapping("/{targetKind}/{targetId}")
    public CorrectnessApiEnvelope<CorrectnessWorkspaceProjection> get(
            @PathVariable TargetKind targetKind,
            @PathVariable String targetId,
            @RequestParam String targetFingerprint,
            @RequestParam(defaultValue = "") String definitionId,
            @RequestParam(defaultValue = "") String caseCursor,
            @RequestParam(defaultValue = "100") int caseLimit,
            @RequestHeader HttpHeaders headers
    ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_WORKSPACE_READ);
        CorrectnessWorkspaceProjection projection = query.get(
                targetKind, targetId, targetFingerprint, definitionId,
                caseCursor, caseLimit, identity);
        EnterpriseScope scope = new EnterpriseScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
        return CorrectnessApiEnvelope.of(
                identity.correlationId(), scope, projection.capabilities(), projection);
    }
}
