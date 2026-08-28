package com.leanowtech.bloge.gateway.visualadapter.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.WriteRequest;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureSource;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.QualityProfile;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RedactionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RetentionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.SourceKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.FixtureSubject;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureCatalogCommandException;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureCatalogService;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.simulation.VisualSimulationCaptureEvidenceRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Derives a governed Draft Fixture from an immutable graph-draft node capture.
 *
 * <p>The service deliberately owns every server-derived coordinate: scope, source lineage,
 * protected material reference, exact schema reference, fingerprint, retention expiry, and DRAFT
 * lifecycle. Clients cannot inject these values.</p>
 */
public class GraphNodeFixturePromotionService {
    private static final Set<String> SUPPORTED_CLASSIFICATIONS =
            Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    private final GraphDraftRepository drafts;
    private final VisualOperatorCatalog operators;
    private final FixtureCatalogService fixtures;
    private final PromotedGraphNodeFixtureMaterialWriter materials;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final VisualSimulationCaptureEvidenceRepository simulationCaptures;

    /**
     * Creates the graph-node Fixture promotion service.
     */
    public GraphNodeFixturePromotionService(
            GraphDraftRepository drafts,
            VisualOperatorCatalog operators,
            FixtureCatalogService fixtures,
            PromotedGraphNodeFixtureMaterialWriter materials,
            ObjectMapper mapper,
            Clock clock) {
        this(drafts, operators, fixtures, materials, mapper, clock, null);
    }

