package com.leanowtech.bloge.gateway.testing.correctness.coverage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.coverage.CoverageDerivationSource.DerivationSnapshot;
import com.leanowtech.bloge.gateway.testing.correctness.coverage.CoverageImpactProposal.ChangeKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSourceSnapshotRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewStatus;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.Waiver;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.CoverageObligation;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.InventoryLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationDimension;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationSource;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.DatabaseCoverageInventoryRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoverageInventoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T08:00:00Z");

    private DatabaseCoverageInventoryRepository repository;
    private ObjectMapper mapper;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        var database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-coverage-inventory-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        repository = new DatabaseCoverageInventoryRepository(jdbc, mapper, fixedClock());
    }

    @Test
    void freezesResolvedDenominatorWithIndependentAuthorizedReview() {
        CoverageInventoryService service = service(
                (scope, inventory, actor) -> actor.id().equals(reviewer().id()), unchangedSource());
        var draft = service.saveDraft(0, inventory(
                0, resolvedObligations(waiverExpiring("2027-08-15T00:00:00Z"))), author());

        CoverageInventoryService.FreezeResult frozen = service.freeze(
                scope(), "loan-inventory", draft.inventory().revision(),
                "Freeze denominator", reviewer());

        assertThat(frozen.stored().inventory().lifecycle()).isEqualTo(InventoryLifecycle.FROZEN);
        assertThat(frozen.stored().inventory().revision()).isEqualTo(2);
        assertThat(frozen.obligationCount()).isEqualTo(3);
        assertThat(frozen.waivedCount()).isEqualTo(1);
        assertThat(repository.findRevision(scope(), "loan-inventory", 1)
                .orElseThrow().inventory().lifecycle()).isEqualTo(InventoryLifecycle.DRAFT);
        assertThatThrownBy(() -> service.saveDraft(
                2, inventory(2, resolvedObligations(
                        waiverExpiring("2027-08-15T00:00:00Z"))), author()))
                .isInstanceOf(CoverageCommandException.class)
                .extracting(error -> ((CoverageCommandException) error).code())
                .isEqualTo("RG.CORRECTNESS.INVENTORY_IMMUTABLE");
    }

    @Test
    void freezeFailsClosedForProposedExpiredUnauthorizedOrSameActorReview() {
        CoverageInventoryService denied = service((scope, inventory, actor) -> false,
                unchangedSource());
        denied.saveDraft(0, inventory(
                0, resolvedObligations(waiverExpiring("2027-08-15T00:00:00Z"))), author());
        assertCode(() -> denied.freeze(
                scope(), "loan-inventory", 1, "Freeze denominator", reviewer()),
                "RG.CORRECTNESS.FREEZE_FORBIDDEN");

        DatabaseCoverageInventoryRepository proposedRepository = newRepository();
        CoverageInventoryService proposed = serviceWith(
                proposedRepository, (scope, inventory, actor) -> true, unchangedSource());
        proposed.saveDraft(0, inventory(0, List.of(
                obligation("policy.eligibility", "Eligibility", ObligationLifecycle.PROPOSED,
                        null, RiskLevel.CRITICAL))), author());
        assertCode(() -> proposed.freeze(
                scope(), "loan-inventory", 1, "Freeze denominator", reviewer()),
                "RG.CORRECTNESS.OBLIGATION_REVIEW_REQUIRED");

        DatabaseCoverageInventoryRepository expiredRepository = newRepository();
        CoverageInventoryService expired = serviceWith(
                expiredRepository, (scope, inventory, actor) -> true, unchangedSource());
        expired.saveDraft(0, inventory(
                0, resolvedObligations(waiverExpiring("2026-08-15T07:59:59Z"))), author());
        assertCode(() -> expired.freeze(
                scope(), "loan-inventory", 1, "Freeze denominator", reviewer()),
                "RG.CORRECTNESS.WAIVER_EXPIRED");

        DatabaseCoverageInventoryRepository sameActorRepository = newRepository();
        CoverageInventoryService sameActor = serviceWith(
                sameActorRepository, (scope, inventory, actor) -> true, unchangedSource());
        sameActor.saveDraft(0, inventory(
                0, resolvedObligations(waiverExpiring("2027-08-15T00:00:00Z"))), author());
        assertCode(() -> sameActor.freeze(
                scope(), "loan-inventory", 1, "Self review", author()),
                "RG.CORRECTNESS.FOUR_EYES_REQUIRED");
    }

    @Test
    void targetAndSourceDriftProduceProposalWithoutMutatingFrozenDenominator() {
        ExactTargetRef nextTarget = new ExactTargetRef(
                TargetKind.GRAPH, "loan-graph", 4, fingerprint('d'));
        CoverageDerivationSource source = (scope, requested) -> new DerivationSnapshot(
                scope, requested,
                List.of(
                        new ExactSourceSnapshotRef("CONTRACT", "loan-contract", 3,
                                fingerprint('e')),
                        new ExactSourceSnapshotRef("DAG", "loan-graph", 4, fingerprint('d'))),
                List.of(
                        obligation("policy.eligibility", "Eligibility",
                                ObligationLifecycle.PROPOSED, null, RiskLevel.CRITICAL),
                        obligation("risk.manual-review", "Escalate suspicious application",
                                ObligationLifecycle.PROPOSED, null, RiskLevel.HIGH),
                        obligation("incident.timeout", "Timeout compensation",
                                ObligationLifecycle.PROPOSED, null, RiskLevel.HIGH)));
        CoverageInventoryService service = service(
                (scope, inventory, actor) -> true, source);
        service.saveDraft(0, inventory(
                0, resolvedObligations(waiverExpiring("2027-08-15T00:00:00Z"))), author());
        service.freeze(scope(), "loan-inventory", 1, "Freeze denominator", reviewer());

        CoverageImpactProposal proposal = service.proposeImpact(
                scope(), "loan-inventory", nextTarget);

        assertThat(proposal.targetDrifted()).isTrue();
        assertThat(proposal.sourcesDrifted()).isTrue();
        assertThat(proposal.proposalFingerprint()).matches("sha256:[0-9a-f]{64}");
        assertThat(proposal.changes())
                .extracting(change -> change.obligationId() + ":" + change.kind())
                .containsExactly(
                        "boundary.amount:REMOVAL_PROPOSED",
                        "incident.timeout:ADDED",
                        "policy.eligibility:UNCHANGED",
                        "risk.manual-review:MODIFIED");
        assertThat(repository.findHead(scope(), "loan-inventory")
                .orElseThrow().inventory().revision()).isEqualTo(2);
        assertThat(repository.revisions(scope(), "loan-inventory")).hasSize(2);
    }

    @Test
    void impactAnalysisRejectsNonFrozenAndUntrustedDerivationCoordinates() {
        CoverageInventoryService service = service(
                (scope, inventory, actor) -> true, unchangedSource());
        service.saveDraft(0, inventory(
                0, resolvedObligations(waiverExpiring("2027-08-15T00:00:00Z"))), author());
        assertCode(() -> service.proposeImpact(scope(), "loan-inventory", target()),
                "RG.CORRECTNESS.DENOMINATOR_NOT_FROZEN");
        service.freeze(scope(), "loan-inventory", 1, "Freeze denominator", reviewer());

        CoverageInventoryService compromised = serviceWith(
                repository, (scope, inventory, actor) -> true,
                (scope, requested) -> new DerivationSnapshot(
                        new EnterpriseScope("other", "org-a", "credit", "test", "sg"),
                        requested, sources(), List.of()));
        assertCode(() -> compromised.proposeImpact(scope(), "loan-inventory", target()),
                "RG.CORRECTNESS.DERIVATION_SOURCE_INVALID");
    }

    @Test
    void freezeIdempotencyReplaysExactResultAndRejectsKeyReuse() {
        CoverageInventoryService service = new CoverageInventoryService(
                repository, (scope, inventory, actor) -> true, unchangedSource(),
                new DatabaseCoverageFreezeReceiptRepository(jdbc, mapper), mapper, fixedClock());
        service.saveDraft(0, inventory(
                0, resolvedObligations(waiverExpiring("2027-08-15T00:00:00Z"))), author());

        var first = service.freezeIdempotently(
                scope(), "loan-inventory", 1, "Reviewed denominator", reviewer(),
                "freeze-loan-v1");
        var replay = service.freezeIdempotently(
                scope(), "loan-inventory", 1, "Reviewed denominator", reviewer(),
                "freeze-loan-v1");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.stored()).isEqualTo(first.stored());
        assertThat(repository.revisions(scope(), "loan-inventory")).hasSize(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_correctness_command_receipts", Integer.class))
                .isEqualTo(1);
        assertCode(() -> service.freezeIdempotently(
                scope(), "loan-inventory", 1, "A different review", reviewer(),
                "freeze-loan-v1"), "RG.CORRECTNESS.IDEMPOTENCY_CONFLICT");
    }

    @Test
    void freezeRevisionAndReceiptRollBackAsOneTransaction() {
        CoverageFreezeReceiptRepository failingReceipts = new CoverageFreezeReceiptRepository() {
            @Override
            public Optional<CoverageFreezeReceipt> find(
                    EnterpriseScope scope,
                    String idempotencyKeyFingerprint
            ) {
                return Optional.empty();
            }

            @Override
            public boolean saveIfAbsent(CoverageFreezeReceipt receipt) {
                throw new IllegalStateException("receipt store unavailable");
            }
        };
        CoverageInventoryService service = new CoverageInventoryService(
                repository, (scope, inventory, actor) -> true, unchangedSource(),
                failingReceipts, mapper, fixedClock());
        service.saveDraft(0, inventory(
                0, resolvedObligations(waiverExpiring("2027-08-15T00:00:00Z"))), author());
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                service.freezeIdempotently(
                        scope(), "loan-inventory", 1, "Reviewed denominator", reviewer(),
                        "freeze-loan-v1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("receipt store unavailable");

        assertThat(repository.findHead(scope(), "loan-inventory")
                .orElseThrow().inventory().lifecycle()).isEqualTo(InventoryLifecycle.DRAFT);
        assertThat(repository.revisions(scope(), "loan-inventory")).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_correctness_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void machineContractsAreClosedBoundedAndKeepProposalOffTheEventWire() throws Exception {
        var authoring = mapper.readTree(Files.readString(Path.of(
                "..", "docs", "schemas", "bloge-correctness-authoring-v1.schema.json")));
        assertThat(authoring.at("/$defs/coverageInventory/properties/obligations/maxItems")
                .asInt()).isEqualTo(10_000);
        assertThat(authoring.at("/$defs/coverageInventory/properties/derivationSources/maxItems")
                .asInt()).isEqualTo(1000);

        for (String schema : List.of(
                "bloge-stored-coverage-inventory-v1.schema.json",
                "bloge-coverage-inventory-changed-v1.schema.json",
                "bloge-coverage-inventory-frozen-v1.schema.json",
                "bloge-coverage-freeze-receipt-v1.schema.json",
                "bloge-coverage-impact-proposal-v1.schema.json")) {
            var document = mapper.readTree(Files.readString(Path.of(
                    "..", "docs", "schemas", schema)));
            assertThat(document.path("additionalProperties").asBoolean(true)).isFalse();
        }
        var frozenEvent = mapper.readTree(Files.readString(Path.of(
                "..", "docs", "schemas", "bloge-coverage-inventory-frozen-v1.schema.json")));
        assertThat(frozenEvent.toString()).doesNotContain(
                "statement", "waiver", "payload", "given", "proposed");
        var proposal = mapper.readTree(Files.readString(Path.of(
                "..", "docs", "schemas", "bloge-coverage-impact-proposal-v1.schema.json")));
        assertThat(proposal.at("/properties/changes/maxItems").asInt()).isEqualTo(20_000);
        var receipt = mapper.readTree(Files.readString(Path.of(
                "..", "docs", "schemas", "bloge-coverage-freeze-receipt-v1.schema.json")));
        assertThat(receipt.toString()).doesNotContain(
                "idempotencyKey\"", "reviewComment", "statement", "payload");
    }

    private CoverageInventoryService service(
            CoverageReviewAuthorizer authorizer,
            CoverageDerivationSource source
    ) {
        return serviceWith(repository, authorizer, source);
    }

    private CoverageInventoryService serviceWith(
            DatabaseCoverageInventoryRepository targetRepository,
            CoverageReviewAuthorizer authorizer,
            CoverageDerivationSource source
    ) {
        return new CoverageInventoryService(
                targetRepository, authorizer, source, mapper, fixedClock());
    }

    private DatabaseCoverageInventoryRepository newRepository() {
        var database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-coverage-inventory-schema.sql")).execute(database);
        return new DatabaseCoverageInventoryRepository(
                new JdbcTemplate(database), mapper, fixedClock());
    }

    private CoverageDerivationSource unchangedSource() {
        return (scope, requested) -> new DerivationSnapshot(
                scope, requested, sources(), List.of(
                        obligation("policy.eligibility", "Eligibility",
                                ObligationLifecycle.PROPOSED, null, RiskLevel.CRITICAL),
                        obligation("risk.manual-review", "Manual review",
                                ObligationLifecycle.PROPOSED, null, RiskLevel.HIGH),
                        obligation("boundary.amount", "Amount boundary",
                                ObligationLifecycle.PROPOSED, null, RiskLevel.MEDIUM)));
    }

    private CoverageInventory inventory(long revision, List<CoverageObligation> obligations) {
        Instant forged = Instant.parse("2001-01-01T00:00:00Z");
        return new CoverageInventory(
                "", "loan-inventory", revision, scope(), target(), InventoryLifecycle.DRAFT,
                obligations, sources(), ReviewRecord.pending(),
                new AuditMetadata(forged, forged, reviewer(), reviewer()));
    }

    private List<CoverageObligation> resolvedObligations(Waiver waiver) {
        return List.of(
                obligation("policy.eligibility", "Eligibility", ObligationLifecycle.FROZEN,
                        null, RiskLevel.CRITICAL),
                obligation("risk.manual-review", "Manual review", ObligationLifecycle.FROZEN,
                        null, RiskLevel.HIGH),
                obligation("boundary.amount", "Amount boundary", ObligationLifecycle.WAIVED,
                        waiver, RiskLevel.MEDIUM));
    }

    private CoverageObligation obligation(
            String id,
            String title,
            ObligationLifecycle lifecycle,
            Waiver waiver,
            RiskLevel risk
    ) {
        return new CoverageObligation(
                id, id.startsWith("policy") ? ObligationDimension.POLICY
                        : id.startsWith("risk") ? ObligationDimension.RISK
                        : id.startsWith("incident") ? ObligationDimension.INCIDENT
                        : ObligationDimension.BOUNDARY,
                title, "Required behavior: " + title, risk, author(),
                id.startsWith("incident") ? ObligationSource.INCIDENT
                        : ObligationSource.AUTOMATED,
                lifecycle, waiver, List.of("loan"));
    }

    private Waiver waiverExpiring(String expiresAt) {
        return new Waiver("Approved temporary exception", Instant.parse(expiresAt), reviewer(),
                NOW.minusSeconds(3600));
    }

    private List<ExactSourceSnapshotRef> sources() {
        return List.of(
                new ExactSourceSnapshotRef("CONTRACT", "loan-contract", 2, fingerprint('b')),
                new ExactSourceSnapshotRef("DAG", "loan-graph", 3, fingerprint('a')));
    }

    private ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a'));
    }

    private EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg");
    }

    private PrincipalRef author() {
        return new PrincipalRef("author-a", PrincipalKind.USER, "Author A");
    }

    private PrincipalRef reviewer() {
        return new PrincipalRef("reviewer-a", PrincipalKind.USER, "Reviewer A");
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static void assertCode(Runnable action, String expectedCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(CoverageCommandException.class)
                .extracting(error -> ((CoverageCommandException) error).code())
                .isEqualTo(expectedCode);
    }
}
