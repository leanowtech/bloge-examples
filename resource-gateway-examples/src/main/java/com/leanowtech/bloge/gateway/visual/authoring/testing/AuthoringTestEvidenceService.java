package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringDraftService;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringLifecycleException;
import com.leanowtech.bloge.gateway.visual.authoring.application.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringCompileResult;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDiagnostic;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDraft;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringProblem;
import com.leanowtech.bloge.gateway.visual.authoring.model.VisualLibraryAuthoringDocument;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.AssetGate;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.AssetKind;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.CaseSummary;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.Coverage;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.DraftGate;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.EvidenceRecord;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.EvidenceView;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.FreshnessStatus;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.GateReason;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.GateStatus;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.StaleReason;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.FunctionRunEvidence;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestProtocol.OperatorRunEvidence;
import com.leanowtech.bloge.gateway.visual.catalog.BuiltInFunctionContract;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibrary;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Persists payload-free signed test evidence and derives live freshness and TEST_EVIDENCED gates.
 */
@Service
public final class AuthoringTestEvidenceService {

    private final AuthoringDraftService drafts;
    private final AuthoringTestEvidenceRepository evidence;
    private final AuthoringFunctionTestWorker functionWorker;
    private final Clock clock;

    @Autowired
    public AuthoringTestEvidenceService(
            AuthoringDraftService drafts,
            AuthoringTestEvidenceRepository evidence,
            AuthoringFunctionTestWorker functionWorker) {
        this(drafts, evidence, functionWorker, Clock.systemUTC());
    }

    AuthoringTestEvidenceService(
            AuthoringDraftService drafts,
            AuthoringTestEvidenceRepository evidence,
            AuthoringFunctionTestWorker functionWorker,
            Clock clock) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.functionWorker = Objects.requireNonNull(functionWorker, "functionWorker");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public EvidenceRecord persistOperator(
            OperatorRunEvidence run,
            AuthoringTestPrincipal identity) {
        AuthoringTestScope scope = requireScope(identity);
        AuthoringDraft draft = exactDraft(
                authoringScope(scope), run.draftId(), run.authoringRevision());
        List<String> declaredRefs = operatorTestRefs(draft.document(), run.result().operatorRef());
        List<CaseSummary> cases = run.result().results().stream()
                .map(result -> new CaseSummary(
                        result.name(),
                        "CONTRACT",
                        result.passed() ? "PASSED" : "FAILED",
                        result.passed(),
                        result.assertionCount(),
                        0,
                        "",
                        diagnosticCodes(result.diagnostics())))
                .toList();
        var sourceCoverage = run.result().coverage();
        Coverage coverage = new Coverage(
                sourceCoverage.inputPortSchemaValidated(),
                sourceCoverage.configSchemaValidated(),
                sourceCoverage.mockedOutputSchemaValidated(),
                sourceCoverage.mockedOutputCount(),
                sourceCoverage.assertionCount());
        EvidenceRecord candidate = new EvidenceRecord(
                EvidenceRecord.SCHEMA_VERSION,
                scope,
                run.runId(),
                AssetKind.OPERATOR,
                run.result().operatorRef(),
                run.draftId(),
                run.authoringRevision(),
                run.authoringFingerprint(),
                run.canonicalFingerprint(),
                run.artifactFingerprint(),
                "",
                "",
                run.suiteFingerprint(),
                run.evidenceFingerprint(),
                AuthoringTestEvidenceProtocol.POLICY_VERSION,
                run.result().mode().name(),
                "",
                run.result().passed(),
                run.result().totalCases(),
                run.result().passedCases(),
                run.result().failedCases(),
                requiredCases(declaredRefs),
                coverage,
                cases,
                declaredRefs,
                diagnosticCodes(run.result().diagnostics(), run.diagnostics()),
                run.executedAt(),
                identity.actorId(),
                false,
                "",
                null);
        return create(candidate, run.draftId(), run.authoringRevision());
    }

