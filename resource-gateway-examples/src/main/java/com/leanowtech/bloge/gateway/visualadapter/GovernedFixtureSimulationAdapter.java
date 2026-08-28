package com.leanowtech.bloge.gateway.visualadapter;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.simulation.GovernedFixtureRef;
import com.leanowtech.bloge.gateway.visual.simulation.NodeFixture;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationRequest;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationResponse;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationService;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGovernedFixtureResolutionException;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGovernedFixtureSimulationPort;
import com.leanowtech.bloge.gateway.visual.simulation.VisualSimulationCaptureEvidenceRepository;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visualadapter.fixture.GovernedFixtureSimulationResolver;

import org.springframework.http.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway adapter for governed visual simulation.
 *
 * <p>This class is the only composition point between visual simulation DTOs and authenticated
 * correctness/material services. The visual controller depends on the narrow visual port, while
 * this adapter owns identity-to-scope conversion, protected output resolution, and reuse audit.</p>
 */
public final class GovernedFixtureSimulationAdapter
        implements VisualGovernedFixtureSimulationPort {

    private final VisualGraphSimulationService simulationService;
    private final IntegrationRequestAuthenticator authenticator;
    private final GovernedFixtureSimulationResolver governedFixtures;
    private final VisualOperatorCatalog operators;
    private final VisualSimulationCaptureEvidenceRepository simulationCaptures;

    /** Creates the authenticated adapter around the visual simulation service. */
    public GovernedFixtureSimulationAdapter(
            VisualGraphSimulationService simulationService,
            IntegrationRequestAuthenticator authenticator,
            GovernedFixtureSimulationResolver governedFixtures) {
        this(simulationService, authenticator, governedFixtures, null, null);
    }

    /**
     * Creates the adapter with the server-owned catalog and bounded simulation capture store.
     *
     * @param simulationService visual simulation executor
     * @param authenticator authenticated integration identity resolver
     * @param governedFixtures protected fixture resolver
     * @param operators authoritative visual operator catalog, or {@code null} when capture is disabled
     * @param simulationCaptures short-lived server simulation evidence store, or {@code null}
     */
    public GovernedFixtureSimulationAdapter(
            VisualGraphSimulationService simulationService,
            IntegrationRequestAuthenticator authenticator,
            GovernedFixtureSimulationResolver governedFixtures,
            VisualOperatorCatalog operators,
            VisualSimulationCaptureEvidenceRepository simulationCaptures) {
        this.simulationService = simulationService;
        this.authenticator = authenticator;
        this.governedFixtures = governedFixtures;
        this.operators = operators;
        this.simulationCaptures = simulationCaptures;
    }

    /**
     * Resolves governed material using the authenticated request and delegates the visual-only
     * request to the simulation service without exposing protected payloads to the controller.
     */
    @Override
    public VisualGraphSimulationResponse simulate(
            VisualGraphSimulationRequest request, HttpHeaders headers) {
        VisualGraphSimulationRequest originalRequest = request;
        Map<String, NodeFixture> effectiveOriginalFixtures = effectiveFixtures(request);
        IntegrationRequestContext identity = null;
        EnterpriseScope scope = null;
        if (effectiveOriginalFixtures.values().stream()
                .anyMatch(fixture -> fixture != null && fixture.governedRef() != null)) {
            if (authenticator == null || governedFixtures == null) {
                throw new VisualGovernedFixtureResolutionException(503,
                        "Governed Fixture simulation is unavailable");
            }
            identity = authenticator.authenticate(
                    headers, IntegrationOperation.CORRECTNESS_FIXTURE_MATERIAL_READ);
            scope = new EnterpriseScope(identity.tenantId(), identity.organizationId(),
                    identity.projectId(), identity.environmentId(), identity.region());
            Map<String, NodeFixture> resolved = resolveFixtures(
                    request, effectiveOriginalFixtures, identity, scope);
            request = new VisualGraphSimulationRequest(
                    request.draft(), request.context(), request.outputNode(), resolved);
        }
        VisualGraphSimulationResponse response = simulationService.simulate(
                request.draft(), request.context(), request.outputNode(), request.fixtures());
        if (response.success() && operators != null && simulationCaptures != null) {
            // Capture the original request: governed material is resolved only for execution, and
            // the repository excludes all client fixture overrides before deriving evidence.
            simulationCaptures.recordSuccessfulSimulation(originalRequest, response, operators);
        }
        if (response.success() && identity != null && scope != null) {
            List<GovernedFixtureRef> refs = effectiveOriginalFixtures.entrySet().stream()
                    .filter(entry -> entry.getValue() != null
                            && entry.getValue().governedRef() != null)
                    .filter(entry -> originalRequest.draft() != null && originalRequest.draft().nodes().stream()
                            .anyMatch(node -> node.id().equals(entry.getKey())))
                    .map(entry -> entry.getValue().governedRef()).toList();
            if (!refs.isEmpty()) {
                governedFixtures.recordReuse(scope, originalRequest.draft(), refs);
            }
        }
        return response;
    }

    private Map<String, NodeFixture> resolveFixtures(
            VisualGraphSimulationRequest request,
            Map<String, NodeFixture> originals,
            IntegrationRequestContext identity,
            EnterpriseScope scope) {
        Map<String, NodeFixture> resolved = new LinkedHashMap<>();
        originals.forEach((nodeId, fixture) -> {
            if (fixture == null || fixture.governedRef() == null) {
                resolved.put(nodeId, fixture);
                return;
            }
            NodeFixture materialized = governedFixtures.resolve(
                    scope, fixture.governedRef(), identity, request.draft(), nodeId);
            // The resolver returns only protected output; all caller-selected evidence facts are
            // copied from the request so resolution cannot downgrade protocol/transport fidelity.
            resolved.put(nodeId, new NodeFixture(
                    materialized.output(), fixture.expectedInput(), fixture.governedRef(),
                    fixture.resourceFidelity()));
        });
        return resolved;
    }

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

    private static NodeFixture toSimulationFixture(GraphDraft.NodeFixture fixture) {
        if (fixture == null) {
            return null;
        }
        return new NodeFixture(fixture.output(), fixture.expectedInput(),
                fixture.governedRef() == null ? null : new GovernedFixtureRef(
                        fixture.governedRef().fixtureAssetId(), fixture.governedRef().revision(),
                        fixture.governedRef().schemaFingerprint()),
                fixture.resourceFidelity() == null
                        ? NodeFixture.ResourceFidelity.OUTPUT_LEVEL
                        : NodeFixture.ResourceFidelity.valueOf(fixture.resourceFidelity().name()));
    }
}
