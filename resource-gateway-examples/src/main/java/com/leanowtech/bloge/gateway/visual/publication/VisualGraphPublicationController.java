package com.leanowtech.bloge.gateway.visual.publication;

import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftDependencyReport;
import com.leanowtech.bloge.gateway.visual.golden.VisualGraphGoldenCase;
import com.leanowtech.bloge.gateway.visual.golden.VisualGraphGoldenCaseRepository;
import com.leanowtech.bloge.gateway.visual.golden.VisualGraphGoldenCertification;
import com.leanowtech.bloge.gateway.visual.golden.VisualGraphGoldenCertificationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualStoredDraftRunRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public API for immutable visual graph publications.
 */
@RestController
@RequestMapping("/api/visual/publications")
public class VisualGraphPublicationController {

    private final VisualGraphPublicationRepository repository;
    private final VisualGraphRunService runner;
    private final VisualGraphRunRepository runRepository;
    private final VisualOperatorCatalog catalog;
    private final VisualGraphGoldenCaseRepository goldenCases;
    private final VisualGraphGoldenCertificationRepository goldenCertifications;

    /**
     * @param repository publication repository
     * @param runner publication runner
     * @param runRepository run history repository
     * @param catalog current visual operator catalog used for target-environment import review
     * @param goldenCases golden regression case repository used for portable publication snapshots
     * @param goldenCertifications latest golden certification repository used for portable publication snapshots
     */
    @Autowired
    public VisualGraphPublicationController(VisualGraphPublicationRepository repository,
                                            VisualGraphRunService runner,
                                            VisualGraphRunRepository runRepository,
                                            VisualOperatorCatalog catalog,
                                            VisualGraphGoldenCaseRepository goldenCases,
                                            VisualGraphGoldenCertificationRepository goldenCertifications) {
        this.repository = repository;
        this.runner = runner;
        this.runRepository = runRepository;
        this.catalog = catalog;
        this.goldenCases = goldenCases;
        this.goldenCertifications = goldenCertifications;
    }

    /**
     * Backward-compatible constructor for tests that do not need golden snapshot export/import.
     */
    VisualGraphPublicationController(VisualGraphPublicationRepository repository,
                                     VisualGraphRunService runner,
                                     VisualGraphRunRepository runRepository,
                                     VisualOperatorCatalog catalog) {
        this(repository, runner, runRepository, catalog, null, null);
    }

    /**
     * Backward-compatible constructor for tests that do not need target-environment dependency review.
     */
    VisualGraphPublicationController(VisualGraphPublicationRepository repository,
                                     VisualGraphRunService runner,
                                     VisualGraphRunRepository runRepository) {
        this(repository, runner, runRepository, null, null, null);
    }

    /**
     * @param tenantId tenant scope, empty for all
     * @param namespace namespace scope, empty for all
     * @param environment environment scope, empty for all
     * @param artifactKind optional publication artifact kind, such as EXECUTABLE or DESIGN
     * @return publications in scope
     */
    @GetMapping
    public Collection<VisualGraphPublication> list(@RequestParam(defaultValue = "") String tenantId,
                                                   @RequestParam(defaultValue = "") String namespace,
                                                   @RequestParam(defaultValue = "") String environment,
                                                   @RequestParam(defaultValue = "") String artifactKind) {
        return repository.all().stream()
                .filter(publication -> matchesPublicationScope(publication, tenantId, namespace, environment))
                .filter(publication -> matchesArtifactKind(publication.artifactKind(), artifactKind))
                .toList();
    }

    /**
     * Backward-compatible direct-call helper for tests and non-Spring callers.
     */
    public Collection<VisualGraphPublication> list() {
        return list("", "", "", "");
    }

    /**
     * Backward-compatible direct-call helper for tests and non-Spring callers.
     */
    public Collection<VisualGraphPublication> list(String tenantId, String namespace, String environment) {
        return list(tenantId, namespace, environment, "");
    }

