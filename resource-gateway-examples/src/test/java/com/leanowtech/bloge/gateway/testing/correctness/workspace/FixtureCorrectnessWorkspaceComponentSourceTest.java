package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureSource;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.QualityProfile;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RedactionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RetentionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.SourceKind;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository.FixtureReferenceUsage;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.Coordinate;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.PageRequest;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.Availability;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FixtureCorrectnessWorkspaceComponentSourceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    @Test
    void projectsExactUsageAndStaleReasonsWithoutFixtureMaterial() throws Exception {
        ScenarioDraftSetV2Repository scenarios = mock(ScenarioDraftSetV2Repository.class);
        FixtureAssetRepository fixtures = mock(FixtureAssetRepository.class);
        StoredFixtureAsset active = stored(
                "active-profile", 3, 'a', FixtureLifecycle.ACTIVE, NOW.plusSeconds(3600));
        StoredFixtureAsset old = stored(
                "old-pricing", 2, 'b', FixtureLifecycle.ACTIVE, NOW.plusSeconds(3600));
        StoredFixtureAsset newHead = stored(
                "old-pricing", 3, 'c', FixtureLifecycle.DRAFT, NOW.plusSeconds(3600));
        ExactAssetRef missing = new ExactAssetRef(
                "FIXTURE_ASSET", "deleted-risk", 1, fingerprint('d'));
        when(scenarios.fixtureUsagesByTarget(scope(), target())).thenReturn(List.of(
                usage("set-a", '1', active.exactRef()),
                usage("set-b", '2', active.exactRef()),
                usage("set-a", '1', old.exactRef()),
                usage("set-a", '1', missing)));
        when(fixtures.findRevision(scope(), active.exactRef().id(), 3))
                .thenReturn(Optional.of(active));
        when(fixtures.findHead(scope(), active.exactRef().id()))
                .thenReturn(Optional.of(active));
        when(fixtures.findRevision(scope(), old.exactRef().id(), 2))
                .thenReturn(Optional.of(old));
        when(fixtures.findHead(scope(), old.exactRef().id()))
                .thenReturn(Optional.of(newHead));
        var source = new FixtureCorrectnessWorkspaceComponentSource(
                new DefinitionOnlyCorrectnessWorkspaceComponentSource(), scenarios, fixtures,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = source.load(coordinate(), page());

        assertThat(result.fixtures().availability()).isEqualTo(Availability.AVAILABLE);
        assertThat(result.fixtures().total()).isEqualTo(3);
        assertThat(result.fixtures().active()).isEqualTo(1);
        assertThat(result.fixtures().stale()).isEqualTo(2);
        assertThat(result.fixtures().rows())
                .extracting(row -> row.descriptorRef().id() + ":" + row.usageCount())
                .containsExactly("active-profile:2", "old-pricing:1");
        assertThat(result.staleReasons()).extracting(value -> value.code())
                .containsExactly("FIXTURE_REFERENCE_MISSING", "FIXTURE_HEAD_DRIFT");
        assertThat(result.verdict().reasons()).extracting(value -> value.code())
                .contains("FIXTURE_REFERENCE_STALE");
        assertThat(result.capabilities())
                .contains("FIXTURE_CATALOG_METADATA_V1", "FIXTURE_USAGE_STALE_V1");

        String json = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(result);
        assertThat(json)
                .doesNotContain("customerPhone", "13800000000", "fixtureMaterial", "payload")
                .contains(active.descriptor().materialRef().fingerprint());
    }

    @Test
    void marksNonActiveAndExpiredCurrentHeadsAsStale() {
        ScenarioDraftSetV2Repository scenarios = mock(ScenarioDraftSetV2Repository.class);
        FixtureAssetRepository fixtures = mock(FixtureAssetRepository.class);
        StoredFixtureAsset draft = stored(
                "draft-profile", 1, 'e', FixtureLifecycle.DRAFT, NOW.plusSeconds(3600));
        StoredFixtureAsset expired = stored(
                "expired-profile", 1, 'f', FixtureLifecycle.ACTIVE, NOW);
        when(scenarios.fixtureUsagesByTarget(scope(), target())).thenReturn(List.of(
                usage("set-a", '1', draft.exactRef()), usage("set-a", '1', expired.exactRef())));
        when(fixtures.findRevision(scope(), draft.exactRef().id(), 1))
                .thenReturn(Optional.of(draft));
        when(fixtures.findHead(scope(), draft.exactRef().id())).thenReturn(Optional.of(draft));
        when(fixtures.findRevision(scope(), expired.exactRef().id(), 1))
                .thenReturn(Optional.of(expired));
        when(fixtures.findHead(scope(), expired.exactRef().id())).thenReturn(Optional.of(expired));
        var source = new FixtureCorrectnessWorkspaceComponentSource(
                new DefinitionOnlyCorrectnessWorkspaceComponentSource(), scenarios, fixtures,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var result = source.load(coordinate(), page());

        assertThat(result.staleReasons()).extracting(value -> value.code())
                .containsExactly("FIXTURE_NOT_ACTIVE", "FIXTURE_RETENTION_EXPIRED");
        assertThat(result.fixtures().active()).isZero();
        assertThat(result.fixtures().stale()).isEqualTo(2);
    }

    private static FixtureReferenceUsage usage(
            String setId,
            char setSeed,
            ExactAssetRef fixtureRef
    ) {
        return new FixtureReferenceUsage(
                new ExactAssetRef("SCENARIO_DRAFT_SET", setId, 2, fingerprint(setSeed)),
                fixtureRef);
    }

    private static StoredFixtureAsset stored(
            String id,
            long revision,
            char descriptorSeed,
            FixtureLifecycle lifecycle,
            Instant expiresAt
    ) {
        var descriptor = new FixtureAssetDescriptor(
                "", id, revision, scope(), "Customer profile",
                new FixtureSource(SourceKind.SAMPLE, null),
                new ExactAssetRef("FIXTURE_MATERIAL", id, 2, fingerprint('9')),
                new ExactSchemaRef("profile-schema", 2, fingerprint('8')), "eligible",
                lifecycle, "RESTRICTED", owner(),
                new RedactionDescriptor("redaction-v2", List.of("/phone"), true),
                new RetentionDescriptor("retention-v2", 30, expiresAt),
                new QualityProfile(true, true, 0, 1), List.of("profile"), metadata());
        return new StoredFixtureAsset("", fingerprint(descriptorSeed), descriptor);
    }

    private static Coordinate coordinate() {
        return new Coordinate(
                scope(), new ExactAssetRef("DEFINITION", "loan", 1, fingerprint('6')),
                target(), null);
    }

    private static PageRequest page() {
        return new PageRequest("", 20, fingerprint('5'));
    }

    private static ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('7'));
    }

    private static EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg");
    }

    private static PrincipalRef owner() {
        return new PrincipalRef("credit-owner", PrincipalKind.TEAM, "Credit Owner");
    }

    private static AuditMetadata metadata() {
        return new AuditMetadata(NOW, NOW, owner(), owner());
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
