package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministically adapts a payload-free Capability Studio Dataset to the existing
 * {@link ScenarioDraftSet} authoring protocol.
 *
 * <p>This class only materializes the existing Scenario model. Execution remains the
 * responsibility of the existing {@code ScenarioGovernedCompiler} and testing control plane.</p>
 */
public final class CapabilityStudioScenarioDatasetCompiler {

    private static final int MAX_FINGERPRINT_BYTES = 16 * 1_048_576;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final String DATASET_SCHEMA =
            "resource-gateway.capability-studio.scenario-dataset.v1";
    private static final String ERROR_PREFIX = "RG.CAPABILITY_STUDIO.DATASET_COMPILE.";

    private final ObjectMapper mapper;

    public CapabilityStudioScenarioDatasetCompiler(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Compiles all Dataset cases using only exact target coordinates and protected material.
     *
     * @param dataset payload-free Dataset projection
     * @param target exact target plus the exact Contract fingerprint
     * @param resolver controlled material resolver, never exposed by a Controller
     * @return existing ScenarioDraftSet plus payload-free source map and semantic fingerprint
     */
    public CapabilityStudioScenarioDatasetCompilation compile(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            ExactCompilationTarget target,
            CapabilityStudioScenarioDatasetMaterialResolver resolver) {
        validateDataset(dataset);
        validateTarget(dataset, target);
        if (resolver == null) {
            fail("MATERIAL_RESOLVER_MISSING", "/resolver");
        }

        List<CapabilityStudioScenarioDatasetProjector.DataCase> cases = dataset.cases().stream()
                .sorted(Comparator.comparing(value -> value.caseRef().id()))
                .toList();
        List<ScenarioDraftSet.ScenarioDraft> scenarios = new ArrayList<>();
        List<CapabilityStudioScenarioDatasetSourceMap.CaseSource> sourceCases = new ArrayList<>();
        Set<String> scenarioIds = new HashSet<>();
        for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
            CapabilityStudioScenarioDatasetProjector.DataCase dataCase = cases.get(caseIndex);
            String path = "/cases/" + caseIndex;
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial material = resolve(
                    dataset, dataCase, resolver, path);
            validateMaterial(dataset, dataCase, material, path);

            String scenarioId = dataCase.caseRef().id();
            if (!scenarioIds.add(scenarioId)) {
                fail("DUPLICATE_CASE", path + "/caseRef");
            }
            List<ScenarioDraftSet.DependencyBehaviorDraft> dependencies = new ArrayList<>();
            List<CapabilityStudioScenarioDatasetSourceMap.BehaviorSource> behaviorSources =
                    new ArrayList<>();
            Set<String> ruleIds = new HashSet<>();
            for (int dependencyIndex = 0; dependencyIndex < material.dependencies().size();
                    dependencyIndex++) {
                CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency =
                        material.dependencies().get(dependencyIndex);
                String ruleId = stableRuleId(dependency, dependencyIndex);
                if (!ruleIds.add(ruleId)) {
                    fail("DUPLICATE_RULE", path + "/dependencies/" + dependencyIndex);
                }
                dependencies.add(new ScenarioDraftSet.DependencyBehaviorDraft(
                        ruleId,
                        dependency.selector(),
                        dependency.behavior(),
                        dependency.consumption(),
                        dependency.schemaCheck(),
                        "CAPABILITY_STUDIO_DATASET"));
                String behavior = behaviorProfile(dataCase, dependency.behaviorRef()).behavior();
                behaviorSources.add(new CapabilityStudioScenarioDatasetSourceMap.BehaviorSource(
                        ruleId, dependency.behaviorRef(), dependency.dependencyRef(), behavior));
            }
            Set<String> assertionIds = new HashSet<>();
            Set<String> assertionFingerprints = new HashSet<>();
            for (ScenarioDraftSet.AssertionDraft assertion : material.assertions()) {
                if (assertion == null) {
                    fail("MATERIAL_MISSING", path + "/assertions");
                }
                String assertionFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                        mapper, assertion, MAX_FINGERPRINT_BYTES);
                if (assertion.assertionId().isBlank()
                        || !assertionIds.add(assertion.assertionId())
                        || !assertionFingerprints.add(assertionFingerprint)) {
                    fail("DUPLICATE_ASSERTION", path + "/assertions");
                }
            }
            ScenarioDraftSet.CaseType caseType = caseType(dataCase.category(), path);
            scenarios.add(new ScenarioDraftSet.ScenarioDraft(
                    scenarioId,
                    dataCase.name(),
                    dataCase.businessIntent(),
                    caseType,
                    List.of("dataset", dataCase.category()),
                    material.given(),
                    dependencies,
                    new ScenarioDraftSet.Then(material.assertions())));
            sourceCases.add(new CapabilityStudioScenarioDatasetSourceMap.CaseSource(
                    scenarioId,
                    dataCase.category(),
                    caseType,
                    dataCase.caseRef(),
                    dataCase.sourceRef(),
                    dataCase.oracleRef(),
                    dataCase.applicableContractRefs(),
                    behaviorSources,
                    dataCase.behaviorProfiles().stream()
                            .filter(profile -> "BUSINESS_EXPECTATION".equals(profile.purpose()))
                            .map(profile -> new CapabilityStudioScenarioDatasetSourceMap.ExpectationSource(
                                    profile.behaviorRef(), profile.dependencyRef(), profile.behavior()))
                            .toList(),
                    material.assertions().stream()
                            .map(ScenarioDraftSet.AssertionDraft::assertionId)
                            .toList()));
        }

