package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.AssertionSetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.BusinessOracleRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CoverageInventoryRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Payload-free exact-revision queries used by the Correctness Studio editors. */
@RestController
@ConditionalOnBean({
        CoverageInventoryRepository.class,
        BusinessOracleRepository.class,
        AssertionSetRepository.class,
        ScenarioDraftSetV2Repository.class,
        FixtureAssetRepository.class
})
@RequestMapping("/api/visual")
public final class CorrectnessAuthoringAssetQueryController {

    private final CoverageInventoryRepository inventories;
    private final BusinessOracleRepository oracles;
    private final AssertionSetRepository assertionSets;
    private final ScenarioDraftSetV2Repository scenarios;
    private final FixtureAssetRepository fixtures;
    private final IntegrationRequestAuthenticator authenticator;

    public CorrectnessAuthoringAssetQueryController(
            CoverageInventoryRepository inventories,
            BusinessOracleRepository oracles,
            AssertionSetRepository assertionSets,
            ScenarioDraftSetV2Repository scenarios,
            FixtureAssetRepository fixtures,
            IntegrationRequestAuthenticator authenticator
    ) {
        this.inventories = inventories;
        this.oracles = oracles;
        this.assertionSets = assertionSets;
        this.scenarios = scenarios;
        this.fixtures = fixtures;
        this.authenticator = authenticator;
    }

    @GetMapping("/coverage-inventories/{assetId}")
    public ResponseEntity<CorrectnessApiEnvelope<?>> inventory(
            @PathVariable String assetId,
            @RequestParam(required = false) Long revision,
            @RequestHeader HttpHeaders headers
    ) {
        IntegrationRequestContext identity = authenticate(headers);
        EnterpriseScope scope = scope(identity);
        return response(identity, scope, "COVERAGE_INVENTORY_READ_V1",
                select(revision,
                        () -> inventories.findHead(scope, assetId),
                        value -> inventories.findRevision(scope, assetId, value)),
                assetId);
    }

    @GetMapping("/oracles/{assetId}")
    public ResponseEntity<CorrectnessApiEnvelope<?>> oracle(
            @PathVariable String assetId,
            @RequestParam(required = false) Long revision,
            @RequestHeader HttpHeaders headers
    ) {
        IntegrationRequestContext identity = authenticate(headers);
        EnterpriseScope scope = scope(identity);
        return response(identity, scope, "BUSINESS_ORACLE_READ_V1",
                select(revision,
                        () -> oracles.findHead(scope, assetId),
                        value -> oracles.findRevision(scope, assetId, value)),
                assetId);
    }

    @GetMapping("/assertion-sets/{assetId}")
    public ResponseEntity<CorrectnessApiEnvelope<?>> assertionSet(
            @PathVariable String assetId,
            @RequestParam(required = false) Long revision,
            @RequestHeader HttpHeaders headers
    ) {
        IntegrationRequestContext identity = authenticate(headers);
        EnterpriseScope scope = scope(identity);
        return response(identity, scope, "ASSERTION_SET_READ_V1",
                select(revision,
                        () -> assertionSets.findHead(scope, assetId),
                        value -> assertionSets.findRevision(scope, assetId, value)),
                assetId);
    }

    @GetMapping("/scenario-draft-sets-v2/{assetId}")
    public ResponseEntity<CorrectnessApiEnvelope<?>> scenarioDraftSet(
            @PathVariable String assetId,
            @RequestParam(required = false) Long revision,
            @RequestHeader HttpHeaders headers
    ) {
        IntegrationRequestContext identity = authenticate(headers);
        EnterpriseScope scope = scope(identity);
        return response(identity, scope, "SCENARIO_DRAFT_SET_READ_V2",
                select(revision,
                        () -> scenarios.findHead(scope, assetId),
                        value -> scenarios.findRevision(scope, assetId, value)),
                assetId);
    }

    @GetMapping("/fixture-assets/{assetId}")
    public ResponseEntity<CorrectnessApiEnvelope<?>> fixture(
            @PathVariable String assetId,
            @RequestParam(required = false) Long revision,
            @RequestHeader HttpHeaders headers
    ) {
        IntegrationRequestContext identity = authenticate(headers);
        EnterpriseScope scope = scope(identity);
        return response(identity, scope, "FIXTURE_ASSET_READ_V1",
                select(revision,
                        () -> fixtures.findHead(scope, assetId),
                        value -> fixtures.findRevision(scope, assetId, value)),
                assetId);
    }

    private IntegrationRequestContext authenticate(HttpHeaders headers) {
        return authenticator.authenticate(headers, IntegrationOperation.CORRECTNESS_WORKSPACE_READ);
    }

    private static <T> Optional<T> select(
            Long revision,
            java.util.function.Supplier<Optional<T>> head,
            java.util.function.LongFunction<Optional<T>> exact
    ) {
        if (revision == null) return head.get();
        if (revision < 1) {
            throw problem("RG.CORRECTNESS.REVISION_INVALID",
                    "A positive asset revision is required.", "");
        }
        return exact.apply(revision);
    }

    private static <T> ResponseEntity<CorrectnessApiEnvelope<?>> response(
            IntegrationRequestContext identity,
            EnterpriseScope scope,
            String capability,
            Optional<T> value,
            String assetId
    ) {
        T stored = value.orElseThrow(() -> problem(
                "RG.CORRECTNESS.AUTHORING_ASSET_NOT_FOUND",
                "The exact correctness authoring asset was not found in the authorized scope.",
                identity.correlationId()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(CorrectnessApiEnvelope.of(
                        identity.correlationId(), scope, List.of(capability), stored));
    }

    private static EnterpriseScope scope(IntegrationRequestContext identity) {
        return new EnterpriseScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }

    private static IntegrationProblemException problem(
            String code,
            String message,
            String correlationId
    ) {
        int status = "RG.CORRECTNESS.REVISION_INVALID".equals(code) ? 400 : 404;
        return new IntegrationProblemException(new IntegrationProblem(
                "", "urn:bloge:problem:correctness-authoring", message, status,
                code, false, correlationId, Map.of()));
    }
}
