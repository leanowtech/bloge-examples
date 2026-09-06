package com.leanowtech.bloge.gateway.solution.feature;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the no-store HTTP boundary for human Feature-suite review. */
class FeatureControlledSuiteReviewControllerTest {
    @Test
    void authenticatesTheDedicatedPurposeAndReturnsNoStoreSuiteMetadata() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        FeatureControlledSuiteReviewService reviews = mock(FeatureControlledSuiteReviewService.class);
        HttpHeaders headers = new HttpHeaders();
        IntegrationRequestContext reviewer = reviewer();
        when(authenticator.authenticate(headers, IntegrationOperation.SOLUTION_GOLDEN_REVIEW))
                .thenReturn(reviewer);
        var summaries = List.of(new FeatureControlledSuiteReviewService.SuiteReviewSummary(
                "feature:party", 2, "PASSED", 3, 2, "sha256:evidence", true));
        when(reviews.listForSolution("solution:cancel", reviewer)).thenReturn(summaries);

        var response = new FeatureControlledSuiteReviewController(authenticator, reviews)
                .list("solution:cancel", headers);

        assertThat(response.getHeaders().getCacheControl()).contains("no-store", "private");
        assertThat(response.getHeaders().getPragma()).isEqualTo("no-cache");
        assertThat(response.getBody()).isEqualTo(summaries);
        verify(authenticator).authenticate(headers, IntegrationOperation.SOLUTION_GOLDEN_REVIEW);
    }

    private static IntegrationRequestContext reviewer() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "HUMAN", "reviewer", "", "SOLUTION_GOLDEN_REVIEW", "corr-1",
                Set.of("solution-golden-reviewers"), "RESTRICTED", "");
    }
}
