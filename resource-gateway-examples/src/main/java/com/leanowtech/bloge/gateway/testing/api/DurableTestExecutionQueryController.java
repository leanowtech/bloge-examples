package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Profile-isolated HTTP boundary for reading durable test execution control state.
 *
 * <p>Authentication completes before the run id enters the service. The response is a
 * payload-free checkpoint projection and cannot be used as a worker dispatch or authorization
 * token.</p>
 */
@RestController
@Profile("!production & (test | staging)")
@RequestMapping("/api/testing/durable-executions")
public final class DurableTestExecutionQueryController {

    private final DurableTestExecutionQueryService service;
    private final IntegrationRequestAuthenticator authenticator;

    /**
     * Creates the authenticated durable execution read transport.
     *
     * @param service scoped payload-free read service
     * @param authenticator verified workload identity boundary
     */
    public DurableTestExecutionQueryController(
            DurableTestExecutionQueryService service,
            IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    /**
     * Returns one integrity-verified durable execution view.
     *
     * @param runId path-bound durable run identity
     * @param headers workload credential and explicit testing purpose
     * @return payload-free lifecycle, fence, dependency, and boundary identities
     */
    @GetMapping("/{runId}")
    public DurableTestExecutionQueryResponse find(
            @PathVariable String runId,
            @RequestHeader HttpHeaders headers) {
        return service.find(runId, authenticator.authenticate(
                headers, IntegrationOperation.TEST_DURABLE_EXECUTION_READ));
    }
}
