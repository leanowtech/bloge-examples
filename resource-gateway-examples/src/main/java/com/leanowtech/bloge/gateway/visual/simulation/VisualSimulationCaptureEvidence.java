package com.leanowtech.bloge.gateway.visual.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Payload-free server evidence that one persisted graph node produced an output during simulation.
 *
 * <p>The record deliberately contains only bounded identity and fingerprints. It is therefore
 * safe to keep for a short time between the visible Simulate, Pin, and Promote commands without
 * turning the capture store into a second payload vault. Promotion still has to present the
 * persisted node output and exact operator/schema material; this evidence only establishes that
 * the server, rather than a client provenance flag, observed a successful simulation.</p>
 *
 * @param schemaVersion evidence protocol version
 * @param tenantId tenant boundary
 * @param namespace logical graph namespace
 * @param environment authoring/runtime environment
 * @param draftId persisted graph draft id
 * @param draftRevision revision observed by the simulation request
 * @param draftFingerprint semantic draft fingerprint with fixtures and mutable audit metadata removed
 * @param nodeId exact simulated graph node id
 * @param operatorRef exact operator reference at simulation time
 * @param operatorFingerprint server catalog fingerprint for that operator
 * @param outputFingerprint fingerprint of the server-produced node output
 * @param capturedAt server capture time
 * @param expiresAt bounded evidence expiry
 */
