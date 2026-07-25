package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Independently verified Scenario-workbook source for Domain Fidelity projection.
 *
 * <p>The adapter resolves exact durable runs through the Scenario authority, independently
 * re-verifies the signed aggregate, signed retention registration, workbook content address, and
 * every assertion-result content address, then requires the supplied workbooks to cover the
 * inventory's Scenario units exactly once. It maps only assertion dimensions that the workbook
 * can prove. Outcome, request-space, and error-distribution obligations remain absent so the
 * projection kernel records explicit abstention debt.</p>
 */
public final class ScenarioRehearsalDomainFidelitySource
        implements DomainFidelityMeasurementSource {
    private static final String EVIDENCE_READ_PURPOSE =
            "GOVERNANCE_EVIDENCE_INGESTION";

    private final ScenarioRehearsalRuntimeService rehearsals;
    private final ScenarioRehearsalEvidenceIntegrityService evidenceIntegrity;
    private final VisualEvidenceSigner signer;
    private final DomainFidelityPolicy policy;
    private final ObjectMapper mapper;

    /**
     * Creates the source adapter.
     *
     * @param rehearsals authoritative durable Scenario read boundary
     * @param evidenceIntegrity independent aggregate verifier
     * @param signer managed aggregate and retention verification authority
     * @param policy server-owned projector authorization policy
     * @param mapper canonical protocol mapper
     */
    public ScenarioRehearsalDomainFidelitySource(
            ScenarioRehearsalRuntimeService rehearsals,
            ScenarioRehearsalEvidenceIntegrityService evidenceIntegrity,
            VisualEvidenceSigner signer,
            DomainFidelityPolicy policy,
            ObjectMapper mapper) {
        this.rehearsals = Objects.requireNonNull(
                rehearsals, "rehearsals");
        this.evidenceIntegrity = Objects.requireNonNull(
                evidenceIntegrity, "evidenceIntegrity");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Type type() {
        return Type.SCENARIO_REHEARSAL;
    }

    @Override
    public boolean ready() {
        try {
            return signer.available();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Converts an exact set of durable Scenario runs into ordered payload-free measurements.
     *
     * @param inventory verified current owner denominator
     * @param runIds exact aggregate runs whose case union must equal the inventory
     * @param identity authenticated Fidelity projector service
     * @return one synthesized-source measurement for every inventory unit
     */
    public List<DomainFidelityProfileProjector.Measurement>
    measurements(
            DomainFidelityInventory inventory,
            List<String> runIds,
            IntegrationRequestContext identity) {
        IntegrationRequestContext projector =
                requireProjector(identity);
        DomainFidelityInventory denominator =
                Objects.requireNonNull(inventory, "inventory");
        denominator.verify(mapper);
        requireScope(denominator.scope(), projector);
        if (!ready()) {
            throw new IntegrationProblemException(
                    IntegrationProblem.serviceUnavailable(
                            "RG.MIRROR.FIDELITY.SCENARIO_SOURCE_UNAVAILABLE",
                            "The Scenario Fidelity source verification authority is unavailable.",
                            projector.correlationId(),
                            Map.of()));
        }
        List<String> runs = canonicalRunIds(runIds);
        Map<MirrorArtifactRef, DomainFidelityInventory.CoverageUnit>
                inventoryByScenario = new LinkedHashMap<>();
        for (DomainFidelityInventory.CoverageUnit unit
                : denominator.units()) {
            inventoryByScenario.put(
                    unit.scenarioCaseRef(),
                    unit);
        }
        Map<String, DomainFidelityProfileProjector.Measurement>
                measurementsByUnit = new LinkedHashMap<>();
        IntegrationRequestContext reader =
                evidenceReader(projector);
        for (String runId : runs) {
            ScenarioRehearsalEvidenceBundle bundle =
                    evidenceIntegrity.requireVerified(
                            rehearsals.evidence(runId, reader))
                            .bundle();
            ScenarioRehearsalWorkbookSeed workbook =
                    rehearsals.workbookSeed(runId, reader);
            verifyWorkbook(workbook, bundle, denominator.scope());
            for (ScenarioRehearsalWorkbookSeed.CaseResult result
                    : workbook.cases()) {
                DomainFidelityInventory.CoverageUnit unit =
                        inventoryByScenario.get(
                                result.scenarioCaseRef());
                if (unit == null
                        || !unit.scenarioCaseRef().equals(
                        result.scenarioCaseRef())
                        || unit.caseType() != result.caseType()
                        || !unit.targetCapabilityRef().equals(
                        workbook.targetCapabilityRef())
                        || measurementsByUnit.containsKey(
                        unit.unitId())) {
                    throw invalidSource(
                            projector,
                            "Scenario workbooks do not form the exact inventory case closure.");
                }
                measurementsByUnit.put(
                        unit.unitId(),
                        measurement(unit, result, workbook));
            }
        }
        if (measurementsByUnit.size()
                != denominator.units().size()) {
            throw invalidSource(
                    projector,
                    "Scenario workbooks do not cover every inventory unit exactly once.");
        }
        return denominator.units().stream()
                .map(unit -> measurementsByUnit.get(unit.unitId()))
                .toList();
    }

    private DomainFidelityProfileProjector.Measurement
    measurement(
            DomainFidelityInventory.CoverageUnit unit,
            ScenarioRehearsalWorkbookSeed.CaseResult result,
            ScenarioRehearsalWorkbookSeed workbook) {
        boolean certifiable =
                result.evidenceBacked()
                        && MirrorRunEvidence.EvidenceClass.CERTIFIABLE
                        .name().equals(result.evidenceClass());
        boolean complete =
                result.evidenceBacked()
                        && !MirrorRunEvidence.Status.EVIDENCE_INCOMPLETE
                        .name().equals(result.evidenceStatus())
                        && result.assertionResults().stream()
                        .noneMatch(assertion ->
                                assertion.reasonCode()
                                == ScenarioHandlingAssertionResult
                                .ReasonCode
                                .ASSERTION_EVIDENCE_INCOMPLETE);
        return new DomainFidelityProfileProjector.Measurement(
                unit.unitId(),
                unit.scenarioCaseRef(),
                new MirrorArtifactRef(
                        "SCENARIO_REHEARSAL_WORKBOOK_SEED",
                        workbook.runId(),
                        1,
                        workbook.seedFingerprint()),
                workbook.retentionProof().occurredAt(),
                DomainFidelityProfile.SourceMode.SYNTHESIZED,
                certifiable,
                complete,
                dimensionResults(
                        unit.requiredDimensions(),
                        result.assertionResults()));
    }

    private List<DomainFidelityProfile.DimensionResult>
    dimensionResults(
            List<DomainFidelityProfile.Dimension> required,
            List<ScenarioHandlingAssertionResult> assertions) {
        EnumMap<DomainFidelityProfile.Dimension,
                List<ScenarioHandlingAssertionResult>> byDimension =
                new EnumMap<>(
                        DomainFidelityProfile.Dimension.class);
        for (ScenarioHandlingAssertionResult assertion
                : assertions) {
            DomainFidelityProfile.Dimension dimension =
                    dimension(assertion.observation());
            if (dimension != null
                    && required.contains(dimension)) {
                byDimension.computeIfAbsent(
                        dimension,
                        ignored -> new ArrayList<>())
                        .add(assertion);
            }
        }
        List<DomainFidelityProfile.DimensionResult> results =
                new ArrayList<>();
        for (DomainFidelityProfile.Dimension dimension
                : required) {
            List<ScenarioHandlingAssertionResult> values =
                    byDimension.getOrDefault(
                            dimension, List.of());
            if (!values.isEmpty()) {
                results.add(result(dimension, values));
            }
        }
        return List.copyOf(results);
    }

    private static DomainFidelityProfile.DimensionResult result(
            DomainFidelityProfile.Dimension dimension,
            List<ScenarioHandlingAssertionResult> assertions) {
        if (assertions.stream().anyMatch(value ->
                value.outcome()
                == ScenarioHandlingAssertionResult.Outcome.FAIL)) {
            return new DomainFidelityProfile.DimensionResult(
                    dimension,
                    DomainFidelityProfile.MeasurementOutcome.FAIL,
                    DomainFidelityProfile.MeasurementReason
                            .ASSERTION_FAILED);
        }
        if (assertions.stream().anyMatch(value ->
                value.outcome()
                == ScenarioHandlingAssertionResult.Outcome
                .INDETERMINATE)) {
            return new DomainFidelityProfile.DimensionResult(
                    dimension,
                    DomainFidelityProfile.MeasurementOutcome
                            .ABSTAINED,
                    DomainFidelityProfile.MeasurementReason
                            .ASSERTION_EVIDENCE_INDETERMINATE);
        }
        return new DomainFidelityProfile.DimensionResult(
                dimension,
                DomainFidelityProfile.MeasurementOutcome.PASS,
                DomainFidelityProfile.MeasurementReason
                        .ASSERTIONS_PASSED);
    }

    private static DomainFidelityProfile.Dimension dimension(
            CaseHandlingAssertion.Observation observation) {
        return switch (observation) {
            case GRAPH_OUTPUT_SCHEMA ->
                    DomainFidelityProfile.Dimension.CONTRACT;
            case COMPENSATION, SIDE_EFFECT_RECEIPT ->
                    DomainFidelityProfile.Dimension.EFFECT;
            case STATE_TRANSITION, FINAL_STATE_INVARIANT ->
                    DomainFidelityProfile.Dimension.STATE_TRANSITION;
            case GRAPH_OUTPUT_VALUE, NODE_STATUS, EDGE_STATUS,
                    CAPABILITY_OCCURRENCE, INVOCATION_INPUT, ERROR,
                    FALLBACK, GOVERNANCE_EXPECTATION, LATENCY_BUDGET,
                    RETRY_BUDGET, RESOURCE_BUDGET ->
                    DomainFidelityProfile.Dimension.BEHAVIOR;
        };
    }

    private void verifyWorkbook(
            ScenarioRehearsalWorkbookSeed workbook,
            ScenarioRehearsalEvidenceBundle bundle,
            CapabilitySnapshot.Scope scope) {
        ScenarioRehearsalWorkbookSeed exact =
                Objects.requireNonNull(workbook, "workbook");
        exact.verify(mapper);
        ScenarioRehearsalResult aggregate = bundle.result();
        if (!exact.scope().equals(scope)
                || !aggregate.scope().equals(scope)
                || !exact.runId().equals(
                bundle.attestation().runId())
                || !exact.requestId().equals(
                aggregate.requestId())
                || !exact.evidenceBundleFingerprint().equals(
                bundle.bundleFingerprint())
                || !exact.resultFingerprint().equals(
                aggregate.resultFingerprint())
                || !exact.compiledPlanRef().equals(
                aggregate.compiledPlanRef())
                || !exact.targetCapabilityRef().equals(
                aggregate.targetCapabilityRef())
                || exact.outcome() != aggregate.outcome()
                || !exact.summary().equals(
                aggregate.summary())
                || exact.cases().size()
                != aggregate.caseResults().size()) {
            throw new IllegalArgumentException(
                    "Scenario workbook differs from its signed aggregate");
        }
        ScenarioRehearsalRetentionEvent retention =
                exact.retentionProof();
        if (!signer.verify(
                retention.evidenceSeal(),
                retention.eventFingerprint()).valid()) {
            throw new IllegalArgumentException(
                    "Scenario workbook retention signature is invalid");
        }
        for (int index = 0;
             index < exact.cases().size();
             index++) {
            ScenarioRehearsalWorkbookSeed.CaseResult projected =
                    exact.cases().get(index);
            ScenarioCaseRehearsalResult source =
                    aggregate.caseResults().get(index);
            if (projected.caseIndex() != source.caseIndex()
                    || !projected.scenarioCaseRef().equals(
                    source.scenarioCaseRef())
                    || projected.caseType() != source.caseType()
                    || !projected.testSuiteRef().equals(
                    source.testSuiteRef())
                    || !projected.testCaseId().equals(
                    source.testCaseId())
                    || !projected.mirrorPlanRef().equals(
                    source.mirrorPlanRef())
                    || !projected.fixtureBundleRef().equals(
                    source.fixtureBundleRef())
                    || !Objects.equals(
                    projected.sessionCheckpointRef(),
                    source.sessionCheckpointRef())
                    || !projected.childRunId().equals(
                    source.runId())
                    || !projected.childEvidenceBundleFingerprint()
                    .equals(source.evidenceBundleFingerprint())
                    || !projected.evidenceStatus().equals(
                    source.evidenceStatus() == null
                            ? ""
                            : source.evidenceStatus().name())
                    || !projected.evidenceClass().equals(
                    source.evidenceClass() == null
                            ? ""
                            : source.evidenceClass().name())
                    || projected.outcome() != source.outcome()
                    || !projected.diagnosticCode().equals(
                    source.diagnosticCode())
                    || !projected.assertionResults().equals(
                    source.assertionResults())) {
                throw new IllegalArgumentException(
                        "Scenario workbook case differs from its signed aggregate");
            }
            for (ScenarioHandlingAssertionResult assertion
                    : projected.assertionResults()) {
                ScenarioHandlingAssertionResultIntegrity.verify(
                        mapper, assertion);
            }
        }
    }

    private IntegrationRequestContext requireProjector(
            IntegrationRequestContext identity) {
        IntegrationRequestContext exact =
                Objects.requireNonNull(identity, "identity");
        exact.requireComplete();
        if (!DomainFidelityPolicy.PROJECTION_PURPOSE.equals(
                exact.purpose())
                || !policy.mayProject(exact)) {
            throw new IntegrationProblemException(
                    IntegrationProblem.forbidden(
                            "RG.MIRROR.FIDELITY.PROJECTOR_FORBIDDEN",
                            "The authenticated service is not an authorized Fidelity projector.",
                            exact.correlationId(),
                            Map.of()));
        }
        return exact;
    }

    private static void requireScope(
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        if (!scope.tenantId().equals(identity.tenantId())
                || !scope.organizationId().equals(
                identity.organizationId())
                || !scope.projectId().equals(
                identity.projectId())
                || !scope.environmentId().equals(
                identity.environmentId())
                || !scope.region().equals(identity.region())) {
            throw new IntegrationProblemException(
                    IntegrationProblem.notFound(
                            "RG.MIRROR.FIDELITY.INVENTORY_NOT_FOUND",
                            "Fidelity inventory was not found in the authorized scope.",
                            identity.correlationId(),
                            Map.of()));
        }
    }

    private static List<String> canonicalRunIds(
            List<String> runIds) {
        List<String> source =
                runIds == null ? List.of() : List.copyOf(runIds);
        if (source.isEmpty()
                || source.size()
                > DomainFidelityInventory.MAXIMUM_UNITS) {
            throw new IllegalArgumentException(
                    "Scenario Fidelity source requires a bounded non-empty run set");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String runId : source) {
            String exact = runId == null ? "" : runId.trim();
            if (!ScenarioRehearsalRunIdentity
                    .hasCanonicalShape(exact)
                    || !unique.add(exact)) {
                throw new IllegalArgumentException(
                        "Scenario Fidelity run ids must be canonical and unique");
            }
        }
        return List.copyOf(unique);
    }

    private static IntegrationRequestContext evidenceReader(
            IntegrationRequestContext identity) {
        return new IntegrationRequestContext(
                identity.tenantId(),
                identity.organizationId(),
                identity.projectId(),
                identity.environmentId(),
                identity.region(),
                identity.actorType(),
                identity.actorId(),
                identity.delegatedBy(),
                EVIDENCE_READ_PURPOSE,
                identity.correlationId(),
                identity.groups(),
                identity.clearance(),
                identity.delegationGrantId());
    }

    private static IntegrationProblemException invalidSource(
            IntegrationRequestContext identity,
            String title) {
        return new IntegrationProblemException(
                IntegrationProblem.conflict(
                        "RG.MIRROR.FIDELITY.SCENARIO_SOURCE_INVALID",
                        title,
                        identity.correlationId(),
                        Map.of()));
    }
}
