package com.leanowtech.bloge.gateway.authoring.scenario;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated non-production control plane for exact Scenario table batches. */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/visual/table-suite-runs")
public final class TableSuiteRunController {

    private final TableSuiteRunService service;
    private final IntegrationRequestAuthenticator authenticator;

    /** Creates the protected transport adapter. */
    public TableSuiteRunController(
            TableSuiteRunService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /** Admits one exact immutable selection. */
    @PostMapping
    public TableSuiteRunBatch submit(
            @RequestBody TableSuiteRunCommand command,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.TEST_SUITE_EXECUTION);
        return service.submit(command, identity);
    }

    /** Restores the complete payload-free batch projection after refresh. */
    @GetMapping("/{batchId}")
    public TableSuiteRunBatch find(
            @PathVariable String batchId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.TEST_SUITE_STABILITY_JOB_READ);
        return service.find(batchId, identity);
    }

    /** Polls only transitions newer than one durable revision. */
    @GetMapping("/{batchId}/events")
    public TableSuiteRunBatch.Delta events(
            @PathVariable String batchId,
            @RequestParam(defaultValue = "0") long afterRevision,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.TEST_SUITE_STABILITY_JOB_READ);
        return service.delta(batchId, afterRevision, identity);
    }

    /** Requests cooperative cancellation. */
    @PostMapping("/{batchId}/cancel")
    public TableSuiteRunBatch cancel(
            @PathVariable String batchId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.TEST_SUITE_STABILITY_JOB_CANCEL);
        return service.cancel(batchId, identity);
    }

    /** Appends physical attempts for only the failed exact rows. */
    @PostMapping("/{batchId}/retry-failed")
    public TableSuiteRunBatch retryFailed(
            @PathVariable String batchId,
            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.TEST_SUITE_EXECUTION);
        return service.retryFailed(batchId, identity);
    }
}
