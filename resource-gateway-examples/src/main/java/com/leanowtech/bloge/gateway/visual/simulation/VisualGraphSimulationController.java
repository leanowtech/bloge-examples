package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.visual.simulation.GovernedFixtureSimulationResolver.GovernedFixtureResolutionException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint exposing the visual graph mock-run (simulate) capability.
 *
 * <p>Unlike {@code POST /api/visual/drafts/run}, which blocks graphs that reference unimplemented
 * (design-only) operators, this endpoint synthesizes schema-conforming stand-ins for such operators so
 * the whole graph can be executed for runtime-correctness validation. The response marks which nodes
 * were mocked versus executed for real.</p>
 */
@RestController
@RequestMapping("/api/visual/graphs")
public class VisualGraphSimulationController {

    private final VisualGraphSimulationService simulationService;
    private final IntegrationRequestAuthenticator authenticator;
    private final GovernedFixtureSimulationResolver governedFixtures;

    /**
     * @param simulationService visual graph simulation service
     */
    public VisualGraphSimulationController(VisualGraphSimulationService simulationService) {
        this(simulationService, null, null);
    }

    /** Spring-owned constructor enabling authenticated governed Fixture reuse. */
    @org.springframework.beans.factory.annotation.Autowired
    public VisualGraphSimulationController(
            VisualGraphSimulationService simulationService,
            IntegrationRequestAuthenticator authenticator,
            ObjectProvider<GovernedFixtureSimulationResolver> governedFixtures) {
        this.simulationService = simulationService;
        this.authenticator = authenticator;
        this.governedFixtures = governedFixtures == null ? null : governedFixtures.getIfAvailable();
    }

    /**
     * Simulates a transient visual graph draft.
     *
     * @param request the simulation request
     * @return the simulation result
     */
    public VisualGraphSimulationResponse simulate(VisualGraphSimulationRequest request) {
        return simulate(request, null);
    }

    /**
     * Simulates a request while optionally resolving authenticated governed Fixtures.
     *
     * <p>Governed material is resolved to a protected output before simulation. The request's
     * exact Fixture coordinate, expected-input assertion, and caller-selected evidence fidelity
     * remain intact, so material resolution cannot silently downgrade the server's evidence
     * boundary.</p>
     *
     * @param request transient graph and request-scoped Fixture references
     * @param headers authenticated request headers used for governed material reads
     * @return server-authoritative simulation result
     */
    @PostMapping("/simulate")
    public VisualGraphSimulationResponse simulate(
            @RequestBody VisualGraphSimulationRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(required = false) HttpHeaders headers) {
        VisualGraphSimulationRequest incoming = request;
        Map<String, NodeFixture> effectiveOriginalFixtures = effectiveFixtures(request);
        IntegrationRequestContext governedIdentity = null;
        EnterpriseScope governedScope = null;
        if (effectiveOriginalFixtures.values().stream()
                .anyMatch(fixture -> fixture != null && fixture.governedRef() != null)) {
            if (authenticator == null || governedFixtures == null) {
                throw new GovernedFixtureResolutionException(503,
                        "Governed Fixture simulation is unavailable");
            }
            governedIdentity = authenticator.authenticate(
                    headers, IntegrationOperation.CORRECTNESS_FIXTURE_MATERIAL_READ);
            governedScope = new EnterpriseScope(governedIdentity.tenantId(), governedIdentity.organizationId(),
                    governedIdentity.projectId(), governedIdentity.environmentId(), governedIdentity.region());
            final IntegrationRequestContext resolvedIdentity = governedIdentity;
            final EnterpriseScope resolvedScope = governedScope;
            final var requestDraft = incoming.draft();
            Map<String, NodeFixture> resolved = new LinkedHashMap<>();
            effectiveOriginalFixtures.forEach((nodeId, fixture) -> {
                if (fixture == null || fixture.governedRef() == null) {
                    resolved.put(nodeId, fixture);
                    return;
                }
                NodeFixture materialized = governedFixtures.resolve(
                        resolvedScope, fixture.governedRef(), resolvedIdentity,
                        requestDraft, nodeId);
                // The resolver intentionally returns only protected output. The caller-selected
                // evidence boundary, input assertion, and exact coordinate remain request facts;
                // dropping them here silently downgraded protocol/transport simulations to output
                // fidelity and made the server response contradict the visible authoring choice.
                resolved.put(nodeId, new NodeFixture(
                        materialized.output(), fixture.expectedInput(), fixture.governedRef(),
                        fixture.resourceFidelity()));
            });
            incoming = new VisualGraphSimulationRequest(
                    incoming.draft(), incoming.context(), incoming.outputNode(), resolved);
        }
        VisualGraphSimulationResponse response = simulationService.simulate(
                incoming.draft(), incoming.context(), incoming.outputNode(), incoming.fixtures());
        if (response.success() && request != null && governedFixtures != null) {
            List<GovernedFixtureRef> refs = effectiveOriginalFixtures.entrySet().stream()
                    .filter(entry -> entry.getValue() != null && entry.getValue().governedRef() != null)
                    .filter(entry -> request.draft() != null && request.draft().nodes().stream()
                            .anyMatch(node -> node.id().equals(entry.getKey())))
                    .map(entry -> entry.getValue().governedRef()).toList();
            if (!refs.isEmpty() && governedIdentity != null && governedScope != null) {
                governedFixtures.recordReuse(governedScope, request.draft(), refs);
            }
        }
        return response;
    }

