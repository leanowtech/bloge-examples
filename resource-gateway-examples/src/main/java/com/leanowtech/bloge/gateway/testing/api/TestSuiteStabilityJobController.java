package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** Profile-isolated authenticated HTTP adapter for durable suite-stability parent jobs. */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing")
public final class TestSuiteStabilityJobController {

    private final TestSuiteStabilityJobService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * Creates the non-blocking job transport.
     *
     * @param service authenticated queue application boundary
     * @param authenticator verified workload identity boundary
     */
    public TestSuiteStabilityJobController(
            TestSuiteStabilityJobService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /**
     * Admits or exactly replays one durable job and returns immediately with {@code 202}.
     *
     * @param suiteId path-bound immutable suite id
     * @param request exact execution, queue priority, and deadline intent
     * @param headers workload credential and explicit test purpose
     * @return payload-free retained job plus a canonical query location
     */
    @PostMapping("/suites/{suiteId}/stability-jobs")
    public ResponseEntity<TestSuiteStabilityJobSubmitResponse> submit(
            @PathVariable String suiteId,
            @RequestBody TestSuiteStabilityJobSubmitRequest request,
            @RequestHeader HttpHeaders headers) {
        TestSuiteStabilityJobSubmitResponse response = service.submit(
                suiteId, request, authenticator.authenticate(headers,
                        IntegrationOperation.TEST_SUITE_STABILITY_JOB_SUBMIT));
        return ResponseEntity.accepted()
                .location(URI.create("/api/testing/stability-jobs/" + response.job().jobId()))
                .body(response);
    }

    /**
     * Resolves one retained payload-free job lifecycle.
     *
     * @param jobId deterministic queue identity
     * @param headers workload credential and explicit test purpose
     * @return authorized lifecycle projection
     */
    @GetMapping("/stability-jobs/{jobId}")
    public TestSuiteStabilityJobView find(
            @PathVariable String jobId,
            @RequestHeader HttpHeaders headers) {
        return service.find(jobId, authenticator.authenticate(headers,
                IntegrationOperation.TEST_SUITE_STABILITY_JOB_READ));
    }

    /**
     * Requests immediate queued or cooperative running cancellation.
     *
     * @param jobId deterministic queue identity
     * @param request caller-idempotent cancellation command
     * @param headers workload credential and explicit test purpose
     * @return resulting authorized lifecycle projection
     */
    @PostMapping("/stability-jobs/{jobId}/cancellations")
    public TestSuiteStabilityJobView cancel(
            @PathVariable String jobId,
            @RequestBody TestSuiteStabilityJobCancelRequest request,
            @RequestHeader HttpHeaders headers) {
        return service.cancel(jobId, request, authenticator.authenticate(headers,
                IntegrationOperation.TEST_SUITE_STABILITY_JOB_CANCEL));
    }
}
