package com.leanowtech.bloge.gateway.testing.correctness.oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.AssertionLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.CompilationCompatibility;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.EvaluationKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.InvocationAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.InvocationOperator;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.OutputAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.OutputOperator;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle.OracleLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactBasisRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.DatabaseAssertionSetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.DatabaseBusinessOracleRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredAssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredBusinessOracle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OracleAssertionServiceTest {

    private static final Instant COMMAND_TIME = Instant.parse("2026-08-15T10:00:00Z");

    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private DatabaseBusinessOracleRepository oracles;
    private DatabaseAssertionSetRepository assertionSets;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        var database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-oracle-assertion-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        Clock clock = Clock.fixed(COMMAND_TIME, ZoneOffset.UTC);
        oracles = new DatabaseBusinessOracleRepository(jdbc, mapper, clock);
        assertionSets = new DatabaseAssertionSetRepository(jdbc, mapper, clock);
    }

    @Test
    void ownerApprovalUsesServerIdentityTimeAndIndependentReview() {
        BusinessOracleService service = oracleService(
                (scope, oracle, actor) -> OracleReviewAuthorizer.ApprovalDecision.ownerReview(),
                (scope, target, refs) -> true);
        StoredBusinessOracle proposed = service.saveProposed(
                0, oracle(0), author());
        StoredBusinessOracle approved = service.approve(
                scope(), "loan-approved", 1, "Policy owner accepts this outcome", reviewer());

        assertThat(proposed.oracle().lifecycle()).isEqualTo(OracleLifecycle.PROPOSED);
        assertThat(approved.oracle().lifecycle()).isEqualTo(OracleLifecycle.APPROVED);
        assertThat(approved.oracle().approval().reviewer()).isEqualTo(reviewer());
        assertThat(approved.oracle().approval().reviewedAt()).isEqualTo(COMMAND_TIME);
        assertThat(approved.oracle().approval().comment())
                .isEqualTo("Policy owner accepts this outcome");
        assertThat(approved.oracle().metadata().createdBy()).isEqualTo(author());
        assertThat(approved.oracle().metadata().updatedBy()).isEqualTo(reviewer());

        assertThatThrownBy(() -> service.saveProposed(
                2, approved.oracle(), author()))
                .isInstanceOf(OracleAssertionCommandException.class)
                .extracting(failure -> ((OracleAssertionCommandException) failure).code())
                .isEqualTo("RG.CORRECTNESS.ORACLE_DRAFT_INVALID");
    }

    @Test
    void approvalFailsClosedForSelfReviewAuthorizationAndBasisDrift() {
        BusinessOracleService independent = oracleService(
                (scope, oracle, actor) -> OracleReviewAuthorizer.ApprovalDecision.ownerReview(),
                (scope, target, refs) -> true);
        independent.saveProposed(0, oracle(0), author());
        assertCode(() -> independent.approve(
                scope(), "loan-approved", 1, "Self review", author()),
                "RG.CORRECTNESS.FOUR_EYES_REQUIRED");

        BusinessOracleService forbidden = oracleService(
                OracleReviewAuthorizer.denyAll(), (scope, target, refs) -> true);
        assertCode(() -> forbidden.approve(
                scope(), "loan-approved", 1, "Unauthorized review", reviewer()),
                "RG.CORRECTNESS.ORACLE_APPROVAL_FORBIDDEN");

        BusinessOracleService drifted = oracleService(
                (scope, oracle, actor) -> OracleReviewAuthorizer.ApprovalDecision.ownerReview(),
                OracleBasisSource.denyAll());
        assertCode(() -> drifted.approve(
                scope(), "loan-approved", 1, "Drifted basis", reviewer()),
                "RG.CORRECTNESS.ORACLE_BASIS_DRIFT");
    }

    @Test
    void draftCompatibilityIsServerNormalizedThenValidatedAgainstApprovedOracle() {
        StoredBusinessOracle approved = approvedOracle();
        ExactAssetRef oracleRef = exactOracle(approved);
        AssertionSetService service = assertionService();
        AssertionSet forgedDraft = assertionSet(
                0, oracleRef,
                List.of(new OutputAssertion(
                        "decision", EvaluationKind.RUNTIME, "/decision",
                        OutputOperator.EQUALS, "approve")),
                new CompilationCompatibility(
                        true, "forged-client", List.of("EVERYTHING"), ""));

        StoredAssertionSet draft = service.saveDraft(scope(), 0, forgedDraft, author());
        AssertionSetService.ValidationResult valid = service.validate(
                scope(), "loan-checks", 1, reviewer());

        assertThat(draft.assertionSet().compatibility().supported()).isFalse();
        assertThat(draft.assertionSet().compatibility().reasonCode())
                .isEqualTo("RG.CORRECTNESS.NOT_VALIDATED");
        assertThat(valid.stored().assertionSet().lifecycle())
                .isEqualTo(AssertionLifecycle.VALID);
        assertThat(valid.stored().assertionSet().compatibility().evaluatorVersion())
                .isEqualTo("bloge.fixtureAssertionEvaluator.v1");
        assertThat(valid.compilation().runtimeAssertions()).hasSize(1);
    }

    @Test
    void validationRejectsUnsupportedSemanticsAndExactOracleDrift() {
        StoredBusinessOracle approved = approvedOracle();
        AssertionSetService service = assertionService();
        service.saveDraft(
                scope(), 0,
                assertionSet(
                        0, exactOracle(approved),
                        List.of(new InvocationAssertion(
                                "manual-not-used", EvaluationKind.EVIDENCE,
                                "manual-review", InvocationOperator.NOT_USED, true)),
                        CompilationCompatibility.unsupported("client")),
                author());

        assertCode(() -> service.validate(scope(), "loan-checks", 1, reviewer()),
                "RG.CORRECTNESS.ASSERTION_UNSUPPORTED");
        assertThat(assertionSets.findHead(scope(), "loan-checks").orElseThrow()
                .assertionSet().lifecycle()).isEqualTo(AssertionLifecycle.DRAFT);

        ExactAssetRef drifted = new ExactAssetRef(
                "ORACLE", approved.oracle().oracleId(), approved.oracle().revision(),
                fingerprint('f'));
        assertCode(() -> service.compilePreview(
                scope(), assertionSet(
                        0, drifted,
                        List.of(new OutputAssertion(
                                "decision", EvaluationKind.RUNTIME, "/decision",
                                OutputOperator.EQUALS, "approve")),
                        CompilationCompatibility.unsupported("client"))),
                "RG.CORRECTNESS.ORACLE_REFERENCE_DRIFT");
    }

    @Test
    void approvalReceiptReplaysExactResultAndRejectsKeyReuse() throws Exception {
        BusinessOracleService service = idempotentOracleService(
                new DatabaseOracleApprovalReceiptRepository(jdbc, mapper));
        service.saveProposed(0, oracle(0), author());

        BusinessOracleService.ApprovalResult first = service.approveIdempotently(
                scope(), "loan-approved", 1, "Owner approval", reviewer(), "approve-001");
        BusinessOracleService.ApprovalResult replay = service.approveIdempotently(
                scope(), "loan-approved", 1, "Owner approval", reviewer(), "approve-001");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.stored()).isEqualTo(first.stored());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_correctness_command_receipts", Integer.class))
                .isEqualTo(1);
        String receiptJson = jdbc.queryForObject(
                "SELECT receipt_json FROM rg_correctness_command_receipts", String.class);
        OracleApprovalReceipt receipt = mapper.readValue(receiptJson, OracleApprovalReceipt.class);
        assertSchemaFields("bloge-oracle-approval-receipt-v1.schema.json", receipt);
        assertThat(receiptJson)
                .doesNotContain("approve-001", "Owner approval", "statement", "forbidden");
        assertCode(() -> service.approveIdempotently(
                scope(), "loan-approved", 1, "Different comment", reviewer(), "approve-001"),
                "RG.CORRECTNESS.IDEMPOTENCY_CONFLICT");
    }

    @Test
    void approvalAndOutboxRollBackWhenReceiptPersistenceFails() {
        BusinessOracleService service = idempotentOracleService(new OracleApprovalReceiptRepository() {
            @Override
            public java.util.Optional<OracleApprovalReceipt> find(
                    EnterpriseScope scope,
                    String idempotencyKeyFingerprint
            ) {
                return java.util.Optional.empty();
            }

            @Override
            public boolean saveIfAbsent(OracleApprovalReceipt receipt) {
                throw new IllegalStateException("receipt store unavailable");
            }
        });
        service.saveProposed(0, oracle(0), author());
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                service.approveIdempotently(
                        scope(), "loan-approved", 1, "Owner approval", reviewer(),
                        "approve-rollback")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("receipt store unavailable");
        assertThat(oracles.findHead(scope(), "loan-approved").orElseThrow()
                .oracle().lifecycle()).isEqualTo(OracleLifecycle.PROPOSED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_correctness_outbox", Integer.class)).isEqualTo(1);
    }

    private StoredBusinessOracle approvedOracle() {
        BusinessOracleService service = oracleService(
                (scope, oracle, actor) -> OracleReviewAuthorizer.ApprovalDecision.ownerReview(),
                (scope, target, refs) -> true);
        service.saveProposed(0, oracle(0), author());
        return service.approve(
                scope(), "loan-approved", 1, "Owner approval", reviewer());
    }

    private BusinessOracleService oracleService(
            OracleReviewAuthorizer authorizer,
            OracleBasisSource basisSource
    ) {
        return new BusinessOracleService(
                oracles, authorizer, basisSource,
                Clock.fixed(COMMAND_TIME, ZoneOffset.UTC));
    }

    private AssertionSetService assertionService() {
        return new AssertionSetService(
                assertionSets, oracles, new AssertionSetCompiler(mapper),
                AssertionEvaluatorProfile.fixtureEvaluatorV1());
    }

    private BusinessOracleService idempotentOracleService(
            OracleApprovalReceiptRepository receipts
    ) {
        return new BusinessOracleService(
                oracles,
                (scope, oracle, actor) -> OracleReviewAuthorizer.ApprovalDecision.ownerReview(),
                (scope, target, refs) -> true, receipts, mapper,
                Clock.fixed(COMMAND_TIME, ZoneOffset.UTC));
    }

    private BusinessOracle oracle(long revision) {
        Instant forged = Instant.parse("2001-01-01T00:00:00Z");
        return new BusinessOracle(
                "", "loan-approved", revision, scope(), target(),
                "Prime applicants are approved without manual review",
                List.of("manual review", "rejection"),
                List.of(new ExactBasisRef("POLICY", "loan-policy", 7, fingerprint('b'))),
                new PrincipalRef("credit-owner", PrincipalKind.TEAM, "Credit Owner"),
                OracleLifecycle.PROPOSED, ReviewRecord.pending(), List.of(),
                new AuditMetadata(forged, forged, author(), author()));
    }

    private AssertionSet assertionSet(
            long revision,
            ExactAssetRef oracleRef,
            List<AssertionSet.ExecutableAssertionSpec> assertions,
            CompilationCompatibility compatibility
    ) {
        Instant forged = Instant.parse("2001-01-01T00:00:00Z");
        return new AssertionSet(
                "", "loan-checks", revision, target(), oracleRef,
                AssertionLifecycle.DRAFT, assertions, compatibility,
                new AuditMetadata(forged, forged, author(), author()));
    }

    private ExactAssetRef exactOracle(StoredBusinessOracle approved) {
        return new ExactAssetRef(
                "ORACLE", approved.oracle().oracleId(), approved.oracle().revision(),
                approved.oracleFingerprint());
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

    private static void assertCode(Runnable operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(OracleAssertionCommandException.class)
                .extracting(failure -> ((OracleAssertionCommandException) failure).code())
                .isEqualTo(code);
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private void assertSchemaFields(String name, Object value) throws Exception {
        var schema = mapper.readTree(Files.readString(
                Path.of("..", "docs", "schemas", name)));
        Set<String> serialized = new HashSet<>();
        mapper.valueToTree(value).fieldNames().forEachRemaining(serialized::add);
        Set<String> documented = new HashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(documented::add);
        assertThat(documented).isEqualTo(serialized);
    }
}