        CapabilityStudioScenarioDatasetSourceMap sourceMap =
                new CapabilityStudioScenarioDatasetSourceMap(
                        dataset.datasetRef(), dataset.targetRef(), target.contractFingerprint(), sourceCases);
        ScenarioDraftSet draftSet = new ScenarioDraftSet(
                ScenarioDraftSet.SCHEMA_VERSION,
                dataset.datasetRef().id(),
                dataset.datasetRef().revision(),
                scope(dataset.datasetRef().scope()),
                target.target(),
                target.contractFingerprint(),
                scenarios,
                metadata(dataset, target, sourceCases));
        String semanticFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper,
                Map.of("draftSet", draftSet, "sourceMap", sourceMap,
                        "target", target.target(), "contractFingerprint", target.contractFingerprint()),
                MAX_FINGERPRINT_BYTES);
        return new CapabilityStudioScenarioDatasetCompilation(
                draftSet, sourceMap, target.target(), target.contractFingerprint(), semanticFingerprint);
    }

    private CapabilityStudioScenarioDatasetMaterial.CaseMaterial resolve(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            CapabilityStudioScenarioDatasetProjector.DataCase dataCase,
            CapabilityStudioScenarioDatasetMaterialResolver resolver,
            String path) {
        try {
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial result = resolver.resolve(dataset, dataCase);
            if (result == null) {
                fail("MATERIAL_MISSING", path + "/material");
            }
            return result;
        } catch (CapabilityStudioScenarioDatasetCompilationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            fail("MATERIAL_RESOLUTION_FAILED", path + "/material");
            return null;
        }
    }

    private void validateDataset(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset) {
        if (dataset == null || !DATASET_SCHEMA.equals(dataset.schemaVersion())) {
            fail("DATASET_INVALID", "/schemaVersion");
        }
        if (dataset.datasetRef() == null || !"DATASET".equals(dataset.datasetRef().kind())) {
            fail("DATASET_REF_INVALID", "/datasetRef");
        }
        if (!"REVIEW_READY".equals(dataset.lifecycle()) || dataset.cases().isEmpty()) {
            fail("DATASET_NOT_READY", "/lifecycle");
        }
        validateRef(dataset.datasetRef(), dataset.datasetRef().scope(), "/datasetRef");
        validateRef(dataset.targetRef(), dataset.datasetRef().scope(), "/targetRef");
        Set<String> contracts = new HashSet<>();
        for (int index = 0; index < dataset.contractRefs().size(); index++) {
            CapabilityStudioScenarioDatasetProjector.ExactRef ref = dataset.contractRefs().get(index);
            validateRef(ref, dataset.datasetRef().scope(), "/contractRefs/" + index);
            if (!"CONTRACT".equals(ref.kind()) || !contracts.add(identity(ref))) {
                fail("DUPLICATE_CONTRACT", "/contractRefs/" + index);
            }
        }
        Set<String> cases = new HashSet<>();
        for (int index = 0; index < dataset.cases().size(); index++) {
            CapabilityStudioScenarioDatasetProjector.DataCase dataCase = dataset.cases().get(index);
            String path = "/cases/" + index;
            if (dataCase == null) {
                fail("CASE_INVALID", path);
            }
            validateRef(dataCase.caseRef(), dataset.datasetRef().scope(), path + "/caseRef");
            validateRef(dataCase.sourceRef(), dataset.datasetRef().scope(), path + "/sourceRef");
            validateRef(dataCase.oracleRef(), dataset.datasetRef().scope(), path + "/oracleRef");
            if (!"DATA_CASE".equals(dataCase.caseRef().kind()) || !cases.add(identity(dataCase.caseRef()))) {
                fail("DUPLICATE_CASE", path + "/caseRef");
            }
            Set<String> applicable = new HashSet<>();
            for (int contractIndex = 0; contractIndex < dataCase.applicableContractRefs().size();
                    contractIndex++) {
                CapabilityStudioScenarioDatasetProjector.ExactRef ref =
                        dataCase.applicableContractRefs().get(contractIndex);
                validateRef(ref, dataset.datasetRef().scope(), path + "/applicableContractRefs/" + contractIndex);
                if (!"CONTRACT".equals(ref.kind()) || !contracts.contains(identity(ref))
                        || !applicable.add(identity(ref))) {
                    fail("REF_CLOSURE", path + "/applicableContractRefs/" + contractIndex);
                }
            }
            Set<String> behaviorRefs = new HashSet<>();
            for (int profileIndex = 0; profileIndex < dataCase.behaviorProfiles().size(); profileIndex++) {
                CapabilityStudioScenarioDatasetProjector.BehaviorProfile profile =
                        dataCase.behaviorProfiles().get(profileIndex);
                String profilePath = path + "/behaviorProfiles/" + profileIndex;
                validateRef(profile.behaviorRef(), dataset.datasetRef().scope(), profilePath + "/behaviorRef");
                validateRef(profile.dependencyRef(), dataset.datasetRef().scope(), profilePath + "/dependencyRef");
                if (!"BEHAVIOR_PROFILE".equals(profile.behaviorRef().kind())
                        || !behaviorRefs.add(identity(profile.behaviorRef()))) {
                    fail("DUPLICATE_BEHAVIOR", profilePath + "/behaviorRef");
                }
                if (!Set.of("RUNTIME_CONTROL", "BUSINESS_EXPECTATION")
                        .contains(profile.purpose())) {
                    fail("BEHAVIOR_PURPOSE_INVALID", profilePath + "/purpose");
                }
            }
        }
    }

    private void validateTarget(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            ExactCompilationTarget target) {
        if (target == null || target.target() == null || target.contractFingerprint().isBlank()
                || !FINGERPRINT.matcher(target.contractFingerprint()).matches()) {
            fail("TARGET_INVALID", "/target");
        }
        ContractDraft.Target actual = target.target();
        CapabilityStudioScenarioDatasetProjector.ExactRef projected = dataset.targetRef();
        String expectedKind = switch (projected.kind()) {
            case "GRAPH" -> "GRAPH";
            case "OPERATOR", "TOOL" -> "OPERATOR";
            default -> "";
        };
        if (!expectedKind.equals(actual.kind().name())
                || !actual.id().equals(projected.id())
                || actual.revision() != projected.revision()
                || !actual.fingerprint().equals(projected.fingerprint())) {
            fail("TARGET_MISMATCH", "/target");
        }
        long matches = dataset.contractRefs().stream()
                .filter(ref -> ref.fingerprint().equals(target.contractFingerprint()))
                .count();
        if (matches != 1) {
            fail("CONTRACT_MISMATCH", "/contractFingerprint");
        }
    }

    private void validateMaterial(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            CapabilityStudioScenarioDatasetProjector.DataCase dataCase,
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial material,
            String path) {
        if (!sameScope(material.caseRef(), dataset.datasetRef().scope())
                || !sameScope(material.sourceRef(), dataset.datasetRef().scope())
                || !sameScope(material.oracleRef(), dataset.datasetRef().scope())) {
            fail("SCOPE_MISMATCH", path + "/material/refs");
        }
        if (!same(material.caseRef(), dataCase.caseRef())
                || !same(material.sourceRef(), dataCase.sourceRef())
                || !same(material.oracleRef(), dataCase.oracleRef())) {
            fail("MATERIAL_REF_MISMATCH", path + "/material/refs");
        }
        if (material.assertions().isEmpty()) {
            fail("ASSERTION_MISSING", path + "/assertions");
        }
        Map<String, CapabilityStudioScenarioDatasetProjector.BehaviorProfile> profiles = new LinkedHashMap<>();
        for (CapabilityStudioScenarioDatasetProjector.BehaviorProfile profile : dataCase.behaviorProfiles()) {
            if ("RUNTIME_CONTROL".equals(profile.purpose())) {
                profiles.put(identity(profile.behaviorRef()), profile);
            }
        }
        if (profiles.size() != material.dependencies().size()
                || material.dependencies().isEmpty()) {
            fail("BEHAVIOR_CLOSURE", path + "/dependencies");
        }
        Set<String> behaviorRefs = new HashSet<>();
        Set<String> selectorFingerprints = new HashSet<>();
        Map<String, Integer> dependencyCounts = new LinkedHashMap<>();
        for (int index = 0; index < material.dependencies().size(); index++) {
            CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency =
                    material.dependencies().get(index);
            String dependencyPath = path + "/dependencies/" + index;
            if (dependency == null) {
                fail("MATERIAL_MISSING", dependencyPath);
            }
            if (!behaviorRefs.add(identity(dependency.behaviorRef()))) {
                fail("DUPLICATE_BEHAVIOR", dependencyPath + "/behaviorRef");
            }
            validateNoReal(dependency, dependencyPath);
            validateSelector(dependency.selector(), dependencyPath + "/selector");
            String selectorFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                    mapper, dependency.selector(), MAX_FINGERPRINT_BYTES);
            if (!selectorFingerprints.add(selectorFingerprint)) {
                fail("AMBIGUOUS_SELECTOR", dependencyPath + "/selector");
            }
            dependencyCounts.merge(identity(dependency.dependencyRef()), 1, Integer::sum);
            CapabilityStudioScenarioDatasetProjector.BehaviorProfile profile =
                    profiles.get(identity(dependency.behaviorRef()));
            if (profile == null || !same(profile.dependencyRef(), dependency.dependencyRef())
                    || !behaviorKind(profile.behavior()).equals(dependency.behavior().kind().name())) {
                fail("BEHAVIOR_MISMATCH", dependencyPath);
            }
        }
        for (int index = 0; index < material.dependencies().size(); index++) {
            CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency =
                    material.dependencies().get(index);
            if (dependencyCounts.getOrDefault(identity(dependency.dependencyRef()), 0) > 1
                    && dependency.selector().attempts().isEmpty()
                    && dependency.selector().occurrences().isEmpty()) {
                fail("SEQUENCE_SELECTOR_REQUIRED", path + "/dependencies/" + index + "/selector");
            }
        }
    }

    private static void validateSelector(ScenarioDraftSet.DependencySelector selector, String path) {
        if (selector.graphPath().isBlank() && selector.nodeId().isBlank()
                && selector.operatorRef().isBlank() && selector.resourceRef().isBlank()
                && selector.functionRef().isBlank()) {
            fail("SELECTOR_UNRESOLVED", path);
        }
    }

    private static void validateNoReal(
            CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency,
            String path) {
        if (dependency.behavior().boundary() == ScenarioDraftSet.BehaviorBoundary.TRANSPORT) {
            boolean descriptorBackedResponse = "httpResource".equals(
                    dependency.selector().operatorRef())
                    && !dependency.selector().resourceRef().isBlank()
                    && dependency.behavior().kind() == ScenarioDraftSet.BehaviorKind.RETURN
                    && dependency.behavior().output() == null
                    && dependency.behavior().statusCode() != null
                    && !dependency.behavior().rawBody().isBlank();
            if (!descriptorBackedResponse) {
                fail("TRANSPORT_BOUNDARY_UNSUPPORTED", path + "/behavior/boundary");
            }
        }
        if (!dependency.selector().functionRef().isBlank()) {
            fail("FUNCTION_SELECTOR_UNSUPPORTED", path + "/selector/functionRef");
        }
        switch (dependency.behavior().kind()) {
            case REPLAY -> fail("REPLAY_UNSUPPORTED", path + "/behavior/kind");
            case OBSERVE -> fail("OBSERVE_UNSUPPORTED", path + "/behavior/kind");
            default -> {
                // Stage 0 only admits behaviors with an exact existing governed lowering.
            }
        }
        if (dependency.behavior().kind() == ScenarioDraftSet.BehaviorKind.REAL) {
            fail("REAL_FORBIDDEN", path + "/behavior");
        }
        String exhausted = dependency.consumption().onExhausted().toUpperCase();
        String unmatched = dependency.consumption().onUnmatched().toUpperCase();
        if (!Set.of("FAIL").contains(exhausted)
                || !Set.of("FAIL", "WARN").contains(unmatched)) {
            fail("REAL_FALLBACK_FORBIDDEN", path + "/consumption");
        }
        if (!Set.of("STRICT", "WAIVED").contains(dependency.schemaCheck().mode().toUpperCase())) {
            fail("POLICY_INVALID", path + "/schemaCheck");
        }
    }

    private static ScenarioDraftSet.CaseType caseType(String category, String path) {
        return switch (category) {
            case "GOLDEN" -> ScenarioDraftSet.CaseType.GOLDEN;
            case "NEGATIVE", "FAULT" -> ScenarioDraftSet.CaseType.NEGATIVE;
            case "BOUNDARY" -> ScenarioDraftSet.CaseType.BOUNDARY;
            case "REGRESSION", "SECURITY" -> ScenarioDraftSet.CaseType.REGRESSION;
            default -> {
                fail("CATEGORY_UNSUPPORTED", path + "/category");
                yield ScenarioDraftSet.CaseType.GOLDEN;
            }
        };
    }

    private static CapabilityStudioScenarioDatasetProjector.BehaviorProfile behaviorProfile(
            CapabilityStudioScenarioDatasetProjector.DataCase dataCase,
            CapabilityStudioScenarioDatasetProjector.ExactRef behaviorRef) {
        return dataCase.behaviorProfiles().stream()
                .filter(profile -> "RUNTIME_CONTROL".equals(profile.purpose()))
                .filter(profile -> same(profile.behaviorRef(), behaviorRef))
                .findFirst()
                .orElseThrow(() -> new CapabilityStudioScenarioDatasetCompilationException(
                        ERROR_PREFIX + "BEHAVIOR_CLOSURE", "/behaviorProfiles"));
    }

    private static String behaviorKind(String behavior) {
        return switch (behavior) {
            case "RETURN", "RETURN_EMPTY", "RETURN_VERSIONED", "IDEMPOTENT" -> "RETURN";
            case "MUST_NOT_CALL", "MUST_NOT_CALL_WRITE" -> "MUST_NOT_CALL";
            case "ERROR", "TIMEOUT", "DELAY", "REPLAY", "OBSERVE" -> behavior;
            default -> "UNSUPPORTED";
        };
    }

    private static ScenarioDraftSet.Metadata metadata(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset,
            ExactCompilationTarget target,
            List<CapabilityStudioScenarioDatasetSourceMap.CaseSource> cases) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("datasetRef", identity(dataset.datasetRef()));
        provenance.put("targetRef", identity(dataset.targetRef()));
        provenance.put("contractFingerprint", target.contractFingerprint());
        provenance.put("sourceMap", cases.stream()
                .map(CapabilityStudioScenarioDatasetSourceMap.CaseSource::scenarioId)
                .map(id -> "case:" + id)
                .toList());
        return new ScenarioDraftSet.Metadata(
                dataset.owner() == null ? "" : dataset.owner().id(),
                dataset.classification(),
                null,
                null,
                provenance);
    }

    private static ScenarioDraftSet.EnterpriseScope scope(
            CapabilityStudioScenarioDatasetProjector.Scope scope) {
        return new ScenarioDraftSet.EnterpriseScope(
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region());
    }

    private static void validateRef(
            CapabilityStudioScenarioDatasetProjector.ExactRef ref,
            CapabilityStudioScenarioDatasetProjector.Scope expectedScope,
            String path) {
        if (ref == null || ref.kind().isBlank() || ref.id().isBlank() || ref.revision() < 1
                || !FINGERPRINT.matcher(ref.fingerprint()).matches()
                || ref.authority() == null || ref.authority().isBlank()
                || !completeScope(ref.scope()) || !completeScope(expectedScope)) {
            fail("REF_INVALID", path);
        }
        if (!sameScope(ref, expectedScope)) {
            fail("SCOPE_MISMATCH", path + "/scope");
        }
    }

    private static boolean sameScope(
            CapabilityStudioScenarioDatasetProjector.ExactRef ref,
            CapabilityStudioScenarioDatasetProjector.Scope expected) {
        return ref != null && Objects.equals(ref.scope(), expected);
    }

    private static boolean completeScope(CapabilityStudioScenarioDatasetProjector.Scope scope) {
        return scope != null
                && scope.tenantId() != null && !scope.tenantId().isBlank()
                && scope.organizationId() != null && !scope.organizationId().isBlank()
                && scope.projectId() != null && !scope.projectId().isBlank()
                && scope.environmentId() != null && !scope.environmentId().isBlank()
                && scope.region() != null && !scope.region().isBlank();
    }

    private static boolean same(
            CapabilityStudioScenarioDatasetProjector.ExactRef left,
            CapabilityStudioScenarioDatasetProjector.ExactRef right) {
        return left != null && right != null
                && left.kind().equals(right.kind())
                && left.id().equals(right.id())
                && left.revision() == right.revision()
                && left.fingerprint().equals(right.fingerprint())
                && Objects.equals(left.authority(), right.authority())
                && Objects.equals(left.scope(), right.scope());
    }

    private static String identity(CapabilityStudioScenarioDatasetProjector.ExactRef ref) {
        if (ref == null) {
            return "";
        }
        return ref.kind() + "|" + ref.id() + "|" + ref.revision() + "|"
                + ref.fingerprint() + "|" + ref.authority() + "|" + ref.scope();
    }

    private static String stableRuleId(
            CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency,
            int sequence) {
        return "dataset-rule-" + dependency.behaviorRef().id() + "-" + (sequence + 1);
    }

    private static void fail(String suffix, String path) {
        throw new CapabilityStudioScenarioDatasetCompilationException(ERROR_PREFIX + suffix, path);
    }

    /** Exact target used to bind the compiled Dataset to an existing Contract/runtime target. */
    public record ExactCompilationTarget(ContractDraft.Target target, String contractFingerprint) {
        public ExactCompilationTarget {
            Objects.requireNonNull(target, "target");
            contractFingerprint = contractFingerprint == null ? "" : contractFingerprint.trim();
        }
    }
}
