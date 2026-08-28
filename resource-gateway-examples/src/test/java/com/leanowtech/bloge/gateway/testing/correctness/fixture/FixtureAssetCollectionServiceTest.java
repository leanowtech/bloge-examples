package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.*;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.*;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FixtureAssetCollectionServiceTest {
    @Test
    void mapsOnlyDescriptorMetadataAndUsageIndexFacts() {
        FixtureAssetRepository repository = mock(FixtureAssetRepository.class);
        EnterpriseScope scope = new EnterpriseScope("t", "o", "p", "test", "sg");
        StoredFixtureAsset stored = StoredFixtureAsset.verified(new ObjectMapper().findAndRegisterModules(),
                descriptor(scope));
        when(repository.listHeads(scope, true, 50, 0)).thenReturn(List.of(stored));
        when(repository.countUsages(scope, stored.exactRef())).thenReturn(3);

        var summary = new FixtureAssetCollectionService(repository).list(scope, true, 50, 0);

        assertThat(summary).singleElement().satisfies(value -> {
            assertThat(value.fixtureAssetId()).isEqualTo("profile");
            assertThat(value.usageCount()).isEqualTo(3);
            assertThat(value.lifecycle()).isEqualTo(FixtureLifecycle.ACTIVE);
        });
        verify(repository).countUsages(scope, stored.exactRef());
    }

    @Test
    void rejectsUnboundedCollectionRequestsAtServiceBoundary() {
        FixtureAssetRepository repository = mock(FixtureAssetRepository.class);
        var service = new FixtureAssetCollectionService(repository);
        var scope = new EnterpriseScope("t", "o", "p", "test", "sg");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.list(scope, true, FixtureAssetCollectionService.MAX_LIMIT + 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void reportsUnknownAndStaleOperatorCompatibilityWithoutReadingMaterial() {
        FixtureAssetRepository repository = mock(FixtureAssetRepository.class);
        VisualOperatorCatalog catalog = mock(VisualOperatorCatalog.class);
        EnterpriseScope scope = new EnterpriseScope("t", "o", "p", "test", "sg");
        StoredFixtureAsset stored = StoredFixtureAsset.verified(new ObjectMapper().findAndRegisterModules(), descriptor(scope));
        when(repository.listHeads(scope, true, 50, 0)).thenReturn(List.of(stored));
        when(repository.countUsages(scope, stored.exactRef())).thenReturn(0);
        when(catalog.find("resource:profile")).thenReturn(java.util.Optional.of(operator()));

        var service = new FixtureAssetCollectionService(repository, catalog,
                new ObjectMapper().findAndRegisterModules());
        var unknown = service.list(scope, true, 50, 0, "resource:missing").getFirst();
        var stale = service.list(scope, true, 50, 0, "resource:profile").getFirst();

        assertThat(unknown.compatibleWithOperatorRef()).isFalse();
        assertThat(unknown.currentSchemaFingerprint()).isNull();
        assertThat(stale.compatibleWithOperatorRef()).isFalse();
        assertThat(stale.currentSchemaFingerprint()).startsWith("sha256:");
        verify(repository, times(2)).countUsages(scope, stored.exactRef());
    }

    @Test
    void reportsCompatibleWhenStoredSchemaUsesTheSharedExactSchemaDerivation() {
        FixtureAssetRepository repository = mock(FixtureAssetRepository.class);
        VisualOperatorCatalog catalog = mock(VisualOperatorCatalog.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        EnterpriseScope scope = new EnterpriseScope("t", "o", "p", "test", "sg");
        OperatorDefinition operator = operator();
        String fingerprint = com.leanowtech.bloge.gateway.visualadapter.fixture.GraphNodeFixturePromotionService
                .exactOutputSchemaRef(operator, mapper).fingerprint();
        StoredFixtureAsset stored = StoredFixtureAsset.verified(mapper,
                descriptor(scope, new ExactSchemaRef("profile", 1, fingerprint)));
        when(repository.listHeads(scope, true, 50, 0)).thenReturn(List.of(stored));
        when(repository.countUsages(scope, stored.exactRef())).thenReturn(0);
        when(catalog.find("resource:profile")).thenReturn(java.util.Optional.of(operator));

        var summary = new FixtureAssetCollectionService(repository, catalog, mapper)
                .list(scope, true, 50, 0, "resource:profile").getFirst();

        assertThat(summary.compatibleWithOperatorRef()).isTrue();
        assertThat(summary.currentSchemaFingerprint()).isEqualTo(fingerprint);
    }

    private static FixtureAssetDescriptor descriptor(EnterpriseScope scope) {
        return descriptor(scope, new ExactSchemaRef("profile", 1, fp('b')));
    }

    private static FixtureAssetDescriptor descriptor(EnterpriseScope scope, ExactSchemaRef schema) {
        var ref = new ExactAssetRef("FIXTURE_MATERIAL", "profile", 1, fp('a'));
        return new FixtureAssetDescriptor("", "profile", 1, scope, "Profile",
                new FixtureSource(SourceKind.SAMPLE, null), ref,
                schema, "default", FixtureLifecycle.ACTIVE,
                "INTERNAL", new PrincipalRef("owner", PrincipalKind.USER, ""),
                new RedactionDescriptor("r1", List.of(), true),
                new RetentionDescriptor("t1", 1, Instant.parse("2026-09-01T00:00:00Z")),
                new QualityProfile(true, true, 0, 0), List.of(),
                new AuditMetadata(Instant.EPOCH, Instant.EPOCH,
                        new PrincipalRef("owner", PrincipalKind.USER, ""),
                        new PrincipalRef("owner", PrincipalKind.USER, "")));
    }
    private static String fp(char value) { return "sha256:" + String.valueOf(value).repeat(64); }

    private static OperatorDefinition operator() {
        SchemaEnvelope schema = SchemaEnvelope.object(Map.of("score", Map.of("type", "integer")), List.of("score"));
        return new OperatorDefinition("", "resource:profile", "1", "",
                new OperatorDefinition.Display("Profile", "", List.of()),
                new OperatorDefinition.Source("resource", "profile", "GET", "/", true),
                new OperatorDefinition.Ports(List.of(), List.of(new OperatorDefinition.Port("payload", schema, true, ""))),
                SchemaEnvelope.opaque(), OperatorDefinition.Capabilities.pure(), null,
                new OperatorDefinition.Lowering("native", "", Map.of()), List.of());
    }
}
