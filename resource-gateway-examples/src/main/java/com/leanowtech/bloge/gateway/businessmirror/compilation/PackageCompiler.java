package com.leanowtech.bloge.gateway.businessmirror.compilation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredDomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLinkClosure;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetRef;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageSnapshot;
import com.leanowtech.bloge.gateway.businessmirror.domain.PackageReadinessReport;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic, fail-closed compiler from one persisted Package draft to immutable mirror facts.
 *
 * <p>The compiler owns validation and derivation only. Registry access and mutable-head fencing are
 * delegated to {@link PackageCompilationAuthority}, keeping repository and product adapters outside
 * the compilation kernel.</p>
 */
public final class PackageCompiler {
    /** Compiler generation embedded in every immutable snapshot. */
    public static final String COMPILER_VERSION = "business-mirror-package-compiler-v1";
    private static final int MAXIMUM_DRAFT_BYTES = 8 * 1024 * 1024;
    private static final Set<String> MUTABLE_MATERIAL_KINDS =
            Set.of("GRAPH_DRAFT", "CAPABILITY_PROPOSAL");

    private final ObjectMapper mapper;
    private final PackageCompilationAuthority authority;

    public PackageCompiler(ObjectMapper mapper, PackageCompilationAuthority authority) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    /**
     * Compiles one exact stored draft under an authority-frozen dependency window.
     *
     * @param source repository-verified Package source
     * @param snapshotRevision positive immutable result revision assigned by persistence
     * @param compiledAt stable command timestamp reused by idempotent retries
     * @return readiness and relation closure, plus a snapshot unless readiness is blocked
     */
    public PackageCompilationResult compile(
            StoredDomainCapabilityPackageDraft source,
            long snapshotRevision,
            Instant compiledAt) {
        Objects.requireNonNull(source, "source");
        if (snapshotRevision < 1) {
            throw new IllegalArgumentException("snapshotRevision must be positive");
        }
        Instant exactCompiledAt = Objects.requireNonNull(compiledAt, "compiledAt");
        verifySource(source);
        FrozenPackageDependencies frozen = Objects.requireNonNull(
                authority.freeze(source), "authority freeze result");
        if (!source.scope().equals(frozen.scope())) {
            throw new IllegalArgumentException("frozen dependency scope must match Package scope");
        }

        DomainCapabilityPackageDraft draft = source.draft();
        List<PackageReadinessReport.Finding> findings = new ArrayList<>();
        findings.addAll(draftFindings(draft));
        findings.addAll(dependencyFindings(draft, frozen));

        BusinessAssetLinkClosure linkClosure = compileLinkClosure(
                draft, frozen, snapshotRevision, exactCompiledAt, findings);
        if (frozen.capabilityClosureRef() == null) {
            findings.add(finding("CAPABILITY_CLOSURE_MISSING",
                    PackageReadinessReport.Severity.ERROR,
                    PackageReadinessReport.Category.DEPENDENCY,
                    "/capabilityRefs", null));
        }
        if (frozen.mirrorPlanRefs().isEmpty()) {
            findings.add(finding("MIRROR_PLAN_MISSING",
                    PackageReadinessReport.Severity.ERROR,
                    PackageReadinessReport.Category.DEPENDENCY,
                    "/graphRefs", null));
        }
        if (frozen.policyGenerationRef() == null) {
            findings.add(finding("COMPILATION_POLICY_MISSING",
                    PackageReadinessReport.Severity.ERROR,
                    PackageReadinessReport.Category.GOVERNANCE,
                    "/policyGenerationRef", null));
        }

        List<MirrorArtifactRef> manifest = dependencyManifest(frozen, findings);
        if (manifest.isEmpty()) {
            findings.add(finding("DEPENDENCY_MANIFEST_EMPTY",
                    PackageReadinessReport.Severity.ERROR,
                    PackageReadinessReport.Category.DEPENDENCY,
                    "/dependencyManifest", null));
        }
        PackageReadinessReport readiness = new PackageReadinessReport(
                PackageReadinessReport.SCHEMA_VERSION,
                draft.packageId() + "-readiness", snapshotRevision, "", draft.scope(),
                draft.packageId(), draft.revision(), source.draftFingerprint(), null,
                findings, exactCompiledAt).seal(mapper);

        DomainCapabilityPackageSnapshot snapshot = readiness.status()
                == PackageReadinessReport.Status.BLOCKED ? null
                : new DomainCapabilityPackageSnapshot(
                DomainCapabilityPackageSnapshot.SCHEMA_VERSION,
                draft.packageId(), snapshotRevision, "", draft.scope(), draft.revision(),
                source.draftFingerprint(), draft.businessDefinition(), draft.packageContractRef(),
                frozen.capabilityClosureRef(), frozen.mirrorPlanRefs(), linkClosure.artifactRef(),
                readiness.artifactRef(), manifest, frozen.evidenceRefs(), COMPILER_VERSION,
                frozen.policyGenerationRef(), draft.provenance(), exactCompiledAt).seal(mapper);

        authority.assertUnchanged(frozen);
        return new PackageCompilationResult(readiness, linkClosure, snapshot, frozen);
    }

