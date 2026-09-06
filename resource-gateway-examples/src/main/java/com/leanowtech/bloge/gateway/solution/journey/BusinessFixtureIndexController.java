package com.leanowtech.bloge.gateway.solution.journey;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/** Authenticated, no-store transport for Solution-scoped Fixture descriptor metadata. */
@RestController
@ConditionalOnBean(BusinessFixtureIndexService.class)
@RequestMapping("/api/agent-tdd/solutions")
public final class BusinessFixtureIndexController {
    private final IntegrationRequestAuthenticator authenticator;
    private final BusinessFixtureIndexService index;

    /** Creates the metadata-only control-panel endpoint. */
    public BusinessFixtureIndexController(
            IntegrationRequestAuthenticator authenticator, BusinessFixtureIndexService index) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.index = Objects.requireNonNull(index, "index");
    }

    /** Returns Feature and Instruction Fixture groups without protected material coordinates. */
    @GetMapping("/{solutionRef}/fixtures")
    public ResponseEntity<List<BusinessFixtureIndexService.CapabilityFixtures>> list(
            @PathVariable String solutionRef, @RequestHeader HttpHeaders headers) {
        var identity = authenticator.authenticate(
                headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(index.listForSolution(solutionRef, identity));
    }
}
