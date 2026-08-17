package com.leanowtech.bloge.gateway.visualadapter.reference;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CorrectnessDefinitionRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCorrectnessDefinition;
import com.leanowtech.bloge.gateway.visual.reference.Page;
import com.leanowtech.bloge.gateway.visual.reference.ReferenceCandidate;
import com.leanowtech.bloge.gateway.visual.reference.ReferenceCandidateProvider;
import com.leanowtech.bloge.gateway.visual.reference.ReferenceCandidateService;
import com.leanowtech.bloge.gateway.visual.reference.ReferenceScope;
import com.leanowtech.bloge.gateway.visual.reference.ReferenceSearchException;
import com.leanowtech.bloge.gateway.visual.reference.ResolveRequest;
import com.leanowtech.bloge.gateway.visual.reference.ResolveResult;
import com.leanowtech.bloge.gateway.visual.reference.SearchRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Bounded second-level selector for Correctness Definitions bound to one exact target. */
@RestController
@RequestMapping("/api/visual/correctness-targets")
public class CorrectnessDefinitionCandidateController {
    private final CorrectnessDefinitionRepository definitions;
    private final IntegrationRequestAuthenticator authenticator;

    public CorrectnessDefinitionCandidateController(
            ObjectProvider<CorrectnessDefinitionRepository> definitions,
            IntegrationRequestAuthenticator authenticator) {
        this.definitions = definitions.getIfAvailable();
        this.authenticator = authenticator;
    }

    @GetMapping
    public ResponseEntity<Page> targets(@RequestParam(defaultValue = "") String targetKind,
                        @RequestParam(defaultValue = "") String query,
                        @RequestParam(defaultValue = "") String cursor,
                        @RequestParam(defaultValue = "20") int limit,
                        @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext context = authenticate(headers);
        requireRepository(context);
        try {
            ReferenceScope scope = scope(context);
            List<ReferenceCandidate> targetCandidates = definitions
                    .listHeads(enterpriseScope(context), SearchRequest.MAX_LIMIT).stream()
                    .map(stored -> targetCandidate(stored, scope)).toList();
            ReferenceCandidateProvider provider = new ReferenceCandidateProvider() {
                @Override
                public ProviderSnapshot snapshot(SearchRequest ignored) {
                    long generation = Integer.toUnsignedLong(targetCandidates.stream()
                            .map(ReferenceCandidate::fingerprint).sorted().toList().hashCode());
                    return new ProviderSnapshot(generation, targetCandidates);
                }

                @Override
                public ProviderResolution resolve(ResolveRequest ignored) {
                    return new ProviderResolution(ResolveResult.Status.NOT_FOUND, null);
                }
            };
            return noStore(new ReferenceCandidateService(provider).search(new SearchRequest(
                    targetKind, query, cursor, limit, scope)));
        } catch (ReferenceSearchException failure) {
            throw new IntegrationProblemException(IntegrationProblem.retryableConflict(
                    failure.code().wireCode(), failure.getMessage(), context.correlationId(), Map.of()));
        } catch (IllegalArgumentException failure) {
            throw invalid(context, failure);
        } catch (RuntimeException failure) {
            throw unavailable(context);
        }
    }

    @GetMapping("/{kind}/{id}/definitions")
    public ResponseEntity<Page> definitions(@PathVariable String kind,
                                            @PathVariable String id,
                                            @RequestParam String targetFingerprint,
                                            @RequestHeader HttpHeaders headers) {
        IntegrationRequestContext context = authenticate(headers);
        requireRepository(context);
        try {
            TargetKind targetKind = TargetKind.valueOf(kind.trim().toUpperCase(java.util.Locale.ROOT));
            ReferenceScope scope = scope(context);
            List<ReferenceCandidate> candidates = definitions.findHeadCandidatesByTarget(
                            enterpriseScope(context), targetKind, id, targetFingerprint)
                    .stream().map(stored -> candidate(stored, scope)).toList();
            SearchRequest fingerprintInput = new SearchRequest(
                    "CORRECTNESS_DEFINITION", id + "\u0000" + targetFingerprint, scope);
            long generation = Integer.toUnsignedLong(candidates.stream()
                    .map(ReferenceCandidate::fingerprint).sorted().toList().hashCode());
            return noStore(new Page(Page.SCHEMA_VERSION, candidates, "",
                    ReferenceCandidateService.queryFingerprint(fingerprintInput), generation));
        } catch (IllegalArgumentException failure) {
            throw invalid(context, failure);
        } catch (RuntimeException failure) {
            throw unavailable(context);
        }
    }

