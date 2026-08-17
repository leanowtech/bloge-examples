package com.leanowtech.bloge.gateway.visualadapter.reference;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.reference.Page;
import com.leanowtech.bloge.gateway.visual.reference.ReferenceCandidateService;
import com.leanowtech.bloge.gateway.visual.reference.ReferenceResolveCommand;
import com.leanowtech.bloge.gateway.visual.reference.ReferenceScope;
import com.leanowtech.bloge.gateway.visual.reference.ReferenceSearchException;
import com.leanowtech.bloge.gateway.visual.reference.ResolveResult;
import com.leanowtech.bloge.gateway.visual.reference.SearchRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Authenticated metadata-only BFF for searchable references and exact re-resolution. */
@RestController
@RequestMapping("/api/visual")
public class ReferenceCandidateController {
    private final ReferenceCandidateService service;
    private final IntegrationRequestAuthenticator authenticator;

    public ReferenceCandidateController(ReferenceCandidateService service,
                                        IntegrationRequestAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @GetMapping("/reference-candidates")
    public ResponseEntity<Page> search(@RequestParam(defaultValue = "") String kind,
                       @RequestParam(defaultValue = "") String query,
                       @RequestParam(defaultValue = "") String cursor,
                       @RequestParam(defaultValue = "20") int limit,
                       @RequestParam(defaultValue = "") String lifecycle,
                       @RequestParam(defaultValue = "") String compatibleWith,
                       @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext context = authenticate(
                headers, IntegrationOperation.REFERENCE_CANDIDATE_READ);
        try {
            return noStore(service.search(new SearchRequest(kind, query, cursor, limit,
                    scope(context), lifecycle, compatibleWith)));
        } catch (ReferenceSearchException failure) {
            throw new IntegrationProblemException(IntegrationProblem.retryableConflict(
                    failure.code().wireCode(), failure.getMessage(), context.correlationId(), Map.of()));
        } catch (IllegalArgumentException failure) {
            throw invalidRequest(context, failure);
        } catch (RuntimeException failure) {
            throw catalogUnavailable(context);
        }
    }

    @PostMapping("/reference-candidates:resolve")
    public ResponseEntity<ResolveResult> resolve(@RequestBody ReferenceResolveCommand command,
                                                 @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext context = authenticate(
                headers, IntegrationOperation.REFERENCE_CANDIDATE_RESOLVE);
        try {
            return noStore(service.resolve(command.toRequest(scope(context))));
        } catch (IllegalArgumentException failure) {
            throw invalidRequest(context, failure);
        } catch (RuntimeException failure) {
            throw catalogUnavailable(context);
        }
    }

    private IntegrationRequestContext authenticate(HttpHeaders headers, IntegrationOperation operation) {
        if (authenticator == null) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.INTEGRATION.AUTHENTICATOR_UNAVAILABLE",
                    "Reference discovery authentication is unavailable.", "", Map.of()));
        }
        IntegrationRequestContext context = authenticator.authenticate(headers, operation);
        context.requireComplete();
        if (context.projectId().isBlank() || context.region().isBlank()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.CONTEXT_REQUIRED",
                    "Project and region are required for reference discovery.",
                    context.correlationId(), Map.of("projectId", "required", "region", "required")));
        }
        return context;
    }

    private static ReferenceScope scope(IntegrationRequestContext context) {
        return new ReferenceScope(context.tenantId(), context.organizationId(), context.projectId(),
                context.environmentId(), context.region());
    }

    private static IntegrationProblemException invalidRequest(IntegrationRequestContext context,
                                                              IllegalArgumentException failure) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                "RG.REFERENCE.REQUEST_INVALID", failure.getMessage(), context.correlationId(), Map.of()));
    }

    private static IntegrationProblemException catalogUnavailable(IntegrationRequestContext context) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                "RG.REFERENCE.CATALOG_UNAVAILABLE",
                "The authorized reference catalog is temporarily unavailable.",
                context.correlationId(), Map.of()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }
}
