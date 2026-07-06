package com.leanowtech.bloge.gateway.gateway;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for running schema-gated, table-driven resource graph contract tests.
 */
@RestController
@RequestMapping("/api/gateway/graphs/contracts/tests")
public class GatewayGraphContractTestController {

    private final GatewayGraphContractTestService testService;

    public GatewayGraphContractTestController(GatewayGraphContractTestService testService) {
        this.testService = testService;
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
}