    public EvidenceRecord persistFunction(
            FunctionRunEvidence run,
            String functionRef,
            AuthoringTestPrincipal identity) {
        AuthoringTestScope scope = requireScope(identity);
        AuthoringDraft draft = exactDraft(
                authoringScope(scope), run.draftId(), run.authoringRevision());
        String requiredFunctionRef = normalized(functionRef);
        if (requiredFunctionRef.isBlank()) {
            throw new AuthoringTestEvidenceIntegrityException();
        }
        List<String> declaredRefs = functionTestRefs(draft.document(), requiredFunctionRef);
        List<CaseSummary> cases = run.results().stream()
                .map(result -> new CaseSummary(
                        result.id(),
                        result.kind().name(),
                        result.status().name(),
                        result.passed(),
                        1,
                        result.durationMicros(),
                        result.errorCode(),
                        diagnosticCodes(result.diagnostics())))
                .toList();
        EvidenceRecord candidate = new EvidenceRecord(
                EvidenceRecord.SCHEMA_VERSION,
                scope,
                run.runId(),
                AssetKind.FUNCTION,
                requiredFunctionRef,
                run.draftId(),
                run.authoringRevision(),
                run.authoringFingerprint(),
                run.canonicalFingerprint(),
                run.functionFingerprint(),
                run.runtimeFingerprint(),
                run.executionProfile(),
                run.suiteFingerprint(),
                run.evidenceFingerprint(),
                AuthoringTestEvidenceProtocol.POLICY_VERSION,
                "FUNCTION_RUNTIME",
                run.bindingStatus().name(),
                run.passed(),
                run.totalCases(),
                run.passedCases(),
                run.failedCases(),
                requiredCases(declaredRefs),
                new Coverage(0, 0, 0, 0, run.totalCases()),
                cases,
                declaredRefs,
                diagnosticCodes(run.diagnostics()),
                run.executedAt(),
                identity.actorId(),
                false,
                "",
                null);
        return create(candidate, run.draftId(), run.authoringRevision());
    }

    public EvidenceView find(
            String draftId,
            String runId,
            AuthoringTestPrincipal identity) {
        AuthoringTestScope scope = requireScope(identity);
        EvidenceRecord record;
        try {
            record = evidence.find(scope, runId)
                    .filter(value -> value.draftId().equals(normalized(draftId)))
                    .orElseThrow(() -> failure(
                            404,
                            "RG.AUTHORING.TEST_EVIDENCE_NOT_FOUND",
                            "Test evidence was not found in the authorized enterprise scope.",
                            draftId,
                            0,
                            "/runId"));
        } catch (AuthoringLifecycleException known) {
            throw known;
        } catch (AuthoringTestEvidenceIntegrityException invalid) {
            throw integrityFailure(draftId, 0);
        } catch (RuntimeException unavailable) {
            throw storeUnavailable(draftId, 0);
        }
        return evaluate(record, authoringScope(scope));
    }

