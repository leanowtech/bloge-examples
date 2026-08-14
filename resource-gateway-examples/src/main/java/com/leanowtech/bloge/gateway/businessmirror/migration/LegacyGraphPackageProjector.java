package com.leanowtech.bloge.gateway.businessmirror.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.authoring.DomainCapabilityPackageAuthoringService;
import com.leanowtech.bloge.gateway.businessmirror.authoring.DomainCapabilityPackageSaveCoordinator;
import com.leanowtech.bloge.gateway.businessmirror.compilation.BuiltInGraphAssetAuthority;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityProjectionException;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.leanowtech.bloge.gateway.businessmirror.migration.LegacyGraphPackageProjection.Gap;
import static com.leanowtech.bloge.gateway.businessmirror.migration.LegacyGraphPackageProjection.GapCategory;
import static com.leanowtech.bloge.gateway.businessmirror.migration.LegacyGraphPackageProjection.GapOrigin;
import static com.leanowtech.bloge.gateway.businessmirror.migration.LegacyGraphPackageProjection.GapSeverity;

/** Projects and incrementally imports existing built-in Graphs as fail-closed Package drafts. */
public final class LegacyGraphPackageProjector {
    private static final String PURPOSE = "BUSINESS_MIRROR_LEGACY_MIGRATION";

    private final BuiltInGraphAssetAuthority authority;
    private final DomainCapabilityPackageAuthoringService authoring;
    private final ObjectMapper mapper;

    public LegacyGraphPackageProjector(
            BuiltInGraphAssetAuthority authority,
            DomainCapabilityPackageAuthoringService authoring,
            ObjectMapper mapper) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.authoring = Objects.requireNonNull(authoring, "authoring");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Returns true only when at least one source Graph is advertised by the installed authority. */
    public boolean ready() {
        return !authority.graphNames().isEmpty();
    }

    /** Projects every built-in Graph in stable order without writing Package state. */
    public LegacyGraphPackageProjectionCatalog catalog(IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = scope(identity);
        List<LegacyGraphPackageProjection> items = authority.graphNames().stream()
                .map(graphName -> preview(graphName, identity))
                .toList();
        return new LegacyGraphPackageProjectionCatalog("", scope, items);
    }

