package com.leanowtech.bloge.gateway.visual.golden;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Public API for golden regression cases bound to immutable visual graph publications.
 */
@RestController
@RequestMapping("/api/visual/golden-cases")
public class VisualGraphGoldenCaseController {

    private final VisualGraphGoldenCaseRepository repository;
    private final VisualGraphPublicationRepository publicationRepository;
    private final VisualGraphRunService runner;
    private final VisualGraphRunRepository runRepository;
    private final VisualGraphGoldenCertificationRepository certificationRepository;
    private final ObjectMapper objectMapper;

    /**
     * @param repository golden case repository
     * @param publicationRepository publication repository
     * @param runner visual graph runner
     * @param runRepository run history repository
     * @param certificationRepository golden certification repository
     * @param objectMapper JSON mapper
     */
    public VisualGraphGoldenCaseController(VisualGraphGoldenCaseRepository repository,
                                           VisualGraphPublicationRepository publicationRepository,
                                           VisualGraphRunService runner,
                                           VisualGraphRunRepository runRepository,
                                           VisualGraphGoldenCertificationRepository certificationRepository,
                                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.publicationRepository = publicationRepository;
        this.runner = runner;
        this.runRepository = runRepository;
        this.certificationRepository = certificationRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Lists golden cases.
     *
     * @param publicationId optional publication filter
     * @return golden cases
     */
    @GetMapping
    public Collection<VisualGraphGoldenCase> list(@RequestParam(required = false) String publicationId) {
        return repository.findByPublicationId(publicationId);
    }

    /**
     * Gets one golden case.
     *
     * @param caseId case id
     * @return golden case when present
     */
    @GetMapping("/{caseId}")
    public ResponseEntity<VisualGraphGoldenCase> get(@PathVariable String caseId) {
        return repository.find(caseId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Deletes one golden case.
     *
     * @param caseId case id
     * @return no content when removed
     */
    @DeleteMapping("/{caseId}")
    public ResponseEntity<?> delete(@PathVariable String caseId) {
        Optional<VisualGraphGoldenCase> current = repository.find(caseId);
        if (current.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            return repository.delete(caseId)
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.notFound().build();
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new VisualValidationResult(false, List.of(goldenCasePersistenceFailureDiagnostic(
                            "visual.golden.deletePersistenceFailed",
                            "Golden case '%s' could not be deleted.".formatted(caseId),
                            "/caseId",
                            "DELETE",
                            current.get(),
                            ex))));
        }
    }

    /**
     * Saves or replaces a golden case.
     *
     * @param testCase golden case body
     * @return stored case when publication exists
     */
    @PostMapping
    public ResponseEntity<?> save(@RequestBody VisualGraphGoldenCase testCase) {
        if (testCase == null || testCase.publicationId().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        Optional<VisualGraphPublication> publication = publicationRepository.find(testCase.publicationId());
        if (publication.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        VisualValidationResult validation = validateGoldenCase(testCase, publication.get());
        if (!validation.valid()) {
            return ResponseEntity.badRequest().body(validation);
        }
        try {
            return ResponseEntity.ok(repository.save(testCase));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new VisualValidationResult(false, List.of(goldenCasePersistenceFailureDiagnostic(
                            "visual.golden.savePersistenceFailed",
                            "Golden case for publication '%s' could not be stored."
                                    .formatted(testCase.publicationId()),
                            "/case",
                            "SAVE",
                            testCase,
                            ex))));
        }
    }

    /**
     * Runs one golden case against its frozen publication.
     *
     * @param caseId case id
     * @return run result
     */
    @PostMapping("/{caseId}/run")
    public ResponseEntity<VisualGraphGoldenCaseRunResult> run(@PathVariable String caseId) {
        return repository.find(caseId)
                .map(this::runCase)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Runs all golden cases for one immutable publication.
     *
     * @param publicationId publication id
     * @return suite run result
     */
    @PostMapping("/publications/{publicationId}/run")
    public ResponseEntity<VisualGraphGoldenSuiteRunResult> runPublication(@PathVariable String publicationId) {
        return publicationRepository.find(publicationId)
                .map(publication -> responseForSuite(runSuite(publication)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Reads the latest golden certification for one publication.
     *
     * @param publicationId publication id
     * @return certification when present
     */
    @GetMapping("/publications/{publicationId}/certification")
    public ResponseEntity<VisualGraphGoldenCertification> certification(@PathVariable String publicationId) {
        if (publicationRepository.find(publicationId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return certificationRepository.find(publicationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Reads promotion-readiness status for one publication's latest golden certification.
     *
     * @param publicationId publication id
     * @return current certification status
     */
    @GetMapping("/publications/{publicationId}/certification/status")
    public ResponseEntity<VisualGraphGoldenCertificationStatus> certificationStatus(@PathVariable String publicationId) {
        if (publicationRepository.find(publicationId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<VisualGraphGoldenCase> testCases = goldenCasesFor(publicationId);
        return ResponseEntity.ok(VisualGraphGoldenCertificationStatus.from(
                publicationId,
                testCases,
                certificationRepository.find(publicationId).orElse(null),
                caseSetFingerprint(testCases)
        ));
    }

    /**
     * Runs the publication golden suite and stores the latest certification.
     *
     * @param publicationId publication id
     * @return stored certification
     */
    @PostMapping("/publications/{publicationId}/certify")
    public ResponseEntity<VisualGraphGoldenCertification> certify(@PathVariable String publicationId) {
        return publicationRepository.find(publicationId)
                .map(publication -> {
                    List<VisualGraphGoldenCase> testCases = goldenCasesFor(publication.publicationId());
                    VisualGraphGoldenSuiteRunResult suite = runSuite(publication, testCases);
                    VisualGraphGoldenCertification certification =
                            VisualGraphGoldenCertification.from(suite, caseSetFingerprint(testCases));
                    if (hasDiagnostic(suite.diagnostics(), "visual.golden.runHistoryPersistenceFailed")) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(certification);
                    }
                    try {
                        return ResponseEntity.ok(certificationRepository.save(certification));
                    } catch (RuntimeException ex) {
                        return ResponseEntity.status(HttpStatus.CONFLICT).body(withDiagnostic(certification,
                                goldenCertificationPersistenceFailureDiagnostic(certification, ex)));
                    }
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<VisualGraphGoldenCaseRunResult> runCase(VisualGraphGoldenCase testCase) {
        return publicationRepository.find(testCase.publicationId())
                .map(publication -> responseForSingleRun(runSingleCase(testCase, publication)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<VisualGraphGoldenCaseRunResult> responseForSingleRun(
            VisualGraphGoldenCaseRunResult result) {
        return ResponseEntity.status(hasDiagnostic(result.diagnostics(),
                "visual.golden.runHistoryPersistenceFailed") ? HttpStatus.CONFLICT : HttpStatus.OK).body(result);
    }

    private static ResponseEntity<VisualGraphGoldenSuiteRunResult> responseForSuite(
            VisualGraphGoldenSuiteRunResult result) {
        return ResponseEntity.status(hasDiagnostic(result.diagnostics(),
                "visual.golden.runHistoryPersistenceFailed") ? HttpStatus.CONFLICT : HttpStatus.OK).body(result);
    }

    private VisualGraphGoldenSuiteRunResult runSuite(VisualGraphPublication publication) {
        return runSuite(publication, goldenCasesFor(publication.publicationId()));
    }

    private VisualGraphGoldenSuiteRunResult runSuite(VisualGraphPublication publication,
                                                     List<VisualGraphGoldenCase> testCases) {
        if (testCases.isEmpty()) {
            return VisualGraphGoldenSuiteRunResult.from(publication.publicationId(), List.of(), List.of(
                    VisualDiagnostic.error("visual.golden.noCases",
                            "Publication '%s' has no golden cases.".formatted(publication.publicationId()),
                            "/publicationId")
            ));
        }

        List<VisualGraphGoldenCaseRunResult> results = testCases.stream()
                .map(testCase -> runSingleCase(testCase, publication))
                .toList();
        int failedCases = (int) results.stream()
                .filter(result -> !result.passed())
                .count();
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        long runHistoryFailures = results.stream()
                .filter(result -> hasDiagnostic(result.diagnostics(), "visual.golden.runHistoryPersistenceFailed"))
                .count();
        if (runHistoryFailures > 0) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("publicationId", publication.publicationId());
            metadata.put("mutationAction", "RUN_HISTORY");
            metadata.put("failedRunHistoryRecords", runHistoryFailures);
            metadata.put("totalCases", results.size());
            diagnostics.add(VisualDiagnostic.error("visual.golden.runHistoryPersistenceFailed",
                    "Golden suite for publication '%s' could not store %d run history record(s)."
                            .formatted(publication.publicationId(), runHistoryFailures),
                    "/results",
                    metadata));
        }
        if (failedCases > 0) {
            diagnostics.add(VisualDiagnostic.error("visual.golden.suiteFailed",
                    "Golden suite for publication '%s' failed %d of %d case(s)."
                            .formatted(publication.publicationId(), failedCases, results.size()),
                    "/results"));
        }
        return VisualGraphGoldenSuiteRunResult.from(publication.publicationId(), results, diagnostics);
    }

    private VisualValidationResult validateGoldenCase(VisualGraphGoldenCase testCase,
                                                      VisualGraphPublication publication) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (!VisualGraphGoldenCase.SCHEMA_VERSION.equals(testCase.schemaVersion())) {
            diagnostics.add(VisualDiagnostic.error("visual.golden.schemaVersion.unsupported",
                    "Golden case schemaVersion '%s' is unsupported; visual authoring supports '%s'."
                            .formatted(testCase.schemaVersion(), VisualGraphGoldenCase.SCHEMA_VERSION),
                    "/schemaVersion"));
        }
        GraphDraft draft = publication.draft();
        if (draft != null) {
            validateGoldenOutputNode(testCase, draft, diagnostics);
            diagnostics.addAll(VisualSchemaValidator.validateValue(draft.inputSchema(), testCase.context(),
                    "/context"));
        }
        validateGoldenAssertions(testCase, diagnostics);
        return new VisualValidationResult(false, diagnostics);
    }

    private static void validateGoldenOutputNode(VisualGraphGoldenCase testCase,
                                                 GraphDraft draft,
                                                 List<VisualDiagnostic> diagnostics) {
        if (testCase.outputNode().isBlank()) {
            return;
        }
        boolean known = draft.nodes().stream()
                .anyMatch(node -> node.id().equals(testCase.outputNode()));
        if (known) {
            return;
        }
        diagnostics.add(VisualDiagnostic.error("visual.golden.outputNode.unknown",
                "Golden case outputNode '%s' does not exist in publication '%s'."
                        .formatted(testCase.outputNode(), testCase.publicationId()),
                "/outputNode"));
    }

    private void validateGoldenAssertions(VisualGraphGoldenCase testCase,
                                          List<VisualDiagnostic> diagnostics) {
        for (int i = 0; i < testCase.assertions().size(); i++) {
            VisualGraphGoldenAssertion assertion = testCase.assertions().get(i);
            String assertionPath = "/assertions/%d".formatted(i);
            if (assertion.mode() == VisualGraphGoldenAssertion.Mode.OUTPUT_MATCHES_SCHEMA) {
                assertionSchemaEnvelope(assertion.expectedValue(), assertionPath + "/expectedValue", diagnostics);
            } else if (assertion.mode() == VisualGraphGoldenAssertion.Mode.PATH_APPROX_EQUALS) {
                if (!validJsonPointer(assertion.path())) {
                    diagnostics.add(VisualDiagnostic.error("visual.golden.assertionInvalidPath",
                            "Golden case '%s' assertion path '%s' is not a JSON Pointer."
                                    .formatted(testCase.caseId(), assertion.path()),
                            assertionPath + "/path"));
                }
                approximateExpectation(assertion.expectedValue(), assertionPath + "/expectedValue", diagnostics);
            } else if (assertion.mode() != VisualGraphGoldenAssertion.Mode.OUTPUT_EQUALS
                    && !validJsonPointer(assertion.path())) {
                diagnostics.add(VisualDiagnostic.error("visual.golden.assertionInvalidPath",
                        "Golden case '%s' assertion path '%s' is not a JSON Pointer."
                                .formatted(testCase.caseId(), assertion.path()),
                        assertionPath + "/path"));
            }
        }
    }

    private List<VisualGraphGoldenCase> goldenCasesFor(String publicationId) {
        return List.copyOf(repository.findByPublicationId(publicationId));
    }

    private VisualGraphGoldenCaseRunResult runSingleCase(VisualGraphGoldenCase testCase,
                                                         VisualGraphPublication publication) {
        VisualGraphRunResponse response = runner.run(publication, testCase.context(), testCase.outputNode());
        VisualGraphRunResponse recorded = response;
        try {
            VisualGraphRunRecord record = runRepository.create(VisualGraphRunRecord.publication(
                    publication, testCase.context(), response));
            recorded = response.withRunId(record.runId());
        } catch (RuntimeException ex) {
            return new VisualGraphGoldenCaseRunResult(false, testCase, recorded,
                    List.of(goldenRunHistoryPersistenceFailureDiagnostic(testCase, publication, ex)));
        }
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (!recorded.success()) {
            diagnostics.add(VisualDiagnostic.error("visual.golden.runFailed",
                    "Golden case '%s' did not complete successfully.".formatted(testCase.caseId()),
                    "/run"));
        }
        if (testCase.assertions().isEmpty() && !jsonEquals(testCase.expectedOutput(), recorded.output())) {
            diagnostics.add(VisualDiagnostic.error("visual.golden.outputMismatch",
                    "Golden case '%s' expected output does not match actual output.".formatted(testCase.caseId()),
                    "/expectedOutput"));
        }
        if (!testCase.assertions().isEmpty()) {
            diagnostics.addAll(assertionDiagnostics(testCase, recorded.output()));
        }
        boolean passed = recorded.success() && diagnostics.stream().noneMatch(VisualDiagnostic::error);
        return new VisualGraphGoldenCaseRunResult(passed, testCase, recorded, diagnostics);
    }

    private static VisualDiagnostic goldenCasePersistenceFailureDiagnostic(String code,
                                                                          String message,
                                                                          String target,
                                                                          String mutationAction,
                                                                          VisualGraphGoldenCase testCase,
                                                                          RuntimeException ex) {
        Map<String, Object> metadata = goldenFailureMetadata(mutationAction, ex);
        metadata.put("publicationId", testCase == null ? "" : testCase.publicationId());
        metadata.put("caseId", testCase == null ? "" : testCase.caseId());
        return VisualDiagnostic.error(code, message, target, metadata);
    }

    private static VisualDiagnostic goldenRunHistoryPersistenceFailureDiagnostic(VisualGraphGoldenCase testCase,
                                                                                VisualGraphPublication publication,
                                                                                RuntimeException ex) {
        Map<String, Object> metadata = goldenFailureMetadata("RUN_HISTORY", ex);
        metadata.put("publicationId", publication == null ? "" : publication.publicationId());
        metadata.put("caseId", testCase == null ? "" : testCase.caseId());
        metadata.put("graphName", publication == null ? "" : publication.graphName());
        metadata.put("outputNode", testCase == null ? "" : testCase.outputNode());
        return VisualDiagnostic.error(
                "visual.golden.runHistoryPersistenceFailed",
                "Golden case '%s' run could not be stored in run history."
                        .formatted(testCase == null ? "" : testCase.caseId()),
                "/run",
                metadata);
    }

    private static VisualDiagnostic goldenCertificationPersistenceFailureDiagnostic(
            VisualGraphGoldenCertification certification,
            RuntimeException ex) {
        Map<String, Object> metadata = goldenFailureMetadata("CERTIFY", ex);
        metadata.put("publicationId", certification == null ? "" : certification.publicationId());
        metadata.put("caseSetFingerprint", certification == null ? "" : certification.caseSetFingerprint());
        metadata.put("runIds", certification == null ? List.of() : certification.runIds());
        return VisualDiagnostic.error(
                "visual.golden.certificationPersistenceFailed",
                "Golden certification for publication '%s' could not be stored."
                        .formatted(certification == null ? "" : certification.publicationId()),
                "/certification",
                metadata);
    }

    private static Map<String, Object> goldenFailureMetadata(String mutationAction, RuntimeException ex) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mutationAction", mutationAction == null ? "" : mutationAction);
        metadata.put("exceptionType", ex == null ? "" : ex.getClass().getSimpleName());
        if (ex != null && ex.getMessage() != null && !ex.getMessage().isBlank()) {
            metadata.put("exceptionMessage", ex.getMessage());
        }
        return metadata;
    }

    private static VisualGraphGoldenCertification withDiagnostic(VisualGraphGoldenCertification certification,
                                                                 VisualDiagnostic diagnostic) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>(certification.diagnostics());
        diagnostics.add(diagnostic);
        return new VisualGraphGoldenCertification(
                certification.schemaVersion(),
                certification.publicationId(),
                false,
                certification.totalCases(),
                certification.passedCases(),
                certification.failedCases(),
                certification.runIds(),
                certification.caseSetFingerprint(),
                diagnostics,
                certification.certifiedAt()
        );
    }

    private static boolean hasDiagnostic(List<VisualDiagnostic> diagnostics, String code) {
        return diagnostics != null
                && diagnostics.stream().anyMatch(diagnostic -> diagnostic != null && diagnostic.code().equals(code));
    }

    private List<VisualDiagnostic> assertionDiagnostics(VisualGraphGoldenCase testCase, Object actualOutput) {
        JsonNode actualNode = objectMapper.valueToTree(actualOutput);
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        for (int i = 0; i < testCase.assertions().size(); i++) {
            VisualGraphGoldenAssertion assertion = testCase.assertions().get(i);
            String target = "/assertions/%d".formatted(i);
            diagnostics.addAll(assertionDiagnostics(testCase.caseId(), assertion, actualOutput, actualNode, target));
        }
        return diagnostics;
    }

    private List<VisualDiagnostic> assertionDiagnostics(String caseId,
                                                        VisualGraphGoldenAssertion assertion,
                                                        Object actualOutput,
                                                        JsonNode actualNode,
                                                        String target) {
        if (assertion.mode() == VisualGraphGoldenAssertion.Mode.OUTPUT_EQUALS) {
            return jsonEquals(assertion.expectedValue(), actualOutput)
                    ? List.of()
                    : List.of(assertionFailed(caseId, "output equals expected value", target + "/expectedValue"));
        }
        if (assertion.mode() == VisualGraphGoldenAssertion.Mode.OUTPUT_MATCHES_SCHEMA) {
            return schemaAssertionDiagnostics(caseId, assertion, actualOutput, target + "/expectedValue");
        }
        if (!validJsonPointer(assertion.path())) {
            return List.of(VisualDiagnostic.error("visual.golden.assertionInvalidPath",
                    "Golden case '%s' assertion path '%s' is not a JSON Pointer."
                            .formatted(caseId, assertion.path()),
                    target + "/path"));
        }

        JsonNode actualValue = actualNode.at(assertion.path());
        return switch (assertion.mode()) {
            case PATH_EQUALS -> jsonEquals(assertion.expectedValue(), actualValue)
                    ? List.of()
                    : List.of(assertionFailed(caseId,
                            "path '%s' equals expected value".formatted(assertion.path()),
                            target + "/expectedValue"));
            case PATH_APPROX_EQUALS -> approximateAssertionDiagnostics(caseId, assertion, actualValue, target);
            case PATH_EXISTS -> actualValue.isMissingNode()
                    ? List.of(assertionFailed(caseId,
                            "path '%s' exists".formatted(assertion.path()),
                            target + "/path"))
                    : List.of();
            case PATH_ABSENT -> actualValue.isMissingNode()
                    ? List.of()
                    : List.of(assertionFailed(caseId,
                            "path '%s' is absent".formatted(assertion.path()),
                            target + "/path"));
            case OUTPUT_EQUALS, OUTPUT_MATCHES_SCHEMA -> List.of();
        };
    }

    private List<VisualDiagnostic> approximateAssertionDiagnostics(String caseId,
                                                                   VisualGraphGoldenAssertion assertion,
                                                                   JsonNode actualValue,
                                                                   String target) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        Optional<ApproximateExpectation> expectation = approximateExpectation(
                assertion.expectedValue(), target + "/expectedValue", diagnostics);
        if (diagnostics.stream().anyMatch(VisualDiagnostic::error) || expectation.isEmpty()) {
            return diagnostics;
        }
        if (actualValue.isMissingNode()) {
            return List.of(assertionFailed(caseId,
                    "path '%s' exists and is numeric".formatted(assertion.path()),
                    target + "/path"));
        }
        if (!actualValue.isNumber()) {
            return List.of(assertionFailed(caseId,
                    "path '%s' is numeric".formatted(assertion.path()),
                    target + "/path"));
        }
        double actual = actualValue.doubleValue();
        ApproximateExpectation expected = expectation.get();
        double allowedDelta = expected.allowedDelta();
        double actualDelta = Math.abs(actual - expected.value());
        return actualDelta <= allowedDelta
                ? List.of()
                : List.of(assertionFailed(caseId,
                        "path '%s' approximately equals %s within tolerance %s"
                                .formatted(assertion.path(), expected.value(), allowedDelta),
                        target + "/expectedValue"));
    }

    private Optional<ApproximateExpectation> approximateExpectation(Object value,
                                                                   String target,
                                                                   List<VisualDiagnostic> diagnostics) {
        int initialErrors = (int) diagnostics.stream().filter(VisualDiagnostic::error).count();
        if (!(value instanceof Map<?, ?> rawMap)) {
            diagnostics.add(VisualDiagnostic.error("visual.golden.assertionToleranceInvalid",
                    "Approximate assertion expectedValue must be an object with numeric value and tolerance fields.",
                    target));
            return Optional.empty();
        }
        Map<String, Object> expected = stringKeyMap(rawMap);
        Optional<Double> expectedValue = finiteNumber(expected.get("value"));
        if (expectedValue.isEmpty()) {
            diagnostics.add(VisualDiagnostic.error("visual.golden.assertionToleranceInvalid",
                    "Approximate assertion expectedValue.value must be a finite number.",
                    target + "/value"));
        }
        double tolerance = optionalNonNegativeFiniteNumber(expected.get("tolerance"), "tolerance",
                target + "/tolerance", diagnostics);
        double relativeTolerance = optionalNonNegativeFiniteNumber(expected.get("relativeTolerance"),
                "relativeTolerance", target + "/relativeTolerance", diagnostics);
        if (tolerance <= 0.0 && relativeTolerance <= 0.0) {
            diagnostics.add(VisualDiagnostic.error("visual.golden.assertionToleranceInvalid",
                    "Approximate assertion must declare a positive tolerance or relativeTolerance.",
                    target));
        }
        int finalErrors = (int) diagnostics.stream().filter(VisualDiagnostic::error).count();
        if (finalErrors > initialErrors || expectedValue.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ApproximateExpectation(expectedValue.get(), tolerance, relativeTolerance));
    }

    private static Optional<Double> finiteNumber(Object value) {
        if (!(value instanceof Number number)) {
            return Optional.empty();
        }
        double parsed = number.doubleValue();
        return Double.isFinite(parsed) ? Optional.of(parsed) : Optional.empty();
    }

    private static double optionalNonNegativeFiniteNumber(Object value,
                                                         String field,
                                                         String target,
                                                         List<VisualDiagnostic> diagnostics) {
        if (value == null) {
            return 0.0;
        }
        Optional<Double> parsed = finiteNumber(value);
        if (parsed.isEmpty() || parsed.get() < 0.0) {
            diagnostics.add(VisualDiagnostic.error("visual.golden.assertionToleranceInvalid",
                    "Approximate assertion expectedValue.%s must be a non-negative finite number."
                            .formatted(field),
                    target));
            return 0.0;
        }
        return parsed.get();
    }

    private record ApproximateExpectation(
            double value,
            double tolerance,
            double relativeTolerance
    ) {
        double allowedDelta() {
            return Math.max(tolerance, Math.abs(value) * relativeTolerance);
        }
    }

    private List<VisualDiagnostic> schemaAssertionDiagnostics(String caseId,
                                                              VisualGraphGoldenAssertion assertion,
                                                              Object actualOutput,
                                                              String target) {
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        Optional<SchemaEnvelope> schema = assertionSchemaEnvelope(assertion.expectedValue(), target, diagnostics);
        if (diagnostics.stream().anyMatch(VisualDiagnostic::error) || schema.isEmpty()) {
            return diagnostics;
        }
        return VisualSchemaValidator.validateValue(schema.get(), actualOutput, target).stream()
                .map(diagnostic -> VisualDiagnostic.error("visual.golden.schemaAssertionFailed",
                        "Golden case '%s' output does not satisfy assertion schema."
                                .formatted(caseId),
                        diagnostic.target()))
                .toList();
    }

    private Optional<SchemaEnvelope> assertionSchemaEnvelope(Object value,
                                                            String target,
                                                            List<VisualDiagnostic> diagnostics) {
        if (!(value instanceof Map<?, ?> rawMap)) {
            diagnostics.add(VisualDiagnostic.error("visual.golden.assertionSchemaInvalid",
                    "Golden schema assertion expectedValue must be a JSON schema object or SchemaEnvelope.",
                    target));
            return Optional.empty();
        }
        try {
            Map<String, Object> schemaMap = stringKeyMap(rawMap);
            SchemaEnvelope envelope = schemaMap.get("schema") instanceof Map<?, ?>
                    ? objectMapper.convertValue(schemaMap, SchemaEnvelope.class)
                    : new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schemaMap);
            diagnostics.addAll(VisualSchemaValidator.validateEnvelope(envelope, target));
            return Optional.of(envelope);
        } catch (IllegalArgumentException ex) {
            diagnostics.add(VisualDiagnostic.error("visual.golden.assertionSchemaInvalid",
                    "Golden schema assertion expectedValue could not be parsed as a visual schema.",
                    target));
            return Optional.empty();
        }
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> rawMap) {
        Map<String, Object> values = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> values.put(String.valueOf(key), value));
        return values;
    }

    private VisualDiagnostic assertionFailed(String caseId, String expectation, String target) {
        return VisualDiagnostic.error("visual.golden.assertionFailed",
                "Golden case '%s' assertion failed: expected %s.".formatted(caseId, expectation),
                target);
    }

    private boolean jsonEquals(Object expected, Object actual) {
        JsonNode expectedNode = objectMapper.valueToTree(expected);
        JsonNode actualNode = objectMapper.valueToTree(actual);
        return expectedNode.equals(actualNode);
    }

    private boolean jsonEquals(Object expected, JsonNode actual) {
        JsonNode expectedNode = objectMapper.valueToTree(expected);
        return expectedNode.equals(actual);
    }

    private boolean validJsonPointer(String path) {
        return path == null || path.isBlank() || path.startsWith("/");
    }

    private String caseSetFingerprint(Collection<VisualGraphGoldenCase> testCases) {
        if (testCases == null || testCases.isEmpty()) {
            return "";
        }
        List<Map<String, Object>> material = testCases.stream()
                .sorted(Comparator.comparing(VisualGraphGoldenCase::caseId))
                .map(this::caseFingerprintMaterial)
                .toList();
        try {
            String json = objectMapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(material);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize golden case fingerprint material.", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable for golden case fingerprinting.", e);
        }
    }

    private Map<String, Object> caseFingerprintMaterial(VisualGraphGoldenCase testCase) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("caseId", testCase.caseId());
        material.put("publicationId", testCase.publicationId());
        material.put("name", testCase.name());
        material.put("description", testCase.description());
        material.put("outputNode", testCase.outputNode());
        material.put("context", testCase.context());
        material.put("expectedOutput", testCase.expectedOutput());
        material.put("assertions", testCase.assertions());
        material.put("createdAt", testCase.createdAt());
        return material;
    }
}
