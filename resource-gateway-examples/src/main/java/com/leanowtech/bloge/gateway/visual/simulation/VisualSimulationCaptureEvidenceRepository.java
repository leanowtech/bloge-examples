package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;

import java.util.Optional;

/**
 * Short-lived server store for payload-free successful visual simulation captures.
 *
 * <p>The repository accepts the original request and response only so an implementation can
 * exclude client fixture overrides and derive fingerprints at the trusted server boundary. It
 * must never persist simulation payloads or accept a client provenance/source claim.</p>
 */
public interface VisualSimulationCaptureEvidenceRepository {

    /**
     * Records successful simulation evidence for eligible, unpinned operator nodes.
     *
     * @param request original server request
     * @param response server simulation response
     * @param catalog authoritative operator catalog
     */
    void recordSuccessfulSimulation(VisualGraphSimulationRequest request,
                                     VisualGraphSimulationResponse response,
                                     VisualOperatorCatalog catalog);

    /**
     * Finds evidence by its tenant/draft/node coordinate.
     *
     * @param tenantId tenant boundary
     * @param namespace logical namespace
     * @param environment environment boundary
     * @param draftId persisted draft id
     * @param nodeId exact graph node id
     * @return active evidence, if present
     */
    Optional<VisualSimulationCaptureEvidence> find(String tenantId,
                                                    String namespace,
                                                    String environment,
                                                    String draftId,
                                                    String nodeId);

}
