package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;

import java.util.List;
import java.util.Objects;

/** Payload-free, scope-exact governed Fixture collection and trusted usage projection. */
public final class FixtureAssetCollectionService {
    /** Default page size for the governed Fixture collection. */
    public static final int DEFAULT_LIMIT = 50;
    /** Maximum page size for the governed Fixture collection. */
    public static final int MAX_LIMIT = 100;
    /** Maximum offset for the governed Fixture collection. */
    public static final int MAX_OFFSET = 100_000;

    private final FixtureAssetRepository fixtures;

    /** Creates a collection service backed by the canonical Fixture repository. */
    public FixtureAssetCollectionService(FixtureAssetRepository fixtures) {
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
    }

    /** Lists bounded descriptor heads; usage is read only from the reverse-usage index. */
    public List<FixtureAssetSummary> list(
            EnterpriseScope scope, boolean activeOnly, int limit, int offset) {
        if (limit < 1 || limit > MAX_LIMIT || offset < 0 || offset > MAX_OFFSET) {
            throw new IllegalArgumentException("Fixture collection bounds are invalid");
        }
        return fixtures.listHeads(scope, activeOnly, limit, offset).stream()
                .map(this::summary).toList();
    }

    private FixtureAssetSummary summary(StoredFixtureAsset stored) {
        FixtureAssetDescriptor descriptor = stored.descriptor();
        int usageCount = fixtures.countUsages(descriptor.scope(), stored.exactRef());
        return new FixtureAssetSummary(
                descriptor.fixtureAssetId(), descriptor.revision(), stored.exactRef(),
                descriptor.schemaRef(), descriptor.lifecycle(), descriptor.name(),
                descriptor.variantKey(), descriptor.classification(), usageCount);
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
            int usageCount
    ) {
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