    private static ReferenceCandidate targetCandidate(StoredCorrectnessDefinition stored,
                                                      ReferenceScope scope) {
        CorrectnessDefinition definition = stored.definition();
        return new ReferenceCandidate(
                ReferenceCandidate.SCHEMA_VERSION,
                definition.target().kind().name(),
                definition.target().id(),
                definition.title(),
                definition.businessIntent(),
                definition.target().revision(),
                definition.target().fingerprint(),
                "resource-gateway://correctness-targets",
                scope,
                ReferenceCandidate.Lifecycle.ACTIVE,
                new ReferenceCandidate.Owner(
                        definition.owner().id(), definition.owner().displayName()),
                List.of(definition.riskLevel().name(), definition.definitionId()),
                ReferenceCandidate.Compatibility.COMPATIBLE,
                "");
    }

    private static ReferenceCandidate candidate(StoredCorrectnessDefinition stored,
                                                ReferenceScope scope) {
        CorrectnessDefinition definition = stored.definition();
        ReferenceCandidate.Lifecycle lifecycle = switch (definition.lifecycle()) {
            case DRAFT -> ReferenceCandidate.Lifecycle.DRAFT;
            case REVIEWED, ACTIVE -> ReferenceCandidate.Lifecycle.ACTIVE;
            case SUPERSEDED -> ReferenceCandidate.Lifecycle.SUPERSEDED;
        };
        return new ReferenceCandidate(
                ReferenceCandidate.SCHEMA_VERSION,
                "CORRECTNESS_DEFINITION",
                definition.definitionId(),
                definition.title(),
                definition.businessIntent(),
                definition.revision(),
                stored.definitionFingerprint(),
                "resource-gateway://correctness-definitions",
                scope,
                lifecycle,
                new ReferenceCandidate.Owner(
                        definition.owner().id(), definition.owner().displayName()),
                List.of(definition.riskLevel().name(), definition.target().kind().name(),
                        definition.target().id()),
                ReferenceCandidate.Compatibility.COMPATIBLE,
                "");
    }

    private static EnterpriseScope enterpriseScope(IntegrationRequestContext context) {
        return new EnterpriseScope(context.tenantId(), context.organizationId(), context.projectId(),
                context.environmentId(), context.region());
    }

    private IntegrationRequestContext authenticate(HttpHeaders headers) {
        IntegrationRequestContext context = authenticator.authenticate(
                headers, IntegrationOperation.REFERENCE_CANDIDATE_READ);
        context.requireComplete();
        return context;
    }

    private void requireRepository(IntegrationRequestContext context) {
        if (definitions == null || !definitions.supportsHeadListing()) {
            throw unavailable(context);
        }
    }

    private static IntegrationProblemException invalid(IntegrationRequestContext context,
                                                       IllegalArgumentException failure) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                "RG.REFERENCE.REQUEST_INVALID", failure.getMessage(),
                context.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(IntegrationRequestContext context) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                "RG.REFERENCE.CATALOG_UNAVAILABLE",
                "The Correctness Definition catalog is not installed in this deployment.",
                context.correlationId(), Map.of()));
    }

    private static ReferenceScope scope(IntegrationRequestContext context) {
        return new ReferenceScope(context.tenantId(), context.organizationId(), context.projectId(),
                context.environmentId(), context.region());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }
}