    /**
     * Creates promotion with the bounded server simulation-capture store.
     *
     * <p>The optional store is deliberately a separate seam: deployments without a capture
     * repository retain the historical SAMPLE fallback, while configured deployments can prove
     * SCENARIO lineage from a server response rather than from client metadata.</p>
     *
     * @param drafts authoritative graph-draft repository
     * @param operators exact operator-catalog view
     * @param fixtures governed Fixture catalog
     * @param materials protected material write boundary
     * @param mapper canonical JSON mapper
     * @param clock source of server time
     * @param simulationCaptures short-lived successful-simulation evidence, or {@code null}
     */
    public GraphNodeFixturePromotionService(
            GraphDraftRepository drafts,
            VisualOperatorCatalog operators,
            FixtureCatalogService fixtures,
            PromotedGraphNodeFixtureMaterialWriter materials,
            ObjectMapper mapper,
            Clock clock,
            VisualSimulationCaptureEvidenceRepository simulationCaptures) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.operators = Objects.requireNonNull(operators, "operators");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.simulationCaptures = simulationCaptures;
    }

    /**
     * Promotes one captured node output into a new governed DRAFT Fixture.
     *
     * <p>The controller entry point is REQUIRED-transactional so the protected material revision,
     * its write audit, and the catalog descriptor commit or roll back as one database unit when
     * the configured repositories share a transaction manager.</p>
     *
     * @param draftId authoritative graph draft id
     * @param nodeId exact graph node id
     * @param request author-controlled bounded promotion request
     * @param identity authenticated integration context
     * @return payload-free governed Fixture receipt
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PromotionResult promote(
            String draftId,
            String nodeId,
            GraphNodeFixturePromotionRequest request,
            IntegrationRequestContext identity) {
        try {
            requireIdentity(identity);
            return promote(draftId, nodeId, request, actor(identity), identity);
        } catch (IllegalArgumentException invalidRequest) {
            throw invalid("REQUEST_INVALID", invalidRequest.getMessage());
        }
    }

    /**
     * Direct-call overload used by non-Spring clients and focused service tests.
     *
     * <p>When invoked through the Spring bean proxy this overload has the same atomic boundary as
     * the controller entry point. Direct, non-Spring callers must provide their own transaction
     * if they require atomicity across non-database implementations.</p>
     *
     * @param draftId authoritative graph draft id
     * @param nodeId exact graph node id
     * @param request author-controlled bounded promotion request
     * @param owner authenticated actor that owns the resulting Fixture
     * @param identityForMaterialWrite trusted context used for scope and material authorization
     * @return payload-free governed Fixture receipt
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PromotionResult promote(
            String draftId,
            String nodeId,
            GraphNodeFixturePromotionRequest request,
            PrincipalRef owner,
            IntegrationRequestContext identityForMaterialWrite) {
        requireIdentity(identityForMaterialWrite);
        if (request == null) throw invalid("REQUEST_INVALID", "A promotion request is required");
        try {
            request.requireValid();
        } catch (IllegalArgumentException invalidRequest) {
            throw invalid("REQUEST_INVALID", invalidRequest.getMessage());
        }
        if (owner == null) throw invalid("ACTOR_REQUIRED", "An authenticated actor is required");

        GraphDraft draft = drafts.find(requiredText(draftId, "draftId"))
                .orElseThrow(() -> notFound("DRAFT_NOT_FOUND", "Graph draft was not found"));
        // Scope closure is checked before node/operator/output reads or protected-material writes.
        // A caller who is authenticated as an actor in another tenant or environment must receive
        // the same not-found response as an unknown draft; exposing the mismatch would become a
        // cross-scope draft oracle.
        if (!identityForMaterialWrite.tenantId().equals(draft.tenantId())
                || !identityForMaterialWrite.environmentId().equals(draft.environment())) {
            throw notFound("DRAFT_NOT_FOUND", "Graph draft was not found");
        }
        GraphDraft.DraftNode node = draft.nodes().stream()
                .filter(candidate -> candidate.id().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> notFound("NODE_NOT_FOUND", "Graph node was not found"));
        GraphDraft.NodeFixture fixture = draft.nodeFixtures().get(nodeId);
        if (fixture == null || fixture.output() == null) {
            throw unprocessable("OUTPUT_MISSING", "The selected node has no captured Fixture output");
        }
        OperatorDefinition operator = operators.find(node.operatorRef())
                .orElseThrow(() -> unprocessable(
                        "OPERATOR_NOT_FOUND", "The selected node operator is unavailable"));
        SchemaEnvelope outputSchema = operatorPorts(operator);
        if (operator.ports().outputs().size() > 1) {
            throw unprocessable(
                    "OUTPUT_SCHEMA_NON_UNIQUE",
                    "Governed Fixture promotion requires one unambiguous operator output schema");
        }
        if (outputSchema == null || outputSchema.equals(SchemaEnvelope.opaque())) {
            throw unprocessable(
                    "OUTPUT_SCHEMA_OPAQUE",
                    "Governed Fixture promotion requires an exact operator output schema");
        }
        List<com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic> schemaDiagnostics =
                VisualSchemaValidator.validateValue(outputSchema, fixture.output(), "/nodeFixture");
        if (!schemaDiagnostics.isEmpty()) {
            throw unprocessable(
                    "OUTPUT_SCHEMA_INVALID",
                    "The captured node output does not satisfy its exact operator schema");
        }
        ExactSchemaRef schemaRef = exactOutputSchemaRef(operator, mapper);
        boolean isResource = node.operatorRef().startsWith("resource:");
        String sourceValue = isResource ? nodeIdOperatorResource(node.operatorRef()) : node.operatorRef();
        ExactAssetRef sourceRef = new ExactAssetRef(
                isResource ? "RESOURCE" : "OPERATOR", sourceValue, 1, operator.fingerprint());
        EnterpriseScope scope = new EnterpriseScope(
                requiredText(draft.tenantId(), "tenantId"),
                requiredText(identityForMaterialWrite.organizationId(), "organizationId"),
                requiredText(draft.namespace(), "namespace"),
                requiredText(draft.environment(), "environment"),
                requiredText(identityForMaterialWrite.region(), "region"));
        Instant now = clock.instant();
        RetentionDescriptor retention = new RetentionDescriptor(
                "graph-node-fixture-retention-v1",
                request.retentionDays(),
                now.plus(Duration.ofDays(request.retentionDays())));
        RedactionDescriptor redaction = new RedactionDescriptor(
                "graph-node-fixture-redaction-v1", request.redactionPaths(), false);
        // Provenance is derived only from a server capture that closes the current draft/node/
        // operator/output coordinates. A missing, stale, or mismatched receipt stays SAMPLE;
        // client fixture fields and promotion request metadata are never consulted.
        boolean capturedFromSimulation = simulationCaptures != null
                && simulationCaptures.find(draft.tenantId(), draft.namespace(), draft.environment(),
                        draft.draftId(), nodeId)
                .map(evidence -> evidence.matches(
                        draft, nodeId, operator, fixture.output(), mapper, now))
                .orElse(false);
        FixtureSource source = new FixtureSource(
                capturedFromSimulation ? SourceKind.SCENARIO : SourceKind.SAMPLE, sourceRef);
        Receipt materialReceipt = materials.write(new WriteRequest(
                "",
                request.fixtureAssetId(),
                0,
                source,
                FixtureSubject.GRAPH,
                new ExactTargetRef(TargetKind.GRAPH, draft.draftId(), positiveRevision(draft.revision()),
                        fingerprint(Map.of("graphName", draft.graphName(), "nodeIds",
                                draft.nodes().stream().map(GraphDraft.DraftNode::id).toList()))),
                schemaRef,
                request.classification(),
                retention,
                redaction,
                fixture.output()),
                identityForMaterialWrite);
        AuditMetadata auditMetadata = new AuditMetadata(now, now, owner, owner);
        var candidate = new com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor(
                "", request.fixtureAssetId(), 0, scope, node.label() + " governed Fixture",
                materialReceipt.source(), materialReceipt.materialRef(), schemaRef, nodeId,
                com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle.DRAFT,
                request.classification(), owner, redaction, retention,
                new QualityProfile(true, false, 0, 0), List.of("graph-node"), auditMetadata);
        try {
            var stored = fixtures.saveDraft(0, candidate, owner);
            return new PromotionResult(
                    stored.descriptor().fixtureAssetId(),
                    stored.descriptor().revision(),
                    stored.descriptor().lifecycle().name(),
                    stored.exactRef(),
                    schemaRef,
                    "governed");
        } catch (FixtureCatalogCommandException exception) {
            if ("RG.CORRECTNESS.REVISION_CONFLICT".equals(exception.code())) {
                throw new GraphNodeFixturePromotionException(
                        409, exception.code(), exception.getMessage());
            }
            throw unprocessable("FIXTURE_CATALOG_REJECTED", exception.getMessage());
        }
    }

    private static SchemaEnvelope operatorPorts(OperatorDefinition operator) {
        return operator.ports().outputs().size() != 1
                ? null
                : operator.ports().outputs().getFirst().schema();
    }

    /** Derives the canonical schema reference shared by promotion and governed simulation. */
    public static ExactSchemaRef exactOutputSchemaRef(OperatorDefinition operator, ObjectMapper mapper) {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(mapper, "mapper");
        SchemaEnvelope schema = operatorPorts(operator);
        if (schema == null || schema.equals(SchemaEnvelope.opaque())) {
            throw unprocessable("OUTPUT_SCHEMA_OPAQUE", "An exact single output schema is required");
        }
        return new ExactSchemaRef(
                sourceId(operator.operatorRef()), 1,
                com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint
                        .derivedFingerprint(mapper, Map.of(
                                "operatorFingerprint", operator.fingerprint(), "schema", schema.schema())));
    }

    /**
     * Requires the authenticated identity fields needed to close promotion to a draft scope.
     *
     * <p>Tenant and environment are security coordinates, not optional display metadata. They
     * must be present before any draft lookup; a later mismatch is deliberately reported as the
     * same not-found result as an unknown draft.</p>
     *
     * @param identity authenticated request context
     */
    private void requireIdentity(IntegrationRequestContext identity) {
        if (identity == null || identity.actorId().isBlank()
                || identity.organizationId().isBlank() || identity.region().isBlank()
                || identity.tenantId().isBlank() || identity.environmentId().isBlank()) {
            throw invalid("IDENTITY_REQUIRED",
                    "Tenant, environment, actor id, organization id, and region are required for promotion");
        }
    }

    private static PrincipalRef actor(IntegrationRequestContext identity) {
        PrincipalKind kind;
        try {
            kind = PrincipalKind.valueOf(identity.actorType());
        } catch (IllegalArgumentException ignored) {
            kind = PrincipalKind.SERVICE;
        }
        return new PrincipalRef(identity.actorId(), kind, identity.actorId());
    }

    private static String requiredText(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw invalid(field.toUpperCase(java.util.Locale.ROOT) + "_REQUIRED", field + " is required");
        }
        return normalized;
    }

    private static long positiveRevision(long revision) {
        if (revision < 1) {
            throw unprocessable("DRAFT_REVISION_INVALID", "Only persisted graph revisions can be promoted");
        }
        return revision;
    }

    private static String sourceId(String operatorRef) {
        return operatorRef.startsWith("resource:")
                ? nodeIdOperatorResource(operatorRef)
                : operatorRef;
    }

    private static String nodeIdOperatorResource(String operatorRef) {
        return operatorRef.substring("resource:".length());
    }

    private String fingerprint(Object value) {
        Objects.requireNonNull(value, "value");
        return com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint
                .derivedFingerprint(mapper, value);
    }

    private static GraphNodeFixturePromotionException invalid(String code, String message) {
        return new GraphNodeFixturePromotionException(400, "RG.VISUAL.PROMOTION." + code, message);
    }

    private static GraphNodeFixturePromotionException unprocessable(String code, String message) {
        return new GraphNodeFixturePromotionException(422, "RG.VISUAL.PROMOTION." + code, message);
    }

    private static GraphNodeFixturePromotionException notFound(String code, String message) {
        return new GraphNodeFixturePromotionException(404, "RG.VISUAL.PROMOTION." + code, message);
    }

    /**
     * Payload-free receipt returned after successful promotion.
     */
    public record PromotionResult(
            /** Newly created governed Fixture id. */
            String fixtureAssetId,
            /** Persisted Fixture revision. */
            long revision,
            /** Persisted lifecycle, initially {@code DRAFT}. */
            String lifecycle,
            /** Payload-free exact Fixture reference. */
            ExactAssetRef assetRef,
            /** Exact output schema reference used by the Fixture. */
            ExactSchemaRef schemaRef,
            /** Stable provenance label for this promotion result. */
            String provenance
    ) { }
}
