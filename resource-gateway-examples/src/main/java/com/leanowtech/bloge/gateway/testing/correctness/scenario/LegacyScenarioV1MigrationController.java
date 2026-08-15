package com.leanowtech.bloge.gateway.testing.correctness.scenario;

import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessApiEnvelope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Authenticated, no-store HTTP boundary for payload-bearing Scenario v1 migration previews. */
@RestController
@ConditionalOnBean(LegacyScenarioV1MigrationAdapter.class)
public final class LegacyScenarioV1MigrationController {

    private final LegacyScenarioV1MigrationAdapter adapter;
    private final IntegrationRequestAuthenticator authenticator;

    public LegacyScenarioV1MigrationController(
            LegacyScenarioV1MigrationAdapter adapter,
            IntegrationRequestAuthenticator authenticator
    ) {
        this.adapter = adapter;
        this.authenticator = authenticator;
    }

    @PostMapping("/api/visual/scenario-draft-sets-v2:migrate-v1-preview")
    public ResponseEntity<CorrectnessApiEnvelope<LegacyScenarioV1MigrationPreview>> preview(
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) MigrationRequest request
    ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_SCENARIO_WRITE);
        EnterpriseScope scope = scope(identity);
        try {
            if (request == null || request.legacy() == null || request.exactTarget() == null
                    || request.exactContractRef() == null || request.legacySourceRef() == null
                    || request.defaultOwner() == null) {
                throw new IllegalArgumentException("Complete migration coordinates are required");
            }
            LegacyScenarioV1MigrationPreview result = adapter.preview(
                    request.legacy(), scope, request.exactTarget(), request.exactContractRef(),
                    request.legacySourceRef(), actor(identity), request.defaultOwner());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .body(CorrectnessApiEnvelope.of(
                            identity.correlationId(), scope,
                            List.of("SCENARIO_V1_MIGRATION_PREVIEW_V1"), result));
        } catch (IllegalArgumentException failure) {
            throw new IntegrationProblemException(new IntegrationProblem(
                    "", "urn:bloge:problem:correctness-authoring",
                    "Legacy Scenario migration input failed exact-coordinate validation.",
                    422, "RG.CORRECTNESS.MIGRATION_INPUT_INVALID", false,
                    identity.correlationId(), Map.of()));
        }
    }

    private static EnterpriseScope scope(IntegrationRequestContext identity) {
        return new EnterpriseScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }

    private static PrincipalRef actor(IntegrationRequestContext identity) {
        PrincipalKind kind = switch (identity.actorType()) {
            case "USER" -> PrincipalKind.USER;
            case "TEAM" -> PrincipalKind.TEAM;
            default -> PrincipalKind.SERVICE;
        };
        return new PrincipalRef(identity.actorId(), kind, "");
    }

    public record MigrationRequest(
            ScenarioDraftSet legacy,
            ExactTargetRef exactTarget,
            ExactAssetRef exactContractRef,
            ExactAssetRef legacySourceRef,
            PrincipalRef defaultOwner
    ) {}
}