    /** Projects one existing Graph into an unsigned-owner Package draft and formal gap inventory. */
    public LegacyGraphPackageProjection preview(
            String graphName, IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = scope(identity);
        String exactGraphName = normalized(graphName);
        if (!authority.graphNames().contains(exactGraphName)) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.BUSINESS_MIRROR.LEGACY_GRAPH_NOT_FOUND",
                    "Legacy Graph was not found in the installed migration authority.",
                    identity.correlationId(), Map.of("graphName", exactGraphName)));
        }
        try {
            BuiltInGraphAssetAuthority.Snapshot source = authority.resolve(scope, exactGraphName);
            DomainCapabilityPackageDraft draft = draft(identity, source);
            LegacyGraphPackageProjection projection = new LegacyGraphPackageProjection(
                    "", "", LegacyGraphPackageProjection.MigrationMode.LEGACY_IMPORTED,
                    exactGraphName, scope, source.graphRef(), source.contractRef(),
                    source.rootCapabilityRef(), source.capabilityClosureRef(), source.testSuiteRefs(),
                    draft, gaps(draft, source), null, "").seal(mapper);
            projection.verify(mapper);
            return projection;
        } catch (CapabilityProjectionException.Failure failure) {
            throw authorityUnavailable(identity, failure.problem().code());
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw authorityUnavailable(identity, "RG.BUSINESS_MIRROR.LEGACY_PROJECTION_REJECTED");
        }
    }

    /** Durably creates revision one through the existing Package authoring/idempotency boundary. */
    public ImportOutcome importPackage(
            String graphName, String idempotencyKey, IntegrationRequestContext identity) {
        LegacyGraphPackageProjection projection = preview(graphName, identity);
        DomainCapabilityPackageSaveCoordinator.Outcome outcome =
                authoring.create(projection.packageDraft(), idempotencyKey, identity);
        return new ImportOutcome(projection, outcome);
    }

    /** Binds one exact projection to its durable Package create outcome. */
    public record ImportOutcome(
            LegacyGraphPackageProjection projection,
            DomainCapabilityPackageSaveCoordinator.Outcome saveOutcome
    ) {
        public ImportOutcome {
            projection = Objects.requireNonNull(projection, "projection");
            saveOutcome = Objects.requireNonNull(saveOutcome, "saveOutcome");
            if (!projection.packageDraft().packageId()
                    .equals(saveOutcome.receipt().result().packageId())) {
                throw new IllegalArgumentException("Legacy projection and save outcome differ");
            }
        }
    }

    private static DomainCapabilityPackageDraft draft(
            IntegrationRequestContext identity, BuiltInGraphAssetAuthority.Snapshot source) {
        List<MirrorArtifactRef> sourceRefs = new ArrayList<>();
        sourceRefs.add(source.graphRef());
        sourceRefs.add(source.contractRef());
        sourceRefs.add(source.rootCapabilityRef());
        sourceRefs.add(source.capabilityClosureRef());
        sourceRefs.addAll(source.testSuiteRefs());
        sourceRefs.sort(Comparator.comparing(MirrorArtifactRef::kind)
                .thenComparing(MirrorArtifactRef::id)
                .thenComparingLong(MirrorArtifactRef::revision));
        ArtifactProvenance provenance = new ArtifactProvenance(
                "", ArtifactProvenance.SourceType.INFERRED, sourceRefs,
                identity.tenantId(), PURPOSE, null, null, null, null,
                List.of(
                        "Graph topology and Contract structure do not prove customer-business semantics.",
                        "Discovered Contract test suites are not an owner-governed Scenario denominator.",
                        "No production Outcome, Fidelity, state, or effect evidence was inferred."),
                "", null, null, "");
        return new DomainCapabilityPackageDraft(
                "", "legacy:" + source.graphName(), 0, source.scope(),
                DomainCapabilityPackageDraft.BusinessDefinition.empty(), source.contractRef(),
                List.of(), List.of(source.graphRef()), List.of(), List.of(), List.of(), null,
                List.of(), List.of(), List.of(), List.of(), null, List.of(),
                List.of(
                        "The imported Graph Contract requires Package-owner confirmation.",
                        "Contract test suites require governed Scenario conversion before readiness.",
                        "Business bindings, Fidelity, Outcome, state, and effect semantics are absent."),
                List.of("Existing Graph execution behavior is preserved without topology rewriting."),
                null, provenance, DomainCapabilityPackageDraft.Lifecycle.DRAFT);
    }

    private static List<Gap> gaps(
            DomainCapabilityPackageDraft draft, BuiltInGraphAssetAuthority.Snapshot source) {
        List<Gap> gaps = new ArrayList<>();
        for (String blocker : draft.readinessBlockers()) {
            gaps.add(readinessGap(blocker, source));
        }
        gaps.add(gap("GRAPH_CONTRACT_OWNER_CONFIRMATION_MISSING", GapOrigin.MIGRATION_POLICY,
                GapCategory.CONTRACT, GapSeverity.BLOCKING, "/packageContractRef",
                "The existing Graph Contract is technical source evidence, not an approved Package Contract.",
                "Have the accountable Package owner confirm or replace the Contract reference.",
                List.of(source.contractRef())));
        gaps.add(gap("MIRROR_PLAN_MISSING", GapOrigin.MIGRATION_POLICY,
                GapCategory.EXECUTION_MODEL, GapSeverity.BLOCKING, "/graphRefs/0",
                "No exact MirrorPlan has been compiled for the imported Package.",
                "Compile and review a MirrorPlan after the Package business boundary is confirmed.",
                List.of(source.graphRef(), source.capabilityClosureRef())));
        gaps.add(gap("LEGACY_PROJECTION_OWNER_APPROVAL_MISSING", GapOrigin.MIGRATION_POLICY,
                GapCategory.MIGRATION_TRUST, GapSeverity.BLOCKING, "/provenance/approvedBy",
                "The migration projection is inferred and carries no business-owner approval.",
                "Review every inferred binding and record an independent owner approval.",
                List.of(source.graphRef(), source.contractRef())));
        if (!source.testSuiteRefs().isEmpty()) {
            gaps.add(gap("DISCOVERED_TEST_SUITE_REQUIRES_SCENARIO_GOVERNANCE",
                    GapOrigin.MIGRATION_POLICY, GapCategory.SCENARIO, GapSeverity.WARNING,
                    "/scenarioPackRefs",
                    "Executable Contract tests were discovered but are not Scenario inventory or packs.",
                    "Classify, deduplicate, version, and approve them into the Scenario governance model.",
                    source.testSuiteRefs()));
        }
        return gaps;
    }

    private static Gap readinessGap(
            String code, BuiltInGraphAssetAuthority.Snapshot source) {
        return switch (code) {
            case "BUSINESS_DOMAIN_MISSING" -> gap(code, GapCategory.BUSINESS_CONTEXT,
                    "/businessDefinition/domainId", "Customer-business domain is unknown.",
                    "Select the governed business domain.", List.of());
            case "PROBLEM_TAXONOMY_MISSING" -> gap(code, GapCategory.BUSINESS_CONTEXT,
                    "/businessDefinition/problemTaxonomyRef", "Problem taxonomy is unknown.",
                    "Bind an exact governed problem taxonomy revision.", List.of());
            case "PROBLEM_CODE_MISSING" -> gap(code, GapCategory.BUSINESS_CONTEXT,
                    "/businessDefinition/problemCode", "Customer problem code is unknown.",
                    "Select the taxonomy-local problem code.", List.of());
            case "BUSINESS_GOAL_MISSING" -> gap(code, GapCategory.BUSINESS_CONTEXT,
                    "/businessDefinition/businessGoal", "Business goal cannot be inferred from topology.",
                    "Author a measurable customer-service goal.", List.of());
            case "EXPECTED_OUTCOME_MISSING" -> gap(code, GapCategory.OUTCOME,
                    "/businessDefinition/expectedOutcome", "Expected business outcome is unknown.",
                    "Define the independently observable expected outcome.", List.of());
            case "ACCOUNTABLE_OWNER_MISSING" -> gap(code, GapCategory.MIGRATION_TRUST,
                    "/businessDefinition/accountableOwner", "Accountable business owner is unknown.",
                    "Assign the accountable Package owner.", List.of());
            case "PACKAGE_CONTRACT_MISSING" -> gap(code, GapCategory.CONTRACT,
                    "/packageContractRef", "Package Contract is missing.",
                    "Author and bind an exact Package Contract.", List.of(source.contractRef()));
            case "EXECUTABLE_PROJECTION_MISSING" -> gap(code, GapCategory.EXECUTION_MODEL,
                    "/graphRefs", "Executable projection is missing.",
                    "Bind an exact Graph or Capability.", List.of(source.graphRef()));
            case "SCENARIO_INVENTORY_MISSING" -> gap(code, GapCategory.SCENARIO,
                    "/scenarioInventoryRef", "Owner-governed Scenario denominator is missing.",
                    "Freeze the expected Scenario inventory.", source.testSuiteRefs());
            case "SCENARIO_PACK_MISSING" -> gap(code, GapCategory.SCENARIO,
                    "/scenarioPackRefs", "Governed executable Scenario packs are missing.",
                    "Convert reviewed tests and business cases into exact Scenario packs.",
                    source.testSuiteRefs());
            case "SOLUTION_BINDING_MISSING" -> gap(code, GapCategory.SERVICE_ASSET,
                    "/solutionRefs", "No L1 Solution is bound.",
                    "Bind the Package to an exact L1 Solution asset.", List.of());
            case "SERVICE_CARRIER_BINDING_MISSING" -> gap(code, GapCategory.SERVICE_ASSET,
                    "/carrierRefs", "No L2 service carrier is bound.",
                    "Bind an exact SOP, Workflow, or Agent asset.", List.of());
            case "CHANNEL_BINDING_MISSING" -> gap(code, GapCategory.SERVICE_ASSET,
                    "/channelRefs", "No L3 application channel is bound.",
                    "Bind the consuming channel application.", List.of());
            case "FIDELITY_INVENTORY_MISSING" -> gap(code, GapCategory.FIDELITY,
                    "/fidelityInventoryRef", "Fidelity denominator is missing.",
                    "Define dimensions, strata, thresholds, and observation horizons.", List.of());
            case "OUTCOME_DEFINITION_MISSING" -> gap(code, GapCategory.OUTCOME,
                    "/outcomeDefinitionRefs", "Independent Outcome definitions are missing.",
                    "Bind exact Outcome definitions and their authority source.", List.of());
            case "HIGH_RISK_STATE_MODEL_MISSING" -> gap(code, GapCategory.EXECUTION_MODEL,
                    "/stateModelRefs", "Fail-closed legacy risk defaults require a state model.",
                    "Confirm risk and bind an exact state model when risk remains high.",
                    List.of(source.graphRef()));
            case "HIGH_RISK_EFFECT_MODEL_MISSING" -> gap(code, GapCategory.EXECUTION_MODEL,
                    "/effectModelRefs", "Fail-closed legacy risk defaults require an effect model.",
                    "Confirm risk and bind exact write/effect semantics when risk remains high.",
                    List.of(source.graphRef()));
            default -> throw new IllegalStateException(
                    "Unsupported Package readiness blocker in Legacy projector: " + code);
        };
    }

    private static Gap gap(
            String code, GapCategory category, String path, String explanation,
            String action, List<MirrorArtifactRef> evidence) {
        return gap(code, GapOrigin.PACKAGE_READINESS, category, GapSeverity.BLOCKING,
                path, explanation, action, evidence);
    }

    private static Gap gap(
            String code, GapOrigin origin, GapCategory category, GapSeverity severity,
            String path, String explanation, String action, List<MirrorArtifactRef> evidence) {
        return new Gap(code, origin, category, severity, path, explanation, action, evidence);
    }

    private static CapabilitySnapshot.Scope scope(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        return new CapabilitySnapshot.Scope(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region());
    }

    private static IntegrationProblemException authorityUnavailable(
            IntegrationRequestContext identity, String reasonCode) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                "RG.BUSINESS_MIRROR.LEGACY_PROJECTION_UNAVAILABLE",
                "Legacy Graph source authority could not produce an exact migration projection.",
                identity.correlationId(), Map.of("reasonCode", normalized(reasonCode))));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
