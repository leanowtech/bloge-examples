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

    /** Simulates a request while optionally resolving authenticated governed Fixtures. */
    @PostMapping("/simulate")
    public VisualGraphSimulationResponse simulate(
            @RequestBody VisualGraphSimulationRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(required = false) HttpHeaders headers) {
        VisualGraphSimulationRequest incoming = request;
        final VisualGraphSimulationRequest requestForCheck = incoming;
        IntegrationRequestContext governedIdentity = null;
        EnterpriseScope governedScope = null;
        java.util.List<GovernedFixtureRef> governedRefs = new java.util.ArrayList<>();
        if (requestForCheck != null && requestForCheck.fixtures().values().stream()
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
            VisualGraphSimulationRequest requestForResolution = incoming;
            Map<String, NodeFixture> resolved = new LinkedHashMap<>();
            requestForResolution.fixtures().forEach((nodeId, fixture) -> resolved.put(nodeId,
                    fixture == null || fixture.governedRef() == null
                            ? fixture
                            : resolveGoverned(governedRefs, resolvedScope, fixture.governedRef(), resolvedIdentity,
                                    requestForResolution.draft(), nodeId)));
            incoming = new VisualGraphSimulationRequest(
                    requestForResolution.draft(), requestForResolution.context(),
                    requestForResolution.outputNode(), resolved);
        }
        VisualGraphSimulationResponse response = simulationService.simulate(
                incoming.draft(), incoming.context(), incoming.outputNode(), incoming.fixtures());
        if (response.success() && request != null && governedFixtures != null) {
            java.util.List<GovernedFixtureRef> refs = request.fixtures().values().stream()
                    .filter(fixture -> fixture != null && fixture.governedRef() != null)
                    .map(NodeFixture::governedRef).toList();
            if (!refs.isEmpty() && governedIdentity != null && governedScope != null) {
                governedFixtures.recordReuse(governedScope, request.draft(), refs);
            }
        }
        return response;
    }

    private NodeFixture resolveGoverned(java.util.List<GovernedFixtureRef> refs,
                                        EnterpriseScope scope, GovernedFixtureRef ref,
                                        IntegrationRequestContext identity, com.leanowtech.bloge.gateway.visual.draft.GraphDraft draft,
                                        String nodeId) {
        refs.add(ref);
        return governedFixtures.resolve(scope, ref, identity, draft, nodeId);
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
