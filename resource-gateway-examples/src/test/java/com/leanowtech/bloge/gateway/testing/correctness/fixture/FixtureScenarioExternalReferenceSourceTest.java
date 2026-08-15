package com.leanowtech.bloge.gateway.testing.correctness.fixture;

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
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FixtureScenarioExternalReferenceSourceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    @Test
    void acceptsOnlyExactCurrentActiveAndUnexpiredFixtureMetadata() {
        FixtureAssetRepository fixtures = mock(FixtureAssetRepository.class);
        StoredFixtureAsset active = stored(
                4, 'a', FixtureLifecycle.ACTIVE, NOW.plusSeconds(3600));
        when(fixtures.findRevision(scope(), "profile", 4)).thenReturn(Optional.of(active));
        when(fixtures.findHead(scope(), "profile")).thenReturn(Optional.of(active));
        var source = new FixtureScenarioExternalReferenceSource(
                fixtures, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(source.referenceIsCurrent(scope(), target(), active.exactRef())).isTrue();
        assertThat(source.referenceIsCurrent(
                scope(), target(), new ExactAssetRef(
                        "FIXTURE_ASSET", "profile", 4, fingerprint('b')))).isFalse();
        assertThat(source.referenceIsCurrent(
                scope(), target(), new ExactAssetRef("ORACLE", "profile", 4, fingerprint('a'))))
                .isFalse();
    }

    @Test
    void rejectsOldNonActiveAndExpiredFixtureRevisions() {
        FixtureAssetRepository fixtures = mock(FixtureAssetRepository.class);
        StoredFixtureAsset old = stored(
                3, 'c', FixtureLifecycle.ACTIVE, NOW.plusSeconds(3600));
        StoredFixtureAsset currentDraft = stored(
                4, 'd', FixtureLifecycle.DRAFT, NOW.plusSeconds(3600));
        StoredFixtureAsset expired = stored(
                5, 'e', FixtureLifecycle.ACTIVE, NOW.minusSeconds(1));
        when(fixtures.findRevision(scope(), "profile", 3)).thenReturn(Optional.of(old));
        when(fixtures.findRevision(scope(), "profile", 4)).thenReturn(Optional.of(currentDraft));
        when(fixtures.findRevision(scope(), "profile", 5)).thenReturn(Optional.of(expired));
        when(fixtures.findHead(scope(), "profile")).thenReturn(Optional.of(currentDraft));
        var source = new FixtureScenarioExternalReferenceSource(
                fixtures, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(source.referenceIsCurrent(scope(), target(), old.exactRef())).isFalse();
        assertThat(source.referenceIsCurrent(scope(), target(), currentDraft.exactRef())).isFalse();
        when(fixtures.findHead(scope(), "profile")).thenReturn(Optional.of(expired));
        assertThat(source.referenceIsCurrent(scope(), target(), expired.exactRef())).isFalse();
    }

    @Test
    void composesWithResolversForOtherAssetKinds() {
        FixtureAssetRepository fixtures = mock(FixtureAssetRepository.class);
        var fixtureSource = new FixtureScenarioExternalReferenceSource(
                fixtures, Clock.fixed(NOW, ZoneOffset.UTC));
        var combined = fixtureSource.orElse(
                (scope, target, reference) -> "CONTRACT".equals(reference.kind()));

        assertThat(combined.referenceIsCurrent(
                scope(), target(), new ExactAssetRef(
                        "CONTRACT", "loan", 1, fingerprint('f')))).isTrue();
    }

    private static StoredFixtureAsset stored(
            long revision,
            char descriptorSeed,
            FixtureLifecycle lifecycle,
            Instant expiresAt
    ) {
        var descriptor = new FixtureAssetDescriptor(
                "", "profile", revision, scope(), "Customer profile",
                new FixtureSource(SourceKind.SAMPLE, null),
                new ExactAssetRef("FIXTURE_MATERIAL", "profile", 2, fingerprint('9')),
                new ExactSchemaRef("profile-schema", 2, fingerprint('8')), "eligible",
                lifecycle, "RESTRICTED", owner(),
                new RedactionDescriptor("redaction-v2", List.of("/phone"), true),
                new RetentionDescriptor("retention-v2", 30, expiresAt),
                new QualityProfile(true, true, 0, 1), List.of("profile"), metadata());
        return new StoredFixtureAsset("", fingerprint(descriptorSeed), descriptor);
    }

    private static EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg");
    }

    private static ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('7'));
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
