package com.leanowtech.bloge.gateway.visual.testing;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API for schema-gated operator mock/table contract tests.
 */
@RestController
@RequestMapping("/api/visual/operators/tests")
public class VisualOperatorContractTestController {

    private final VisualOperatorContractTestService testService;
    private final VisualOperatorContractTestSuiteRepository suiteRepository;

    /**
     * @param testService operator contract-test service
     * @param suiteRepository stored suite repository
     */
    public VisualOperatorContractTestController(VisualOperatorContractTestService testService,
                                                VisualOperatorContractTestSuiteRepository suiteRepository) {
        this.testService = testService;
        this.suiteRepository = suiteRepository;
    }

    /**
     * Runs an operator table-test suite.
     *
     * @param request suite request
     * @return suite result
     */
    @PostMapping("/run")
    public VisualOperatorContractTestSuiteResult run(@RequestBody VisualOperatorContractTestSuiteRequest request) {
        return testService.run(request);
    }

    /**
     * Generates an editable operator table-test suite from schemas.
     *
     * @param request draft request
     * @return generated suite draft
     */
    @PostMapping("/draft")
    public VisualOperatorContractTestDraftResponse draft(
            @RequestBody VisualOperatorContractTestDraftRequest request) {
        return testService.draft(request);
    }

    /**
     * @return stored suite catalog
     */
    @GetMapping("/suites")
    public VisualOperatorContractTestSuiteCatalogResponse suites() {
        List<VisualOperatorContractTestSuiteSummary> summaries = suiteRepository.all().stream()
                .map(VisualOperatorContractTestSuiteSummary::from)
                .toList();
        return new VisualOperatorContractTestSuiteCatalogResponse(summaries);
    }

    /**
     * @param suiteId suite id
     * @return stored suite, or 404
     */
    @GetMapping("/suites/{suiteId}")
    public ResponseEntity<VisualOperatorContractTestSuite> suite(@PathVariable String suiteId) {
        return suiteRepository.find(suiteId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Saves or replaces a stored suite.
     *
     * @param suiteId suite id
     * @param suite suite body
     * @return stored suite
     */
    @PutMapping("/suites/{suiteId}")
    public VisualOperatorContractTestSuite saveSuite(@PathVariable String suiteId,
                                                     @RequestBody VisualOperatorContractTestSuite suite) {
        VisualOperatorContractTestSuite safeSuite = suite == null
                ? new VisualOperatorContractTestSuite(suiteId, suiteId, "", List.of(),
                        new VisualOperatorContractTestSuiteRequest("", List.of()))
                : suite.withSuiteId(suiteId);
        return suiteRepository.save(safeSuite);
    }

    /**
     * Runs one stored suite.
     *
     * @param suiteId suite id
     * @return suite result, or 404
     */
    @PostMapping("/suites/{suiteId}/run")
    public ResponseEntity<VisualOperatorContractTestSuiteResult> runSuite(@PathVariable String suiteId) {
        return suiteRepository.find(suiteId)
                .map(testService::run)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Runs all stored suites and aggregates evidence counters.
     *
     * @return batch result
     */
    @PostMapping("/suites/run-all")
    public VisualOperatorContractTestBatchResult runAllSuites() {
        return testService.runAll(suiteRepository.all());
    }
}