    /**
     * Lists lightweight publication summaries for asset indexes.
     *
     * @param tenantId tenant scope, empty for all
     * @param namespace namespace scope, empty for all
     * @param environment environment scope, empty for all
     * @param artifactKind optional publication artifact kind, such as EXECUTABLE or DESIGN
     * @return publication summaries newest first in scope
     */
    @GetMapping("/summaries")
    public List<VisualGraphPublicationSummary> summaries(@RequestParam(defaultValue = "") String tenantId,
                                                         @RequestParam(defaultValue = "") String namespace,
                                                         @RequestParam(defaultValue = "") String environment,
                                                         @RequestParam(defaultValue = "") String artifactKind) {
        return repository.all().stream()
                .map(VisualGraphPublicationSummary::from)
                .filter(summary -> matchesPublicationSummaryScope(summary, tenantId, namespace, environment))
                .filter(summary -> matchesArtifactKind(summary.artifactKind(), artifactKind))
                .toList();
    }

    /**
     * Backward-compatible direct-call helper for tests and non-Spring callers.
     */
    public List<VisualGraphPublicationSummary> summaries() {
        return summaries("", "", "", "");
    }

    /**
     * Backward-compatible direct-call helper for tests and non-Spring callers.
     */
    public List<VisualGraphPublicationSummary> summaries(String tenantId, String namespace, String environment) {
        return summaries(tenantId, namespace, environment, "");
    }

    private static boolean matchesPublicationScope(VisualGraphPublication publication,
                                                   String tenantId,
                                                   String namespace,
                                                   String environment) {
        return publication != null
                && matchesScope(publication.tenantId(), tenantId)
                && matchesScope(publication.namespace(), namespace)
                && matchesScope(publication.environment(), environment);
    }

    private static boolean matchesPublicationSummaryScope(VisualGraphPublicationSummary summary,
                                                          String tenantId,
                                                          String namespace,
                                                          String environment) {
        return summary != null
                && matchesScope(summary.tenantId(), tenantId)
                && matchesScope(summary.namespace(), namespace)
                && matchesScope(summary.environment(), environment);
    }

    private static boolean matchesScope(String actual, String expected) {
        return expected == null || expected.isBlank() || String.valueOf(actual).equals(expected);
    }

    private static boolean matchesArtifactKind(String actual, String expected) {
        return expected == null || expected.isBlank()
                || String.valueOf(actual).equalsIgnoreCase(expected.trim());
    }

