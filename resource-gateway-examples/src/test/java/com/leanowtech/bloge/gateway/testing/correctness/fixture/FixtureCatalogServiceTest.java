package com.leanowtech.bloge.gateway.testing.correctness.fixture;

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
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.FixtureSubject;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.DatabaseFixtureAssetRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureCatalogServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T11:00:00Z");

    private DatabaseFixtureAssetRepository repository;
    private Receipt receipt;
    private FixtureCatalogService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-correctness-fixture-schema.sql")).execute(database);
        repository = new DatabaseFixtureAssetRepository(
                new JdbcTemplate(database), mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        receipt = receipt();
        FixtureMaterialMetadataSource materials = (scope, ref) ->
                scope.equals(scope()) && ref.equals(receipt.materialRef())
                        ? Optional.of(receipt) : Optional.empty();
        service = new FixtureCatalogService(
                repository, materials,
                (scope, schema) -> scope.equals(scope()) && schema.equals(schema()),
                (scope, fixture, actor) -> actor.id().equals(fixture.owner().id())
                        ? FixtureReviewAuthorizer.ApprovalDecision.ownerReview()
                        : FixtureReviewAuthorizer.ApprovalDecision.denied(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void enforcesDraftProposedApprovedActiveLifecycleAndServerQuality() {
        var draft = service.saveDraft(0, descriptor(0, FixtureLifecycle.DRAFT, owner()), author());
        assertThat(draft.descriptor().lifecycle()).isEqualTo(FixtureLifecycle.DRAFT);
        assertThat(draft.descriptor().quality())
                .isEqualTo(new QualityProfile(true, true, 0, 0));

        var proposed = service.submitForReview(scope(), "prime-applicant", 1, author());
        assertThat(proposed.descriptor().lifecycle()).isEqualTo(FixtureLifecycle.PROPOSED);
        var approved = service.approve(scope(), "prime-applicant", 2, owner());
        assertThat(approved.descriptor().lifecycle()).isEqualTo(FixtureLifecycle.APPROVED);
        var active = service.activate(scope(), "prime-applicant", 3, author());
        assertThat(active.descriptor().lifecycle()).isEqualTo(FixtureLifecycle.ACTIVE);
        assertThat(repository.revisions(scope(), "prime-applicant"))
                .extracting(stored -> stored.descriptor().lifecycle())
                .containsExactly(
                        FixtureLifecycle.ACTIVE, FixtureLifecycle.APPROVED,
                        FixtureLifecycle.PROPOSED, FixtureLifecycle.DRAFT);
    }

    @Test
    void cannotSaveActiveOrBypassIndependentOwnerReview() {
        assertThatThrownBy(() -> service.saveDraft(
                0, descriptor(0, FixtureLifecycle.ACTIVE, owner()), author()))
                .isInstanceOf(FixtureCatalogCommandException.class)
                .extracting(error -> ((FixtureCatalogCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FIXTURE_DRAFT_INVALID");

        service.saveDraft(0, descriptor(0, FixtureLifecycle.DRAFT, author()), author());
        service.submitForReview(scope(), "prime-applicant", 1, author());
        assertThatThrownBy(() -> service.approve(scope(), "prime-applicant", 2, author()))
                .isInstanceOf(FixtureCatalogCommandException.class)
                .extracting(error -> ((FixtureCatalogCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FOUR_EYES_REQUIRED");
    }

    @Test
    void failsClosedOnSchemaMaterialBindingOrRetentionDrift() {
        FixtureCatalogService noSchema = new FixtureCatalogService(
                repository, (scope, ref) -> Optional.of(receipt),
                FixtureSchemaSource.denyAll(), FixtureReviewAuthorizer.denyAll(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> noSchema.saveDraft(
                0, descriptor(0, FixtureLifecycle.DRAFT, owner()), author()))
                .isInstanceOf(FixtureCatalogCommandException.class)
                .extracting(error -> ((FixtureCatalogCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FIXTURE_SCHEMA_DRIFT");

        FixtureAssetDescriptor mismatched = new FixtureAssetDescriptor(
                "", "prime-applicant", 0, scope(), "Prime applicant",
                receipt.source(), receipt.materialRef(), receipt.schemaRef(), "prime",
                FixtureLifecycle.DRAFT, "INTERNAL", owner(), receipt.redaction(),
                receipt.retention(), new QualityProfile(false, false, 99, 99),
                List.of("loan"), metadata());
        assertThatThrownBy(() -> service.saveDraft(0, mismatched, author()))
                .isInstanceOf(FixtureCatalogCommandException.class)
                .extracting(error -> ((FixtureCatalogCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FIXTURE_MATERIAL_BINDING_MISMATCH");

        FixtureCatalogService expired = new FixtureCatalogService(
                repository, (scope, ref) -> Optional.of(receipt),
                (scope, schema) -> true, FixtureReviewAuthorizer.denyAll(),
                Clock.fixed(NOW.plusSeconds(86_401), ZoneOffset.UTC));
        assertThatThrownBy(() -> expired.saveDraft(
                0, descriptor(0, FixtureLifecycle.DRAFT, owner()), author()))
                .isInstanceOf(FixtureCatalogCommandException.class)
                .extracting(error -> ((FixtureCatalogCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FIXTURE_MATERIAL_EXPIRED");
    }

    @Test
    void activeRevisionCanOnlyEvolveThroughANewDraftAndOldExactRefRemainsReadable() {
        var draft = service.saveDraft(0, descriptor(0, FixtureLifecycle.DRAFT, owner()), author());
        service.submitForReview(scope(), "prime-applicant", 1, author());
        service.approve(scope(), "prime-applicant", 2, owner());
        var active = service.activate(scope(), "prime-applicant", 3, author());

        var nextDraft = service.saveDraft(
                4, descriptor(4, FixtureLifecycle.DRAFT, owner()), author());

        assertThat(nextDraft.descriptor().lifecycle()).isEqualTo(FixtureLifecycle.DRAFT);
        assertThat(repository.findRevision(scope(), "prime-applicant", active.descriptor().revision()))
                .contains(active);
        assertThat(repository.findRevision(scope(), "prime-applicant", draft.descriptor().revision()))
                .contains(draft);
    }

    private FixtureAssetDescriptor descriptor(
            long revision, FixtureLifecycle lifecycle, PrincipalRef owner) {
        return new FixtureAssetDescriptor(
                "", "prime-applicant", revision, scope(), "Prime applicant", receipt.source(),
                receipt.materialRef(), receipt.schemaRef(), "prime", lifecycle, "RESTRICTED", owner,
                receipt.redaction(), receipt.retention(),
                new QualityProfile(false, false, 17, 23), List.of("loan"), metadata());
    }

    private static Receipt receipt() {
        return new Receipt(
                "", "prime-applicant", asset("FIXTURE_MATERIAL", "prime-applicant", 1, 'c'),
                fingerprint('c'), new FixtureSource(SourceKind.INCIDENT_CAPTURE,
                        asset("INCIDENT", "incident-17", 2, 'd')),
                FixtureSubject.GRAPH,
                new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 7, fingerprint('a')),
                schema(), "RESTRICTED",
                new RetentionDescriptor("retention-v2", 1, NOW.plusSeconds(86_400)),
                new RedactionDescriptor("redaction-v2", List.of("/phone"), true),
                List.of(asset("INCIDENT", "incident-17", 2, 'd')), true, false);
    }

    private static ExactSchemaRef schema() {
        return new ExactSchemaRef("loan-request", 3, fingerprint('b'));
    }

    private static EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg");
    }

    private static PrincipalRef author() {
        return new PrincipalRef("author-a", PrincipalKind.USER, "Author A");
    }

    private static PrincipalRef owner() {
        return new PrincipalRef("owner-a", PrincipalKind.USER, "Owner A");
    }

    private static AuditMetadata metadata() {
        Instant forged = Instant.parse("2001-01-01T00:00:00Z");
        return new AuditMetadata(forged, forged, author(), author());
    }

    private static ExactAssetRef asset(String kind, String id, long revision, char seed) {
        return new ExactAssetRef(kind, id, revision, fingerprint(seed));
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