    private void verifySource(StoredDomainCapabilityPackageDraft source) {
        String expected = VisualBundleFingerprint.fromCanonicalValue(
                mapper, source.draft(), MAXIMUM_DRAFT_BYTES);
        if (!expected.equals(source.draftFingerprint())) {
            throw new IllegalArgumentException("Stored Package draft fingerprint mismatch");
        }
    }

    private static List<PackageReadinessReport.Finding> draftFindings(
            DomainCapabilityPackageDraft draft) {
        return draft.readinessBlockers().stream()
                .map(code -> finding(code, PackageReadinessReport.Severity.ERROR,
                        category(code), blockerPath(code), null))
                .toList();
    }

    private static List<PackageReadinessReport.Finding> dependencyFindings(
            DomainCapabilityPackageDraft draft,
            FrozenPackageDependencies frozen) {
        List<PackageReadinessReport.Finding> findings = new ArrayList<>();
        Map<MirrorArtifactRef, PackageDependencyObservation> actual = new HashMap<>();
        frozen.observations().forEach(value -> actual.put(value.sourceRef(), value));
        Set<MirrorArtifactRef> expected = expectedRefs(draft);
        for (MirrorArtifactRef ref : expected) {
            PackageDependencyObservation observation = actual.get(ref);
            if (observation == null) {
                findings.add(finding("DEPENDENCY_OBSERVATION_MISSING",
                        PackageReadinessReport.Severity.ERROR,
                        PackageReadinessReport.Category.DEPENDENCY, path(ref), ref));
                continue;
            }
            if (observation.status() != PackageDependencyObservation.Status.RESOLVED) {
                findings.add(finding("DEPENDENCY_" + observation.status().name(),
                        PackageReadinessReport.Severity.ERROR,
                        PackageReadinessReport.Category.DEPENDENCY, path(ref), ref));
                continue;
            }
            if (!draft.scope().equals(observation.scope())) {
                findings.add(finding("DEPENDENCY_SCOPE_VIOLATION",
                        PackageReadinessReport.Severity.ERROR,
                        PackageReadinessReport.Category.DEPENDENCY, path(ref), ref));
            }
            requireAssurance(findings, observation,
                    PackageDependencyObservation.Assurance.SCHEMA_VALID,
                    "DEPENDENCY_SCHEMA_INVALID", PackageReadinessReport.Category.DEPENDENCY);
            if ("SCENARIO_INVENTORY".equals(ref.kind())) {
                requireAssurance(findings, observation,
                        PackageDependencyObservation.Assurance.NON_EMPTY_DENOMINATOR,
                        "SCENARIO_DENOMINATOR_EMPTY", PackageReadinessReport.Category.SCENARIO);
            }
            if ("OUTCOME_DEFINITION".equals(ref.kind())) {
                requireAssurance(findings, observation,
                        PackageDependencyObservation.Assurance.OUTCOME_PARSABLE,
                        "OUTCOME_DEFINITION_UNPARSABLE", PackageReadinessReport.Category.OUTCOME);
            }
            if ("CAPABILITY_PROPOSAL".equals(ref.kind())) {
                requireAssurance(findings, observation,
                        PackageDependencyObservation.Assurance.SIMULATION_BOUNDED,
                        "PROPOSAL_RESOLVER_UNBOUNDED", PackageReadinessReport.Category.ISOLATION);
                requireAssurance(findings, observation,
                        PackageDependencyObservation.Assurance.REAL_EXTERNAL_CALLS_FORBIDDEN,
                        "PROPOSAL_REAL_CALL_GUARD_MISSING", PackageReadinessReport.Category.ISOLATION);
            }
            if ((draft.businessDefinition().riskClass() == DomainCapabilityPackageDraft.RiskClass.HIGH
                    || draft.businessDefinition().riskClass()
                    == DomainCapabilityPackageDraft.RiskClass.CRITICAL)
                    && ("EFFECT_CONTRACT".equals(ref.kind()) || "WRITE_EFFECT".equals(ref.kind()))) {
                requireAssurance(findings, observation,
                        PackageDependencyObservation.Assurance.STATE_EFFECT_PROTECTED,
                        "HIGH_RISK_EFFECT_UNPROTECTED", PackageReadinessReport.Category.ISOLATION);
            }
        }
        for (PackageDependencyObservation observation : frozen.observations()) {
            if (!expected.contains(observation.sourceRef())) {
                findings.add(finding("UNDECLARED_DEPENDENCY_OBSERVED",
                        PackageReadinessReport.Severity.ERROR,
                        PackageReadinessReport.Category.DEPENDENCY,
                        path(observation.sourceRef()), observation.sourceRef()));
            }
        }
        return findings;
    }

