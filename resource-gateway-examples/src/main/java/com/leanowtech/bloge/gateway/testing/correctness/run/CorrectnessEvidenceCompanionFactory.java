package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.SourceMapping;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactCaseRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredFixtureAsset;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublicationAttempt;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceProtocol;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds and validates exact authoring-to-runtime lineage without reading Fixture material. */
public final class CorrectnessEvidenceCompanionFactory {

    private final ScenarioDraftSetV2Repository scenarios;
    private final FixtureAssetRepository fixtures;
    private final CorrectnessVerdictProjector verdicts;
    private final ObjectMapper mapper;

    public CorrectnessEvidenceCompanionFactory(
            ScenarioDraftSetV2Repository scenarios,
            FixtureAssetRepository fixtures,
            CorrectnessVerdictProjector verdicts,
            ObjectMapper mapper
    ) {
        this.scenarios = Objects.requireNonNull(scenarios, "scenarios");
        this.fixtures = Objects.requireNonNull(fixtures, "fixtures");
        this.verdicts = Objects.requireNonNull(verdicts, "verdicts");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public StoredCorrectnessEvidenceCompanion create(
            CorrectnessRunRequest request,
            CorrectnessPreflightReport preflight,
            StoredCorrectnessPublication storedPublication,
            StoredCorrectnessPublicationAttempt committedAttempt,
            TestSuiteExecutionResponse suiteResponse,
            String clientRequestFingerprint,
            IntegrationRequestContext identity
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(preflight, "preflight");
        Objects.requireNonNull(storedPublication, "storedPublication");
        Objects.requireNonNull(committedAttempt, "committedAttempt");
        Objects.requireNonNull(suiteResponse, "suiteResponse");
        Objects.requireNonNull(identity, "identity");
        CorrectnessPublication publication = storedPublication.publication();
        EnterpriseScope scope = scope(identity);
        requireTerminalClosure(request, preflight, storedPublication, suiteResponse);

        List<ExactCaseRef> caseRefs = caseRefs(
                scope, publication, preflight.cases().stream()
                        .map(CorrectnessPreflightReport.CasePlan::caseId).toList());
        List<String> classifications = classifications(
                scope, publication.fixtureAssetRefs());
        List<SourceMapping> sourceMap = committedAttempt.compilationReport().sourceMap();
        requireSourceMapClosure(publication, preflight, sourceMap);
        List<CorrectnessEvidenceCompanion.CaseExecutionRef> caseExecutions =
                caseExecutions(preflight, suiteResponse.evidence());
        Instant completedAt = suiteResponse.evidence().completedAt();
        PrincipalRef actor = actor(identity);
        CorrectnessEvidenceCompanion companion = new CorrectnessEvidenceCompanion(
                "", companionId(suiteResponse.suiteRunId()), scope,
                suiteResponse.suiteRunId(), suiteResponse.evidenceFingerprint(),
                clientRequestFingerprint, request.publicationRef(), publication.target(),
                publication.definitionRef(), publication.inventoryRef(),
                publication.scenarioDraftSetRef(), caseRefs, publication.oracleRefs(),
                publication.assertionSetRefs(), publication.fixtureAssetRefs(),
                publication.compiledFixtureBundleRefs(), publication.compiledTestSuiteRef(),
                request.selection(), caseExecutions, sourceMap, preflight.riskSummary(),
                classifications,
                verdicts.project(suiteResponse, preflight.proofLevel(), true),
                suiteResponse.attestation(),
                new AuditMetadata(completedAt, completedAt, actor, actor));
        return StoredCorrectnessEvidenceCompanion.verified(mapper, companion);
    }

    private List<ExactCaseRef> caseRefs(
            EnterpriseScope scope,
            CorrectnessPublication publication,
            List<String> selectedCaseIds
    ) {
        ExactAssetRef expected = publication.scenarioDraftSetRef();
        StoredScenarioDraftSetV2 stored = scenarios.findRevision(
                        scope, expected.id(), expected.revision())
                .orElseThrow(() -> failure(
                        "RG.CORRECTNESS.EVIDENCE_SCENARIO_NOT_FOUND",
                        "Exact Scenario revision is unavailable for evidence lineage"));
        var set = stored.scenarioDraftSet();
        if (!stored.scenarioDraftSetFingerprint().equals(expected.fingerprint())
                || !set.scenarioDraftSetId().equals(expected.id())
                || set.revision() != expected.revision()
                || !set.scope().equals(scope)
                || !set.target().equals(publication.target())) {
            throw failure("RG.CORRECTNESS.EVIDENCE_SCENARIO_DRIFT",
                    "Scenario revision differs from the Publication closure");
        }
        Map<String, com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2
                .ScenarioDraftV2> byId = set.scenarios().stream().collect(Collectors.toMap(
                value -> value.scenarioId(), Function.identity()));
        List<String> missing = selectedCaseIds.stream()
                .filter(caseId -> !byId.containsKey(caseId)).toList();
        if (!missing.isEmpty()) {
            throw failure("RG.CORRECTNESS.EVIDENCE_CASE_LINEAGE_MISSING",
                    "Selected compiled Case has no exact authoring source");
        }
        return selectedCaseIds.stream().map(caseId -> new ExactCaseRef(
                expected, caseId,
                CorrectnessProtocolFingerprint.scenarioFingerprint(mapper, byId.get(caseId))))
                .toList();
    }

    private List<String> classifications(
            EnterpriseScope scope,
            List<ExactAssetRef> expectedRefs
    ) {
        List<StoredFixtureAsset> resolved = fixtures.resolveExact(scope, expectedRefs);
        Set<ExactAssetRef> expected = Set.copyOf(expectedRefs);
        Set<ExactAssetRef> actual = resolved.stream()
                .map(StoredFixtureAsset::exactRef).collect(Collectors.toSet());
        if (!actual.equals(expected)) {
            throw failure("RG.CORRECTNESS.EVIDENCE_FIXTURE_LINEAGE_MISSING",
                    "Fixture descriptor closure is unavailable for evidence lineage");
        }
        return resolved.stream().map(value -> value.descriptor().classification())
                .distinct().sorted().toList();
    }

    private static List<CorrectnessEvidenceCompanion.CaseExecutionRef> caseExecutions(
            CorrectnessPreflightReport preflight,
            TestSuiteRunEvidenceProtocol evidence
    ) {
        Map<String, TestSuiteRunEvidence.CaseResult> results = evidence.caseResults().stream()
                .collect(Collectors.toMap(
                        TestSuiteRunEvidence.CaseResult::caseId, Function.identity()));
        if (results.size() != preflight.cases().size()) {
            throw failure("RG.CORRECTNESS.EVIDENCE_CASE_CLOSURE_INVALID",
                    "Suite evidence Case closure differs from reviewed preflight");
        }
        List<CorrectnessEvidenceCompanion.CaseExecutionRef> result = new ArrayList<>();
        for (CorrectnessPreflightReport.CasePlan plan : preflight.cases()) {
            TestSuiteRunEvidence.CaseResult observed = results.get(plan.caseId());
            if (observed == null || !fixtureRef(observed).equals(plan.fixtureBundleRef())) {
                throw failure("RG.CORRECTNESS.EVIDENCE_CASE_CLOSURE_INVALID",
                        "Suite evidence Case differs from reviewed preflight");
            }
            result.add(new CorrectnessEvidenceCompanion.CaseExecutionRef(
                    plan.caseId(), plan.fixtureBundleRef(), plan.executionPlanFingerprint(),
                    observed.status(), observed.runId(), observed.evidenceClass()));
        }
        return result;
    }

    private static void requireTerminalClosure(
            CorrectnessRunRequest request,
            CorrectnessPreflightReport preflight,
            StoredCorrectnessPublication storedPublication,
            TestSuiteExecutionResponse response
    ) {
        CorrectnessPublication publication = storedPublication.publication();
        TestSuiteRunEvidenceProtocol evidence = response.evidence();
        TestSuite.Target target = evidence.target();
        ExactAssetRef suite = publication.compiledTestSuiteRef();
        boolean valid = evidence.status() != TestSuiteRunEvidence.Status.RUNNING
                && evidence.completedAt() != null
                && response.evidenceFingerprint() != null
                && response.evidenceFingerprint().matches("sha256:[0-9a-f]{64}")
                && response.suiteRunId().equals(evidence.suiteRunId())
                && response.attestation().scope() == TestSuiteRunAttestation.Scope.TERMINAL
                && response.attestation().suiteRunId().equals(response.suiteRunId())
                && response.attestation().aggregateEvidenceFingerprint()
                .equals(response.evidenceFingerprint())
                && request.publicationRef().equals(preflight.publicationRef())
                && request.selection().equals(preflight.selection())
                && request.publicationRef().publicationId().equals(publication.publicationId())
                && request.publicationRef().fingerprint()
                .equals(storedPublication.publicationFingerprint())
                && evidence.suiteRef().suiteId().equals(suite.id())
                && evidence.suiteRef().revision() == suite.revision()
                && evidence.suiteRef().fingerprint().equals(suite.fingerprint())
                && target.kind().equals(publication.target().kind().name())
                && target.id().equals(publication.target().id())
                && target.fingerprint().equals(publication.target().fingerprint());
        if (!valid) {
            throw failure("RG.CORRECTNESS.EVIDENCE_RUNTIME_CLOSURE_INVALID",
                    "Terminal suite evidence differs from the exact correctness run closure");
        }
    }

    private static void requireSourceMapClosure(
            CorrectnessPublication publication,
            CorrectnessPreflightReport preflight,
            List<SourceMapping> sourceMap
    ) {
        Map<String, ExactAssetRef> fixturesByCase = new HashMap<>();
        preflight.cases().forEach(value -> fixturesByCase.put(
                value.caseId(), value.fixtureBundleRef()));
        for (CorrectnessPreflightReport.CasePlan testCase : preflight.cases()) {
            boolean scenario = sourceMap.stream().anyMatch(mapping ->
                    mapping.source().assetRef().equals(publication.scenarioDraftSetRef())
                            && mapping.source().elementKind().equals("SCENARIO_CASE")
                            && mapping.source().elementId().equals(testCase.caseId())
                            && mapping.output().assetRef().equals(publication.compiledTestSuiteRef())
                            && mapping.output().elementKind().equals("TEST_CASE")
                            && mapping.output().elementId().equals(testCase.caseId()));
            boolean obligation = sourceMap.stream().anyMatch(mapping ->
                    mapping.source().assetRef().equals(publication.inventoryRef())
                            && mapping.source().elementKind().equals("OBLIGATION")
                            && mapping.output().assetRef().equals(publication.compiledTestSuiteRef())
                            && mapping.output().elementKind().equals("TEST_CASE")
                            && mapping.output().elementId().equals(testCase.caseId()));
            boolean assertion = sourceMap.stream().anyMatch(mapping ->
                    publication.assertionSetRefs().contains(mapping.source().assetRef())
                            && mapping.source().elementKind().equals("ASSERTION")
                            && mapping.output().assetRef().equals(
                            fixturesByCase.get(testCase.caseId())));
            boolean oracle = sourceMap.stream().anyMatch(mapping ->
                    publication.oracleRefs().contains(mapping.source().assetRef())
                            && mapping.source().elementKind().equals("BUSINESS_ORACLE")
                            && mapping.output().assetRef().equals(
                            fixturesByCase.get(testCase.caseId())));
            if (!scenario || !obligation || !assertion || !oracle) {
                throw failure("RG.CORRECTNESS.EVIDENCE_SOURCE_MAP_INCOMPLETE",
                        "Publication source map cannot explain a selected Case");
            }
        }
    }

    private static ExactAssetRef fixtureRef(TestSuiteRunEvidence.CaseResult result) {
        TestSuite.FixtureBundleRef ref = result.fixtureBundleRef();
        if (ref == null) return null;
        return new ExactAssetRef(
                "FIXTURE_BUNDLE", ref.fixtureBundleId(), ref.revision(), ref.fingerprint());
    }

    private static EnterpriseScope scope(IntegrationRequestContext identity) {
        return new EnterpriseScope(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region());
    }

    private static PrincipalRef actor(IntegrationRequestContext identity) {
        PrincipalKind kind;
        try {
            kind = PrincipalKind.valueOf(identity.actorType());
        } catch (IllegalArgumentException unsupported) {
            kind = PrincipalKind.SERVICE;
        }
        return new PrincipalRef(identity.actorId(), kind, "");
    }

    private static String companionId(String suiteRunId) {
        return "correctness-evidence-" + suiteRunId;
    }

    private static CorrectnessRunException failure(String code, String message) {
        return new CorrectnessRunException(409, code, message, false);
    }
}
