package com.leanowtech.bloge.gateway.visual.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublication;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;

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
import java.util.List;

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
     * Saves or replaces a golden case.
     *
     * @param testCase golden case body
     * @return stored case when publication exists
     */
    @PostMapping
    public ResponseEntity<VisualGraphGoldenCase> save(@RequestBody VisualGraphGoldenCase testCase) {
        if (testCase == null || testCase.publicationId().isBlank()
                || publicationRepository.find(testCase.publicationId()).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(repository.save(testCase));
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
                .map(publication -> ResponseEntity.ok(runSuite(publication)))
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
     * Runs the publication golden suite and stores the latest certification.
     *
     * @param publicationId publication id
     * @return stored certification
     */
    @PostMapping("/publications/{publicationId}/certify")
    public ResponseEntity<VisualGraphGoldenCertification> certify(@PathVariable String publicationId) {
        return publicationRepository.find(publicationId)
                .map(publication -> {
                    VisualGraphGoldenSuiteRunResult suite = runSuite(publication);
                    return ResponseEntity.ok(certificationRepository.save(
                            VisualGraphGoldenCertification.from(suite)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<VisualGraphGoldenCaseRunResult> runCase(VisualGraphGoldenCase testCase) {
        return publicationRepository.find(testCase.publicationId())
                .map(publication -> ResponseEntity.ok(runSingleCase(testCase, publication)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private VisualGraphGoldenSuiteRunResult runSuite(VisualGraphPublication publication) {
        List<VisualGraphGoldenCase> testCases = List.copyOf(repository.findByPublicationId(publication.publicationId()));
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
        if (failedCases > 0) {
            diagnostics.add(VisualDiagnostic.error("visual.golden.suiteFailed",
                    "Golden suite for publication '%s' failed %d of %d case(s)."
                            .formatted(publication.publicationId(), failedCases, results.size()),
                    "/results"));
        }
        return VisualGraphGoldenSuiteRunResult.from(publication.publicationId(), results, diagnostics);
    }

    private VisualGraphGoldenCaseRunResult runSingleCase(VisualGraphGoldenCase testCase,
                                                         VisualGraphPublication publication) {
        VisualGraphRunResponse response = runner.run(publication, testCase.context(), testCase.outputNode());
        VisualGraphRunRecord record = runRepository.create(VisualGraphRunRecord.publication(
                publication, testCase.context(), response));
        VisualGraphRunResponse recorded = response.withRunId(record.runId());
        List<VisualDiagnostic> diagnostics = new ArrayList<>();
        if (!recorded.success()) {
            diagnostics.add(VisualDiagnostic.error("visual.golden.runFailed",
                    "Golden case '%s' did not complete successfully.".formatted(testCase.caseId()),
                    "/run"));
        }
        if (!jsonEquals(testCase.expectedOutput(), recorded.output())) {
            diagnostics.add(VisualDiagnostic.error("visual.golden.outputMismatch",
                    "Golden case '%s' expected output does not match actual output.".formatted(testCase.caseId()),
                    "/expectedOutput"));
        }
        boolean passed = recorded.success() && diagnostics.stream().noneMatch(VisualDiagnostic::error);
        return new VisualGraphGoldenCaseRunResult(passed, testCase, recorded, diagnostics);
    }

    private boolean jsonEquals(Object expected, Object actual) {
        JsonNode expectedNode = objectMapper.valueToTree(expected);
        JsonNode actualNode = objectMapper.valueToTree(actual);
        return expectedNode.equals(actualNode);
    }
}
