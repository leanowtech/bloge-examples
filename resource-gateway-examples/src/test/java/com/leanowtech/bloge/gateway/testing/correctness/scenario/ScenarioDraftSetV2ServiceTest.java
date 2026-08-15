package com.leanowtech.bloge.gateway.testing.correctness.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.CompilationCompatibility;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.EvaluationKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.OutputAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.OutputOperator;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle.OracleLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactBasisRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactObligationRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSourceSnapshotRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewStatus;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.CoverageObligation;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.InventoryLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationDimension;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationSource;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.CaseType;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.GivenV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.InlineValue;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioDraftV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.AssertionSetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.BusinessOracleRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CoverageInventoryRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredAssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredBusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.scenario.ScenarioClosureReport.CheckStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScenarioDraftSetV2ServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T15:00:00Z");

    private ObjectMapper mapper;
    private InMemoryScenarioRepository scenarios;
    private CoverageInventoryRepository inventories;
    private BusinessOracleRepository oracles;
    private AssertionSetRepository assertionSets;
    private ScenarioDraftSetV2Service service;
    private StoredCoverageInventory inventory;
    private StoredBusinessOracle oracle;
    private StoredAssertionSet assertionSet;
    private InMemoryApprovalReceipts approvalReceipts;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        scenarios = new InMemoryScenarioRepository(mapper);
        inventories = mock(CoverageInventoryRepository.class);
        oracles = mock(BusinessOracleRepository.class);
        assertionSets = mock(AssertionSetRepository.class);
        inventory = StoredCoverageInventory.verified(mapper, inventory());
        oracle = StoredBusinessOracle.verified(mapper, oracle());
        assertionSet = StoredAssertionSet.verified(mapper, scope(), assertionSet(oracleRef()));
        approvalReceipts = new InMemoryApprovalReceipts();
        when(inventories.findRevision(any(), any(), any(Long.class)))
                .thenReturn(Optional.of(inventory));
        when(oracles.findRevision(any(), any(), any(Long.class)))
                .thenReturn(Optional.of(oracle));
        when(assertionSets.findRevision(any(), any(), any(Long.class)))
                .thenReturn(Optional.of(assertionSet));
        ScenarioClosureValidator validator = new ScenarioClosureValidator(
                inventories, oracles, assertionSets,
                (scope, target, ref) -> ref != null && "CONTRACT".equals(ref.kind()), mapper);
        service = new ScenarioDraftSetV2Service(
                scenarios, validator, (scope, set, scenario, actor) ->
                        ScenarioReviewAuthorizer.ReviewDecision.governed(),
                approvalReceipts, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void promotesOnlyAnExactlyClosedCaseAndInjectsIndependentReview() {
        StoredScenarioDraftSetV2 draft = service.saveDraft(0, draftSet(0), author());

        var ready = service.markReviewReady(
                scope(), "loan-scenarios", "prime-approved", 1, author());
        var approved = service.approveCanonical(
                scope(), "loan-scenarios", "prime-approved", 2,
                "Validated against approved policy", reviewer());

        assertThat(draft.scenarioDraftSet().metadata().createdBy()).isEqualTo(author());
        assertThat(ready.stored().scenarioDraftSet().scenarios().getFirst().lifecycle())
                .isEqualTo(ScenarioLifecycle.REVIEW_READY);
        assertThat(approved.closure().checks())
                .allMatch(check -> check.status() == CheckStatus.VERIFIED);
        ScenarioDraftV2 canonical = approved.stored().scenarioDraftSet().scenarios().getFirst();
        assertThat(approved.stored().scenarioDraftSet().revision()).isEqualTo(3);
        assertThat(canonical.lifecycle()).isEqualTo(ScenarioLifecycle.CANONICAL);
        assertThat(canonical.review().reviewer()).isEqualTo(reviewer());
        assertThat(canonical.review().reviewedAt()).isEqualTo(NOW);
        assertThat(canonical.review().comment()).isEqualTo(
                "Validated against approved policy");
    }

    @Test
    void returnsAllReferenceFailuresInsteadOfFailingAtTheFirstGap() {
        when(inventories.findRevision(any(), any(), any(Long.class)))
                .thenReturn(Optional.empty());
        when(oracles.findRevision(any(), any(), any(Long.class)))
                .thenReturn(Optional.empty());
        when(assertionSets.findRevision(any(), any(), any(Long.class)))
                .thenReturn(Optional.empty());
        ScenarioClosureValidator denied = new ScenarioClosureValidator(
                inventories, oracles, assertionSets,
                ScenarioExternalReferenceSource.denyAll(), mapper);
        service = new ScenarioDraftSetV2Service(
                scenarios, denied, (scope, set, scenario, actor) ->
                        ScenarioReviewAuthorizer.ReviewDecision.governed());
        service.saveDraft(0, draftSet(0), author());

        assertThatThrownBy(() -> service.markReviewReady(
                scope(), "loan-scenarios", "prime-approved", 1, author()))
                .isInstanceOfSatisfying(ScenarioV2CommandException.class, failure -> {
                    assertThat(failure.code())
                            .isEqualTo("RG.CORRECTNESS.SCENARIO_CLOSURE_INCOMPLETE");
                    assertThat(failure.closureReport().checks())
                            .extracting(check -> check.assetKind())
                            .containsExactly("CONTRACT", "OBLIGATION", "ORACLE", "ASSERTION_SET");
                    assertThat(failure.closureReport().checks())
                            .allMatch(check -> check.status() == CheckStatus.STALE);
                });
    }

    @Test
    void draftSaveCannotBypassTransitionsOrMutateReviewedCases() {
        StoredScenarioDraftSetV2 draft = service.saveDraft(0, draftSet(0), author());
        ScenarioDraftSetV2 forgedReady = withCase(
                draft.scenarioDraftSet(), copyCase(
                        draft.scenarioDraftSet().scenarios().getFirst(),
                        "Prime approved", ScenarioLifecycle.REVIEW_READY,
                        ReviewRecord.pending()));

        assertThatThrownBy(() -> service.saveDraft(1, forgedReady, author()))
                .isInstanceOfSatisfying(ScenarioV2CommandException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo("RG.CORRECTNESS.SCENARIO_TRANSITION_REQUIRED"));

        var ready = service.markReviewReady(
                scope(), "loan-scenarios", "prime-approved", 1, author());
        ScenarioDraftSetV2 editedReviewed = withCase(
                ready.stored().scenarioDraftSet(), copyCase(
                        ready.stored().scenarioDraftSet().scenarios().getFirst(),
                        "Changed after review", ScenarioLifecycle.REVIEW_READY,
                        ReviewRecord.pending()));
        assertThatThrownBy(() -> service.saveDraft(2, editedReviewed, author()))
                .isInstanceOfSatisfying(ScenarioV2CommandException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo("RG.CORRECTNESS.SCENARIO_IMMUTABLE"));
    }

    @Test
    void rejectsSameAuthorApprovalAndStaleRevision() {
        service.saveDraft(0, draftSet(0), author());
        service.markReviewReady(scope(), "loan-scenarios", "prime-approved", 1, author());

        assertThatThrownBy(() -> service.approveCanonical(
                scope(), "loan-scenarios", "prime-approved", 2,
                "Self approval", author()))
                .isInstanceOfSatisfying(ScenarioV2CommandException.class, failure ->
                        assertThat(failure.code()).isEqualTo(
                                "RG.CORRECTNESS.FOUR_EYES_REQUIRED"));
        assertThatThrownBy(() -> service.markReviewReady(
                scope(), "loan-scenarios", "prime-approved", 1, reviewer()))
                .isInstanceOfSatisfying(ScenarioV2CommandException.class, failure ->
                        assertThat(failure.code()).isEqualTo(
                                "RG.CORRECTNESS.REVISION_CONFLICT"));
    }

    @Test
    void canonicalApprovalReplaysByHashedIdempotencyKeyAndRejectsKeyReuse() {
        service.saveDraft(0, draftSet(0), author());
        service.markReviewReady(scope(), "loan-scenarios", "prime-approved", 1, author());

        var approved = service.approveCanonicalIdempotently(
                scope(), "loan-scenarios", "prime-approved", 2,
                "Independent approval", reviewer(), "scenario-approval-1");
        var replayed = service.approveCanonicalIdempotently(
                scope(), "loan-scenarios", "prime-approved", 2,
                "Independent approval", reviewer(), "scenario-approval-1");

        assertThat(approved.replayed()).isFalse();
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.stored()).isEqualTo(approved.stored());
        assertThat(approvalReceipts.receipts.keySet())
                .allMatch(key -> key.startsWith("sha256:"))
                .noneMatch(key -> key.contains("scenario-approval-1"));
        assertThatThrownBy(() -> service.approveCanonicalIdempotently(
                scope(), "loan-scenarios", "prime-approved", 2,
                "Different approval", reviewer(), "scenario-approval-1"))
                .isInstanceOfSatisfying(ScenarioV2CommandException.class, failure ->
                        assertThat(failure.code()).isEqualTo(
                                "RG.CORRECTNESS.IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void detectsWhenAnAssertionDoesNotImplementTheCasesOracle() {
        ExactAssetRef otherOracle = new ExactAssetRef(
                "ORACLE", "other-oracle", 1, fingerprint('9'));
        assertionSet = StoredAssertionSet.verified(
                mapper, scope(), assertionSet(otherOracle));
        when(assertionSets.findRevision(any(), any(), any(Long.class)))
                .thenReturn(Optional.of(assertionSet));
        ScenarioClosureValidator validator = new ScenarioClosureValidator(
                inventories, oracles, assertionSets,
                (scope, target, ref) -> true, mapper);

        ScenarioClosureReport report = validator.validate(
                draftSet(0), draftSet(0).scenarios().getFirst(),
                ScenarioClosureReport.ClosurePhase.REVIEW_READY);

        assertThat(report.complete()).isFalse();
        assertThat(report.checks()).anySatisfy(check -> {
            assertThat(check.status()).isEqualTo(CheckStatus.INCOMPATIBLE);
            assertThat(check.reasonCode()).isEqualTo(
                    "RG.CORRECTNESS.ASSERTION_ORACLE_MISMATCH");
        });
    }

    @Test
    void closureReportWireFieldsStayAlignedWithItsMachineSchema() throws Exception {
        ScenarioClosureValidator validator = new ScenarioClosureValidator(
                inventories, oracles, assertionSets,
                (scope, target, ref) -> true, mapper);
        ScenarioClosureReport report = validator.validate(
                draftSet(0), draftSet(0).scenarios().getFirst(),
                ScenarioClosureReport.ClosurePhase.CANONICAL);
        var schema = mapper.readTree(Files.readString(Path.of(
                "..", "docs", "schemas", "bloge-scenario-closure-report-v1.schema.json")));
        Set<String> actual = new java.util.HashSet<>();
        mapper.valueToTree(report).fieldNames().forEachRemaining(actual::add);
        Set<String> documented = new java.util.HashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(documented::add);

        assertThat(report.schemaVersion()).isEqualTo("bloge.scenarioClosureReport.v1");
        assertThat(documented).isEqualTo(actual);
        assertThat(schema.path("$defs").path("closureCheck")
                .path("required").size()).isEqualTo(6);
    }

    private ScenarioDraftSetV2 draftSet(long revision) {
        ScenarioDraftV2 scenario = new ScenarioDraftV2(
                "prime-approved", "Prime approved", "Prove eligible approval", "",
                CaseType.GOLDEN, RiskLevel.HIGH, owner(), ScenarioLifecycle.EXPLORATORY,
                List.of(obligationRef()), List.of(oracleRef()), List.of(assertionRef()),
                List.of(), new GivenV2(new InlineValue(Map.of("applicantId", "A-100"))),
                List.of(), ReviewRecord.pending(), List.of("loan", "golden"));
        return new ScenarioDraftSetV2(
                "", "loan-scenarios", revision, scope(), target(),
                new ExactAssetRef("CONTRACT", "loan-contract", 2, fingerprint('c')),
                List.of(scenario), metadata());
    }

    private CoverageInventory inventory() {
        return new CoverageInventory(
                "", "loan-inventory", 2, scope(), target(), InventoryLifecycle.FROZEN,
                List.of(obligation()),
                List.of(new ExactSourceSnapshotRef(
                        "POLICY", "loan-policy", 3, fingerprint('b'))),
                approvedReview(), metadata());
    }

    private CoverageObligation obligation() {
        return new CoverageObligation(
                "policy.eligibility", ObligationDimension.POLICY, "Eligibility policy",
                "Only eligible applicants can be approved", RiskLevel.HIGH, owner(),
                ObligationSource.BUSINESS, ObligationLifecycle.FROZEN, null,
                List.of("loan"));
    }

    private BusinessOracle oracle() {
        return new BusinessOracle(
                "", "loan-oracle", 2, scope(), target(),
                "Eligible prime applicants are approved", List.of("Approve ineligible"),
                List.of(new ExactBasisRef("POLICY", "loan-policy", 3, fingerprint('b'))),
                owner(), OracleLifecycle.APPROVED, approvedReview(), List.of(), metadata());
    }

    private AssertionSet assertionSet(ExactAssetRef exactOracle) {
        return new AssertionSet(
                "", "loan-assertions", 2, target(), exactOracle,
                AssertionSet.AssertionLifecycle.VALID,
                List.of(new OutputAssertion(
                        "approved", EvaluationKind.RUNTIME, "/approved",
                        OutputOperator.EQUALS, true)),
                new CompilationCompatibility(
                        true, "bloge-evidence-evaluator-1", List.of("OUTPUT_EQUALS"), ""),
                metadata());
    }

    private ExactObligationRef obligationRef() {
        return new ExactObligationRef(
                new ExactAssetRef(
                        "INVENTORY", "loan-inventory", 2, inventory.inventoryFingerprint()),
                "policy.eligibility",
                CorrectnessProtocolFingerprint.obligationFingerprint(mapper, obligation()));
    }

    private ExactAssetRef oracleRef() {
        return new ExactAssetRef("ORACLE", "loan-oracle", 2, oracle.oracleFingerprint());
    }

    private ExactAssetRef assertionRef() {
        return new ExactAssetRef(
                "ASSERTION_SET", "loan-assertions", 2,
                assertionSet.assertionSetFingerprint());
    }

    private ScenarioDraftSetV2 withCase(
            ScenarioDraftSetV2 set,
            ScenarioDraftV2 scenario
    ) {
        return new ScenarioDraftSetV2(
                set.schemaVersion(), set.scenarioDraftSetId(), set.revision(), set.scope(),
                set.target(), set.contractRef(), List.of(scenario), set.metadata());
    }

    private ScenarioDraftV2 copyCase(
            ScenarioDraftV2 value,
            String name,
            ScenarioLifecycle lifecycle,
            ReviewRecord review
    ) {
        return new ScenarioDraftV2(
                value.scenarioId(), name, value.businessIntent(), value.description(),
                value.caseType(), value.risk(), value.owner(), lifecycle,
                value.obligationRefs(), value.oracleRefs(), value.assertionSetRefs(),
                value.sourceRefs(), value.given(), value.dependencies(), review, value.tags());
    }

    private EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg");
    }

    private ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a'));
    }

    private AuditMetadata metadata() {
        Instant old = Instant.parse("2001-01-01T00:00:00Z");
        return new AuditMetadata(old, old, author(), author());
    }

    private ReviewRecord approvedReview() {
        return new ReviewRecord(ReviewStatus.APPROVED, reviewer(), NOW, "Approved");
    }

    private PrincipalRef author() {
        return new PrincipalRef("author-a", PrincipalKind.USER, "Author A");
    }

    private PrincipalRef reviewer() {
        return new PrincipalRef("reviewer-a", PrincipalKind.USER, "Reviewer A");
    }

    private PrincipalRef owner() {
        return new PrincipalRef("credit-team", PrincipalKind.TEAM, "Credit Team");
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static final class InMemoryScenarioRepository
            implements ScenarioDraftSetV2Repository {
        private final ObjectMapper mapper;
        private StoredScenarioDraftSetV2 head;
        private final List<StoredScenarioDraftSetV2> history = new ArrayList<>();

        private InMemoryScenarioRepository(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public Optional<StoredScenarioDraftSetV2> findHead(
                EnterpriseScope scope,
                String scenarioDraftSetId
        ) {
            return head != null && head.scenarioDraftSet().scope().equals(scope)
                    && head.scenarioDraftSet().scenarioDraftSetId().equals(scenarioDraftSetId)
                    ? Optional.of(head) : Optional.empty();
        }

        @Override
        public Optional<StoredScenarioDraftSetV2> findRevision(
                EnterpriseScope scope,
                String scenarioDraftSetId,
                long revision
        ) {
            return history.stream().filter(value ->
                    value.scenarioDraftSet().scope().equals(scope)
                            && value.scenarioDraftSet().scenarioDraftSetId()
                                    .equals(scenarioDraftSetId)
                            && value.scenarioDraftSet().revision() == revision)
                    .findFirst();
        }

        @Override
        public List<StoredScenarioDraftSetV2> revisions(
                EnterpriseScope scope,
                String scenarioDraftSetId
        ) {
            return history.stream().filter(value ->
                    value.scenarioDraftSet().scope().equals(scope)
                            && value.scenarioDraftSet().scenarioDraftSetId()
                                    .equals(scenarioDraftSetId))
                    .toList();
        }

        @Override
        public Optional<StoredScenarioDraftSetV2> saveIfRevision(
                long expectedRevision,
                ScenarioDraftSetV2 candidate,
                PrincipalRef actor
        ) {
            if ((head == null && expectedRevision != 0)
                    || (head != null
                    && head.scenarioDraftSet().revision() != expectedRevision)) {
                return Optional.empty();
            }
            AuditMetadata audit = head == null
                    ? new AuditMetadata(NOW, NOW, actor, actor)
                    : new AuditMetadata(
                            head.scenarioDraftSet().metadata().createdAt(), NOW,
                            head.scenarioDraftSet().metadata().createdBy(), actor);
            head = StoredScenarioDraftSetV2.verified(
                    mapper, candidate.persistedAs(expectedRevision + 1, audit));
            history.add(head);
            return Optional.of(head);
        }

        @Override
        public ScenarioCasePage pageByTarget(
                EnterpriseScope scope,
                ExactTargetRef target,
                String cursor,
                int limit
        ) {
            return new ScenarioCasePage(0, List.of(), "");
        }

        @Override
        public Set<String> fulfilledObligationIds(
                EnterpriseScope scope,
                ExactTargetRef target,
                ExactAssetRef inventoryRef
        ) {
            return Set.of();
        }
    }

    private static final class InMemoryApprovalReceipts
            implements ScenarioCanonicalApprovalReceiptRepository {
        private final Map<String, ScenarioCanonicalApprovalReceipt> receipts =
                new LinkedHashMap<>();

        @Override
        public Optional<ScenarioCanonicalApprovalReceipt> find(
                EnterpriseScope scope,
                String idempotencyKeyFingerprint
        ) {
            ScenarioCanonicalApprovalReceipt value = receipts.get(idempotencyKeyFingerprint);
            return value != null && value.scope().equals(scope)
                    ? Optional.of(value) : Optional.empty();
        }

        @Override
        public boolean saveIfAbsent(ScenarioCanonicalApprovalReceipt receipt) {
            return receipts.putIfAbsent(receipt.idempotencyKeyFingerprint(), receipt) == null;
        }
    }
}
