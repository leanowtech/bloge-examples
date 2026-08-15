package com.leanowtech.bloge.gateway.testing.correctness.domain;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.CompilationCompatibility;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.EvaluationKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.GovernanceExpectation;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.GovernanceOperator;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.OutputAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.OutputOperator;
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
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.Waiver;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.AssertionVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.CoverageVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.EvidenceVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.ExecutionVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.GateVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict.ProofLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.CoverageObligation;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.InventoryLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationDimension;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationSource;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureSource;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.QualityProfile;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RedactionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RetentionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.SourceKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.FixtureSubject;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Material;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.WriteRequest;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.CompilationCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.Failure;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.PublicationAttempt;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.BehaviorBoundary;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.BehaviorKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.CaseType;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.Consumption;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ControlledBehavior;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ControlledDependencyV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.DependencySelector;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.FixtureVariantRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.GivenV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioDraftV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioLifecycle;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrectnessProtocolTest {

    private static final Instant CREATED = Instant.parse("2026-08-15T01:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-08-15T02:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void allFirstClassProtocolsRoundTripWithClosedPolymorphicValues() throws Exception {
        assertRoundTrip(definition(4, CREATED, "Credit Team", List.of("No false approval", "Fallback")),
                CorrectnessDefinition.class);
        assertRoundTrip(inventory(), CoverageInventory.class);
        assertRoundTrip(oracle(), BusinessOracle.class);
        assertRoundTrip(assertionSet(), AssertionSet.class);
        assertRoundTrip(scenarios(), ScenarioDraftSetV2.class);
        assertRoundTrip(fixture(), FixtureAssetDescriptor.class);
        assertRoundTrip(materialRequest(), WriteRequest.class);
        assertRoundTrip(materialReceipt(), Receipt.class);
        assertRoundTrip(material(), Material.class);
        assertRoundTrip(publication(), CorrectnessPublication.class);
        assertRoundTrip(publicationAttempt(), PublicationAttempt.class);
        assertRoundTrip(acceptedVerdict(), CorrectnessVerdict.class);
    }

    @Test
    void authoritativeSchemasTrackSerializedFieldsAndClosedUnions() throws Exception {
        JsonNode bundle = schema("bloge-correctness-authoring-v1.schema.json");
        JsonNode materialWrite = schema("bloge-fixture-material-write-request-v2.schema.json");
        JsonNode materialReceipt = schema("bloge-fixture-material-receipt-v2.schema.json");
        JsonNode material = schema("bloge-fixture-material-v2.schema.json");

        assertProperties(mapper.valueToTree(definition(
                4, CREATED, "Credit Team", List.of("Fallback"))),
                bundle.at("/$defs/correctnessDefinition/properties"));
        assertProperties(mapper.valueToTree(inventory()),
                bundle.at("/$defs/coverageInventory/properties"));
        assertProperties(mapper.valueToTree(inventory().obligations().getFirst()),
                bundle.at("/$defs/coverageObligation/properties"));
        assertProperties(mapper.valueToTree(oracle()),
                bundle.at("/$defs/businessOracle/properties"));
        assertProperties(mapper.valueToTree(assertionSet()),
                bundle.at("/$defs/assertionSet/properties"));
        assertProperties(mapper.valueToTree(assertionSet().assertions().getFirst()),
                bundle.at("/$defs/outputAssertion/properties"));
        assertProperties(mapper.valueToTree(scenarios()),
                bundle.at("/$defs/scenarioDraftSetV2/properties"));
        assertProperties(mapper.valueToTree(scenarios().scenarios().getFirst()),
                bundle.at("/$defs/scenarioDraftV2/properties"));
        assertProperties(mapper.valueToTree(
                        scenarios().scenarios().getFirst().dependencies().getFirst().selector()),
                bundle.at("/$defs/dependencySelector/properties"));
        assertProperties(mapper.valueToTree(fixture()),
                bundle.at("/$defs/fixtureAssetDescriptor/properties"));
        assertProperties(mapper.valueToTree(publication()),
                bundle.at("/$defs/correctnessPublication/properties"));
        assertProperties(mapper.valueToTree(publicationAttempt()),
                bundle.at("/$defs/publicationAttempt/properties"));
        assertProperties(mapper.valueToTree(acceptedVerdict()),
                bundle.at("/$defs/correctnessVerdict/properties"));
        assertProperties(mapper.valueToTree(materialRequest()), materialWrite.path("properties"));
        assertProperties(mapper.valueToTree(materialReceipt()), materialReceipt.path("properties"));
        assertProperties(mapper.valueToTree(material()), material.path("properties"));

        for (String definition : List.of(
                "enterpriseScope", "exactAssetRef", "exactTargetRef", "exactSchemaRef",
                "exactObligationRef", "principalRef", "reviewRecord", "waiver", "auditMetadata",
                "correctnessDefinition", "coverageObligation", "coverageInventory",
                "businessOracle", "outputAssertion", "errorAssertion", "nodeAssertion",
                "edgeAssertion", "invocationAssertion", "stateEffectAssertion",
                "governanceExpectation", "compilationCompatibility", "assertionSet",
                "inlineValue", "fixtureVariantRef", "generatedValueRef", "replayMaterialRef",
                "givenV2", "pathMatch", "dependencySelector", "controlledBehavior",
                "consumption", "controlledDependencyV2", "scenarioDraftV2",
                "scenarioDraftSetV2", "fixtureSource", "redactionDescriptor",
                "retentionDescriptor", "qualityProfile", "fixtureAssetDescriptor",
                "correctnessPublication", "compilationCoordinate", "failure",
                "publicationAttempt", "verdictReason", "verdictRemediation",
                "correctnessVerdict")) {
            assertThat(bundle.at("/$defs/" + definition + "/additionalProperties")
                    .asBoolean(true)).as(definition).isFalse();
        }
        assertThat(bundle.at("/$defs/executableAssertion/oneOf"))
                .hasSize(7);
        assertThat(bundle.at("/$defs/valueSource/oneOf"))
                .hasSize(4);
        assertThat(bundle.at("/$defs/controlledBehavior/properties/kind/enum"))
                .extracting(JsonNode::asText)
                .containsExactly("REAL", "RETURN", "ERROR", "DELAY", "TIMEOUT", "REPLAY",
                        "OBSERVE", "MUST_NOT_CALL");
        assertThat(materialWrite.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(materialReceipt.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(materialReceipt.path("properties").has("payload")).isFalse();
        assertThat(materialReceipt.at("/properties/payloadPersisted/const").asBoolean()).isTrue();
        assertThat(materialReceipt.at("/properties/payloadReturned/const").asBoolean(true)).isFalse();
        assertThat(material.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(material.at("/properties/payloadReturned/const").asBoolean()).isTrue();
    }

    @Test
    void additiveFieldsAreToleratedButUnknownEnumsFailClosed() throws Exception {
        ObjectNode json = (ObjectNode) mapper.valueToTree(definition(
                4, CREATED, "Credit Team", List.of("Fallback", "No false approval")));
        json.put("futureProjection", "metadata-only");

        assertThat(mapper.treeToValue(json, CorrectnessDefinition.class).definitionId())
                .isEqualTo("loan-correctness");

        json.put("lifecycle", "FUTURE_ACTIVE");
        assertThatThrownBy(() -> mapper.treeToValue(json, CorrectnessDefinition.class))
                .hasMessageContaining("FUTURE_ACTIVE");
    }

    @Test
    void canonicalFingerprintExcludesHeadRevisionAuditTimesAndDisplayNames() {
        CorrectnessDefinition first = definition(
                4, CREATED, "Credit Team", List.of("No false approval", "Fallback"));
        CorrectnessDefinition changedServerMetadata = definition(
                19, UPDATED, "Renamed Credit Organization", List.of("Fallback", "No false approval"));

        String fingerprint = CorrectnessProtocolFingerprint.fingerprint(mapper, first);

        assertThat(fingerprint).isEqualTo(
                CorrectnessProtocolFingerprint.fingerprint(mapper, changedServerMetadata));
        assertThat(fingerprint).isEqualTo(
                "sha256:38579392332c3c2673ecefed46a9892371f978c4aee4cd76d3494210c8c954bf");
        assertThat(CorrectnessProtocolFingerprint.fingerprint(mapper,
                new CorrectnessDefinition(
                        first.schemaVersion(), first.definitionId(), first.revision(), first.scope(),
                        new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 2, fp('9')),
                        first.title(), first.businessIntent(), first.successCriteria(),
                        first.riskLevel(), first.owner(), first.policyRefs(), first.policyWaiver(),
                        first.activeInventoryRef(), first.lifecycle(), first.review(), first.metadata())))
                .isNotEqualTo(fingerprint);
    }

    @Test
    void setSemanticReferencesAreDeduplicatedBeforeFingerprinting() {
        ExactBasisRef basis = new ExactBasisRef("POLICY", "loan-policy", 3, fp('b'));
        BusinessOracle value = new BusinessOracle(
                "", "oracle-deduplicated", 1, scope(), target(), "Decision is correct",
                List.of(), List.of(basis, basis), principal("owner", "Owner"),
                BusinessOracle.OracleLifecycle.APPROVED, approvedReview(),
                List.of(asset("ASSERTION_SET", "assertions", 1, 'd'),
                        asset("ASSERTION_SET", "assertions", 1, 'd')),
                metadata(CREATED));

        assertThat(value.basisRefs()).containsExactly(basis);
        assertThat(value.assertionSetRefs()).hasSize(1);
    }

    @Test
    void exactReferencesAndLifecycleInvariantsFailClosed() {
        assertThatThrownBy(() -> new ExactAssetRef("ORACLE", "oracle-a", 0, fp('a')))
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new ExactAssetRef("ORACLE", "oracle-a", 1, "latest"))
                .hasMessageContaining("SHA-256");
        assertThatThrownBy(() -> new CorrectnessDefinition(
                "", "definition-a", 0, scope(), target(), "Title", "Intent", List.of("Works"),
                RiskLevel.HIGH, principal("owner", "Owner"), List.of(), null, null,
                CorrectnessDefinition.DefinitionLifecycle.ACTIVE, ReviewRecord.pending(), metadata(CREATED)))
                .hasMessageContaining("inventory");
        assertThatThrownBy(() -> new AssertionSet(
                "", "assertions-a", 1, target(), asset("ORACLE", "oracle-a", 1, 'a'),
                AssertionSet.AssertionLifecycle.VALID,
                List.of(new GovernanceExpectation(
                        "owner", EvaluationKind.GATE, GovernanceOperator.OWNER, "credit-team")),
                new CompilationCompatibility(true, "evaluator-1", List.of("gate"), ""),
                metadata(CREATED)))
                .hasMessageContaining("runtime or evidence");
    }

    @Test
    void inventoryFreezingRejectsDuplicateOrUnreviewedDenominators() {
        CoverageObligation obligation = obligation("critical-decline");
        assertThatThrownBy(() -> new CoverageInventory(
                "", "inventory-a", 1, scope(), target(), InventoryLifecycle.FROZEN,
                List.of(obligation, obligation), List.of(source()), approvedReview(), metadata(CREATED)))
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> new CoverageInventory(
                "", "inventory-a", 1, scope(), target(), InventoryLifecycle.FROZEN,
                List.of(obligation), List.of(source()), ReviewRecord.pending(), metadata(CREATED)))
                .hasMessageContaining("approved freeze review");
    }

    @Test
    void sensitiveMaterialCannotEnterDescriptorPublicationOrVerdict() throws Exception {
        JsonNode descriptor = mapper.valueToTree(fixture());
        JsonNode materialReceipt = mapper.valueToTree(materialReceipt());
        JsonNode manifest = mapper.valueToTree(publication());
        JsonNode verdict = mapper.valueToTree(acceptedVerdict());
        Set<String> fieldNames = new HashSet<>();
        collectFieldNames(descriptor, fieldNames);
        collectFieldNames(materialReceipt, fieldNames);
        collectFieldNames(manifest, fieldNames);
        collectFieldNames(verdict, fieldNames);

        assertThat(fieldNames).doesNotContain(
                "payload", "input", "output", "customerId", "secret", "credential", "token");
        assertThat(verdict.has("passed")).isFalse();
        assertThatThrownBy(() -> new CorrectnessVerdict(
                ExecutionVerdict.SUCCESS, AssertionVerdict.NONE, CoverageVerdict.COMPLETE,
                EvidenceVerdict.CURRENT, GateVerdict.ACCEPTED, ProofLevel.SIMULATED_BUSINESS,
                List.of(), List.of()))
                .hasMessageContaining("Zero assertions");
    }

    @Test
    void capturedFixtureCanActivateOnlyAfterRedactionReview() {
        FixtureSource captured = new FixtureSource(
                SourceKind.INCIDENT_CAPTURE, asset("INCIDENT", "incident-1", 1, 'e'));
        assertThat(fixture(captured, FixtureLifecycle.ACTIVE, true).lifecycle())
                .isEqualTo(FixtureLifecycle.ACTIVE);
        assertThatThrownBy(() -> fixture(
                captured,
                FixtureLifecycle.ACTIVE, false))
                .hasMessageContaining("reviewed redaction");
    }

    private <T> void assertRoundTrip(T value, Class<T> type) throws Exception {
        assertThat(mapper.readValue(mapper.writeValueAsBytes(value), type)).isEqualTo(value);
    }

    private static void collectFieldNames(JsonNode value, Set<String> destination) {
        if (value.isObject()) {
            value.fields().forEachRemaining(entry -> {
                destination.add(entry.getKey());
                collectFieldNames(entry.getValue(), destination);
            });
        } else if (value.isArray()) {
            value.forEach(entry -> collectFieldNames(entry, destination));
        }
    }

    private JsonNode schema(String file) throws Exception {
        return mapper.readTree(Files.readString(Path.of("..", "docs", "schemas", file)));
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private CorrectnessDefinition definition(
            long revision,
            Instant updatedAt,
            String displayName,
            List<String> criteria
    ) {
        PrincipalRef owner = principal("credit-team", displayName);
        return new CorrectnessDefinition(
                "", "loan-correctness", revision, scope(), target(),
                "Loan approval correctness", "Approve only eligible applicants", criteria,
                RiskLevel.CRITICAL, owner,
                List.of(new ExactBasisRef("POLICY", "loan-policy", 3, fp('b'))),
                null, asset("INVENTORY", "loan-inventory", 7, 'c'),
                CorrectnessDefinition.DefinitionLifecycle.ACTIVE,
                new ReviewRecord(ReviewStatus.APPROVED, owner, updatedAt, "Approved basis"),
                new AuditMetadata(CREATED, updatedAt, owner, owner));
    }

    private CoverageInventory inventory() {
        return new CoverageInventory(
                "", "loan-inventory", 7, scope(), target(), InventoryLifecycle.FROZEN,
                List.of(obligation("fallback"), obligation("critical-decline")),
                List.of(source()), approvedReview(), metadata(CREATED));
    }

    private CoverageObligation obligation(String id) {
        return new CoverageObligation(
                id, ObligationDimension.POLICY, "Policy " + id,
                "The policy outcome is explicit for " + id, RiskLevel.CRITICAL,
                principal("credit-team", "Credit Team"), ObligationSource.BUSINESS,
                ObligationLifecycle.FROZEN, null, List.of("loan", "critical"));
    }

    private BusinessOracle oracle() {
        return new BusinessOracle(
                "", "loan-oracle", 2, scope(), target(),
                "Eligible prime applicants are approved", List.of("Approve ineligible applicant"),
                List.of(new ExactBasisRef("POLICY", "loan-policy", 3, fp('b'))),
                principal("credit-owner", "Credit Owner"), BusinessOracle.OracleLifecycle.APPROVED,
                approvedReview(), List.of(asset("ASSERTION_SET", "loan-assertions", 2, 'd')),
                metadata(CREATED));
    }

    private AssertionSet assertionSet() {
        return new AssertionSet(
                "", "loan-assertions", 2, target(), asset("ORACLE", "loan-oracle", 2, 'a'),
                AssertionSet.AssertionLifecycle.VALID,
                List.of(new OutputAssertion(
                        "approved", EvaluationKind.RUNTIME, "/approved", OutputOperator.EQUALS, true)),
                new CompilationCompatibility(
                        true, "bloge-evidence-evaluator-1", List.of("OUTPUT_EQUALS"), ""),
                metadata(CREATED));
    }

    private ScenarioDraftSetV2 scenarios() {
        ExactAssetRef fixtureRef = asset("FIXTURE_ASSET", "prime-applicant", 3, '7');
        ScenarioDraftV2 scenario = new ScenarioDraftV2(
                "prime-approved", "Prime applicant approved", "Protect approval correctness", "",
                CaseType.GOLDEN, RiskLevel.CRITICAL, principal("credit-team", "Credit Team"),
                ScenarioLifecycle.CANONICAL,
                List.of(new ExactObligationRef(
                        asset("INVENTORY", "loan-inventory", 7, 'c'), "critical-decline", fp('5'))),
                List.of(asset("ORACLE", "loan-oracle", 2, 'a')),
                List.of(asset("ASSERTION_SET", "loan-assertions", 2, 'd')),
                List.of(asset("POLICY", "loan-policy", 3, 'b')),
                new GivenV2(new FixtureVariantRef(fixtureRef, "prime")),
                List.of(new ControlledDependencyV2(
                        "credit-score", new DependencySelector(
                                "", "score", "", "", "", List.of(), List.of(), "", List.of()),
                        new ControlledBehavior(
                                BehaviorKind.RETURN, BehaviorBoundary.NODE,
                                new FixtureVariantRef(fixtureRef, "score-760"), "", 0),
                        Consumption.once())),
                approvedReview(), List.of("loan", "golden"));
        return new ScenarioDraftSetV2(
                "", "loan-scenarios", 5, scope(), target(),
                asset("CONTRACT", "loan-contract", 4, '6'), List.of(scenario), metadata(CREATED));
    }

    private FixtureAssetDescriptor fixture() {
        return fixture(
                new FixtureSource(SourceKind.SCENARIO, asset("SCENARIO", "scenario-1", 1, 'f')),
                FixtureLifecycle.ACTIVE, true);
    }

    private FixtureAssetDescriptor fixture(
            FixtureSource source,
            FixtureLifecycle lifecycle,
            boolean redactionReviewed
    ) {
        return new FixtureAssetDescriptor(
                "", "prime-applicant", 3, scope(), "Prime applicant", source,
                asset("FIXTURE_MATERIAL", "prime-material", 3, '7'),
                new ExactSchemaRef("loan-input", 2, fp('8')), "prime", lifecycle,
                "CONFIDENTIAL", principal("credit-team", "Credit Team"),
                new RedactionDescriptor("redaction-2", List.of("/phone"), redactionReviewed),
                new RetentionDescriptor("retention-90d", 90,
                        Instant.parse("2026-11-13T00:00:00Z")),
                new QualityProfile(true, redactionReviewed, 0, 2), List.of("loan"), metadata(CREATED));
    }

    private WriteRequest materialRequest() {
        return new WriteRequest(
                "", "prime-applicant", 2,
                new FixtureSource(SourceKind.SCENARIO, asset("SCENARIO", "scenario-1", 1, 'f')),
                FixtureSubject.SCENARIO, target(), new ExactSchemaRef("loan-input", 2, fp('8')),
                "CONFIDENTIAL",
                new RetentionDescriptor("retention-90d", 90,
                        Instant.parse("2026-11-13T00:00:00Z")),
                new RedactionDescriptor("redaction-2", List.of("/phone"), true),
                Map.of("applicant", Map.of("score", 760)));
    }

    private Receipt materialReceipt() {
        return new Receipt(
                "", "prime-applicant", asset("FIXTURE_MATERIAL", "prime-applicant", 3, '4'),
                fp('4'),
                new FixtureSource(SourceKind.SCENARIO, asset("SCENARIO", "scenario-1", 1, 'f')),
                FixtureSubject.SCENARIO, target(), new ExactSchemaRef("loan-input", 2, fp('8')),
                "CONFIDENTIAL",
                new RetentionDescriptor("retention-90d", 90,
                        Instant.parse("2026-11-13T00:00:00Z")),
                new RedactionDescriptor("redaction-2", List.of("/phone"), true),
                List.of(asset("SCENARIO", "scenario-1", 1, 'f')), true, false);
    }

    private Material material() {
        return new Material(
                "", materialReceipt(), Map.of("applicant", Map.of("score", 760)), true);
    }

    private CorrectnessPublication publication() {
        return new CorrectnessPublication(
                "", "loan-publication-12", scope(), target(),
                asset("DEFINITION", "loan-correctness", 4, '1'),
                asset("INVENTORY", "loan-inventory", 7, 'c'),
                asset("SCENARIO_DRAFT_SET", "loan-scenarios", 5, '2'),
                List.of(asset("ORACLE", "loan-oracle", 2, 'a')),
                List.of(asset("ASSERTION_SET", "loan-assertions", 2, 'd')),
                List.of(asset("FIXTURE_ASSET", "prime-applicant", 3, '7')),
                List.of(asset("FIXTURE_BUNDLE", "compiled-fixtures", 1, '3')),
                asset("TEST_SUITE", "compiled-suite", 1, '4'),
                "correctness-compiler-1", fp('0'), metadata(CREATED));
    }

    private PublicationAttempt publicationAttempt() {
        return new PublicationAttempt(
                "", "attempt-1", 2, fp('a'),
                new CompilationCoordinate(
                        asset("DEFINITION", "loan-correctness", 4, '1'),
                        asset("INVENTORY", "loan-inventory", 7, 'c'),
                        asset("SCENARIO_DRAFT_SET", "loan-scenarios", 5, '2'),
                        List.of(asset("ORACLE", "loan-oracle", 2, 'a')),
                        List.of(asset("ASSERTION_SET", "loan-assertions", 2, 'd')),
                        List.of(asset("FIXTURE_ASSET", "prime-applicant", 3, '7')),
                        target()),
                CorrectnessPublication.AttemptStage.COMPILED,
                List.of(asset("ORACLE", "loan-oracle", 2, 'a')),
                Failure.none(), metadata(CREATED));
    }

    private CorrectnessVerdict acceptedVerdict() {
        return new CorrectnessVerdict(
                ExecutionVerdict.SUCCESS, AssertionVerdict.PASSED, CoverageVerdict.COMPLETE,
                EvidenceVerdict.CURRENT, GateVerdict.ACCEPTED, ProofLevel.SIMULATED_BUSINESS,
                List.of(), List.of());
    }

    private EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg");
    }

    private ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 1, fp('9'));
    }

    private ExactSourceSnapshotRef source() {
        return new ExactSourceSnapshotRef("CONTRACT", "loan-contract", 4, fp('6'));
    }

    private AuditMetadata metadata(Instant updatedAt) {
        PrincipalRef actor = principal("author-a", "Author A");
        return new AuditMetadata(CREATED, updatedAt, actor, actor);
    }

    private ReviewRecord approvedReview() {
        return new ReviewRecord(
                ReviewStatus.APPROVED, principal("reviewer-a", "Reviewer A"), UPDATED, "Approved");
    }

    private PrincipalRef principal(String id, String displayName) {
        return new PrincipalRef(id, PrincipalKind.TEAM, displayName);
    }

    private ExactAssetRef asset(String kind, String id, long revision, char seed) {
        return new ExactAssetRef(kind, id, revision, fp(seed));
    }

    private static String fp(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
