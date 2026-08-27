package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;

import java.util.List;
import java.util.Optional;

/** Scope-exact Fixture catalog and reverse-usage index. No method can return material payloads. */
public interface FixtureAssetRepository {

    /** Lists current descriptor heads in one exact authorized scope. */
    List<StoredFixtureAsset> listHeads(EnterpriseScope scope, boolean activeOnly, int limit, int offset);

    Optional<StoredFixtureAsset> findHead(EnterpriseScope scope, String fixtureAssetId);

    Optional<StoredFixtureAsset> findRevision(
            EnterpriseScope scope, String fixtureAssetId, long revision);

    List<StoredFixtureAsset> revisions(EnterpriseScope scope, String fixtureAssetId);

    Optional<StoredFixtureAsset> saveIfRevision(
            long expectedRevision,
            FixtureAssetDescriptor candidate,
            PrincipalRef actor);

    List<StoredFixtureAsset> resolveExact(
            EnterpriseScope scope, List<ExactAssetRef> fixtureAssetRefs);

    void replaceUsageForConsumer(
            EnterpriseScope scope,
            ExactAssetRef consumerRef,
            List<ExactAssetRef> fixtureAssetRefs);

    List<FixtureUsage> usages(
            EnterpriseScope scope,
            ExactAssetRef fixtureAssetRef,
            int limit);

    /** Counts all reverse-usage rows exactly, without a presentation-page limit. */
    int countUsages(EnterpriseScope scope, ExactAssetRef fixtureAssetRef);

    record FixtureUsage(ExactAssetRef fixtureAssetRef, ExactAssetRef consumerRef) {
        public FixtureUsage {
            if (fixtureAssetRef == null || consumerRef == null
                    || !"FIXTURE_ASSET".equals(fixtureAssetRef.kind())) {
                throw new IllegalArgumentException("Exact Fixture and consumer refs are required");
            }
        }
    }
}