    /**
     * Gets a publication.
     *
     * @param publicationId publication id
     * @return publication when present
     */
    @GetMapping("/{publicationId}")
    public ResponseEntity<VisualGraphPublication> get(@PathVariable String publicationId) {
        return repository.find(publicationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Exports an immutable publication as a portable bundle.
     *
     * @param publicationId publication id
     * @return publication export bundle when present
     */
    @GetMapping("/{publicationId}/export")
    public ResponseEntity<VisualGraphPublicationExportBundle> export(@PathVariable String publicationId) {
        return repository.find(publicationId)
                .map(publication -> ResponseEntity.ok(VisualGraphPublicationExportBundle.from(
                        publication,
                        goldenCasesForExport(publication.publicationId()),
                        goldenCertificationForExport(publication.publicationId()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Validates a portable immutable publication bundle against the target environment without storing it.
     *
     * @param bundle portable publication bundle
     * @return target-environment preflight result
     */
    @PostMapping("/validate-bundle")
    public ResponseEntity<VisualGraphPublicationImportResult> validateBundle(
            @RequestBody(required = false) VisualGraphPublicationExportBundle bundle) {
        if (bundle != null && !VisualGraphPublicationExportBundle.SCHEMA_VERSION.equals(bundle.schemaVersion())) {
            return ResponseEntity.badRequest().body(VisualGraphPublicationImportResult.rejected(
                    bundle, bundle.publication(), publicationBundleSchemaVersionDiagnostics(bundle)));
        }
        if (bundle != null && !bundle.bundleFingerprintVerified()) {
            return ResponseEntity.badRequest().body(VisualGraphPublicationImportResult.rejected(
                    bundle, bundle.publication(), publicationBundleFingerprintDiagnostics(bundle)));
        }
        VisualGraphPublication publication = bundle == null ? null : bundle.publication();
        if (publication == null) {
            return ResponseEntity.badRequest().body(VisualGraphPublicationImportResult.rejected(
                    bundle, publicationBundleSnapshotMissingDiagnostics()));
        }
        if (!VisualGraphPublication.SCHEMA_VERSION.equals(publication.schemaVersion())) {
            return ResponseEntity.badRequest().body(VisualGraphPublicationImportResult.rejected(
                    bundle, publication, publicationSchemaVersionDiagnostics(publication)));
        }
        List<VisualDiagnostic> goldenSnapshotDiagnostics = goldenSnapshotDiagnostics(bundle, publication);
        if (!goldenSnapshotDiagnostics.isEmpty()) {
            return ResponseEntity.badRequest().body(VisualGraphPublicationImportResult.rejected(
                    bundle, publication, goldenSnapshotDiagnostics));
        }
        GraphDraftDependencyReport targetDependencyReport = targetDependencyReport(publication);
        if (!publication.publicationId().isBlank() && repository.find(publication.publicationId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(VisualGraphPublicationImportResult.rejected(
                    bundle, publication, targetDependencyReport, publicationAlreadyExistsDiagnostics(publication)));
        }
        return ResponseEntity.ok(VisualGraphPublicationImportResult.previewed(
                bundle, publication, targetDependencyReport));
    }

    /**
     * Imports a portable immutable publication bundle into the target repository.
     *
     * @param bundle portable publication bundle
     * @return target-environment import result
     */
    @PostMapping("/import-bundle")
    public ResponseEntity<VisualGraphPublicationImportResult> importBundle(
            @RequestBody(required = false) VisualGraphPublicationExportBundle bundle) {
        if (bundle != null && !VisualGraphPublicationExportBundle.SCHEMA_VERSION.equals(bundle.schemaVersion())) {
            return ResponseEntity.badRequest().body(VisualGraphPublicationImportResult.rejected(
                    bundle, bundle.publication(), publicationBundleSchemaVersionDiagnostics(bundle)));
        }
        if (bundle != null && !bundle.bundleFingerprintVerified()) {
            return ResponseEntity.badRequest().body(VisualGraphPublicationImportResult.rejected(
                    bundle, bundle.publication(), publicationBundleFingerprintDiagnostics(bundle)));
        }
        VisualGraphPublication publication = bundle == null ? null : bundle.publication();
        if (publication == null) {
            return ResponseEntity.badRequest().body(VisualGraphPublicationImportResult.rejected(
                    bundle, publicationBundleSnapshotMissingDiagnostics()));
        }
        if (!VisualGraphPublication.SCHEMA_VERSION.equals(publication.schemaVersion())) {
            return ResponseEntity.badRequest().body(VisualGraphPublicationImportResult.rejected(
                    bundle, publication, publicationSchemaVersionDiagnostics(publication)));
        }
        List<VisualDiagnostic> goldenSnapshotDiagnostics = goldenSnapshotDiagnostics(bundle, publication);
        if (!goldenSnapshotDiagnostics.isEmpty()) {
            return ResponseEntity.badRequest().body(VisualGraphPublicationImportResult.rejected(
                    bundle, publication, goldenSnapshotDiagnostics));
        }
        GraphDraftDependencyReport targetDependencyReport = targetDependencyReport(publication);
        if (!publication.publicationId().isBlank() && repository.find(publication.publicationId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(VisualGraphPublicationImportResult.rejected(
                    bundle, publication, targetDependencyReport, publicationAlreadyExistsDiagnostics(publication)));
        }
        try {
            VisualGraphPublication stored = repository.create(publication);
            importGoldenSnapshots(bundle);
            return ResponseEntity.status(HttpStatus.CREATED).body(VisualGraphPublicationImportResult.imported(
                    bundle, stored, targetDependencyReport(stored)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(VisualGraphPublicationImportResult.rejected(
                    bundle, publication, targetDependencyReport, publicationAlreadyExistsDiagnostics(publication)));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(VisualGraphPublicationImportResult.rejected(
                    bundle, publication, targetDependencyReport, publicationImportPersistenceFailureDiagnostics(
                            bundle, publication, e)));
        }
    }

    /**
     * Gets the publish-time dependency report frozen with a publication.
     *
     * @param publicationId publication id
     * @return frozen dependency report when the publication exists
     */
    @GetMapping("/{publicationId}/dependencies")
    public ResponseEntity<GraphDraftDependencyReport> dependencies(@PathVariable String publicationId) {
        return repository.find(publicationId)
                .map(publication -> ResponseEntity.ok(publication.dependencyReport()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Runs a published immutable visual graph artifact.
     *
     * @param publicationId publication id
     * @param request run request
     * @return run response when publication exists
     */
    @PostMapping("/{publicationId}/run")
    public ResponseEntity<VisualGraphRunResponse> run(@PathVariable String publicationId,
                                                      @RequestBody VisualStoredDraftRunRequest request) {
        return repository.find(publicationId)
                .map(publication -> {
                    VisualGraphRunResponse response = runner.run(publication, request.context(), request.outputNode());
                    VisualGraphRunRecord record = runRepository.create(VisualGraphRunRecord.publication(
                            publication, request.context(), response));
                    return ResponseEntity.ok(response.withRunId(record.runId()));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static List<VisualDiagnostic> publicationBundleSchemaVersionDiagnostics(
            VisualGraphPublicationExportBundle bundle) {
        String actual = bundle == null ? "" : bundle.schemaVersion();
        return List.of(VisualDiagnostic.error(
                "visual.publication.bundle.schemaVersionUnsupported",
                "Visual graph publication import bundle schemaVersion '%s' is not supported; expected '%s'."
                        .formatted(actual, VisualGraphPublicationExportBundle.SCHEMA_VERSION),
                "/schemaVersion",
                Map.of("actual", actual, "expected", VisualGraphPublicationExportBundle.SCHEMA_VERSION)
        ));
    }

    private static List<VisualDiagnostic> publicationBundleSnapshotMissingDiagnostics() {
        return List.of(VisualDiagnostic.error(
                "visual.publication.bundle.snapshotMissing",
                "Visual graph publication import bundle must include a publication snapshot.",
                "/publication"
        ));
    }

    private static List<VisualDiagnostic> publicationBundleFingerprintDiagnostics(
            VisualGraphPublicationExportBundle bundle) {
        String actual = bundle == null ? "" : bundle.bundleFingerprint();
        String expected = bundle == null ? "" : bundle.computedBundleFingerprint();
        return List.of(VisualDiagnostic.error(
                "visual.publication.bundle.fingerprintMismatch",
                ("Visual graph publication import bundle fingerprint '%s' does not match the submitted bundle "
                        + "material; expected '%s'.").formatted(actual, expected),
                "/bundleFingerprint",
                Map.of("actual", actual, "expected", expected)
        ));
    }

    private static List<VisualDiagnostic> publicationSchemaVersionDiagnostics(VisualGraphPublication publication) {
        String actual = publication == null ? "" : publication.schemaVersion();
        return List.of(VisualDiagnostic.error(
                "visual.publication.schemaVersionUnsupported",
                "Visual graph publication schemaVersion '%s' is not supported; expected '%s'."
                        .formatted(actual, VisualGraphPublication.SCHEMA_VERSION),
                "/publication/schemaVersion",
                Map.of("actual", actual, "expected", VisualGraphPublication.SCHEMA_VERSION)
        ));
    }

    private static List<VisualDiagnostic> goldenSnapshotDiagnostics(VisualGraphPublicationExportBundle bundle,
                                                                    VisualGraphPublication publication) {
        if (bundle == null || publication == null) {
            return List.of();
        }
        String expectedPublicationId = publication.publicationId();
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        List<VisualGraphGoldenCase> cases = bundle.goldenCases();
        VisualGraphGoldenCertification certification = bundle.goldenCertification();
        if (expectedPublicationId.isBlank() && (!cases.isEmpty() || certification != null)) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.publication.goldenSnapshotPublicationMissing",
                    "Golden snapshots require a stable publicationId before they can be imported.",
                    "/publication/publicationId"
            ));
        }
        for (int i = 0; i < cases.size(); i++) {
            VisualGraphGoldenCase goldenCase = cases.get(i);
            if (!expectedPublicationId.equals(goldenCase.publicationId())) {
                diagnostics.add(VisualDiagnostic.error(
                        "visual.publication.goldenCasePublicationMismatch",
                        "Golden case '%s' belongs to publication '%s', not imported publication '%s'."
                                .formatted(goldenCase.caseId(), goldenCase.publicationId(), expectedPublicationId),
                        "/goldenCases/%d/publicationId".formatted(i),
                        Map.of("caseId", goldenCase.caseId(),
                                "actual", goldenCase.publicationId(),
                                "expected", expectedPublicationId)
                ));
            }
        }
        if (certification != null && !expectedPublicationId.equals(certification.publicationId())) {
            diagnostics.add(VisualDiagnostic.error(
                    "visual.publication.goldenCertificationPublicationMismatch",
                    "Golden certification belongs to publication '%s', not imported publication '%s'."
                            .formatted(certification.publicationId(), expectedPublicationId),
                    "/goldenCertification/publicationId",
                    Map.of("actual", certification.publicationId(), "expected", expectedPublicationId)
            ));
        }
        return diagnostics;
    }

    private static List<VisualDiagnostic> publicationAlreadyExistsDiagnostics(VisualGraphPublication publication) {
        String publicationId = publication == null ? "" : publication.publicationId();
        return List.of(VisualDiagnostic.error(
                "visual.publication.importConflict",
                "Visual graph publication '%s' already exists in the target repository.".formatted(publicationId),
                "/publication/publicationId",
                Map.of("publicationId", publicationId)
        ));
    }

    private static List<VisualDiagnostic> publicationImportPersistenceFailureDiagnostics(
            VisualGraphPublicationExportBundle bundle,
            VisualGraphPublication publication,
            RuntimeException failure) {
        String publicationId = publication == null ? "" : publication.publicationId();
        String exceptionMessage = failure.getMessage() == null ? "" : failure.getMessage();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("publicationId", publicationId);
        metadata.put("draftId", publication == null ? "" : publication.draftId());
        metadata.put("draftRevision", publication == null ? 0 : publication.draftRevision());
        metadata.put("artifactKind", publication == null ? "" : publication.artifactKind());
        metadata.put("sourceBundleFingerprint", bundle == null ? "" : bundle.bundleFingerprint());
        metadata.put("exceptionType", failure.getClass().getSimpleName());
        metadata.put("exceptionMessage", exceptionMessage);
        return List.of(VisualDiagnostic.error(
                "visual.publication.importPersistenceFailed",
                "Visual graph publication '%s' could not be imported into the target repository: %s"
                        .formatted(publicationId, exceptionMessage),
                "/publication",
                metadata
        ));
    }

    private GraphDraftDependencyReport targetDependencyReport(VisualGraphPublication publication) {
        return publication == null ? GraphDraftDependencyReport.empty()
                : GraphDraftDependencyReport.from(publication.draft(), catalog);
    }

    private List<VisualGraphGoldenCase> goldenCasesForExport(String publicationId) {
        if (goldenCases == null) {
            return List.of();
        }
        return goldenCases.findByPublicationId(publicationId).stream()
                .sorted(Comparator.comparing(VisualGraphGoldenCase::caseId))
                .toList();
    }

    private VisualGraphGoldenCertification goldenCertificationForExport(String publicationId) {
        if (goldenCertifications == null) {
            return null;
        }
        return goldenCertifications.find(publicationId).orElse(null);
    }

    private void importGoldenSnapshots(VisualGraphPublicationExportBundle bundle) {
        if (bundle == null) {
            return;
        }
        if (goldenCases != null) {
            bundle.goldenCases().forEach(goldenCases::save);
        }
        if (goldenCertifications != null && bundle.goldenCertification() != null) {
            goldenCertifications.save(bundle.goldenCertification());
        }
    }
}