public record VisualSimulationCaptureEvidence(
        String schemaVersion,
        String tenantId,
        String namespace,
        String environment,
        String draftId,
        long draftRevision,
        String draftFingerprint,
        String nodeId,
        String operatorRef,
        String operatorFingerprint,
        String outputFingerprint,
        Instant capturedAt,
        Instant expiresAt
) {
    /** Current short-lived server simulation capture protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.visualSimulationCaptureEvidence.v1";
    /** Maximum canonical JSON size used for one fingerprint operation. */
    public static final int MAX_FINGERPRINT_BYTES = 4 * 1024 * 1024;
    /** Default lifetime of a capture before promotion must fall back to SAMPLE. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final int MAX_TEXT_LENGTH = 512;

    /** Validates identity, fingerprint, and temporal bounds at the evidence boundary. */
    public VisualSimulationCaptureEvidence {
        schemaVersion = required(schemaVersion, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported simulation capture evidence schemaVersion");
        }
        tenantId = bounded(tenantId, "tenantId");
        namespace = bounded(namespace, "namespace");
        environment = bounded(environment, "environment");
        draftId = bounded(draftId, "draftId");
        if (draftRevision < 1) {
            throw new IllegalArgumentException("draftRevision must be positive");
        }
        draftFingerprint = fingerprint(draftFingerprint, "draftFingerprint");
        nodeId = bounded(nodeId, "nodeId");
        operatorRef = bounded(operatorRef, "operatorRef");
        operatorFingerprint = fingerprint(operatorFingerprint, "operatorFingerprint");
        outputFingerprint = fingerprint(outputFingerprint, "outputFingerprint");
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(capturedAt)) {
            throw new IllegalArgumentException("expiresAt must be after capturedAt");
        }
    }

    /**
     * Returns whether this bounded evidence can be used at the supplied server time.
     *
     * @param observedAt server time used by the consuming command
     * @return true only inside the capture lifetime
     */
    public boolean activeAt(Instant observedAt) {
        return observedAt != null
                && !observedAt.isBefore(capturedAt)
                && observedAt.isBefore(expiresAt);
    }

    /**
     * Compares all server-owned coordinates against the draft presented for promotion.
     *
     * <p>Revision may advance when the author pins the simulated output, so the immutable semantic
     * draft fingerprint is compared while the revision is treated as a monotonic lower bound. Any
     * graph/operator/output drift, scope mismatch, or expired capture fails closed.</p>
     *
     * @param draft current persisted draft
     * @param expectedNodeId node selected for promotion
     * @param operator current server catalog definition
     * @param output current persisted node output
     * @param mapper canonical JSON mapper
     * @param observedAt server time
     * @return true when this exact server capture closes the promotion coordinates
     */
    public boolean matches(GraphDraft draft,
                           String expectedNodeId,
                           OperatorDefinition operator,
                           Object output,
                           ObjectMapper mapper,
                           Instant observedAt) {
        if (!activeAt(observedAt) || draft == null || operator == null || output == null
                || mapper == null || expectedNodeId == null || draft.revision() < draftRevision) {
            return false;
        }
        return tenantId.equals(draft.tenantId())
                && namespace.equals(draft.namespace())
                && environment.equals(draft.environment())
                && draftId.equals(draft.draftId())
                && nodeId.equals(expectedNodeId)
                && operatorRef.equals(operator.operatorRef())
                && operatorFingerprint.equals(operator.fingerprint())
                && draftFingerprint.equals(draftFingerprint(mapper, draft))
                && outputFingerprint.equals(valueFingerprint(mapper, output));
    }

    /**
     * Derives payload-free evidence from one successful server simulation.
     *
     * <p>Nodes carrying a request or persisted fixture are excluded. This is the critical boundary
     * that prevents a client-provided pinned value from being reclassified as a server simulation
     * capture. Only operator nodes with a non-null server result are considered.</p>
     *
     * @param request original request before governed material resolution
     * @param response server simulation response
     * @param catalog server operator catalog
     * @param mapper canonical JSON mapper
     * @param capturedAt server capture time
     * @param ttl bounded evidence lifetime
     * @return one evidence item per eligible simulated operator node
     */
    public static List<VisualSimulationCaptureEvidence> fromSuccessfulSimulation(
            VisualGraphSimulationRequest request,
            VisualGraphSimulationResponse response,
            VisualOperatorCatalog catalog,
            ObjectMapper mapper,
            Instant capturedAt,
            Duration ttl) {
        if (request == null || response == null || catalog == null || mapper == null
                || capturedAt == null || ttl == null || ttl.isZero() || ttl.isNegative()
                || !response.validated() || !response.compiled() || !response.success()
                || request.draft() == null || request.draft().draftId().isBlank()
                || request.draft().revision() < 1) {
            return List.of();
        }
        GraphDraft draft = request.draft();
        Set<String> clientFixtures = new HashSet<>();
        clientFixtures.addAll(draft.nodeFixtures().keySet());
        clientFixtures.addAll(request.fixtures().keySet());
        String draftFingerprint = draftFingerprint(mapper, draft);
        List<VisualSimulationCaptureEvidence> captures = new ArrayList<>();
        for (GraphDraft.DraftNode node : draft.nodes()) {
            if (node == null || node.id().isBlank() || clientFixtures.contains(node.id())) {
                continue;
            }
            Optional<OperatorDefinition> operator = catalog.find(node.operatorRef());
            if (operator.isEmpty()) {
                continue;
            }
            Object output = response.results().containsKey(node.id())
                    ? response.results().get(node.id())
                    : response.outputNode().equals(node.id()) ? response.output() : null;
            if (output == null) {
                continue;
            }
            String status = response.statusMap().get(node.id());
            if (status != null && !"COMPLETED".equalsIgnoreCase(status)) {
                continue;
            }
            captures.add(new VisualSimulationCaptureEvidence(
                    SCHEMA_VERSION,
                    draft.tenantId(),
                    draft.namespace(),
                    draft.environment(),
                    draft.draftId(),
                    draft.revision(),
                    draftFingerprint,
                    node.id(),
                    operator.get().operatorRef(),
                    operator.get().fingerprint(),
                    valueFingerprint(mapper, output),
                    capturedAt,
                    capturedAt.plus(ttl)));
        }
        return List.copyOf(captures);
    }

    /** Computes the semantic draft fingerprint shared by capture and promotion. */
    public static String draftFingerprint(ObjectMapper mapper, GraphDraft draft) {
        Objects.requireNonNull(draft, "draft");
        GraphDraft semanticDraft = draft
                .withIdentity(draft.draftId(), 0)
                .withNodeFixtures(Map.of())
                .withRevisionMetadata(GraphDraft.RevisionMetadata.empty());
        return valueFingerprint(mapper, semanticDraft);
    }

    /** Computes a bounded canonical fingerprint without retaining the value itself. */
    public static String valueFingerprint(ObjectMapper mapper, Object value) {
        return VisualBundleFingerprint.fromCanonicalValue(
                Objects.requireNonNull(mapper, "mapper"), value, MAX_FINGERPRINT_BYTES);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String bounded(String value, String field) {
        String normalized = required(value, field);
        if (normalized.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(field + " exceeds " + MAX_TEXT_LENGTH + " characters");
        }
        return normalized;
    }

    private static String fingerprint(String value, String field) {
        String normalized = bounded(value, field);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be an exact sha256 fingerprint");
        }
        return normalized;
    }
}