    public DraftGate gate(
            String draftId,
            AuthoringTestPrincipal identity) {
        AuthoringTestScope scope = requireScope(identity);
        AuthoringScope draftScope = authoringScope(scope);
        AuthoringDraft draft = drafts.find(draftScope, draftId);
        AuthoringCompileResult preview = drafts.preview(
                draftScope, draft.draftId(), draft.revision());
        List<EvidenceRecord> records;
        try {
            records = evidence.findByDraft(scope, draft.draftId());
        } catch (AuthoringTestEvidenceIntegrityException invalid) {
            throw integrityFailure(draft.draftId(), draft.revision());
        } catch (RuntimeException unavailable) {
            throw storeUnavailable(draft.draftId(), draft.revision());
        }

        Map<AssetKey, EvidenceRecord> latest = records.stream()
                .collect(Collectors.toMap(
                        value -> new AssetKey(value.assetKind(), value.assetRef()),
                        Function.identity(),
                        AuthoringTestEvidenceService::newer,
                        LinkedHashMap::new));
        List<AssetGate> assets = new ArrayList<>();
        if (preview.canonicalLibrary() != null) {
            preview.canonicalLibrary().operators().stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(OperatorDefinition::operatorRef))
                    .forEach(operator -> assets.add(assetGate(
                            draft,
                            preview,
                            AssetKind.OPERATOR,
                            operator.operatorRef(),
                            operator.fingerprint(),
                            operatorTestRefs(draft.document(), operator.operatorRef()),
                            latest.get(new AssetKey(
                                    AssetKind.OPERATOR, operator.operatorRef())))));
            preview.canonicalLibrary().builtInFunctions().stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(OperatorLibrary.BuiltInFunction::name))
                    .forEach(function -> assets.add(assetGate(
                            draft,
                            preview,
                            AssetKind.FUNCTION,
                            function.name(),
                            BuiltInFunctionContract.callableFingerprint(function),
                            functionTestRefs(draft.document(), function.name()),
                            latest.get(new AssetKey(
                                    AssetKind.FUNCTION, function.name())))));
        }

        Set<GateReason> reasons = new LinkedHashSet<>();
        if (!preview.importable() || preview.canonicalLibrary() == null) {
            reasons.add(GateReason.DRAFT_NOT_IMPORTABLE);
        }
        assets.stream().flatMap(asset -> asset.reasons().stream()).forEach(reasons::add);
        int satisfied = (int) assets.stream()
                .filter(asset -> asset.status() == GateStatus.PASSED)
                .count();
        GateStatus status = reasons.isEmpty()
                && satisfied == assets.size()
                && !assets.isEmpty()
                ? GateStatus.PASSED
                : GateStatus.BLOCKED;
        return new DraftGate(
                DraftGate.SCHEMA_VERSION,
                scope,
                draft.draftId(),
                draft.revision(),
                preview.authoringFingerprint(),
                preview.canonicalFingerprint(),
                AuthoringTestEvidenceProtocol.POLICY_VERSION,
                status,
                status == GateStatus.PASSED ? "TEST_EVIDENCED" : "DESIGN_READY",
                assets.size(),
                satisfied,
                List.copyOf(reasons),
                assets,
                clock.instant());
    }

    private AssetGate assetGate(
            AuthoringDraft draft,
            AuthoringCompileResult preview,
            AssetKind kind,
            String assetRef,
            String artifactFingerprint,
            List<String> declaredRefs,
            EvidenceRecord record) {
        int requiredCases = requiredCases(declaredRefs);
        if (record == null) {
            return new AssetGate(
                    kind,
                    assetRef,
                    GateStatus.BLOCKED,
                    List.of(GateReason.MISSING_EVIDENCE),
                    "",
                    "",
                    FreshnessStatus.STALE,
                    requiredCases,
                    0,
                    0,
                    "");
        }
        EvidenceView view = evaluate(record, draft, preview, artifactFingerprint);
        Set<GateReason> reasons = EnumSet.noneOf(GateReason.class);
        if (view.freshness() == FreshnessStatus.STALE) {
            reasons.add(GateReason.EVIDENCE_STALE);
        }
        if (!record.passed()) {
            reasons.add(GateReason.LATEST_RUN_FAILED);
        }
        if (record.totalCases() < requiredCases) {
            reasons.add(GateReason.INSUFFICIENT_CASE_COVERAGE);
        }
        if (record.coverage().assertionCount() < requiredAssertions(kind, record)) {
            reasons.add(GateReason.INSUFFICIENT_ASSERTION_COVERAGE);
        }
        if (kind == AssetKind.FUNCTION && !"BOUND".equals(record.bindingStatus())) {
            reasons.add(GateReason.FUNCTION_NOT_BOUND);
        }
        return new AssetGate(
                kind,
                assetRef,
                reasons.isEmpty() ? GateStatus.PASSED : GateStatus.BLOCKED,
                List.copyOf(reasons),
                record.runId(),
                record.materialFingerprint(),
                view.freshness(),
                requiredCases,
                record.totalCases(),
                record.coverage().assertionCount(),
                record.proofMode());
    }

    private EvidenceView evaluate(EvidenceRecord record, AuthoringScope scope) {
        AuthoringDraft draft = drafts.find(scope, record.draftId());
        AuthoringCompileResult preview = drafts.preview(
                scope, draft.draftId(), draft.revision());
        String currentArtifact = currentArtifactFingerprint(preview, record);
        return evaluate(record, draft, preview, currentArtifact);
    }

    private EvidenceView evaluate(
            EvidenceRecord record,
            AuthoringDraft draft,
            AuthoringCompileResult preview,
            String currentArtifactFingerprint) {
        Set<StaleReason> reasons = EnumSet.noneOf(StaleReason.class);
        if (!AuthoringTestEvidenceProtocol.POLICY_VERSION.equals(record.policyVersion())) {
            reasons.add(StaleReason.POLICY_VERSION_CHANGED);
        }
        if (!record.authoringFingerprint().equals(preview.authoringFingerprint())) {
            reasons.add(StaleReason.AUTHORING_FINGERPRINT_CHANGED);
        }
        if (!record.canonicalFingerprint().equals(preview.canonicalFingerprint())) {
            reasons.add(StaleReason.CANONICAL_FINGERPRINT_CHANGED);
        }
        if (currentArtifactFingerprint.isBlank()) {
            reasons.add(StaleReason.ASSET_MISSING);
        } else if (!record.artifactFingerprint().equals(currentArtifactFingerprint)) {
            reasons.add(StaleReason.ARTIFACT_FINGERPRINT_CHANGED);
        }
        if (record.assetKind() == AssetKind.FUNCTION) {
            TrustedCoreFunctionRuntime.Resolution runtime =
                    TrustedCoreFunctionRuntime.resolve(record.assetRef());
            if (!record.runtimeFingerprint().equals(runtime.runtimeFingerprint())) {
                reasons.add(StaleReason.RUNTIME_FINGERPRINT_CHANGED);
            }
            if (!record.executionProfile().equals(functionWorker.executionProfile())) {
                reasons.add(StaleReason.EXECUTION_PROFILE_CHANGED);
            }
        }
        FreshnessStatus freshness = reasons.isEmpty()
                ? FreshnessStatus.CURRENT : FreshnessStatus.STALE;
        return new EvidenceView(
                EvidenceView.SCHEMA_VERSION,
                record,
                "VERIFIED",
                freshness,
                List.copyOf(reasons),
                draft.revision(),
                preview.authoringFingerprint(),
                preview.canonicalFingerprint(),
                clock.instant());
    }

    private String currentArtifactFingerprint(
            AuthoringCompileResult preview,
            EvidenceRecord record) {
        if (preview.canonicalLibrary() == null) {
            return "";
        }
        if (record.assetKind() == AssetKind.OPERATOR) {
            return preview.canonicalLibrary().operators().stream()
                    .filter(Objects::nonNull)
                    .filter(value -> value.operatorRef().equals(record.assetRef()))
                    .findFirst()
                    .map(OperatorDefinition::fingerprint)
                    .orElse("");
        }
        return preview.canonicalLibrary().builtInFunctions().stream()
                .filter(Objects::nonNull)
                .filter(value -> value.name().equals(record.assetRef()))
                .findFirst()
                .map(BuiltInFunctionContract::callableFingerprint)
                .orElse("");
    }

    private EvidenceRecord create(
            EvidenceRecord candidate,
            String draftId,
            long revision) {
        try {
            return evidence.create(candidate);
        } catch (AuthoringTestEvidenceIntegrityException invalid) {
            throw integrityFailure(draftId, revision);
        } catch (RuntimeException unavailable) {
            throw storeUnavailable(draftId, revision);
        }
    }

    private AuthoringDraft exactDraft(
            AuthoringScope scope,
            String draftId,
            long revision) {
        AuthoringDraft draft = drafts.find(scope, draftId);
        if (draft.revision() != revision) {
            throw failure(
                    412,
                    "RG.AUTHORING.DRAFT_REVISION_STALE",
                    "The authoring draft changed before test evidence could be persisted.",
                    draftId,
                    revision,
                    "/authoringRevision");
        }
        return draft;
    }

    private static AuthoringTestScope requireScope(
            AuthoringTestPrincipal identity) {
        try {
            return Objects.requireNonNull(identity, "identity").requireScope();
        } catch (AuthoringLifecycleException known) {
            throw known;
        } catch (RuntimeException invalid) {
            throw failure(
                    403,
                    "RG.AUTHORING.TEST_EVIDENCE_SCOPE_REQUIRED",
                    "A complete authenticated enterprise scope is required.",
                    "",
                    0,
                    "/scope");
        }
    }

    private static AuthoringScope authoringScope(AuthoringTestScope scope) {
        return new AuthoringScope(
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region());
    }

    private static List<String> operatorTestRefs(
            VisualLibraryAuthoringDocument document,
            String operatorRef) {
        VisualLibraryAuthoringDocument.OperatorAuthoring operator =
                document.operators().get(operatorRef);
        return operator == null ? List.of() : refs(operator.tests());
    }

    private static List<String> functionTestRefs(
            VisualLibraryAuthoringDocument document,
            String functionRef) {
        VisualLibraryAuthoringDocument.FunctionAuthoring function =
                document.functions().get(functionRef);
        return function == null ? List.of() : refs(function.tests());
    }

    private static List<String> refs(
            List<VisualLibraryAuthoringDocument.TestReference> references) {
        return references == null ? List.of() : references.stream()
                .filter(Objects::nonNull)
                .map(VisualLibraryAuthoringDocument.TestReference::ref)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static int requiredCases(List<String> declaredRefs) {
        return Math.max(1, declaredRefs == null ? 0 : declaredRefs.size());
    }

    private static int requiredAssertions(
            AssetKind kind,
            EvidenceRecord record) {
        if (kind == AssetKind.FUNCTION) {
            return record.totalCases();
        }
        return record.coverage().mockedOutputCount() > 0 ? 1 : 0;
    }

    private static List<String> diagnosticCodes(
            List<VisualDiagnostic> diagnostics) {
        return diagnosticCodes(diagnostics, List.of());
    }

    private static List<String> diagnosticCodes(
            List<VisualDiagnostic> first,
            List<VisualDiagnostic> second) {
        Set<String> codes = new LinkedHashSet<>();
        collectDiagnosticCodes(codes, first);
        collectDiagnosticCodes(codes, second);
        return List.copyOf(codes);
    }

    private static void collectDiagnosticCodes(
            Set<String> codes,
            List<VisualDiagnostic> diagnostics) {
        if (diagnostics == null) {
            return;
        }
        diagnostics.stream()
                .filter(Objects::nonNull)
                .map(VisualDiagnostic::code)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .forEach(codes::add);
    }

    private static EvidenceRecord newer(
            EvidenceRecord left,
            EvidenceRecord right) {
        int time = left.executedAt().compareTo(right.executedAt());
        if (time != 0) {
            return time > 0 ? left : right;
        }
        return left.runId().compareTo(right.runId()) >= 0 ? left : right;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static AuthoringLifecycleException integrityFailure(
            String draftId,
            long revision) {
        return failure(
                503,
                "RG.AUTHORING.TEST_EVIDENCE_INTEGRITY_INVALID",
                "Signed test evidence failed immutable-content verification.",
                draftId,
                revision,
                "/tests/evidence");
    }

    private static AuthoringLifecycleException storeUnavailable(
            String draftId,
            long revision) {
        return failure(
                503,
                "RG.AUTHORING.TEST_EVIDENCE_STORE_UNAVAILABLE",
                "Signed test evidence could not be committed or verified.",
                draftId,
                revision,
                "/tests/evidence");
    }

    private static AuthoringLifecycleException failure(
            int status,
            String code,
            String message,
            String draftId,
            long revision,
            String path) {
        AuthoringDiagnostic diagnostic = AuthoringDiagnostic.compiler(
                "ERROR", code, message, path, -1, Map.of());
        return new AuthoringLifecycleException(AuthoringProblem.of(
                code,
                message,
                status,
                normalized(draftId),
                Math.max(0, revision),
                List.of(diagnostic)));
    }

    private record AssetKey(AssetKind kind, String ref) {
    }
}