    private static void requireAssurance(
            List<PackageReadinessReport.Finding> findings,
            PackageDependencyObservation observation,
            PackageDependencyObservation.Assurance assurance,
            String code,
            PackageReadinessReport.Category category) {
        if (!observation.assures(assurance)) {
            findings.add(finding(code, PackageReadinessReport.Severity.ERROR,
                    category, path(observation.sourceRef()), observation.sourceRef()));
        }
    }

    private BusinessAssetLinkClosure compileLinkClosure(
            DomainCapabilityPackageDraft draft,
            FrozenPackageDependencies frozen,
            long revision,
            Instant compiledAt,
            List<PackageReadinessReport.Finding> findings) {
        List<BusinessAssetRef> assets = new ArrayList<>();
        assets.addAll(draft.solutionRefs());
        assets.addAll(draft.carrierRefs());
        assets.addAll(draft.channelRefs());
        if (!assets.isEmpty() && frozen.businessAssetLinks().isEmpty()) {
            findings.add(finding("BUSINESS_ASSET_LINKS_MISSING",
                    PackageReadinessReport.Severity.ERROR,
                    PackageReadinessReport.Category.INDEX, "/businessAssetLinks", null));
        }
        try {
            return new BusinessAssetLinkClosure(BusinessAssetLinkClosure.SCHEMA_VERSION,
                    draft.packageId() + "-asset-links", revision, "", draft.scope(),
                    draft.packageId(), assets, frozen.businessAssetLinks(), compiledAt).seal(mapper);
        } catch (IllegalArgumentException invalid) {
            findings.add(finding("BUSINESS_ASSET_LINK_CLOSURE_INVALID",
                    PackageReadinessReport.Severity.ERROR,
                    PackageReadinessReport.Category.INDEX, "/businessAssetLinks", null));
            return new BusinessAssetLinkClosure(BusinessAssetLinkClosure.SCHEMA_VERSION,
                    draft.packageId() + "-asset-links", revision, "", draft.scope(),
                    draft.packageId(), assets, List.of(), compiledAt).seal(mapper);
        }
    }

