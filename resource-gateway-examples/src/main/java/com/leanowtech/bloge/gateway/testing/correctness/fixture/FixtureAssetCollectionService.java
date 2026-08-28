package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visualadapter.fixture.GraphNodeFixturePromotionService;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Payload-free, scope-exact governed Fixture collection and trusted usage projection. */
public final class FixtureAssetCollectionService {
    /** Default page size for the governed Fixture collection. */
    public static final int DEFAULT_LIMIT = 50;
    /** Maximum page size for the governed Fixture collection. */
    public static final int MAX_LIMIT = 100;
    /** Maximum offset for the governed Fixture collection. */
    public static final int MAX_OFFSET = 100_000;

    private final FixtureAssetRepository fixtures;
    private final VisualOperatorCatalog catalog;
    private final ObjectMapper mapper;

    /** Creates a collection service backed by the canonical Fixture repository. */
    public FixtureAssetCollectionService(FixtureAssetRepository fixtures) {
        this(fixtures, null, null);
    }

    /** Creates a collection service with the catalog used for current-schema comparisons. */
    public FixtureAssetCollectionService(FixtureAssetRepository fixtures,
                                         VisualOperatorCatalog catalog, ObjectMapper mapper) {
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.catalog = catalog;
        this.mapper = mapper;
    }

    /** Lists bounded descriptor heads; usage is read only from the reverse-usage index. */
    public List<FixtureAssetSummary> list(
            EnterpriseScope scope, boolean activeOnly, int limit, int offset) {
        return list(scope, activeOnly, limit, offset, null);
    }

    /** Lists metadata and optionally compares each Fixture with one current operator schema. */
    public List<FixtureAssetSummary> list(
            EnterpriseScope scope, boolean activeOnly, int limit, int offset, String operatorRef) {
        if (limit < 1 || limit > MAX_LIMIT || offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("Fixture collection bounds are invalid");
        }
        if (operatorRef != null && (operatorRef.isBlank() || operatorRef.trim().length() > 256)) {
            throw new IllegalArgumentException("Operator reference is invalid");
        }
        Optional<String> currentSchema = currentSchemaFingerprint(operatorRef);
        return fixtures.listHeads(scope, activeOnly, limit, offset).stream()
                .map(stored -> summary(stored, currentSchema)).toList();
    }

    private FixtureAssetSummary summary(StoredFixtureAsset stored, Optional<String> currentSchema) {
        FixtureAssetDescriptor descriptor = stored.descriptor();
        int usageCount = fixtures.countUsages(descriptor.scope(), stored.exactRef());
        return new FixtureAssetSummary(
                descriptor.fixtureAssetId(), descriptor.revision(), stored.exactRef(),
                descriptor.schemaRef(), descriptor.lifecycle(), descriptor.name(),
                descriptor.variantKey(), descriptor.classification(), usageCount,
                currentSchema.orElse(null), currentSchema.filter(value -> value.equals(
                        stored.descriptor().schemaRef().fingerprint())).isPresent());
    }

    private Optional<String> currentSchemaFingerprint(String operatorRef) {
        if (operatorRef == null || operatorRef.isBlank() || catalog == null || mapper == null) return Optional.empty();
        return catalog.find(operatorRef.trim()).filter(this::hasUniqueTypedOutput)
                .map(operator -> GraphNodeFixturePromotionService.exactOutputSchemaRef(operator, mapper).fingerprint())
                .map(Optional::of).orElse(Optional.empty());
    }

    private boolean hasUniqueTypedOutput(OperatorDefinition operator) {
        return operator.ports().outputs().size() == 1
                && operator.ports().outputs().getFirst().schema() != null
                && !operator.ports().outputs().getFirst().schema().equals(
                        com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope.opaque());
    }

    /** Payload-free metadata projection for one governed Fixture head. */
    public record FixtureAssetSummary(
            String fixtureAssetId,
            long revision,
            ExactAssetRef exactRef,
            com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef schemaRef,
            FixtureAssetDescriptor.FixtureLifecycle lifecycle,
            String name,
            String variantKey,
            String classification,
            int usageCount,
            String currentSchemaFingerprint,
            boolean compatibleWithOperatorRef
    ) {
        /** Backward-compatible payload-free summary constructor without schema comparison. */
        public FixtureAssetSummary(String fixtureAssetId, long revision, ExactAssetRef exactRef,
                                   com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef schemaRef,
                                   FixtureAssetDescriptor.FixtureLifecycle lifecycle, String name,
                                   String variantKey, String classification, int usageCount) {
            this(fixtureAssetId, revision, exactRef, schemaRef, lifecycle, name, variantKey,
                    classification, usageCount, null, false);
        }
        public FixtureAssetSummary {
            if (fixtureAssetId == null || fixtureAssetId.isBlank() || revision < 1
                    || exactRef == null || schemaRef == null || lifecycle == null
                    || name == null || name.isBlank() || variantKey == null || variantKey.isBlank()
                    || classification == null || classification.isBlank() || usageCount < 0) {
                throw new IllegalArgumentException("Complete payload-free Fixture summary is required");
            }
        }
    }
}
