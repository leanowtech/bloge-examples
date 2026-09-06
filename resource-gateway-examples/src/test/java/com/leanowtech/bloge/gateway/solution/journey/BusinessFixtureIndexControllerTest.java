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
                "tenant-a", "org-a", "project-a", "test", "sg", "USER", "reviewer",
                "", "AGENT_TDD_GOVERNANCE", "corr-1");
        when(authenticator.authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE))
                .thenReturn(reviewer);
        List<BusinessFixtureIndexService.CapabilityFixtures> projection = List.of(
                new BusinessFixtureIndexService.CapabilityFixtures(
                        "FEATURE", "feature:party", "取消责任方", List.of()));
        when(index.listForSolution("sol:cancel", reviewer)).thenReturn(projection);

        var response = new BusinessFixtureIndexController(authenticator, index)
                .list("sol:cancel", headers);

        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getPragma()).isEqualTo("no-cache");
        assertThat(response.getBody()).isEqualTo(projection);
        verify(index).listForSolution("sol:cancel", reviewer);
    }
}
