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
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;

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
    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private Receipt receipt;
    private FixtureCatalogService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        var database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-correctness-fixture-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseFixtureAssetRepository(
                jdbc, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
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
    void verifiesProposedMetadataWithFourEyesAndPreservesServerOwnedFields() {
        service.saveDraft(0, descriptor(0, FixtureLifecycle.DRAFT, owner()), author());
        service.submitForReview(scope(), "prime-applicant", 1, author());
        FixtureCatalogService reviewerService = serviceWith(
                (reviewScope, fixture, reviewer) -> FixtureReviewAuthorizer.ApprovalDecision.ownerReview());

        StoredFixtureAsset proposed = repository.findHead(scope(), "prime-applicant").orElseThrow();
        StoredFixtureAsset verified = reviewerService.verifyForApproval(
                scope(), "prime-applicant", proposed.descriptor().revision(),
                new FixtureReviewVerificationRequest(true, true, "Redaction reviewed by reviewer"),
                owner());

        assertThat(verified.descriptor().lifecycle()).isEqualTo(FixtureLifecycle.PROPOSED);
        assertThat(verified.descriptor().redaction().reviewed()).isTrue();
        assertThat(verified.descriptor().quality())
                .isEqualTo(new QualityProfile(true, true, 0, 0));
        assertThat(verified.descriptor().materialRef()).isEqualTo(proposed.descriptor().materialRef());
        assertThat(verified.descriptor().schemaRef()).isEqualTo(proposed.descriptor().schemaRef());
        assertThat(verified.descriptor().scope()).isEqualTo(proposed.descriptor().scope());
        assertThat(verified.descriptor().owner()).isEqualTo(proposed.descriptor().owner());
        assertThat(verified.descriptor().retention()).isEqualTo(proposed.descriptor().retention());
        assertThat(repository.revisions(scope(), "prime-applicant"))
                .extracting(stored -> stored.descriptor().revision())
                .containsExactly(3L, 2L, 1L);
    }

    @Test
    void allowsApprovalAfterOnlyReviewerRedactionAttestationChanges() {
        receipt = receipt(false);
        service.saveDraft(0, descriptor(0, FixtureLifecycle.DRAFT, owner()), author());
        service.submitForReview(scope(), "prime-applicant", 1, author());

        StoredFixtureAsset verified = serviceWith(
                (reviewScope, fixture, reviewer) -> FixtureReviewAuthorizer.ApprovalDecision.ownerReview())
                .verifyForApproval(
                        scope(), "prime-applicant", 2,
                        new FixtureReviewVerificationRequest(true, true, "Independent review"), owner());

        assertThat(verified.descriptor().redaction().reviewed()).isTrue();
        assertThat(service.approve(scope(), "prime-applicant", 3, owner())
                .descriptor().lifecycle()).isEqualTo(FixtureLifecycle.APPROVED);
    }

    @Test
    void rejectsProtectedRedactionProfileVersionDriftAfterVerification() {
        receipt = receipt(false);
        service.saveDraft(0, descriptor(0, FixtureLifecycle.DRAFT, owner()), author());
        service.submitForReview(scope(), "prime-applicant", 1, author());
        serviceWith((reviewScope, fixture, reviewer) ->
                FixtureReviewAuthorizer.ApprovalDecision.ownerReview()).verifyForApproval(
                scope(), "prime-applicant", 2,
                new FixtureReviewVerificationRequest(true, true, "Independent review"), owner());
        receipt = receipt(true, "redaction-tampered", List.of("/phone"));

        assertThatThrownBy(() -> service.approve(scope(), "prime-applicant", 3, owner()))
                .isInstanceOf(FixtureCatalogCommandException.class)
                .extracting(error -> ((FixtureCatalogCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FIXTURE_MATERIAL_BINDING_MISMATCH");
    }

    @Test
    void rejectsProtectedRedactedPathsDriftAfterVerification() {
        receipt = receipt(false);
        service.saveDraft(0, descriptor(0, FixtureLifecycle.DRAFT, owner()), author());
        service.submitForReview(scope(), "prime-applicant", 1, author());
        serviceWith((reviewScope, fixture, reviewer) ->
                FixtureReviewAuthorizer.ApprovalDecision.ownerReview()).verifyForApproval(
                scope(), "prime-applicant", 2,
                new FixtureReviewVerificationRequest(true, true, "Independent review"), owner());
        receipt = receipt(true, "redaction-v2", List.of("/email"));

        assertThatThrownBy(() -> service.approve(scope(), "prime-applicant", 3, owner()))
                .isInstanceOf(FixtureCatalogCommandException.class)
                .extracting(error -> ((FixtureCatalogCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FIXTURE_MATERIAL_BINDING_MISMATCH");
    }

    @Test
    void rejectsIncompleteUnauthorizedSameCreatorAndStaleReviewVerification() {
        FixtureCatalogService reviewerService = serviceWith(
                (reviewScope, fixture, reviewer) -> FixtureReviewAuthorizer.ApprovalDecision.ownerReview());
        service.saveDraft(0, descriptor(0, FixtureLifecycle.DRAFT, owner()), author());
        assertThatThrownBy(() -> reviewerService.verifyForApproval(
                scope(), "prime-applicant", 1,
                new FixtureReviewVerificationRequest(true, true, "Not yet proposed"), author()))
                .isInstanceOf(FixtureCatalogCommandException.class)
                .extracting(error -> ((FixtureCatalogCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FIXTURE_TRANSITION_INVALID");

        service.submitForReview(scope(), "prime-applicant", 1, author());
        assertThatThrownBy(() -> reviewerService.verifyForApproval(
                scope(), "prime-applicant", 2,
                new FixtureReviewVerificationRequest(false, true, "Missing acknowledgement"), owner()))
                .isInstanceOf(FixtureCatalogCommandException.class)
                .extracting(error -> ((FixtureCatalogCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FIXTURE_REVIEW_INVALID");
        assertThatThrownBy(() -> reviewerService.verifyForApproval(
                scope(), "prime-applicant", 2, null, owner()))
                .isInstanceOf(FixtureCatalogCommandException.class)
                .extracting(error -> ((FixtureCatalogCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FIXTURE_REVIEW_INVALID");

        assertThatThrownBy(() -> reviewerService.verifyForApproval(
                scope(), "prime-applicant", 2,
                new FixtureReviewVerificationRequest(true, true, "Creator cannot attest"), author()))
                .isInstanceOf(FixtureCatalogCommandException.class)
                .extracting(error -> ((FixtureCatalogCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FOUR_EYES_REQUIRED");

        StoredFixtureAsset verified = reviewerService.verifyForApproval(
                scope(), "prime-applicant", 2,
                new FixtureReviewVerificationRequest(true, true, "Independent review"), owner());
        assertThatThrownBy(() -> reviewerService.verifyForApproval(
                scope(), "prime-applicant", 2,
                new FixtureReviewVerificationRequest(true, true, "Stale revision"), owner()))
                .isInstanceOf(FixtureCatalogCommandException.class)
                .extracting(error -> ((FixtureCatalogCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.REVISION_CONFLICT");
        assertThat(verified.descriptor().revision()).isEqualTo(3);

        FixtureCatalogService unauthorized = serviceWith(FixtureReviewAuthorizer.denyAll());
        assertThatThrownBy(() -> unauthorized.verifyForApproval(
                scope(), "prime-applicant", 3,
                new FixtureReviewVerificationRequest(true, true, "Denied reviewer"), owner()))
                .isInstanceOf(FixtureCatalogCommandException.class)
                .extracting(error -> ((FixtureCatalogCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.FIXTURE_APPROVAL_FORBIDDEN");
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

    @Test
    void ownerApprovalIsIdempotentAndRawKeyNeverPersists() {
        FixtureCatalogService governed = new FixtureCatalogService(
                repository,
                (scope, ref) -> ref.equals(receipt.materialRef())
                        ? Optional.of(receipt) : Optional.empty(),
                (scope, schema) -> schema.equals(schema()),
                (scope, fixture, actor) -> FixtureReviewAuthorizer.ApprovalDecision.ownerReview(),
                new DatabaseFixtureApprovalReceiptRepository(jdbc, mapper), mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        governed.saveDraft(0, descriptor(0, FixtureLifecycle.DRAFT, owner()), author());
        governed.submitForReview(scope(), "prime-applicant", 1, author());

        var first = governed.approveIdempotently(
                scope(), "prime-applicant", 2, "PII redaction and lineage reviewed",
                owner(), "fixture-approval-key-1");
        var replay = governed.approveIdempotently(
                scope(), "prime-applicant", 2, "PII redaction and lineage reviewed",
                owner(), "fixture-approval-key-1");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.stored()).isEqualTo(first.stored());
        String receiptJson = jdbc.queryForObject(
                "SELECT receipt_json FROM rg_correctness_command_receipts", String.class);
        assertThat(receiptJson).doesNotContain("fixture-approval-key-1");
        assertThat(receiptJson).doesNotContain("PII redaction and lineage reviewed");
        assertThatThrownBy(() -> governed.approveIdempotently(
                scope(), "prime-applicant", 2, "Different review", owner(),
                "fixture-approval-key-1"))
                .isInstanceOf(FixtureCatalogCommandException.class)
                .extracting(error -> ((FixtureCatalogCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.IDEMPOTENCY_CONFLICT");
    }

    private FixtureAssetDescriptor descriptor(
            long revision, FixtureLifecycle lifecycle, PrincipalRef owner) {
        return new FixtureAssetDescriptor(
                "", "prime-applicant", revision, scope(), "Prime applicant", receipt.source(),
                receipt.materialRef(), receipt.schemaRef(), "prime", lifecycle, "RESTRICTED", owner,
                receipt.redaction(), receipt.retention(),
                new QualityProfile(false, false, 17, 23), List.of("loan"), metadata());
    }

    private FixtureCatalogService serviceWith(FixtureReviewAuthorizer authorizer) {
        return new FixtureCatalogService(
                repository, (scope, ref) -> scope.equals(scope()) && ref.equals(receipt.materialRef())
                        ? Optional.of(receipt) : Optional.empty(),
                (scope, schema) -> scope.equals(scope()) && schema.equals(schema()),
                authorizer, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Receipt receipt() {
        return receipt(true);
    }

    private static Receipt receipt(boolean reviewed) {
        return receipt(reviewed, "redaction-v2", List.of("/phone"));
    }

    private static Receipt receipt(
            boolean reviewed, String profileVersion, List<String> redactedPaths) {
        return new Receipt(
                "", "prime-applicant", asset("FIXTURE_MATERIAL", "prime-applicant", 1, 'c'),
                fingerprint('c'), new FixtureSource(SourceKind.INCIDENT_CAPTURE,
                        asset("INCIDENT", "incident-17", 2, 'd')),
                FixtureSubject.GRAPH,
                new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 7, fingerprint('a')),
                schema(), "RESTRICTED",
                new RetentionDescriptor("retention-v2", 1, NOW.plusSeconds(86_400)),
                new RedactionDescriptor(profileVersion, redactedPaths, reviewed),
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