    /**
     * Builds the same request-over-persisted fixture view used by the simulation service.
     * Persisted DTOs are converted at this boundary so governed metadata cannot be dropped.
     */
    private static Map<String, NodeFixture> effectiveFixtures(VisualGraphSimulationRequest request) {
        if (request == null) {
            return Map.of();
        }
        Map<String, NodeFixture> effective = new LinkedHashMap<>();
        if (request.draft() != null) {
            request.draft().nodeFixtures().forEach((nodeId, fixture) ->
                    effective.put(nodeId, toSimulationFixture(fixture)));
        }
        request.fixtures().forEach((nodeId, fixture) -> {
            if (nodeId != null && !nodeId.isBlank() && fixture != null) {
                effective.put(nodeId, fixture);
            }
        });
        return effective;
    }

    private static NodeFixture toSimulationFixture(com.leanowtech.bloge.gateway.visual.draft.GraphDraft.NodeFixture fixture) {
        if (fixture == null) {
            return null;
        }
        return new NodeFixture(fixture.output(), fixture.expectedInput(),
                toSimulationRef(fixture.governedRef()), toSimulationFidelity(fixture.resourceFidelity()));
    }

    private static GovernedFixtureRef toSimulationRef(
            com.leanowtech.bloge.gateway.visual.draft.GraphDraft.GovernedFixtureRef ref) {
        return ref == null ? null : new GovernedFixtureRef(
                ref.fixtureAssetId(), ref.revision(), ref.schemaFingerprint());
    }

    private static NodeFixture.ResourceFidelity toSimulationFidelity(
            com.leanowtech.bloge.gateway.visual.draft.GraphDraft.NodeFixture.ResourceFidelity fidelity) {
        if (fidelity == null) {
            return NodeFixture.ResourceFidelity.OUTPUT_LEVEL;
        }
        return switch (fidelity) {
            case OUTPUT_LEVEL -> NodeFixture.ResourceFidelity.OUTPUT_LEVEL;
            case PROTOCOL_DERIVED -> NodeFixture.ResourceFidelity.PROTOCOL_DERIVED;
            case TRANSPORT_LEVEL -> NodeFixture.ResourceFidelity.TRANSPORT_LEVEL;
        };
    }

    /** Maps fail-closed governed Fixture resolution failures to a payload-free problem response. */
    @ExceptionHandler(GovernedFixtureResolutionException.class)
    public ResponseEntity<ProblemDetail> handleGovernedFixture(
            GovernedFixtureResolutionException failure) {
        ProblemDetail problem = ProblemDetail.forStatus(failure.status());
        problem.setTitle("Governed Fixture cannot be used for simulation");
        problem.setDetail(failure.getMessage());
        problem.setProperty("code", "VISUAL_GOVERNED_FIXTURE_BLOCKED");
        return ResponseEntity.status(failure.status())
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }

    /** Maps the visual-owned admission failure to Spring's standard problem-details contract. */
    @ExceptionHandler(VisualSimulationProductionAdmissionException.class)
    public ResponseEntity<ProblemDetail> handleProductionAdmission(
            VisualSimulationProductionAdmissionException failure) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle(VisualSimulationProductionAdmissionException.TITLE);
        problem.setDetail(VisualSimulationProductionAdmissionException.TITLE);
        problem.setProperty("code", VisualSimulationProductionAdmissionException.CODE);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(problem);
    }
}
