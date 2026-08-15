package com.leanowtech.bloge.gateway.testing.correctness.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.CompilationCompatibility;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.EvaluationKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.OutputAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.OutputOperator;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.StateEffectAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.StateEffectOperator;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition.DefinitionLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactBasisRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactObligationRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSourceSnapshotRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewStatus;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.CompilationCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.CoverageObligation;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.InventoryLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationDimension;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationSource;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureSource;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.QualityProfile;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RedactionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RetentionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.SourceKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.BehaviorBoundary;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.BehaviorKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.CaseType;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.Consumption;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ControlledBehavior;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ControlledDependencyV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.DependencySelector;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.FixtureVariantRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.GeneratedValueRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.GivenV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioDraftV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ValueSource;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionEvaluatorProfile;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Shared exact frozen input builder for correctness compilation integration tests. */
public final class CorrectnessCompilationTestData {

    public static final String SECRET = "customer-account-secret-8848";

    private static final Instant CREATED = Instant.parse("2026-08-15T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final AssertionEvaluatorProfile PROFILE =
            AssertionEvaluatorProfile.fixtureEvaluatorV1();

    private CorrectnessCompilationTestData() {
    }

    public static FrozenCompilationInput input(
            ValueSource dependencyValue,
            boolean supportedAssertion
    ) {
        EnterpriseScope scope = scope();
        ExactTargetRef target = target();
        PrincipalRef owner = principal("owner");
        ReviewRecord review = approvedReview();
        AuditMetadata metadata = metadata(owner);

        CoverageObligation obligation = new CoverageObligation(
                "OBL-PRIME", ObligationDimension.POLICY,
                "Prime applicant approval", "Prime applicants must be approved",
                RiskLevel.CRITICAL, owner, ObligationSource.BUSINESS,
                ObligationLifecycle.FROZEN, null, List.of("approval", "prime"));
        CoverageInventory inventory = new CoverageInventory(
                "", "loan-inventory", 1, scope, target, InventoryLifecycle.FROZEN,
                List.of(obligation),
                List.of(new ExactSourceSnapshotRef(
                        "POLICY", "loan-policy", 3, fp('1'))),
                review, metadata);
        ExactAssetRef inventoryRef = exact(
                "INVENTORY", inventory.inventoryId(), inventory.revision(), inventory);

        CorrectnessDefinition definition = new CorrectnessDefinition(
                "", "loan-correctness", 1, scope, target,
                "Loan correctness", "Approve eligible applicants safely",
                List.of("Prime applicants are approved"), RiskLevel.CRITICAL, owner,
                List.of(new ExactBasisRef("POLICY", "loan-policy", 3, fp('1'))),
                null, inventoryRef, DefinitionLifecycle.ACTIVE, review, metadata);
        ExactAssetRef definitionRef = exact(
                "DEFINITION", definition.definitionId(), definition.revision(), definition);

        BusinessOracle oracle = new BusinessOracle(
                "", "prime-oracle", 1, scope, target,
                "The decision is APPROVE", List.of("REJECT"),
                List.of(new ExactBasisRef("POLICY", "loan-policy", 3, fp('1'))),
                owner, BusinessOracle.OracleLifecycle.APPROVED, review,
                List.of(), metadata);
        ExactAssetRef oracleRef = exact("ORACLE", oracle.oracleId(), oracle.revision(), oracle);

        List<AssertionSet.ExecutableAssertionSpec> assertions = supportedAssertion
                ? List.of(new OutputAssertion(
                "decision-equals", EvaluationKind.RUNTIME, "/decision",
                OutputOperator.EQUALS, "APPROVE"))
                : List.of(new StateEffectAssertion(
                "ledger-write", EvaluationKind.RUNTIME,
                StateEffectOperator.SIDE_EFFECT, "loan-ledger", "WRITTEN"));
        List<String> capabilities = supportedAssertion
                ? PROFILE.capabilities()
                : List.of("RUNTIME:STATE_EFFECT:SIDE_EFFECT");
        AssertionSet assertionSet = new AssertionSet(
                "", "prime-assertions", 1, target, oracleRef,
                AssertionSet.AssertionLifecycle.VALID, assertions,
                new CompilationCompatibility(
                        true, PROFILE.evaluatorVersion(), capabilities, ""),
                metadata);
        ExactAssetRef assertionRef = exact(
                "ASSERTION_SET", assertionSet.assertionSetId(), assertionSet.revision(), assertionSet);

        ExactAssetRef materialRef = ref("FIXTURE_MATERIAL", "prime-applicant", '6');
        FixtureAssetDescriptor fixture = new FixtureAssetDescriptor(
                "", "prime-applicant", 1, scope, "Prime applicant",
                new FixtureSource(SourceKind.SAMPLE, null), materialRef,
                new ExactSchemaRef("loan-input", 1, fp('7')), "prime",
                FixtureLifecycle.ACTIVE, "CONFIDENTIAL", owner,
                new RedactionDescriptor("redaction-v1", List.of("/ssn"), true),
                new RetentionDescriptor(
                        "retention-365d", 365, Instant.parse("2027-08-15T00:00:00Z")),
                new QualityProfile(true, true, 0, 1), List.of("loan"), metadata);
        ExactAssetRef fixtureRef = exact(
                "FIXTURE_ASSET", fixture.fixtureAssetId(), fixture.revision(), fixture);

        boolean generated = dependencyValue instanceof GeneratedValueRef;
        ValueSource given = generated
                ? dependencyValue
                : new FixtureVariantRef(fixtureRef, "prime");
        ControlledDependencyV2 dependency = new ControlledDependencyV2(
                "credit-service",
                new DependencySelector(
                        "/root", "credit-node", "credit.lookup", "", "",
                        List.of(), List.of(), "", List.of()),
                new ControlledBehavior(
                        BehaviorKind.RETURN, BehaviorBoundary.NODE,
                        dependencyValue, "", 0),
                Consumption.once());
        ScenarioDraftV2 scenario = new ScenarioDraftV2(
                "prime-approval", "Prime approval", "Approve a prime applicant", "",
                CaseType.GOLDEN, RiskLevel.CRITICAL, owner, ScenarioLifecycle.CANONICAL,
                List.of(new ExactObligationRef(
                        inventoryRef, obligation.obligationId(),
                        CorrectnessProtocolFingerprint.obligationFingerprint(MAPPER, obligation))),
                List.of(oracleRef), List.of(assertionRef),
                generated ? List.of() : List.of(fixtureRef),
                new GivenV2(given), List.of(dependency), review, List.of("golden"));
        ScenarioDraftSetV2 scenarios = new ScenarioDraftSetV2(
                "", "loan-scenarios", 1, scope, target,
                ref("CONTRACT", "loan-contract", '8'), List.of(scenario), metadata);
        ExactAssetRef scenarioRef = exact(
                "SCENARIO_DRAFT_SET", scenarios.scenarioDraftSetId(), scenarios.revision(), scenarios);
        List<ExactAssetRef> fixtureRefs = generated ? List.of() : List.of(fixtureRef);
        CompilationCoordinate coordinate = new CompilationCoordinate(
                definitionRef, inventoryRef, scenarioRef,
                List.of(oracleRef), List.of(assertionRef), fixtureRefs, target);
        List<FrozenCompilationInput.MaterializedFixture> materials = generated ? List.of()
                : List.of(new FrozenCompilationInput.MaterializedFixture(
                fixtureRef, fixture, materialRef,
                Map.of("applicantId", "applicant-1", "credential", SECRET)));
        return new FrozenCompilationInput(
                scope, coordinate, definition, inventory, scenarios,
                List.of(oracle), List.of(assertionSet), materials);
    }

    public static ExactAssetRef ref(String kind, String id, char digit) {
        return new ExactAssetRef(kind, id, 1, fp(digit));
    }

    public static String fp(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }

    private static EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "loan", "test", "sg");
    }

    private static ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 1, fp('0'));
    }

    private static ReviewRecord approvedReview() {
        return new ReviewRecord(
                ReviewStatus.APPROVED, principal("reviewer"), CREATED, "Approved");
    }

    private static PrincipalRef principal(String id) {
        return new PrincipalRef(id, PrincipalKind.USER, id);
    }

    private static AuditMetadata metadata(PrincipalRef actor) {
        return new AuditMetadata(CREATED, CREATED, actor, actor);
    }

    private static ExactAssetRef exact(String kind, String id, long revision, Object value) {
        return new ExactAssetRef(
                kind, id, revision, CorrectnessProtocolFingerprint.fingerprint(MAPPER, value));
    }
}
