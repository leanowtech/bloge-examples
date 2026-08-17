package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioGoldenScenarioMaterialResolverTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String NESTED_TOOL_PATH =
            "/root/subject/feature-cancellation-dispute-context";
    private static final Map<String, String> NODE_BY_RESOURCE = Map.of(
            "api-order-lookup", "orderLookup",
            "api-cancellation-responsibility", "responsibilityLookup",
            "api-city-pricing-policy", "cityPolicyLookup",
            "api-compensation-history", "compensationHistoryLookup");

    private final CapabilityStudioGoldenDemoPack pack =
            new CapabilityStudioGoldenDemoPackLoader().load(JSON);
    private final CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset =
            new CapabilityStudioScenarioDatasetProjector(pack, JSON).project();
    private final CapabilityStudioGoldenScenarioMaterialResolver resolver =
            new CapabilityStudioGoldenScenarioMaterialResolver(pack);

    @Test
    void materializesNineCasesWithFourExactNestedToolControls() {
        assertThat(dataset.cases()).hasSize(9);

        for (CapabilityStudioScenarioDatasetProjector.DataCase dataCase : dataset.cases()) {
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial material = resolve(dataCase);

            assertThat(material.given().input()).isEqualTo(Map.of(
                    "orderId", "DEMO-ORDER-20260818-001",
                    "caseId", dataCase.caseRef().id()));
            assertThat(material.dependencies()).hasSize(4);
            assertThat(material.dependencies())
                    .extracting(dependency -> dependency.selector().graphPath())
                    .containsOnly(NESTED_TOOL_PATH);
            assertThat(material.dependencies())
                    .extracting(dependency -> dependency.selector().operatorRef())
                    .containsOnly("httpResource");
            assertThat(material.dependencies())
                    .extracting(dependency -> dependency.selector().nodeId())
                    .containsExactlyInAnyOrderElementsOf(NODE_BY_RESOURCE.values());
            assertThat(material.dependencies())
                    .extracting(dependency -> dependency.selector().resourceRef())
                    .containsExactlyInAnyOrderElementsOf(NODE_BY_RESOURCE.keySet());
            assertThat(material.assertions()).isNotEmpty();
        }
    }

    @Test
    void materializesCanonicalPayloadAndSpecialBehaviorExactly() {
        assertThat(dependency("case-standard-cancellation-fee", "api-order-lookup")
                .behavior().output()).isEqualTo(Map.of(
                        "orderId", "DEMO-ORDER-20260818-001",
                        "cityCode", "SZ",
                        "serviceType", "ECONOMY",
                        "status", "CANCELLED"));
        assertThat(dependency("case-rider-not-responsible", "api-cancellation-responsibility")
                .behavior().output()).isEqualTo(Map.of(
                        "owner", "RIDER",
                        "reasonCode", "RIDER_NOT_AT_FAULT",
                        "responsibilityReason", "RIDER_NOT_RESPONSIBLE"));
        assertThat(dependency("case-driver-responsible", "api-cancellation-responsibility")
                .behavior().output()).isEqualTo(Map.of(
                        "owner", "DRIVER",
                        "reasonCode", "DRIVER_LATE",
                        "responsibilityReason", "DRIVER_RESPONSIBLE"));
        assertThat(dependency("case-city-policy-missing", "api-city-pricing-policy")
                .behavior().output()).isEqualTo(Map.of());
        assertThat(dependency("case-compensation-history-empty", "api-compensation-history")
                .behavior().output()).isEqualTo(Map.of());
        assertThat(dependency("case-policy-revision-regression", "api-city-pricing-policy")
                .behavior().output()).isEqualTo(Map.of(
                        "version", "SZ-CANCEL-2026.08-R2",
                        "feeRule", "CANCEL_FEE_AFTER_5_MIN",
                        "effectiveFrom", "2026-08-01T00:00:00Z"));

        ScenarioDraftSet.DependencyBehavior timeout = dependency(
                "case-compensation-history-timeout", "api-compensation-history").behavior();
        assertThat(timeout.kind()).isEqualTo(ScenarioDraftSet.BehaviorKind.TIMEOUT);
        assertThat(timeout.errorCode()).isEqualTo("COMPENSATION_HISTORY_TIMEOUT");
        assertThat(timeout.errorType()).isEqualTo("TIMEOUT");
        assertThat(timeout.after()).isEqualTo(Duration.ofMillis(10));
        assertThat(timeout.output()).isNull();
    }

    @Test
    void keepsBusinessExpectationsAsAssertionsAndNeverAsFixtureRules() {
        for (String caseId : List.of("case-duplicate-cancellation", "case-forbidden-write-effect")) {
            CapabilityStudioScenarioDatasetProjector.DataCase dataCase = caseById(caseId);
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial material = resolve(dataCase);

            assertThat(material.dependencies())
                    .noneMatch(dependency -> dependency.dependencyRef().kind().equals("TOOL"));
            assertThat(dataCase.behaviorProfiles())
                    .filteredOn(profile -> "BUSINESS_EXPECTATION".equals(profile.purpose()))
                    .hasSize(1);
            assertThat(material.assertions())
                    .anySatisfy(assertion -> assertThat(assertion.path())
                            .startsWith("/cancellationDecision/"));
        }
    }

    @Test
    void usesExecutableBusinessResultAssertionsForEveryCase() {
        for (CapabilityStudioScenarioDatasetProjector.DataCase dataCase : dataset.cases()) {
            List<ScenarioDraftSet.AssertionDraft> assertions = resolve(dataCase).assertions();
            assertThat(assertions).anySatisfy(assertion -> {
                assertThat(assertion.scope()).isEqualTo(ScenarioDraftSet.AssertionScope.OUTPUT_PATH);
                assertThat(assertion.path()).startsWith("/cancellationDecision/");
                assertThat(assertion.operator()).isEqualTo(ScenarioDraftSet.AssertionOperator.EQUALS);
            });
        }
    }

    @Test
    void isDeterministicAcrossRepeatedResolution() throws Exception {
        for (CapabilityStudioScenarioDatasetProjector.DataCase dataCase : dataset.cases()) {
            String first = JSON.writeValueAsString(resolve(dataCase));
            String second = JSON.writeValueAsString(resolve(dataCase));
            assertThat(first).isEqualTo(second);
        }
    }

    @Test
    void failsClosedForUnknownCaseProfileDependencyAndBehavior() {
        CapabilityStudioScenarioDatasetProjector.DataCase original = caseById(
                "case-standard-cancellation-fee");
        assertThatThrownBy(() -> resolver.resolve(dataset, copyWithCaseId(original, "case-unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.MATERIAL.UNKNOWN_CASE: case-unknown");

        CapabilityStudioScenarioDatasetProjector.BehaviorProfile profile =
                original.behaviorProfiles().getFirst();
        assertThatThrownBy(() -> resolver.resolve(dataset, copyWithProfiles(original, List.of(
                new CapabilityStudioScenarioDatasetProjector.BehaviorProfile(
                        new CapabilityStudioScenarioDatasetProjector.ExactRef(
                                "BEHAVIOR_PROFILE", "behavior-profile-unknown", 1,
                                profile.behaviorRef().fingerprint(), "capability-studio-demo-pack",
                                dataset.datasetRef().scope()),
                        profile.dependencyRef(), profile.purpose(), profile.behavior(), profile.summary())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.MATERIAL.UNKNOWN_PROFILE: behavior-profile-unknown");

        assertThatThrownBy(() -> resolver.resolve(dataset, copyWithProfiles(original, List.of(
                new CapabilityStudioScenarioDatasetProjector.BehaviorProfile(
                        profile.behaviorRef(),
                        new CapabilityStudioScenarioDatasetProjector.ExactRef(
                                "API", "api-unknown", 1, profile.dependencyRef().fingerprint(),
                                "capability-studio-demo-pack", dataset.datasetRef().scope()),
                        profile.purpose(), profile.behavior(), profile.summary())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.MATERIAL.UNKNOWN_DEPENDENCY: api-unknown");

        assertThatThrownBy(() -> resolver.resolve(dataset, copyWithProfiles(original, List.of(
                new CapabilityStudioScenarioDatasetProjector.BehaviorProfile(
                        profile.behaviorRef(), profile.dependencyRef(), profile.purpose(), "UNKNOWN",
                        profile.summary())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.MATERIAL.UNSUPPORTED_BEHAVIOR: UNKNOWN");
    }

    @Test
    void failsClosedForForeignOrDriftedDatasetExactCoordinate() {
        CapabilityStudioScenarioDatasetProjector.DataCase dataCase = caseById(
                "case-standard-cancellation-fee");
        CapabilityStudioScenarioDatasetProjector.ExactRef datasetRef = dataset.datasetRef();

        assertThatThrownBy(() -> resolver.resolve(copyWithDatasetRef(dataset,
                new CapabilityStudioScenarioDatasetProjector.ExactRef(
                        datasetRef.kind(), datasetRef.id(), datasetRef.revision(),
                        datasetRef.fingerprint(), "foreign-authority", datasetRef.scope())), dataCase))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.MATERIAL.DATASET_COORDINATE_DRIFT: datasetRef");

        assertThatThrownBy(() -> resolver.resolve(copyWithDatasetRef(dataset,
                new CapabilityStudioScenarioDatasetProjector.ExactRef(
                        datasetRef.kind(), datasetRef.id(), datasetRef.revision(),
                        datasetRef.fingerprint(), datasetRef.authority(),
                        new CapabilityStudioScenarioDatasetProjector.Scope(
                                "foreign-tenant", datasetRef.scope().organizationId(),
                                datasetRef.scope().projectId(), datasetRef.scope().environmentId(),
                                datasetRef.scope().region()))), dataCase))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.MATERIAL.DATASET_COORDINATE_DRIFT: datasetRef");
    }

    @Test
    void failsClosedWhenCanonicalProfileBehaviorOrSummaryDrifts() {
        CapabilityStudioScenarioDatasetProjector.DataCase original = caseById(
                "case-standard-cancellation-fee");
        CapabilityStudioScenarioDatasetProjector.BehaviorProfile profile =
                original.behaviorProfiles().getFirst();

        assertThatThrownBy(() -> resolver.resolve(dataset, copyWithProfiles(original, replaceProfile(
                original.behaviorProfiles(), profile,
                new CapabilityStudioScenarioDatasetProjector.BehaviorProfile(
                        profile.behaviorRef(), profile.dependencyRef(), profile.purpose(), "TIMEOUT",
                        profile.summary())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.MATERIAL.PROFILE_BEHAVIOR_DRIFT: "
                        + profile.behaviorRef().id());

        assertThatThrownBy(() -> resolver.resolve(dataset, copyWithProfiles(original, replaceProfile(
                original.behaviorProfiles(), profile,
                new CapabilityStudioScenarioDatasetProjector.BehaviorProfile(
                        profile.behaviorRef(), profile.dependencyRef(), profile.purpose(),
                        profile.behavior(), profile.summary() + " drift")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.MATERIAL.PROFILE_SUMMARY_DRIFT: "
                        + profile.behaviorRef().id());
    }

    @Test
    void failsClosedWhenCaseExecutionAuthorityFieldsDriftButAllowsDisplayOnlyChanges() {
        CapabilityStudioScenarioDatasetProjector.DataCase original = caseById(
                "case-standard-cancellation-fee");

        assertCaseExecutionDrift(original, "businessIntent", copyWithCaseFields(original,
                original.name(), original.businessIntent() + " drift", original.category(),
                original.lifecycle(), original.qualityState(), original.applicableContractRefs()));
        assertCaseExecutionDrift(original, "category", copyWithCaseFields(original,
                original.name(), original.businessIntent(), "NEGATIVE", original.lifecycle(),
                original.qualityState(), original.applicableContractRefs()));
        assertCaseExecutionDrift(original, "lifecycle", copyWithCaseFields(original,
                original.name(), original.businessIntent(), original.category(), "ACTIVE",
                original.qualityState(), original.applicableContractRefs()));
        assertCaseExecutionDrift(original, "qualityState", copyWithCaseFields(original,
                original.name(), original.businessIntent(), original.category(), original.lifecycle(),
                "VERIFIED", original.applicableContractRefs()));

        CapabilityStudioScenarioDatasetProjector.DataCase displayOnly = copyWithDisplayFields(
                original);
        assertThat(resolver.resolve(dataset, displayOnly).dependencies()).hasSize(4);
    }

    @Test
    void keepsProfileClosureFailuresForMissingDuplicateAndCrossCaseProfiles() {
        CapabilityStudioScenarioDatasetProjector.DataCase original = caseById(
                "case-standard-cancellation-fee");

        assertThatThrownBy(() -> resolver.resolve(dataset, copyWithProfiles(original,
                original.behaviorProfiles().subList(0, original.behaviorProfiles().size() - 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.MATERIAL.PROFILE_CLOSURE: "
                        + original.caseRef().id());

        List<CapabilityStudioScenarioDatasetProjector.BehaviorProfile> duplicate =
                new ArrayList<>(original.behaviorProfiles());
        duplicate.add(original.behaviorProfiles().getFirst());
        assertThatThrownBy(() -> resolver.resolve(dataset, copyWithProfiles(original, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.MATERIAL.DUPLICATE_PROFILE: "
                        + original.behaviorProfiles().getFirst().behaviorRef().id());

        CapabilityStudioScenarioDatasetProjector.DataCase other = caseById(
                "case-rider-not-responsible");
        List<CapabilityStudioScenarioDatasetProjector.BehaviorProfile> crossCase =
                new ArrayList<>(original.behaviorProfiles());
        crossCase.set(0, other.behaviorProfiles().getFirst());
        assertThatThrownBy(() -> resolver.resolve(dataset, copyWithProfiles(original, crossCase)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.MATERIAL.UNKNOWN_PROFILE: "
                        + other.behaviorProfiles().getFirst().behaviorRef().id());
    }

    private CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency(
            String caseId, String resourceRef) {
        return resolve(caseById(caseId)).dependencies().stream()
                .filter(value -> value.dependencyRef().id().equals(resourceRef))
                .findFirst()
                .orElseThrow();
    }

    private CapabilityStudioScenarioDatasetMaterial.CaseMaterial resolve(
            CapabilityStudioScenarioDatasetProjector.DataCase dataCase) {
        return resolver.resolve(dataset, dataCase);
    }

    private CapabilityStudioScenarioDatasetProjector.DataCase caseById(String caseId) {
        return dataset.cases().stream()
                .filter(value -> value.caseRef().id().equals(caseId))
                .findFirst()
                .orElseThrow();
    }

    private static CapabilityStudioScenarioDatasetProjector.DataCase copyWithCaseId(
            CapabilityStudioScenarioDatasetProjector.DataCase original, String caseId) {
        CapabilityStudioScenarioDatasetProjector.ExactRef ref = original.caseRef();
        return new CapabilityStudioScenarioDatasetProjector.DataCase(
                new CapabilityStudioScenarioDatasetProjector.ExactRef(
                        ref.kind(), caseId, ref.revision(), ref.fingerprint(), ref.authority(), ref.scope()),
                original.name(), original.businessIntent(), original.category(), original.lifecycle(),
                original.qualityState(), original.owner(), original.sourceRef(), original.source(),
                original.oracleRef(), original.oracle(), original.applicableContractRefs(),
                original.behaviorProfiles());
    }

    private static CapabilityStudioScenarioDatasetProjector.DataCase copyWithProfiles(
            CapabilityStudioScenarioDatasetProjector.DataCase original,
            List<CapabilityStudioScenarioDatasetProjector.BehaviorProfile> profiles) {
        return new CapabilityStudioScenarioDatasetProjector.DataCase(
                original.caseRef(), original.name(), original.businessIntent(), original.category(),
                original.lifecycle(), original.qualityState(), original.owner(), original.sourceRef(),
                original.source(), original.oracleRef(), original.oracle(), original.applicableContractRefs(),
                profiles);
    }

    private static CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection copyWithDatasetRef(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection original,
            CapabilityStudioScenarioDatasetProjector.ExactRef datasetRef) {
        return new CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection(
                original.schemaVersion(), datasetRef, original.name(), original.description(),
                original.lifecycle(), original.classification(), original.owner(), original.targetRef(),
                original.contractRefs(), original.cases(), original.quality());
    }

    private static List<CapabilityStudioScenarioDatasetProjector.BehaviorProfile> replaceProfile(
            List<CapabilityStudioScenarioDatasetProjector.BehaviorProfile> profiles,
            CapabilityStudioScenarioDatasetProjector.BehaviorProfile original,
            CapabilityStudioScenarioDatasetProjector.BehaviorProfile replacement) {
        List<CapabilityStudioScenarioDatasetProjector.BehaviorProfile> result = new ArrayList<>(profiles);
        result.set(result.indexOf(original), replacement);
        return result;
    }

    private void assertCaseExecutionDrift(
            CapabilityStudioScenarioDatasetProjector.DataCase original,
            String field,
            CapabilityStudioScenarioDatasetProjector.DataCase drifted) {
        assertThatThrownBy(() -> resolver.resolve(dataset, drifted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.CAPABILITY_STUDIO.MATERIAL.CASE_EXECUTION_DRIFT: "
                        + original.caseRef().id() + "/" + field);
    }

    private static CapabilityStudioScenarioDatasetProjector.DataCase copyWithCaseFields(
            CapabilityStudioScenarioDatasetProjector.DataCase original,
            String name,
            String businessIntent,
            String category,
            String lifecycle,
            String qualityState,
            List<CapabilityStudioScenarioDatasetProjector.ExactRef> applicableContractRefs) {
        return new CapabilityStudioScenarioDatasetProjector.DataCase(
                original.caseRef(), name, businessIntent, category, lifecycle, qualityState,
                original.owner(), original.sourceRef(), original.source(), original.oracleRef(),
                original.oracle(), applicableContractRefs, original.behaviorProfiles());
    }

    private static CapabilityStudioScenarioDatasetProjector.DataCase copyWithDisplayFields(
            CapabilityStudioScenarioDatasetProjector.DataCase original) {
        return new CapabilityStudioScenarioDatasetProjector.DataCase(
                original.caseRef(), original.name() + " display", original.businessIntent(),
                original.category(), original.lifecycle(), original.qualityState(),
                new CapabilityStudioScenarioDatasetProjector.Owner(
                        original.owner().id(), original.owner().name() + " display"),
                original.sourceRef(), new CapabilityStudioScenarioDatasetProjector.Source(
                        original.source().displayName() + " display", "DISPLAY_ONLY"),
                original.oracleRef(), new CapabilityStudioScenarioDatasetProjector.Oracle(
                        original.oracle().displayName() + " display", "display-only summary"),
                original.applicableContractRefs(), original.behaviorProfiles());
    }
}
