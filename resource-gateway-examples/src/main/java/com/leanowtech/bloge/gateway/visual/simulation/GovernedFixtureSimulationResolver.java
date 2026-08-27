package com.leanowtech.bloge.gateway.visual.simulation;

import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialResolver;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialResolver.MaterialAccessContext;
import com.leanowtech.bloge.gateway.testing.correctness.fixture.FixtureMaterialService;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.fixture.GraphNodeFixturePromotionService;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/** Resolves exact ACTIVE governed Fixture metadata and protected output for simulation only. */
public final class GovernedFixtureSimulationResolver {
    private final FixtureAssetRepository fixtures;
    private final FixtureMaterialResolver materials;
    private final VisualOperatorCatalog catalog;
    private final ObjectMapper mapper;

    /**
     * Creates a resolver backed by the governed catalog and protected material boundary.
     *
     * @param fixtures scope-authorized Fixture catalog
     * @param materials protected material resolver that records access audit
     */
    public GovernedFixtureSimulationResolver(
            FixtureAssetRepository fixtures, FixtureMaterialResolver materials) {
        this(fixtures, materials, null, new ObjectMapper().findAndRegisterModules());
    }

    /** Creates a resolver with catalog/schema evidence for node-level stale protection. */
    public GovernedFixtureSimulationResolver(FixtureAssetRepository fixtures,
                                             FixtureMaterialResolver materials,
                                             VisualOperatorCatalog catalog,
                                             ObjectMapper mapper) {
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.catalog = catalog;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Resolves one exact ACTIVE Fixture without exposing protected material to the caller.
     *
     * @param scope server-derived authorized scope
     * @param ref exact client-provided Fixture identity
     * @param identity authenticated material-read identity
     * @return output-only request-scoped fixture
     * @throws GovernedFixtureResolutionException if closure or lifecycle is not exact
     */
    public NodeFixture resolve(
            EnterpriseScope scope, GovernedFixtureRef ref, IntegrationRequestContext identity) {
        if (scope == null || ref == null || identity == null) throw invalid();
        StoredFixtureAsset stored = fixtures.findRevision(scope, ref.fixtureAssetId(), ref.revision())
                .orElseThrow(GovernedFixtureSimulationResolver::notFound);
        var descriptor = stored.descriptor();
        if (descriptor.lifecycle() != FixtureLifecycle.ACTIVE
                || !descriptor.schemaRef().fingerprint().equals(ref.schemaFingerprint())
                || !descriptor.fixtureAssetId().equals(ref.fixtureAssetId())
                || descriptor.revision() != ref.revision()) {
            throw blocked("Governed Fixture is stale, inactive, or schema-incompatible");
        }
        var resolved = materials.resolve(scope, descriptor.materialRef(), new MaterialAccessContext(
                identity.actorId(), FixtureMaterialService.READ_PURPOSE,
                identity.correlationId(), identity.clearance()));
        if (!resolved.receipt().schemaRef().equals(descriptor.schemaRef())) throw blocked(
                "Governed Fixture material and descriptor schema closure is not exact");
        return new NodeFixture(resolved.payload());
    }

    /** Resolves a node-bound reference and rejects operator schema drift or ambiguous outputs. */
    public NodeFixture resolve(EnterpriseScope scope, GovernedFixtureRef ref,
                               IntegrationRequestContext identity, GraphDraft draft, String nodeId) {
        if (catalog == null || draft == null || nodeId == null) throw invalid();
        GraphDraft.DraftNode node = draft.nodes().stream().filter(value -> value.id().equals(nodeId))
                .findFirst().orElseThrow(GovernedFixtureSimulationResolver::notFound);
        OperatorDefinition operator = catalog.find(node.operatorRef())
                .orElseThrow(GovernedFixtureSimulationResolver::notFound);
        if (operator.ports().outputs().size() != 1) throw blocked(
                "Governed Fixture requires one unambiguous current output schema");
        SchemaEnvelope current = operator.ports().outputs().getFirst().schema();
        if (current == null || current.equals(SchemaEnvelope.opaque())) throw blocked(
                "Governed Fixture cannot bind to an opaque output schema");
        var expected = GraphNodeFixturePromotionService.exactOutputSchemaRef(operator, mapper);
        if (!expected.fingerprint().equals(ref.schemaFingerprint())) throw blocked(
                "Governed Fixture schema is stale for this node");
        NodeFixture resolved = resolve(scope, ref, identity);
        if (!VisualSchemaValidator.validateValue(current, resolved.output(), "/governedFixture").isEmpty()) {
            throw blocked("Governed Fixture output no longer satisfies the current schema");
        }
        return resolved;
    }

    private static GovernedFixtureResolutionException invalid() {
        return new GovernedFixtureResolutionException(400, "GOVERNED_FIXTURE_REFERENCE_INVALID");
    }

    private static GovernedFixtureResolutionException notFound() {
        return new GovernedFixtureResolutionException(404, "GOVERNED_FIXTURE_NOT_FOUND");
    }

    private static GovernedFixtureResolutionException blocked(String message) {
        return new GovernedFixtureResolutionException(422, message);
    }

    /** Stable fail-closed error for governed simulation resolution. */
    public static final class GovernedFixtureResolutionException extends RuntimeException {
        private final int status;

        /** Creates a governed Fixture resolution failure. */
        public GovernedFixtureResolutionException(int status, String message) {
            super(message);
            this.status = status;
        }

        /** @return HTTP-compatible status for this safe failure */
        public int status() {
            return status;
        }
    }
}
