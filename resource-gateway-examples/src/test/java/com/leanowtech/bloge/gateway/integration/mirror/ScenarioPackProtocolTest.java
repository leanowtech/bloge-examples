package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioPackProtocolTest {
    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);
    private static final Instant NOW = Instant.parse("2026-07-24T05:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void sealsAndVerifiesAnExactPayloadFreeScenarioClosure() {
        CaseHandlingAssertion output = ScenarioPackIntegrity.sealAssertion(
                mapper, outputAssertion());
        CaseHandlingAssertion state = ScenarioPackIntegrity.sealAssertion(
                mapper, stateAssertion());
        ScenarioCase golden = ScenarioPackIntegrity.sealCase(
                mapper, scenarioCase(
                        "refund-golden", ScenarioCase.CaseType.GOLDEN,
                        List.of(ScenarioPackIntegrity.reference(output)), null));
        ScenarioCase transition = ScenarioPackIntegrity.sealCase(
                mapper, scenarioCase(
                        "refund-recovery", ScenarioCase.CaseType.STATE_TRANSITION,
                        List.of(ScenarioPackIntegrity.reference(state)), checkpointRef()));
        ScenarioPack pack = ScenarioPackIntegrity.seal(
                mapper, scenarioPack(List.of(
                        ScenarioPackIntegrity.reference(transition),
                        ScenarioPackIntegrity.reference(golden)),
                        List.of(
                                ScenarioPackIntegrity.reference(state),
                                ScenarioPackIntegrity.reference(output))));

        ScenarioPackIntegrity.verifyAssertion(mapper, output);
        ScenarioPackIntegrity.verifyAssertion(mapper, state);
        ScenarioPackIntegrity.verifyCase(mapper, golden);
        ScenarioPackIntegrity.verifyCase(mapper, transition);
        ScenarioPackIntegrity.verify(mapper, pack);

        assertThat(pack.caseRefs()).extracting(MirrorArtifactRef::id)
                .containsExactly("refund-recovery", "refund-golden");
        assertThat(pack.assertionRefs()).extracting(MirrorArtifactRef::id)
                .containsExactly("refund-output", "refund-state");
        assertThat(ScenarioPackIntegrity.reference(pack).kind())
                .isEqualTo("SCENARIO_PACK");
        assertThat(mapper.valueToTree(pack).toString())
                .doesNotContain("O-100", "request", "response", "credential");
    }

    @Test
    void rejectsFingerprintTamperingAndCrossTenantProvenance() {
        CaseHandlingAssertion assertion = ScenarioPackIntegrity.sealAssertion(
                mapper, outputAssertion());
        ScenarioCase scenarioCase = ScenarioPackIntegrity.sealCase(
                mapper, scenarioCase(
                        "refund-golden", ScenarioCase.CaseType.GOLDEN,
                        List.of(ScenarioPackIntegrity.reference(assertion)), null));
        ScenarioPack pack = ScenarioPackIntegrity.seal(
                mapper, scenarioPack(
                        List.of(ScenarioPackIntegrity.reference(scenarioCase)),
                        List.of(ScenarioPackIntegrity.reference(assertion))));
        assertThatThrownBy(() -> ScenarioPackIntegrity.verify(
                mapper, pack.withFingerprint(SHA_C)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scenario pack fingerprint mismatch");

        ArtifactProvenance wrongTenant = new ArtifactProvenance(
                "", ArtifactProvenance.SourceType.OWNER, List.of(),
                "tenant-b", "customer-support-simulation",
                null, null, null, null, List.of(),
                "owner", NOW, NOW.plus(Duration.ofDays(1)), "");
        ScenarioPack crossTenant = new ScenarioPack(
                pack.schemaVersion(), pack.packId(), 2, "", pack.scope(),
                pack.targetCapabilityRef(), pack.caseRefs(), pack.assertionRefs(),
                pack.writeEffectRefs(), pack.corpusSnapshotRef(), pack.stateModelRefs(),
                pack.policy(), wrongTenant, pack.lifecycle(), NOW);

        assertThatThrownBy(() -> ScenarioPackIntegrity.seal(mapper, crossTenant))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope tenant");
    }

    @Test
    void rejectsImplicitFaultsSharedStateAndProductionEscapeHatches() {
        MirrorArtifactRef assertionRef = ref(
                "CASE_HANDLING_ASSERTION", "refund-output", SHA_A);

        assertThatThrownBy(() -> scenarioCase(
                "fault-without-rule", ScenarioCase.CaseType.FAULT,
                List.of(assertionRef), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fault rule");
        assertThatThrownBy(() -> scenarioCase(
                "state-without-checkpoint", ScenarioCase.CaseType.STATE_TRANSITION,
                List.of(assertionRef), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Session checkpoint");
        assertThatThrownBy(() -> new ScenarioPack.RehearsalPolicy(
                ScenarioPack.Scheduling.SEQUENTIAL,
                true, true, false, false,
                ScenarioPack.EvidenceMode.HASH_ONLY,
                8, 100, Duration.ofSeconds(30), Duration.ofMinutes(5),
                true, CapabilityContract.DataClassification.CONFIDENTIAL,
                List.of("sg")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("isolation");
        assertThatThrownBy(() -> new ScenarioPack.RehearsalPolicy(
                ScenarioPack.Scheduling.SEQUENTIAL,
                false, false, false, false,
                ScenarioPack.EvidenceMode.HASH_ONLY,
                8, 100, Duration.ofSeconds(30), Duration.ofMinutes(5),
                true, CapabilityContract.DataClassification.CONFIDENTIAL,
                List.of("sg")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("isolation");
    }

    @Test
    void rejectsDimensionSelectorsThatCannotBeEvaluated() {
        assertThatThrownBy(() -> new CaseHandlingAssertion(
                "", "bad-node-status", 1, "", scope(),
                CaseHandlingAssertion.Observation.NODE_STATUS,
                CaseHandlingAssertion.Selector.empty(),
                new CaseHandlingAssertion.Expectation(
                        List.of("SUCCESS"), "", "", "",
                        null, null, null, null),
                CaseHandlingAssertion.Severity.BLOCKER,
                "RG.MIRROR.SCENARIO.BAD_NODE",
                provenance(), CapabilitySnapshot.Lifecycle.ACTIVE, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nodeId");

        assertThatThrownBy(() -> new CaseHandlingAssertion.Expectation(
                List.of(), "", "", "", 2L, 1L, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounds");
    }

    @Test
    void strictSchemasMatchEverySerializedFieldAndExcludeBusinessPayloads() throws Exception {
        CaseHandlingAssertion assertion = ScenarioPackIntegrity.sealAssertion(
                mapper, outputAssertion());
        ScenarioCase scenarioCase = ScenarioPackIntegrity.sealCase(
                mapper, scenarioCase(
                        "refund-golden", ScenarioCase.CaseType.GOLDEN,
                        List.of(ScenarioPackIntegrity.reference(assertion)), null));
        ScenarioPack pack = ScenarioPackIntegrity.seal(
                mapper, scenarioPack(
                        List.of(ScenarioPackIntegrity.reference(scenarioCase)),
                        List.of(ScenarioPackIntegrity.reference(assertion))));
        CompiledScenarioRehearsalPlan compiled =
                CompiledScenarioRehearsalPlanIntegrity.seal(
                        mapper,
                        new CompiledScenarioRehearsalPlan(
                                "",
                                pack.packId()
                                        + ScenarioRehearsalCompiler
                                        .PLAN_ID_SUFFIX,
                                pack.revision(),
                                "",
                                pack.scope(),
                                ScenarioPackIntegrity.reference(pack),
                                pack.targetCapabilityRef(),
                                List.of(
                                        new CompiledScenarioRehearsalPlan
                                                .CaseBinding(
                                                ScenarioPackIntegrity.reference(
                                                        scenarioCase),
                                                scenarioCase.caseType(),
                                                scenarioCase.testSuiteRef(),
                                                scenarioCase.testCaseId(),
                                                scenarioCase.mirrorPlanRef(),
                                                scenarioCase.fixtureBundleRef(),
                                                scenarioCase
                                                        .sessionCheckpointRef(),
                                                scenarioCase.executionServices(),
                                                scenarioCase.assertionRefs())),
                                pack.assertionRefs(),
                                pack.policy()));
        ScenarioRehearsalCompileRequest compileRequest =
                new ScenarioRehearsalCompileRequest(
                        "", pack.revision(), pack.fingerprint());
        ScenarioHandlingAssertionResult assertionResult =
                ScenarioHandlingAssertionResultIntegrity.seal(
                        mapper,
                        new ScenarioHandlingAssertionResult(
                                "", "", "run-refund-1", SHA_A, SHA_B,
                                ScenarioPackIntegrity.reference(assertion),
                                assertion.observation(),
                                ScenarioHandlingAssertionResult.Outcome
                                        .INDETERMINATE,
                                assertion.severity(),
                                assertion.governanceCode(),
                                ScenarioHandlingAssertionResult.ReasonCode
                                        .ASSERTION_EVIDENCE_FACT_UNAVAILABLE,
                                new ScenarioHandlingAssertionResult
                                        .ObservedFacts(
                                        List.of("PASSED"), List.of(), List.of(),
                                        List.of("EXPLORATORY"), 1L, 12L, false,
                                        List.of(
                                                "MISSING_GRAPH_OUTPUT_SCHEMA_FACT"))));

        assertProperties(assertion, "case-handling-assertion-v1.schema.json");
        assertProperties(
                assertion.selector(),
                schema("case-handling-assertion-v1.schema.json")
                        .at("/$defs/selector/properties"));
        assertProperties(
                assertion.expectation(),
                schema("case-handling-assertion-v1.schema.json")
                        .at("/$defs/expectation/properties"));
        assertProperties(scenarioCase, "scenario-case-v1.schema.json");
        assertProperties(pack, "scenario-pack-v1.schema.json");
        assertProperties(
                compiled,
                "compiled-scenario-rehearsal-plan-v1.schema.json");
        assertProperties(
                compiled.cases().getFirst(),
                schema("compiled-scenario-rehearsal-plan-v1.schema.json")
                        .at("/$defs/caseBinding/properties"));
        assertProperties(
                compileRequest,
                "scenario-rehearsal-compile-request-v1.schema.json");
        assertProperties(
                assertionResult,
                "scenario-handling-assertion-result-v1.schema.json");
        assertProperties(
                assertionResult.observed(),
                schema("scenario-handling-assertion-result-v1.schema.json")
                        .at("/$defs/observedFacts/properties"));
        assertProperties(
                pack.policy(),
                schema("scenario-pack-v1.schema.json")
                        .at("/$defs/policy/properties"));

        for (String file : List.of(
                "case-handling-assertion-v1.schema.json",
                "scenario-case-v1.schema.json",
                "scenario-pack-v1.schema.json",
                "scenario-rehearsal-compile-request-v1.schema.json",
                "compiled-scenario-rehearsal-plan-v1.schema.json",
                "scenario-handling-assertion-result-v1.schema.json")) {
            JsonNode properties = schema(file).path("properties");
            assertThat(properties.has("input")).isFalse();
            assertThat(properties.has("output")).isFalse();
            assertThat(properties.has("request")).isFalse();
            assertThat(properties.has("response")).isFalse();
            assertThat(schema(file).path("additionalProperties").asBoolean()).isFalse();
        }
    }

    private ScenarioPack scenarioPack(
            List<MirrorArtifactRef> cases, List<MirrorArtifactRef> assertions) {
        return new ScenarioPack(
                "", "refund-rehearsal", 1, "", scope(),
                capabilityRef(), cases, assertions,
                List.of(ref("WRITE_EFFECT", "create-refund", SHA_A)),
                null,
                List.of(ref("STATE_MODEL", "refund-world", SHA_B)),
                policy(), provenance(), CapabilitySnapshot.Lifecycle.ACTIVE, NOW);
    }

    private ScenarioCase scenarioCase(
            String caseId,
            ScenarioCase.CaseType type,
            List<MirrorArtifactRef> assertions,
            MirrorArtifactRef checkpoint) {
        return new ScenarioCase(
                "", caseId, 1, "", scope(), type, capabilityRef(),
                ref("TEST_SUITE", "refund-contract-suite", SHA_A),
                caseId,
                ref("MIRROR_PLAN", "refund-plan", SHA_B),
                ref("FIXTURE_BUNDLE", "refund-fixtures", SHA_C),
                checkpoint,
                new MirrorPlan.ExecutionServices(
                        NOW, 42L, null, null),
                List.of(),
                assertions,
                provenance(), CapabilitySnapshot.Lifecycle.ACTIVE, NOW);
    }

    private CaseHandlingAssertion outputAssertion() {
        return new CaseHandlingAssertion(
                "", "refund-output", 1, "", scope(),
                CaseHandlingAssertion.Observation.GRAPH_OUTPUT_SCHEMA,
                new CaseHandlingAssertion.Selector("", "", "", null, "/refund"),
                new CaseHandlingAssertion.Expectation(
                        List.of(), "", SHA_A, "",
                        null, null, null, null),
                CaseHandlingAssertion.Severity.BLOCKER,
                "RG.MIRROR.SCENARIO.REFUND_OUTPUT_INVALID",
                provenance(), CapabilitySnapshot.Lifecycle.ACTIVE, NOW);
    }

    private CaseHandlingAssertion stateAssertion() {
        return new CaseHandlingAssertion(
                "", "refund-state", 1, "", scope(),
                CaseHandlingAssertion.Observation.STATE_TRANSITION,
                CaseHandlingAssertion.Selector.empty(),
                new CaseHandlingAssertion.Expectation(
                        List.of("COMMITTED"), "", "", "",
                        null, null, null, true),
                CaseHandlingAssertion.Severity.BLOCKER,
                "RG.MIRROR.SCENARIO.REFUND_STATE_INVALID",
                provenance(), CapabilitySnapshot.Lifecycle.ACTIVE, NOW);
    }

    private ScenarioPack.RehearsalPolicy policy() {
        return new ScenarioPack.RehearsalPolicy(
                ScenarioPack.Scheduling.SEQUENTIAL,
                true, false, false, false,
                ScenarioPack.EvidenceMode.HASH_ONLY,
                16, 1_000, Duration.ofMinutes(2), Duration.ofMinutes(30),
                true, CapabilityContract.DataClassification.CONFIDENTIAL,
                List.of("sg"));
    }

    private static CapabilitySnapshot.Scope scope() {
        return new CapabilitySnapshot.Scope(
                "tenant-a", "org-a", "tool-studio", "test", "sg");
    }

    private static ArtifactProvenance provenance() {
        return new ArtifactProvenance(
                "", ArtifactProvenance.SourceType.OWNER, List.of(),
                "tenant-a", "customer-support-simulation",
                null, null, null, null, List.of(),
                "refund-owner", NOW, NOW.plus(Duration.ofDays(1)), "");
    }

    private static MirrorArtifactRef capabilityRef() {
        return ref("CAPABILITY", "refund-orchestration", SHA_A);
    }

    private static MirrorArtifactRef checkpointRef() {
        return ref(
                "MIRROR_SESSION_CHECKPOINT",
                "refund-recovery-checkpoint",
                SHA_C);
    }

    private static MirrorArtifactRef ref(String kind, String id, String fingerprint) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint);
    }

    private void assertProperties(Object value, String schemaFile) throws Exception {
        assertProperties(value, schema(schemaFile).path("properties"));
    }

    private void assertProperties(Object value, JsonNode properties) {
        JsonNode serialized = mapper.valueToTree(value);
        Set<String> actual = new LinkedHashSet<>();
        serialized.fieldNames().forEachRemaining(actual::add);
        Set<String> expected = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private JsonNode schema(String file) throws Exception {
        Path moduleRelative = Path.of(
                "..", "docs", "schemas", "resource-gateway-mirror", file);
        Path path = Files.exists(moduleRelative)
                ? moduleRelative
                : Path.of("docs", "schemas", "resource-gateway-mirror", file);
        return mapper.readTree(Files.readString(path));
    }
}
