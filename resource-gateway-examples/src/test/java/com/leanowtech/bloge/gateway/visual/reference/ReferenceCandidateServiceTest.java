package com.leanowtech.bloge.gateway.visual.reference;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceCandidateServiceTest {
    private static final ReferenceScope APAC = new ReferenceScope("tenant-a", "org-a", "project-a", "prod", "apac");
    private static final ReferenceScope EU = new ReferenceScope("tenant-a", "org-a", "project-a", "prod", "eu");
    private final InMemoryReferenceCandidateProvider provider = new InMemoryReferenceCandidateProvider();
    private final ReferenceCandidateService service = new ReferenceCandidateService(provider);

    @Test
    void exactIdMatchRanksBeforeMoreRelevantDisplayName() {
        ReferenceCandidate exact = candidate("graph", "refund-eligibility", "Other name", ReferenceCandidate.Lifecycle.DEPRECATED, APAC);
        ReferenceCandidate displayMatch = candidate("graph", "refund-v2", "refund-eligibility", ReferenceCandidate.Lifecycle.ACTIVE, APAC);
        provider.add(displayMatch).add(exact);

        Page page = service.search(new SearchRequest("graph", "refund-eligibility", APAC));

        assertThat(page.items()).extracting(ReferenceCandidate::id)
                .containsExactly("refund-eligibility", "refund-v2");
    }

    @Test
    void scopeAndKindFiltersNeverCrossTheRequestedBoundary() {
        provider.add(candidate("graph", "apac-graph", "APAC", ReferenceCandidate.Lifecycle.ACTIVE, APAC))
                .add(candidate("graph", "eu-graph", "EU", ReferenceCandidate.Lifecycle.ACTIVE, EU))
                .add(candidate("operator", "apac-operator", "APAC", ReferenceCandidate.Lifecycle.ACTIVE, APAC));

        Page page = service.search(new SearchRequest("graph", "", APAC));

        assertThat(page.items()).extracting(ReferenceCandidate::id).containsExactly("apac-graph");
    }

    @Test
    void pagesHaveNoDuplicatesAndCoverTheStableResultSet() {
        for (int index = 5; index >= 1; index--) {
            provider.add(candidate("graph", "graph-" + index, "Graph " + index,
                    ReferenceCandidate.Lifecycle.ACTIVE, APAC));
        }

        SearchRequest firstRequest = new SearchRequest("graph", "", "", 2, APAC);
        Page first = service.search(firstRequest);
        Page second = service.search(new SearchRequest("graph", "", first.nextCursor(), 2, APAC));
        Page third = service.search(new SearchRequest("graph", "", second.nextCursor(), 2, APAC));

        List<String> ids = new ArrayList<>();
        ids.addAll(first.items().stream().map(ReferenceCandidate::id).toList());
        ids.addAll(second.items().stream().map(ReferenceCandidate::id).toList());
        ids.addAll(third.items().stream().map(ReferenceCandidate::id).toList());
        assertThat(ids).containsExactly("graph-1", "graph-2", "graph-3", "graph-4", "graph-5");
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(third.hasNext()).isFalse();
    }

    @Test
    void generationChangeMakesAnExistingCursorStale() {
        provider.add(candidate("graph", "graph-1", "one", ReferenceCandidate.Lifecycle.ACTIVE, APAC))
                .add(candidate("graph", "graph-2", "two", ReferenceCandidate.Lifecycle.ACTIVE, APAC))
                .add(candidate("graph", "graph-3", "three", ReferenceCandidate.Lifecycle.ACTIVE, APAC));
        Page first = service.search(new SearchRequest("graph", "", "", 1, APAC));
        provider.add(candidate("graph", "graph-4", "four", ReferenceCandidate.Lifecycle.ACTIVE, APAC));

        assertThatThrownBy(() -> service.search(new SearchRequest("graph", "", first.nextCursor(), 1, APAC)))
                .isInstanceOf(ReferenceSearchException.class)
                .extracting(exception -> ((ReferenceSearchException) exception).code())
                .isEqualTo(ReferenceSearchException.Code.CURSOR_STALE);
    }

    @Test
    void changingQueryOrLimitRejectsTheCursorByFingerprint() {
        provider.add(candidate("graph", "graph-1", "one", ReferenceCandidate.Lifecycle.ACTIVE, APAC))
                .add(candidate("graph", "graph-2", "two", ReferenceCandidate.Lifecycle.ACTIVE, APAC));
        Page first = service.search(new SearchRequest("graph", "", "", 1, APAC));

        assertThatThrownBy(() -> service.search(new SearchRequest("operator", "", first.nextCursor(), 1, APAC)))
                .isInstanceOf(ReferenceSearchException.class)
                .extracting(exception -> ((ReferenceSearchException) exception).code())
                .isEqualTo(ReferenceSearchException.Code.QUERY_FINGERPRINT_MISMATCH);
    }

    @Test
    void exactResolveHasResolvedDriftedNotFoundAndForbiddenSemantics() {
        ReferenceCandidate current = candidate("graph", "refund", "Refund", ReferenceCandidate.Lifecycle.ACTIVE, APAC);
        provider.add(current).forbidResolve("operator", "private");

        assertThat(service.resolve(ResolveRequest.from(current, APAC, "CORRECTNESS")))
                .extracting(ResolveResult::status, ResolveResult::candidate, ResolveResult::errorCode)
                .containsExactly(ResolveResult.Status.RESOLVED, current, "");
        assertThat(service.resolve(new ResolveRequest("graph", "refund", 1, "sha256:old", APAC, "CORRECTNESS")))
                .extracting(ResolveResult::status, ResolveResult::candidate, ResolveResult::errorCode)
                .containsExactly(ResolveResult.Status.DRIFTED, current, "RG.REFERENCE.DRIFTED");
        assertThat(service.resolve(new ResolveRequest("graph", "missing", 1, "sha256:none", APAC, "CORRECTNESS")))
                .extracting(ResolveResult::status, ResolveResult::errorCode)
                .containsExactly(ResolveResult.Status.NOT_FOUND, "RG.REFERENCE.NOT_FOUND");
        assertThat(service.resolve(new ResolveRequest("operator", "private", 1, "sha256:private", APAC, "CORRECTNESS")))
                .extracting(ResolveResult::status, ResolveResult::errorCode)
                .containsExactly(ResolveResult.Status.FORBIDDEN, "RG.REFERENCE.FORBIDDEN");
    }

    @Test
    void resolveRejectsAReferenceOutsideTheRequestedScope() {
        ReferenceCandidate current = candidate("graph", "eu-only", "EU", ReferenceCandidate.Lifecycle.ACTIVE, EU);
        provider.add(current);

        ResolveResult result = service.resolve(ResolveRequest.from(current, APAC, "CORRECTNESS"));

        assertThat(result.status()).isEqualTo(ResolveResult.Status.FORBIDDEN);
        assertThat(result.candidate()).isNull();
    }

    @Test
    void serviceDoesNotTrustAProviderThatLeaksAnOutOfScopeDriftCandidate() {
        ReferenceCandidate leaked = candidate(
                "graph", "eu-only", "EU", ReferenceCandidate.Lifecycle.ACTIVE, EU);
        ReferenceCandidateProvider unsafeProvider = new ReferenceCandidateProvider() {
            @Override
            public ProviderSnapshot snapshot(SearchRequest request) {
                return new ProviderSnapshot(1, List.of());
            }

            @Override
            public ProviderResolution resolve(ResolveRequest request) {
                return new ProviderResolution(ResolveResult.Status.DRIFTED, leaked);
            }
        };

        ResolveResult result = new ReferenceCandidateService(unsafeProvider).resolve(
                new ResolveRequest("graph", "eu-only", 1, "sha256:old", APAC, "CORRECTNESS"));

        assertThat(result.status()).isEqualTo(ResolveResult.Status.FORBIDDEN);
        assertThat(result.candidate()).isNull();
        assertThat(result.errorCode()).isEqualTo("RG.REFERENCE.FORBIDDEN");
    }

    @Test
    void limitIsBoundedAtTheProtocolBoundary() {
        assertThatThrownBy(() -> new SearchRequest("graph", "", "", SearchRequest.MAX_LIMIT + 1, APAC))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    void equalRankCandidatesHaveStableIdOrderingRegardlessOfProviderInsertionOrder() {
        ReferenceCandidate beta = candidate("graph", "beta", "same", ReferenceCandidate.Lifecycle.ACTIVE, APAC);
        ReferenceCandidate alpha = candidate("graph", "alpha", "same", ReferenceCandidate.Lifecycle.ACTIVE, APAC);
        provider.add(beta).add(alpha);

        Page page = service.search(new SearchRequest("graph", "same", APAC));

        assertThat(page.items()).extracting(ReferenceCandidate::id).containsExactly("alpha", "beta");
    }

    @Test
    void candidateProtocolIsMetadataOnly() {
        assertThat(ReferenceCandidate.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("schema", "fixture", "evidence", "payload");
        assertThat(candidate("graph", "safe", "metadata", ReferenceCandidate.Lifecycle.ACTIVE, APAC).labels())
                .containsExactly("business");
    }

    @Test
    void lifecycleAndCompatibilityFacetsArePartOfTheStableSearchWindow() {
        provider.add(candidate("graph", "active", "Active", ReferenceCandidate.Lifecycle.ACTIVE, APAC))
                .add(new ReferenceCandidate(
                        ReferenceCandidate.SCHEMA_VERSION, "graph", "review", "Review",
                        "metadata description", 1, "sha256:review", "authority://graph", APAC,
                        ReferenceCandidate.Lifecycle.DRAFT,
                        new ReferenceCandidate.Owner("team-risk", "Risk Team"), List.of("business"),
                        ReferenceCandidate.Compatibility.REVIEW, ""));

        Page page = service.search(new SearchRequest(
                "graph", "", "", 20, APAC, "draft", "review"));

        assertThat(page.items()).extracting(ReferenceCandidate::id).containsExactly("review");
        assertThatThrownBy(() -> new SearchRequest(
                "graph", "", "", 20, APAC, "retired", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lifecycle");
    }

    private static ReferenceCandidate candidate(String kind,
                                                String id,
                                                String displayName,
                                                ReferenceCandidate.Lifecycle lifecycle,
                                                ReferenceScope scope) {
        return new ReferenceCandidate(
                ReferenceCandidate.SCHEMA_VERSION,
                kind,
                id,
                displayName,
                "metadata description",
                1,
                "sha256:" + id,
                "authority://" + kind,
                scope,
                lifecycle,
                new ReferenceCandidate.Owner("team-risk", "Risk Team"),
                List.of("business"),
                ReferenceCandidate.Compatibility.COMPATIBLE,
                ""
        );
    }
}
