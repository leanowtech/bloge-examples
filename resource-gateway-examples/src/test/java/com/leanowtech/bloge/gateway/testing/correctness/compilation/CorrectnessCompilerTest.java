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
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.InlineValue;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioDraftV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ValueSource;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionEvaluatorProfile;
import com.leanowtech.bloge.gateway.testing.correctness.oracle.AssertionSetCompiler;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

public class CorrectnessCompilerTest {

    private static final Instant CREATED = Instant.parse("2026-08-15T00:00:00Z");
    private static final String SECRET = "customer-account-secret-8848";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AssertionEvaluatorProfile profile = AssertionEvaluatorProfile.fixtureEvaluatorV1();
    private final CorrectnessCompiler compiler = new CorrectnessCompiler(
            mapper, new AssertionSetCompiler(mapper), profile);

    @Test
    void compilesExactClosureToExistingRegistrationsWithCompleteCaseSourceMap() {
        FrozenCompilationInput input = input(new InlineValue(Map.of("decision", "APPROVE")), true);

        CompiledCorrectnessPlan plan = compiler.compile(input);

        assertThat(plan.report().publishable()).isTrue();
        assertThat(plan.fixtureRegistrations()).hasSize(1);
        assertThat(plan.suiteRegistration()).isNotNull();
        assertThat(plan.fixtureRegistrations().getFirst().fixtureBundle().assertions()).hasSize(1);
        assertThat(plan.report().sourceMap()).anySatisfy(mapping -> {
            assertThat(mapping.source().elementKind()).isEqualTo("SCENARIO_CASE");
            assertThat(mapping.source().elementId()).isEqualTo("prime-approval");
            assertThat(mapping.output().elementKind()).isEqualTo("TEST_CASE");
        });
        assertThat(plan.report().sourceMap()).anySatisfy(mapping -> {
            assertThat(mapping.source().elementKind()).isEqualTo("OBLIGATION");
            assertThat(mapping.source().elementId()).isEqualTo("OBL-PRIME");
            assertThat(mapping.output().elementKind()).isEqualTo("TEST_CASE");
        });
    }

    @Test
    void repeatedCompilationIsByteEquivalentAndDoesNotLeakFixturePayload() throws Exception {
        FrozenCompilationInput input = input(new InlineValue(Map.of("decision", "APPROVE")), true);
        CompiledCorrectnessPlan first = compiler.compile(input);
        byte[] expectedReport = mapper.writeValueAsBytes(first.report());
        byte[] expectedFixture = mapper.writeValueAsBytes(first.fixtureRegistrations());
        byte[] expectedSuite = mapper.writeValueAsBytes(first.suiteRegistration());

        for (int iteration = 0; iteration < 100; iteration++) {
            CompiledCorrectnessPlan actual = compiler.compile(input);
            assertThat(mapper.writeValueAsBytes(actual.report())).isEqualTo(expectedReport);
            assertThat(mapper.writeValueAsBytes(actual.fixtureRegistrations()))
                    .isEqualTo(expectedFixture);
            assertThat(mapper.writeValueAsBytes(actual.suiteRegistration()))
                    .isEqualTo(expectedSuite);
        }

        assertThat(mapper.writeValueAsString(first.report())).doesNotContain(SECRET);
        assertThat(first.toString()).doesNotContain(SECRET);
        assertThat(input.toString()).doesNotContain(SECRET);
        assertThat(input.fixtures().getFirst().toString()).doesNotContain(SECRET);
        assertThat(mapper.writeValueAsString(first.suiteRegistration())).contains(SECRET);
    }

    @Test
    void localeAndTimezoneDoNotChangeCompilationFingerprint() {
        Locale originalLocale = Locale.getDefault();
        TimeZone originalZone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"));
            String first = compiler.compile(
                    input(new InlineValue(Map.of("decision", "APPROVE")), true))
                    .report().compilationFingerprint();

            Locale.setDefault(Locale.JAPAN);
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            String second = compiler.compile(
                    input(new InlineValue(Map.of("decision", "APPROVE")), true))
                    .report().compilationFingerprint();

            assertThat(second).isEqualTo(first);
        } finally {
            Locale.setDefault(originalLocale);
            TimeZone.setDefault(originalZone);
        }
    }

    @Test
    void unsupportedGeneratedInputBlocksWithoutPartialRegistrations() {
        FrozenCompilationInput input = input(
                new GeneratedValueRef(
                        ref("GENERATOR", "loan-generator", 'e'), fp('f')),
                true);

        CompiledCorrectnessPlan plan = compiler.compile(input);

        assertThat(plan.report().publishable()).isFalse();
        assertThat(plan.fixtureRegistrations()).isEmpty();
        assertThat(plan.suiteRegistration()).isNull();
        assertThat(plan.report().diagnostics()).extracting(value -> value.code())
                .contains("RG.CORRECTNESS.GENERATOR_LOWERING_UNSUPPORTED");
    }

    @Test
    void unsupportedExecutableAssertionIsNeverSilentlyDropped() {
        FrozenCompilationInput input = input(
                new InlineValue(Map.of("decision", "APPROVE")), false);

        CompiledCorrectnessPlan plan = compiler.compile(input);

        assertThat(plan.report().publishable()).isFalse();
        assertThat(plan.fixtureRegistrations()).isEmpty();
        assertThat(plan.report().diagnostics()).extracting(value -> value.code())
                .contains("RG.CORRECTNESS.EVALUATOR_CAPABILITY_UNSUPPORTED");
    }

    public FrozenCompilationInput input(
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
                List.of(new com.leanowtech.bloge.gateway.testing.correctness.domain
                        .CorrectnessProtocol.ExactBasisRef(
                        "POLICY", "loan-policy", 3, fp('1'))),
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
                ? profile.capabilities()
                : List.of("RUNTIME:STATE_EFFECT:SIDE_EFFECT");
        AssertionSet assertionSet = new AssertionSet(
                "", "prime-assertions", 1, target, oracleRef,
                AssertionSet.AssertionLifecycle.VALID, assertions,
                new CompilationCompatibility(true, profile.evaluatorVersion(), capabilities, ""),
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
                        CorrectnessProtocolFingerprint.obligationFingerprint(mapper, obligation))),
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

    private EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "loan", "test", "sg");
    }

    private ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 1, fp('0'));
    }

    private ReviewRecord approvedReview() {
        return new ReviewRecord(
                ReviewStatus.APPROVED, principal("reviewer"), CREATED, "Approved");
    }

    private PrincipalRef principal(String id) {
        return new PrincipalRef(id, PrincipalKind.USER, id);
    }

    private AuditMetadata metadata(PrincipalRef actor) {
        return new AuditMetadata(CREATED, CREATED, actor, actor);
    }

    private ExactAssetRef exact(String kind, String id, long revision, Object value) {
        return new ExactAssetRef(
                kind, id, revision, CorrectnessProtocolFingerprint.fingerprint(mapper, value));
    }

    private ExactAssetRef ref(String kind, String id, char digit) {
        return new ExactAssetRef(kind, id, 1, fp(digit));
    }

    private String fp(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }
}
