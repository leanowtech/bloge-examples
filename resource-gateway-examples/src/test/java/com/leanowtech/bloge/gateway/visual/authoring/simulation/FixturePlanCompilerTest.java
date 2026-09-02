package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSaveReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetSummary;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSubjectRef;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.GeneratedDefaultFixture;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StoredFixtureSet;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixturePlanCompilerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final ExactFixtureSubjectRefV2.ApiResource SUBJECT = new ExactFixtureSubjectRefV2.ApiResource(
            "customer.get-profile", 3, "sha256:" + "a".repeat(64));
    private static final FixtureSubjectRef.ApiResource AUTHORITY_SUBJECT = new FixtureSubjectRef.ApiResource(
            "customer.get-profile", 3, "sha256:" + "a".repeat(64));

    @Test
    void conditionSelectionUsesTheCurrentInvocationInputAndFreezesTheExactCase() throws Exception {
        StoredFixtureSet stored = fixture(FixtureSetView.Status.PRIVATE_DRAFT,
                caseWithCondition("vip", "vip-condition", "$.customer.level", "VIP"),
                caseWithCondition("standard", "standard-condition", "$.customer.level", "STANDARD"));
        FixturePlanCompiler compiler = new FixturePlanCompiler(reader(stored));
        SimulationCommandV2.ExactFixtureSetRef reference = reference(stored);
        SimulationCommandV2 command = command(JSON.readTree("""
                {"customer":{"level":"VIP"}}
                """), new SimulationCommandV2.FixturePlan.Bindings(
                SimulationCommandV2.Unmatched.BLOCK,
                List.of(new SimulationCommandV2.FixtureBinding(
                        new SimulationCommandV2.FixtureTarget.Subject(),
                        new SimulationCommandV2.FixtureSelection.MatchCondition(
                                reference, "vip-condition")))));

        ResolvedFixturePlan plan = compiler.compile(SCOPE, command);

        assertThat(plan.fingerprint()).matches("sha256:[0-9a-f]{64}");
        assertThat(plan.selections()).singleElement().satisfies(selection -> {
            assertThat(selection.target()).isEqualTo(new SimulationCommandV2.FixtureTarget.Subject());
            assertThat(selection.fixtureSet()).isEqualTo(reference);
            assertThat(selection.caseId()).isEqualTo("vip");
            assertThat(selection.matchedBy()).isEqualTo(ResolvedFixturePlan.MatchedBy.CONDITION);
        });
    }

    @Test
    void autoMatchMustResolveExactlyOneCase() throws Exception {
        StoredFixtureSet stored = fixture(FixtureSetView.Status.PRIVATE_DRAFT,
                caseWithCondition("vip-a", "vip-a", "$.level", "VIP"),
                caseWithCondition("vip-b", "vip-b", "$.level", "VIP"));
        FixturePlanCompiler compiler = new FixturePlanCompiler(reader(stored));

        assertCode(() -> compiler.compile(SCOPE, command(
                JSON.readTree("{\"level\":\"VIP\"}"),
                bindings(new SimulationCommandV2.FixtureSelection.AutoMatch(reference(stored))))),
                FixturePlanFailure.Code.AUTO_MATCH_AMBIGUOUS);
        assertCode(() -> compiler.compile(SCOPE, command(
                JSON.readTree("{\"level\":\"UNKNOWN\"}"),
                bindings(new SimulationCommandV2.FixtureSelection.AutoMatch(reference(stored))))),
                FixturePlanFailure.Code.AUTO_MATCH_EMPTY);
    }

    @Test
    void exactCaseIsDeterministicEvenWhenItsConditionDoesNotMatch() throws Exception {
        StoredFixtureSet stored = fixture(FixtureSetView.Status.PRIVATE_DRAFT,
                caseWithCondition("vip", "vip-only", "$.level", "VIP"));
        ResolvedFixturePlan plan = new FixturePlanCompiler(reader(stored)).compile(SCOPE,
                command(JSON.readTree("{\"level\":\"STANDARD\"}"), bindings(
                        new SimulationCommandV2.FixtureSelection.ExactCase(reference(stored), "vip"))));

        assertThat(plan.selections()).singleElement().satisfies(selection -> {
            assertThat(selection.caseId()).isEqualTo("vip");
            assertThat(selection.matchedBy()).isEqualTo(ResolvedFixturePlan.MatchedBy.EXACT_CASE);
        });
    }

    @Test
    void staleFixtureAndFingerprintDriftFailClosed() {
        StoredFixtureSet stale = fixture(FixtureSetView.Status.STALE,
                caseWithCondition("vip", "vip", "$.level", "VIP"));
        FixturePlanCompiler compiler = new FixturePlanCompiler(reader(stale));

        assertCode(() -> compiler.compile(SCOPE, command(JSON.readTree("{\"level\":\"VIP\"}"),
                bindings(new SimulationCommandV2.FixtureSelection.ExactCase(reference(stale), "vip")))),
                FixturePlanFailure.Code.FIXTURE_STALE);
        SimulationCommandV2.ExactFixtureSetRef drifted = new SimulationCommandV2.ExactFixtureSetRef(
                stale.generated().view().fixtureSetId(), stale.generated().view().revision(),
                "sha256:" + "f".repeat(64));
        assertCode(() -> compiler.compile(SCOPE, command(JSON.readTree("{\"level\":\"VIP\"}"),
                bindings(new SimulationCommandV2.FixtureSelection.ExactCase(drifted, "vip")))),
                FixturePlanFailure.Code.FIXTURE_REFERENCE_MISMATCH);
    }

    @Test
    void duplicateAndOverlappingTargetsFailBeforeMaterialResolution() {
        StoredFixtureSet stored = fixture(FixtureSetView.Status.PRIVATE_DRAFT,
                caseWithCondition("vip", "vip", "$.level", "VIP"));
        SimulationCommandV2.ExactFixtureSetRef reference = reference(stored);
        FixturePlanCompiler compiler = new FixturePlanCompiler(reader(stored));
        var subject = new SimulationCommandV2.FixtureTarget.Subject();
        var exact = new SimulationCommandV2.FixtureSelection.ExactCase(reference, "vip");

        assertCode(() -> compiler.compile(SCOPE, command(JSON.createObjectNode(),
                new SimulationCommandV2.FixturePlan.Bindings(SimulationCommandV2.Unmatched.BLOCK,
                        List.of(new SimulationCommandV2.FixtureBinding(subject, exact),
                                new SimulationCommandV2.FixtureBinding(subject, exact))))),
                FixturePlanFailure.Code.TARGET_OVERLAP);

        var node = new SimulationCommandV2.FixtureTarget.NodePath(List.of("risk-tool"));
        var call = new SimulationCommandV2.FixtureTarget.CallSite(
                List.of("risk-tool", "score"), "lookup-customer");
        assertCode(() -> compiler.compile(SCOPE, command(JSON.createObjectNode(),
                new SimulationCommandV2.FixturePlan.Bindings(SimulationCommandV2.Unmatched.BLOCK,
                        List.of(new SimulationCommandV2.FixtureBinding(node, exact),
                                new SimulationCommandV2.FixtureBinding(call, exact))))),
                FixturePlanFailure.Code.TARGET_OVERLAP);
    }

    @Test
    void caseInputAndCaseControlsRemainIndependentAxes() {
        var driverInput = JSON.createObjectNode().put("customerId", "customer-1001");
        StoredFixtureSet stored = fixture(FixtureSetView.Status.PRIVATE_DRAFT,
                new FixtureSetCommand.Case("known", "Known", driverInput, null,
                        List.of(subjectReturn("known")), null));
        FixturePlanCompiler compiler = new FixturePlanCompiler(reader(stored));

        ResolvedFixturePlan inputOnly = compiler.compile(SCOPE, new SimulationCommandV2(
                SimulationCommandV2.SCHEMA_VERSION, SUBJECT,
                new SimulationCommandV2.Input.CaseInput(reference(stored), "known"),
                new SimulationCommandV2.FixturePlan.None(), SimulationCommandV2.ExecutionPolicy.denyAll()));
        assertThat(inputOnly.input()).isEqualTo(driverInput);
        assertThat(inputOnly.selections()).isEmpty();

        ResolvedFixturePlan controls = compiler.compile(SCOPE, new SimulationCommandV2(
                SimulationCommandV2.SCHEMA_VERSION, SUBJECT,
                new SimulationCommandV2.Input.Inline(JSON.createObjectNode().put("customerId", "other")),
                new SimulationCommandV2.FixturePlan.CaseControls(
                        reference(stored), "known", SimulationCommandV2.Unmatched.BLOCK),
                SimulationCommandV2.ExecutionPolicy.denyAll()));
        assertThat(controls.selections()).singleElement().satisfies(selection -> {
            assertThat(selection.caseId()).isEqualTo("known");
            assertThat(selection.matchedBy()).isEqualTo(ResolvedFixturePlan.MatchedBy.CASE_CONTROLS);
        });
    }

    @Test
    void everyRestrictedPredicateIsEvaluatedWithoutScriptsOrExternalState() {
        FixtureSetCommand.Condition condition = new FixtureSetCommand.Condition("eligible", List.of(
                new FixtureSetCommand.Predicate.Eq("$.customer.level", JSON.getNodeFactory().textNode("VIP")),
                new FixtureSetCommand.Predicate.In("$.region", List.of(
                        JSON.getNodeFactory().textNode("SG"), JSON.getNodeFactory().textNode("HK"))),
                new FixtureSetCommand.Predicate.Present("$.customer"),
                new FixtureSetCommand.Predicate.Absent("$.blocked"),
                new FixtureSetCommand.Predicate.NumberRange(
                        "$.customer.age", BigDecimal.valueOf(21), BigDecimal.valueOf(65))));
        StoredFixtureSet stored = fixture(FixtureSetView.Status.PRIVATE_DRAFT,
                new FixtureSetCommand.Case("eligible", "Eligible", JSON.createObjectNode(), condition,
                        List.of(subjectReturn("eligible")), null));
        var input = JSON.createObjectNode().put("region", "SG");
        input.putObject("customer").put("level", "VIP").put("age", 36);

        ResolvedFixturePlan plan = new FixturePlanCompiler(reader(stored)).compile(SCOPE,
                command(input, bindings(new SimulationCommandV2.FixtureSelection.AutoMatch(reference(stored)))));

        assertThat(plan.selections()).singleElement()
                .extracting(ResolvedFixturePlan.Selection::caseId).isEqualTo("eligible");
    }

    @Test
    void malformedConditionAuthorityFailsClosed() {
        StoredFixtureSet emptyRange = fixture(FixtureSetView.Status.PRIVATE_DRAFT,
                new FixtureSetCommand.Case("bad", "Bad", JSON.createObjectNode(),
                        new FixtureSetCommand.Condition("bad", List.of(
                                new FixtureSetCommand.Predicate.NumberRange("$.value", null, null))),
                        List.of(subjectReturn("bad")), null));
        assertCode(() -> new FixturePlanCompiler(reader(emptyRange)).compile(SCOPE,
                command(JSON.createObjectNode(), bindings(
                        new SimulationCommandV2.FixtureSelection.AutoMatch(reference(emptyRange))))),
                FixturePlanFailure.Code.INTEGRITY);

        String tooDeep = "$" + ".value".repeat(17);
        StoredFixtureSet deepPath = fixture(FixtureSetView.Status.PRIVATE_DRAFT,
                caseWithCondition("deep", "deep", tooDeep, "value"));
        assertCode(() -> new FixturePlanCompiler(reader(deepPath)).compile(SCOPE,
                command(JSON.createObjectNode(), bindings(
                        new SimulationCommandV2.FixtureSelection.AutoMatch(reference(deepPath))))),
                FixturePlanFailure.Code.INTEGRITY);
    }

    @Test
    void modelDiagnosticsDoNotExposeConditionValuesOrPolicyJustification() {
        FixtureSetCommand.Condition condition = new FixtureSetCommand.Condition("vip", List.of(
                new FixtureSetCommand.Predicate.Eq("$.customer.level",
                        JSON.getNodeFactory().textNode("secret-tier")),
                new FixtureSetCommand.Predicate.NumberRange(
                        "$.customer.balance", BigDecimal.valueOf(1000), BigDecimal.valueOf(5000))));
        SimulationCommandV2 command = new SimulationCommandV2(SimulationCommandV2.SCHEMA_VERSION,
                SUBJECT, new SimulationCommandV2.Input.Inline(
                JSON.createObjectNode().put("customer", "customer-1001")),
                new SimulationCommandV2.FixturePlan.None(), new SimulationCommandV2.ExecutionPolicy(
                new SimulationCommandV2.ExternalReads.AllowExact(List.of(
                        new com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec.ResourceRef(
                                "API_RESOURCE", "customer.get-profile", 3,
                                "sha256:" + "a".repeat(64))), "private incident details"),
                new SimulationCommandV2.ExternalWrites.Deny()));

        assertThat(condition.toString()).doesNotContain("secret-tier", "1000", "5000");
        assertThat(condition.all().toString()).doesNotContain("secret-tier", "1000", "5000");
        assertThat(command.toString()).doesNotContain(
                "customer-1001", "private incident details");
    }

    private static SimulationCommandV2 command(
            com.fasterxml.jackson.databind.JsonNode input, SimulationCommandV2.FixturePlan plan) {
        return new SimulationCommandV2(SimulationCommandV2.SCHEMA_VERSION, SUBJECT,
                new SimulationCommandV2.Input.Inline(input), plan,
                SimulationCommandV2.ExecutionPolicy.denyAll());
    }

    private static SimulationCommandV2.FixturePlan bindings(
            SimulationCommandV2.FixtureSelection selection) {
        return new SimulationCommandV2.FixturePlan.Bindings(SimulationCommandV2.Unmatched.BLOCK,
                List.of(new SimulationCommandV2.FixtureBinding(
                        new SimulationCommandV2.FixtureTarget.Subject(), selection)));
    }

    private static FixtureSetCommand.Case caseWithCondition(
            String caseId, String conditionId, String path, String value) {
        return new FixtureSetCommand.Case(caseId, caseId, JSON.createObjectNode(),
                new FixtureSetCommand.Condition(conditionId, List.of(
                        new FixtureSetCommand.Predicate.Eq(path, JSON.getNodeFactory().textNode(value)))),
                List.of(new FixtureSetCommand.Control(FixtureSetCommand.Target.subject(),
                        FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(
                                JSON.createObjectNode().put("case", caseId))),
                        FixtureSetCommand.Fidelity.OUTPUT_LEVEL)), null);
    }

    private static FixtureSetCommand.Control subjectReturn(String caseId) {
        return new FixtureSetCommand.Control(FixtureSetCommand.Target.subject(),
                FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(
                        JSON.createObjectNode().put("case", caseId))),
                FixtureSetCommand.Fidelity.OUTPUT_LEVEL);
    }

    private static StoredFixtureSet fixture(FixtureSetView.Status status, FixtureSetCommand.Case... cases) {
        List<FixtureSetCommand.Case> values = List.of(cases);
        String id = "customer-profile-fixtures";
        String fingerprint = FixtureSetFingerprints.of("Customer profile", AUTHORITY_SUBJECT, values);
        FixtureSetView view = new FixtureSetView(FixtureSetView.SCHEMA_VERSION, id, 4, fingerprint,
                1, "Customer profile", AUTHORITY_SUBJECT, values, status);
        FixtureSetSaveReceipt receipt = new FixtureSetSaveReceipt(FixtureSetSaveReceipt.SCHEMA_VERSION,
                id, 4, fingerprint, AUTHORITY_SUBJECT,
                values.stream().map(FixtureSetCommand.Case::caseId).toList(),
                status, 1);
        FixtureSetSummary summary = new FixtureSetSummary(FixtureSetSummary.SCHEMA_VERSION, id, 4,
                fingerprint, "Customer profile", AUTHORITY_SUBJECT,
                values.stream().map(value -> new FixtureSetSummary.CaseSummary(
                        value.caseId(), value.name())).toList(), status, 1);
        return new StoredFixtureSet(SCOPE, new GeneratedDefaultFixture(view, receipt, summary,
                values.stream().map(value -> new GeneratedDefaultFixture.CaseMapping(
                        value.caseId(), value.caseId())).toList()));
    }

    private static SimulationCommandV2.ExactFixtureSetRef reference(StoredFixtureSet stored) {
        FixtureSetView view = stored.generated().view();
        return new SimulationCommandV2.ExactFixtureSetRef(
                view.fixtureSetId(), view.revision(), view.fingerprint());
    }

    private static FixtureSetAuthorityReader reader(StoredFixtureSet stored) {
        return new FixtureSetAuthorityReader() {
            @Override public Optional<StoredFixtureSet> findHead(AuthoringScope scope, String fixtureSetId) {
                return Optional.empty();
            }

            @Override public Optional<StoredFixtureSet> findRevision(
                    AuthoringScope scope, String fixtureSetId, int revision) {
                FixtureSetView view = stored.generated().view();
                return SCOPE.equals(scope) && view.fixtureSetId().equals(fixtureSetId)
                        && view.revision() == revision ? Optional.of(stored) : Optional.empty();
            }

            @Override public List<FixtureSetSummary> listSummariesBySubject(
                    AuthoringScope scope, FixtureSubjectRef subject) {
                return List.of();
            }
        };
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                   FixturePlanFailure.Code code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(
                FixturePlanFailure.class, failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
