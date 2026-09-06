package com.leanowtech.bloge.gateway.solution.journey;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the authenticated, metadata-only business Fixture transport. */
class BusinessFixtureIndexControllerTest {
    @Test
    void requiresGovernanceIdentityAndReturnsNoStoreMetadata() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        BusinessFixtureIndexService index = mock(BusinessFixtureIndexService.class);
        HttpHeaders headers = new HttpHeaders();
        IntegrationRequestContext reviewer = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "HUMAN", "reviewer",
                "", "SOLUTION_GOLDEN_REVIEW", "corr-1",
                java.util.Set.of("solution-golden-reviewers"), "INTERNAL", "");
        when(authenticator.authenticate(headers, IntegrationOperation.SOLUTION_GOLDEN_REVIEW))
                .thenReturn(reviewer);
        List<BusinessFixtureIndexService.CapabilityFixtures> projection = List.of(
                new BusinessFixtureIndexService.CapabilityFixtures(
                        "FEATURE", "feature:party", "取消责任方", List.of()));
        when(index.listForSolution("sol:cancel", reviewer)).thenReturn(projection);

        var response = new BusinessFixtureIndexController(authenticator, index)
                .list("sol:cancel", headers);

        assertThat(response.getHeaders().getCacheControl()).contains("no-store", "private");
        assertThat(response.getHeaders().getPragma()).isEqualTo("no-cache");
        assertThat(response.getBody()).isEqualTo(projection);
        verify(index).listForSolution("sol:cancel", reviewer);
        verify(authenticator).authenticate(headers, IntegrationOperation.SOLUTION_GOLDEN_REVIEW);
    }

    @Test
    void loadsOneProtectedFixtureThroughTheSameHumanReviewerBoundary() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        BusinessFixtureIndexService index = mock(BusinessFixtureIndexService.class);
        HttpHeaders headers = new HttpHeaders();
        IntegrationRequestContext reviewer = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "HUMAN", "reviewer",
                "", "SOLUTION_GOLDEN_REVIEW", "corr-1",
                java.util.Set.of("solution-golden-reviewers"), "RESTRICTED", "");
        when(authenticator.authenticate(headers, IntegrationOperation.SOLUTION_GOLDEN_REVIEW))
                .thenReturn(reviewer);
        var material = new BusinessFixtureIndexService.FixtureMaterialView(
                "fixture:party", "责任方样本", "passenger", "RESTRICTED",
                java.util.Map.of("party", "passenger"));
        when(index.readMaterialForSolution("sol:cancel", "fixture:party", reviewer))
                .thenReturn(material);

        var response = new BusinessFixtureIndexController(authenticator, index)
                .material("sol:cancel", "fixture:party", headers);

        assertThat(response.getHeaders().getCacheControl()).contains("no-store", "private");
        assertThat(response.getBody()).isEqualTo(material);
        verify(index).readMaterialForSolution("sol:cancel", "fixture:party", reviewer);
    }
}
