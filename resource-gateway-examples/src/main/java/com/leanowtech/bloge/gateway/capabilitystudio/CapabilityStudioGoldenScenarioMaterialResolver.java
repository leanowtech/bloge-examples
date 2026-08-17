package com.leanowtech.bloge.gateway.capabilitystudio;

import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Protected material authority for the Capability Studio Stage 0 golden pack.
 *
 * <p>The public Dataset projection contains only references and behavior summaries. This
 * package-private resolver is the one controlled place where those summaries become executable
 * Scenario material. It deliberately emits controls for API dependencies only; Tool-level
 * business expectations remain assertion obligations and never become fixture rules.</p>
 */
final class CapabilityStudioGoldenScenarioMaterialResolver
        implements CapabilityStudioScenarioDatasetMaterialResolver {

    private static final String ERROR_PREFIX = "RG.CAPABILITY_STUDIO.MATERIAL.";
    private static final String NESTED_TOOL_PATH =
            "/root/subject/feature-cancellation-dispute-context";
    private static final String HTTP_RESOURCE_OPERATOR = "httpResource";
    private static final String ORDER_RESOURCE = "api-order-lookup";
    private static final String RESPONSIBILITY_RESOURCE = "api-cancellation-responsibility";
    private static final String POLICY_RESOURCE = "api-city-pricing-policy";
    private static final String COMPENSATION_RESOURCE = "api-compensation-history";
    private static final Map<String, String> NODE_BY_RESOURCE = Map.of(
            ORDER_RESOURCE, "orderLookup",
            RESPONSIBILITY_RESOURCE, "responsibilityLookup",
            POLICY_RESOURCE, "cityPolicyLookup",
            COMPENSATION_RESOURCE, "compensationHistoryLookup");

    private final CapabilityStudioScenarioDatasetProjector.ExactRef canonicalDatasetRef;
    private final Map<String, CapabilityStudioScenarioDatasetProjector.DataCase> canonicalCases;
    private final Map<String, CapabilityStudioScenarioDatasetProjector.BehaviorProfile>
            canonicalProfiles;
    private final Map<String, CapabilityStudioScenarioDatasetProjector.BehaviorProfile>
            canonicalProfilesByCoordinate;
    private final Set<String> canonicalDependencyRefs;
    private final Set<String> canonicalDependencyCoordinates;

    CapabilityStudioGoldenScenarioMaterialResolver(CapabilityStudioGoldenDemoPack pack) {
        Objects.requireNonNull(pack, "pack");
        CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection projection =
                new CapabilityStudioScenarioDatasetProjector(pack).project();
        this.canonicalDatasetRef = projection.datasetRef();
        this.canonicalCases = projection.cases().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        value -> value.caseRef().id(), Function.identity()));
        this.canonicalProfiles = projection.cases().stream()
                .flatMap(value -> value.behaviorProfiles().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        value -> identity(value.behaviorRef()), Function.identity()));
        this.canonicalProfilesByCoordinate = projection.cases().stream()
                .flatMap(value -> value.behaviorProfiles().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        value -> coordinate(value.behaviorRef()), Function.identity()));
        this.canonicalDependencyRefs = projection.cases().stream()
                .flatMap(value -> value.behaviorProfiles().stream())
                .map(value -> identity(value.dependencyRef()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.canonicalDependencyCoordinates = projection.cases().stream()
                .flatMap(value -> value.behaviorProfiles().stream())
                .map(value -> coordinate(value.dependencyRef()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public CapabilityStudioScenarioDatasetMaterial.CaseMaterial resolve(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            CapabilityStudioScenarioDatasetProjector.DataCase dataCase) {
        if (dataset == null) {
            fail("DATASET_MISSING", "dataset");
        }
        if (dataset.datasetRef() == null || !dataset.datasetRef().equals(canonicalDatasetRef)) {
            fail("DATASET_COORDINATE_DRIFT", "datasetRef");
        }
        if (dataCase == null || dataCase.caseRef() == null) {
            fail("CASE_MISSING", "case");
        }
        CapabilityStudioScenarioDatasetProjector.DataCase canonical = canonicalCases.get(
                dataCase.caseRef().id());
        if (canonical == null) {
            fail("UNKNOWN_CASE", dataCase.caseRef().id());
        }
        validateCaseCoordinate(dataCase, canonical);
        validateCaseExecutionAuthority(dataCase, canonical);

        Map<String, CapabilityStudioScenarioDatasetProjector.BehaviorProfile> profiles =
                validateProfiles(dataCase, canonical);
        List<CapabilityStudioScenarioDatasetMaterial.DependencyMaterial> dependencies =
                profiles.values().stream()
                        .filter(profile -> "RUNTIME_CONTROL".equals(profile.purpose()))
                        .sorted(Comparator.comparing(profile -> profile.dependencyRef().id()))
                        .map(profile -> dependency(dataCase, profile))
                        .toList();
        if (dependencies.size() != NODE_BY_RESOURCE.size()) {
            fail("RUNTIME_CONTROL_CLOSURE", dataCase.caseRef().id());
        }

        return new CapabilityStudioScenarioDatasetMaterial.CaseMaterial(
                dataCase.caseRef(),
                dataCase.sourceRef(),
                dataCase.oracleRef(),
                new ScenarioDraftSet.Given(
                        Map.of("orderId", "DEMO-ORDER-20260818-001",
                                "caseId", dataCase.caseRef().id()),
                        ScenarioDraftSet.ValueProvenance.IMPORTED),
                dependencies,
                List.of(assertion(dataCase)));
    }

    private void validateCaseCoordinate(
            CapabilityStudioScenarioDatasetProjector.DataCase actual,
            CapabilityStudioScenarioDatasetProjector.DataCase canonical) {
        if (!actual.caseRef().equals(canonical.caseRef())
                || !actual.sourceRef().equals(canonical.sourceRef())
                || !actual.oracleRef().equals(canonical.oracleRef())) {
            fail("CASE_COORDINATE_DRIFT", actual.caseRef().id());
        }
    }

    /**
     * These fields are the execution authority of a frozen Case: they affect scenario lowering,
     * eligibility, contract closure, or the business meaning that the materializer protects.
     * Name/owner/source/oracle display content and applicable contract bindings are deliberately
     * excluded: the former is presentation metadata, while the latter is a governance closure
     * that the compiler is allowed to retarget to the exact Contract under test. Neither changes
     * this resolver's executable fixture or assertion and should not create needless rejection.
     */
    private void validateCaseExecutionAuthority(
            CapabilityStudioScenarioDatasetProjector.DataCase actual,
            CapabilityStudioScenarioDatasetProjector.DataCase canonical) {
        if (!Objects.equals(actual.businessIntent(), canonical.businessIntent())) {
            fail("CASE_EXECUTION_DRIFT", actual.caseRef().id() + "/businessIntent");
        }
        if (!Objects.equals(actual.category(), canonical.category())) {
            fail("CASE_EXECUTION_DRIFT", actual.caseRef().id() + "/category");
        }
        if (!Objects.equals(actual.lifecycle(), canonical.lifecycle())) {
            fail("CASE_EXECUTION_DRIFT", actual.caseRef().id() + "/lifecycle");
        }
        if (!Objects.equals(actual.qualityState(), canonical.qualityState())) {
            fail("CASE_EXECUTION_DRIFT", actual.caseRef().id() + "/qualityState");
        }
    }

    private Map<String, CapabilityStudioScenarioDatasetProjector.BehaviorProfile> validateProfiles(
            CapabilityStudioScenarioDatasetProjector.DataCase dataCase,
            CapabilityStudioScenarioDatasetProjector.DataCase canonical) {
        Map<String, CapabilityStudioScenarioDatasetProjector.BehaviorProfile> expected =
                canonical.behaviorProfiles().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                value -> identity(value.behaviorRef()), Function.identity()));
        Map<String, CapabilityStudioScenarioDatasetProjector.BehaviorProfile> actual = new HashMap<>();
        for (CapabilityStudioScenarioDatasetProjector.BehaviorProfile profile
                : dataCase.behaviorProfiles()) {
            if (profile == null || profile.behaviorRef() == null) {
                fail("PROFILE_MISSING", dataCase.caseRef().id());
            }
            String profileIdentity = identity(profile.behaviorRef());
            if (!canonicalProfiles.containsKey(profileIdentity)) {
                if (canonicalProfilesByCoordinate.containsKey(coordinate(profile.behaviorRef()))) {
                    fail("PROFILE_COORDINATE_DRIFT", profile.behaviorRef().id());
                }
                fail("UNKNOWN_PROFILE", profile.behaviorRef().id());
            }
            if (!expected.containsKey(profileIdentity)) {
                fail("UNKNOWN_PROFILE", profile.behaviorRef().id());
            }
            if (profile.dependencyRef() == null
                    || !canonicalDependencyRefs.contains(identity(profile.dependencyRef()))) {
                if (profile.dependencyRef() != null
                        && canonicalDependencyCoordinates.contains(
                        coordinate(profile.dependencyRef()))) {
                    fail("DEPENDENCY_COORDINATE_DRIFT", profile.dependencyRef().id());
                }
                fail("UNKNOWN_DEPENDENCY", profile.dependencyRef() == null
                        ? ""
                        : profile.dependencyRef().id());
            }
            if (!Set.of("RUNTIME_CONTROL", "BUSINESS_EXPECTATION").contains(profile.purpose())) {
                fail("UNSUPPORTED_PURPOSE", profile.purpose());
            }
            if (!expected.get(profileIdentity).dependencyRef().equals(profile.dependencyRef())
                    || !expected.get(profileIdentity).purpose().equals(profile.purpose())) {
                fail("PROFILE_COORDINATE_DRIFT", profile.behaviorRef().id());
            }
            validateBehavior(profile.behavior());
            if (!expected.get(profileIdentity).behavior().equals(profile.behavior())) {
                fail("PROFILE_BEHAVIOR_DRIFT", profile.behaviorRef().id());
            }
            if (!Objects.equals(expected.get(profileIdentity).summary(), profile.summary())) {
                fail("PROFILE_SUMMARY_DRIFT", profile.behaviorRef().id());
            }
            if (actual.put(profileIdentity, profile) != null) {
                fail("DUPLICATE_PROFILE", profile.behaviorRef().id());
            }
        }
        if (!actual.keySet().equals(expected.keySet())) {
            fail("PROFILE_CLOSURE", dataCase.caseRef().id());
        }
        return actual;
    }

    private CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency(
            CapabilityStudioScenarioDatasetProjector.DataCase dataCase,
            CapabilityStudioScenarioDatasetProjector.BehaviorProfile profile) {
        String resourceRef = profile.dependencyRef().id();
        String nodeId = NODE_BY_RESOURCE.get(resourceRef);
        if (nodeId == null || !"API".equals(profile.dependencyRef().kind())) {
            fail("RUNTIME_DEPENDENCY_NOT_API", resourceRef);
        }
        ScenarioDraftSet.DependencySelector selector = new ScenarioDraftSet.DependencySelector(
                NESTED_TOOL_PATH,
                nodeId,
                HTTP_RESOURCE_OPERATOR,
                resourceRef,
                "",
                List.of(),
                List.of(),
                "",
                Map.of());
        return new CapabilityStudioScenarioDatasetMaterial.DependencyMaterial(
                profile.behaviorRef(),
                profile.dependencyRef(),
                selector,
                behavior(dataCase.caseRef().id(), resourceRef, profile.behavior(), profile.summary()),
                ScenarioDraftSet.Consumption.once(),
                ScenarioDraftSet.SchemaCheck.strict());
    }

    private static ScenarioDraftSet.DependencyBehavior behavior(
            String caseId, String resourceRef, String kind, String summary) {
        return switch (kind) {
            case "RETURN" -> ScenarioDraftSet.DependencyBehavior.returning(payload(caseId, resourceRef));
            case "ERROR" -> new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.ERROR,
                    ScenarioDraftSet.BehaviorBoundary.NODE,
                    null,
                    null,
                    "",
                    null,
                    Map.of(),
                    "CAPABILITY_STUDIO_" + token(resourceRef) + "_ERROR",
                    "BUSINESS",
                    summary,
                    null,
                    "");
            case "TIMEOUT" -> new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.TIMEOUT,
                    ScenarioDraftSet.BehaviorBoundary.NODE,
                    null,
                    null,
                    "",
                    null,
                    Map.of(),
                    timeoutCode(caseId, resourceRef),
                    "TIMEOUT",
                    summary,
                    Duration.ofMillis(10),
                    "");
            case "MUST_NOT_CALL" -> new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.MUST_NOT_CALL,
                    ScenarioDraftSet.BehaviorBoundary.NODE,
                    null,
                    null,
                    "",
                    null,
                    Map.of(),
                    "CAPABILITY_STUDIO_MUST_NOT_CALL",
                    "DENIED_INVOCATION",
                    summary,
                    null,
                    "");
            default -> throw new IllegalArgumentException(
                    ERROR_PREFIX + "UNSUPPORTED_BEHAVIOR: " + kind);
        };
    }

    private static ScenarioDraftSet.AssertionDraft assertion(
            CapabilityStudioScenarioDatasetProjector.DataCase dataCase) {
        String caseId = dataCase.caseRef().id();
        return new ScenarioDraftSet.AssertionDraft(
                "assert-" + caseId + "-business-result",
                ScenarioDraftSet.AssertionScope.OUTPUT_PATH,
                "",
                "",
                "",
                "/cancellationDecision/action",
                ScenarioDraftSet.AssertionOperator.EQUALS,
                expectedAction(caseId),
                null);
    }

    private static String expectedAction(String caseId) {
        return switch (caseId) {
            case "case-rider-not-responsible" -> "WAIVE_CANCELLATION_FEE";
            case "case-driver-responsible" -> "APPLY_DRIVER_RESPONSIBILITY_RULE";
            case "case-city-policy-missing" -> "MANUAL_REVIEW";
            case "case-compensation-history-timeout" -> "MANUAL_REVIEW";
            case "case-standard-cancellation-fee",
                    "case-compensation-history-empty",
                    "case-duplicate-cancellation",
                    "case-forbidden-write-effect",
                    "case-policy-revision-regression" -> "AUTO_QUOTE";
            default -> throw new IllegalArgumentException(
                    ERROR_PREFIX + "UNKNOWN_CASE: " + caseId);
        };
    }

    private static Map<String, Object> payload(String caseId, String resourceRef) {
        if (caseId.equals("case-rider-not-responsible")
                && resourceRef.equals(RESPONSIBILITY_RESOURCE)) {
            return Map.of("owner", "RIDER", "reasonCode", "RIDER_NOT_AT_FAULT",
                    "responsibilityReason", "RIDER_NOT_RESPONSIBLE");
        }
        if (caseId.equals("case-driver-responsible")
                && resourceRef.equals(RESPONSIBILITY_RESOURCE)) {
            return Map.of("owner", "DRIVER", "reasonCode", "DRIVER_LATE",
                    "responsibilityReason", "DRIVER_RESPONSIBLE");
        }
        if ((caseId.equals("case-city-policy-missing") && resourceRef.equals(POLICY_RESOURCE))
                || (caseId.equals("case-compensation-history-empty")
                && resourceRef.equals(COMPENSATION_RESOURCE))) {
            return Map.of();
        }
        if (caseId.equals("case-policy-revision-regression") && resourceRef.equals(POLICY_RESOURCE)) {
            return Map.of("version", "SZ-CANCEL-2026.08-R2",
                    "feeRule", "CANCEL_FEE_AFTER_5_MIN",
                    "effectiveFrom", "2026-08-01T00:00:00Z");
        }
        return switch (resourceRef) {
            case ORDER_RESOURCE -> Map.of("orderId", "DEMO-ORDER-20260818-001",
                    "cityCode", "SZ", "serviceType", "ECONOMY", "status", "CANCELLED");
            case RESPONSIBILITY_RESOURCE -> Map.of("owner", "PLATFORM",
                    "reasonCode", "DRIVER_LATE");
            case POLICY_RESOURCE -> Map.of("version", "SZ-CANCEL-2026.08",
                    "feeRule", "CANCEL_FEE_AFTER_5_MIN",
                    "effectiveFrom", "2026-08-01T00:00:00Z");
            case COMPENSATION_RESOURCE -> Map.of("hasHistory", true,
                    "records", List.of(Map.of("recordType", "CANCELLATION_REVIEW")));
            default -> throw new IllegalArgumentException(
                    ERROR_PREFIX + "UNKNOWN_DEPENDENCY: " + resourceRef);
        };
    }

    private static String timeoutCode(String caseId, String resourceRef) {
        if (caseId.equals("case-compensation-history-timeout")
                && resourceRef.equals(COMPENSATION_RESOURCE)) {
            return "COMPENSATION_HISTORY_TIMEOUT";
        }
        return "CAPABILITY_STUDIO_" + token(resourceRef) + "_TIMEOUT";
    }

    private static String token(String value) {
        return value.replace('-', '_').toUpperCase();
    }

    private static void validateBehavior(String behavior) {
        if (!Set.of("RETURN", "ERROR", "TIMEOUT", "MUST_NOT_CALL").contains(behavior)) {
            fail("UNSUPPORTED_BEHAVIOR", behavior);
        }
    }

    private static String identity(CapabilityStudioScenarioDatasetProjector.ExactRef ref) {
        return coordinate(ref) + "|" + ref.authority() + "|" + scopeIdentity(ref.scope());
    }

    private static String coordinate(CapabilityStudioScenarioDatasetProjector.ExactRef ref) {
        return ref.kind() + "|" + ref.id() + "|" + ref.revision() + "|" + ref.fingerprint();
    }

    private static String scopeIdentity(CapabilityStudioScenarioDatasetProjector.Scope scope) {
        if (scope == null) {
            return "<null>";
        }
        return scope.tenantId() + "|" + scope.organizationId() + "|" + scope.projectId()
                + "|" + scope.environmentId() + "|" + scope.region();
    }

    private static void fail(String code, String value) {
        throw new IllegalArgumentException(ERROR_PREFIX + code + ": " + value);
    }
}
