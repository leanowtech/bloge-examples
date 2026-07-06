package com.leanowtech.bloge.gateway.gateway;

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
 * REST API for running schema-gated, table-driven resource graph contract tests.
 */
@RestController
@RequestMapping("/api/gateway/graphs/contracts/tests")
public class GatewayGraphContractTestController {

    private final GatewayGraphContractTestService testService;
    private final GatewayGraphContractTestSuiteRepository suiteRepository;

    public GatewayGraphContractTestController(GatewayGraphContractTestService testService,
                                              GatewayGraphContractTestSuiteRepository suiteRepository) {
        this.testService = testService;
        this.suiteRepository = suiteRepository;
    }

    /**
     * Runs one contract-test suite.
     *
     * @param request suite request
     * @return suite result
     */
    @PostMapping("/run")
    public GatewayGraphContractTestSuiteResult run(@RequestBody GatewayGraphContractTestSuiteRequest request) {
        return testService.run(request);
    }

    /**
     * @return stored suite catalog
     */
    @GetMapping("/suites")
    public GatewayGraphContractTestSuiteCatalogResponse suites() {
        List<GatewayGraphContractTestSuiteSummary> summaries = suiteRepository.all().stream()
                .map(GatewayGraphContractTestSuiteSummary::from)
                .toList();
        return new GatewayGraphContractTestSuiteCatalogResponse(summaries);
    }

    /**
     * @param suiteId suite id
     * @return stored suite, or 404
     */
    @GetMapping("/suites/{suiteId}")
    public ResponseEntity<GatewayGraphContractTestSuite> suite(@PathVariable String suiteId) {
        return suiteRepository.find(suiteId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Saves or replaces a stored suite.
     *
     * @param suiteId suite id from path
     * @param suite suite body
     * @return stored suite
     */
    @PutMapping("/suites/{suiteId}")
    public GatewayGraphContractTestSuite saveSuite(@PathVariable String suiteId,
                                                   @RequestBody GatewayGraphContractTestSuite suite) {
        GatewayGraphContractTestSuite safeSuite = suite == null
                ? new GatewayGraphContractTestSuite(suiteId, suiteId, "", List.of(),
                        new GatewayGraphContractTestSuiteRequest("", List.of()),
                        GatewayGraphContractTestCoveragePolicy.none())
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
    public ResponseEntity<GatewayGraphContractTestSuiteResult> runSuite(@PathVariable String suiteId) {
        return suiteRepository.find(suiteId)
                .map(testService::run)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Runs every stored suite and aggregates the evidence counters.
     *
     * @return batch result
     */
    @PostMapping("/suites/run-all")
    public GatewayGraphContractTestBatchResult runAllSuites() {
        return testService.runAll(suiteRepository.all());
    }
}
