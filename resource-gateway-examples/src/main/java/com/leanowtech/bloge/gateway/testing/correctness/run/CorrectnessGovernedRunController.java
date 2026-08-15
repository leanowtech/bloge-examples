package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessApiEnvelope;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Authenticated no-store adapter for governed correctness runs and evidence lookup. */
@RestController
@ConditionalOnBean(CorrectnessRunService.class)
@RequestMapping("/api/visual/correctness-runs")
public final class CorrectnessGovernedRunController {

    private final CorrectnessRunService runs;
    private final IntegrationRequestAuthenticator authenticator;

    public CorrectnessGovernedRunController(
            CorrectnessRunService runs,
            IntegrationRequestAuthenticator authenticator
    ) {
        this.runs = runs;
        this.authenticator = authenticator;
    }

    @PostMapping
    public ResponseEntity<CorrectnessApiEnvelope<CorrectnessRunResponse>> execute(
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) CorrectnessRunRequest request
    ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_RUN_EXECUTE);
        try {
            return noStore(CorrectnessApiEnvelope.of(
                    identity.correlationId(), scope(identity),
                    List.of("CORRECTNESS_RUN_V1"), runs.execute(request, identity)));
        } catch (CorrectnessRunException failure) {
            throw problem(failure, identity);
        }
    }

    @GetMapping("/{suiteRunId}/evidence-companion")
    public ResponseEntity<CorrectnessApiEnvelope<StoredCorrectnessEvidenceCompanion>> evidence(
            @RequestHeader HttpHeaders headers,
            @PathVariable String suiteRunId
    ) {
        IntegrationRequestContext identity = authenticator.authenticate(
                headers, IntegrationOperation.CORRECTNESS_EVIDENCE_READ);
        try {
            return noStore(CorrectnessApiEnvelope.of(
                    identity.correlationId(), scope(identity),
                    List.of("CORRECTNESS_EVIDENCE_COMPANION_V1"),
                    runs.findEvidence(suiteRunId, identity)));
        } catch (CorrectnessRunException failure) {
            throw problem(failure, identity);
        }
    }

    private static <T> ResponseEntity<CorrectnessApiEnvelope<T>> noStore(
            CorrectnessApiEnvelope<T> envelope
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(envelope);
    }

    private static IntegrationProblemException problem(
            CorrectnessRunException failure,
            IntegrationRequestContext identity
    ) {
        return new IntegrationProblemException(new IntegrationProblem(
                "", "urn:bloge:problem:correctness-run", failure.getMessage(),
                failure.status(), failure.code(), failure.retryable(),
                identity.correlationId(), Map.of()));
    }

    private static EnterpriseScope scope(IntegrationRequestContext identity) {
        return new EnterpriseScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region());
    }
}