    private static List<MirrorArtifactRef> dependencyManifest(
            FrozenPackageDependencies frozen,
            List<PackageReadinessReport.Finding> findings) {
        LinkedHashSet<MirrorArtifactRef> refs = new LinkedHashSet<>();
        for (PackageDependencyObservation observation : frozen.observations()) {
            MirrorArtifactRef ref = observation.materializedRef();
            if (observation.status() != PackageDependencyObservation.Status.RESOLVED || ref == null) {
                continue;
            }
            if (MUTABLE_MATERIAL_KINDS.contains(ref.kind())) {
                findings.add(finding("MUTABLE_DEPENDENCY_MATERIAL",
                        PackageReadinessReport.Severity.ERROR,
                        PackageReadinessReport.Category.DEPENDENCY,
                        path(observation.sourceRef()), observation.sourceRef()));
            } else {
                refs.add(ref);
            }
        }
        if (frozen.capabilityClosureRef() != null) {
            refs.add(frozen.capabilityClosureRef());
        }
        refs.addAll(frozen.mirrorPlanRefs());
        if (frozen.policyGenerationRef() != null) {
            refs.add(frozen.policyGenerationRef());
        }
        return refs.stream().sorted(Comparator.comparing(MirrorArtifactRef::kind)
                .thenComparing(MirrorArtifactRef::id)
                .thenComparingLong(MirrorArtifactRef::revision)
                .thenComparing(MirrorArtifactRef::fingerprint)).toList();
    }

    private static Set<MirrorArtifactRef> expectedRefs(DomainCapabilityPackageDraft draft) {
        LinkedHashSet<MirrorArtifactRef> refs = new LinkedHashSet<>();
        add(refs, draft.businessDefinition().problemTaxonomyRef());
        add(refs, draft.packageContractRef());
        refs.addAll(draft.capabilityRefs());
        refs.addAll(draft.graphRefs());
        refs.addAll(draft.proposalRefs());
        refs.addAll(draft.stateModelRefs());
        refs.addAll(draft.effectModelRefs());
        add(refs, draft.scenarioInventoryRef());
        refs.addAll(draft.scenarioPackRefs());
        refs.addAll(draft.solutionRefs().stream().map(PackageCompiler::artifactRef).toList());
        refs.addAll(draft.carrierRefs().stream().map(PackageCompiler::artifactRef).toList());
        refs.addAll(draft.channelRefs().stream().map(PackageCompiler::artifactRef).toList());
        add(refs, draft.fidelityInventoryRef());
        refs.addAll(draft.outcomeDefinitionRefs());
        return Set.copyOf(refs);
    }

    private static MirrorArtifactRef artifactRef(BusinessAssetRef ref) {
        return new MirrorArtifactRef(ref.kind().name(), ref.id(), ref.revision(), ref.fingerprint());
    }

    private static void add(Set<MirrorArtifactRef> refs, MirrorArtifactRef ref) {
        if (ref != null) {
            refs.add(ref);
        }
    }

    private static PackageReadinessReport.Finding finding(
            String code,
            PackageReadinessReport.Severity severity,
            PackageReadinessReport.Category category,
            String fieldPath,
            MirrorArtifactRef ref) {
        String material = code + "|" + fieldPath + "|" + (ref == null ? "" : ref.toString());
        return new PackageReadinessReport.Finding(
                "finding-" + sha256(material).substring(0, 24), code, severity, category,
                fieldPath, ref, "business-mirror.readiness." + code.toLowerCase(java.util.Locale.ROOT));
    }

    private static String sha256(String material) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static PackageReadinessReport.Category category(String code) {
        if (code.startsWith("BUSINESS_") || code.startsWith("PROBLEM_")
                || code.startsWith("EXPECTED_") || code.startsWith("ACCOUNTABLE_")) {
            return PackageReadinessReport.Category.BUSINESS_DEFINITION;
        }
        if (code.contains("CONTRACT")) {
            return PackageReadinessReport.Category.CONTRACT;
        }
        if (code.contains("SCENARIO")) {
            return PackageReadinessReport.Category.SCENARIO;
        }
        if (code.contains("OUTCOME") || code.contains("FIDELITY")) {
            return PackageReadinessReport.Category.OUTCOME;
        }
        if (code.contains("SOLUTION") || code.contains("CARRIER") || code.contains("CHANNEL")) {
            return PackageReadinessReport.Category.INDEX;
        }
        if (code.contains("STATE") || code.contains("EFFECT")) {
            return PackageReadinessReport.Category.ISOLATION;
        }
        return PackageReadinessReport.Category.DEPENDENCY;
    }

    private static String blockerPath(String code) {
        return switch (code) {
            case "BUSINESS_DOMAIN_MISSING" -> "/businessDefinition/domainId";
            case "PROBLEM_TAXONOMY_MISSING" -> "/businessDefinition/problemTaxonomyRef";
            case "PROBLEM_CODE_MISSING" -> "/businessDefinition/problemCode";
            case "BUSINESS_GOAL_MISSING" -> "/businessDefinition/businessGoal";
            case "EXPECTED_OUTCOME_MISSING" -> "/businessDefinition/expectedOutcome";
            case "ACCOUNTABLE_OWNER_MISSING" -> "/businessDefinition/accountableOwner";
            case "PACKAGE_CONTRACT_MISSING" -> "/packageContractRef";
            case "EXECUTABLE_PROJECTION_MISSING" -> "/graphRefs";
            case "SCENARIO_INVENTORY_MISSING" -> "/scenarioInventoryRef";
            case "SCENARIO_PACK_MISSING" -> "/scenarioPackRefs";
            case "SOLUTION_BINDING_MISSING" -> "/solutionRefs";
            case "SERVICE_CARRIER_BINDING_MISSING" -> "/carrierRefs";
            case "CHANNEL_BINDING_MISSING" -> "/channelRefs";
            case "FIDELITY_INVENTORY_MISSING" -> "/fidelityInventoryRef";
            case "OUTCOME_DEFINITION_MISSING" -> "/outcomeDefinitionRefs";
            case "HIGH_RISK_STATE_MODEL_MISSING" -> "/stateModelRefs";
            case "HIGH_RISK_EFFECT_MODEL_MISSING" -> "/effectModelRefs";
            default -> "/";
        };
    }

    private static String path(MirrorArtifactRef ref) {
        return switch (ref.kind()) {
            case "PROBLEM_TAXONOMY" -> "/businessDefinition/problemTaxonomyRef";
            case "CONTRACT" -> "/packageContractRef";
            case "CAPABILITY" -> "/capabilityRefs";
            case "GRAPH_DRAFT" -> "/graphRefs";
            case "CAPABILITY_PROPOSAL" -> "/proposalRefs";
            case "STATE_MODEL" -> "/stateModelRefs";
            case "EFFECT_CONTRACT", "WRITE_EFFECT" -> "/effectModelRefs";
            case "SCENARIO_INVENTORY" -> "/scenarioInventoryRef";
            case "SCENARIO_PACK" -> "/scenarioPackRefs";
            case "SOLUTION" -> "/solutionRefs";
            case "SOP", "AGENT", "WORKFLOW" -> "/carrierRefs";
            case "CHANNEL_APPLICATION" -> "/channelRefs";
            case "DOMAIN_FIDELITY_INVENTORY" -> "/fidelityInventoryRef";
            case "OUTCOME_DEFINITION" -> "/outcomeDefinitionRefs";
            default -> "/dependencies";
        };
    }
}
