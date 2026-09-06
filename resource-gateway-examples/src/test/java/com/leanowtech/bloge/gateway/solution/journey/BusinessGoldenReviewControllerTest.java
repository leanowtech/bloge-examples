package com.leanowtech.bloge.gateway.solution.journey;

import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the protected human HTTP transport and its dedicated purpose. */
class BusinessGoldenReviewControllerTest {
    @Test
    void returnsNoStorePrivateForListsAndProtectedMaterial() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        BusinessGoldenReviewService service = mock(BusinessGoldenReviewService.class);
        HttpHeaders headers = new HttpHeaders();
        IntegrationRequestContext identity = identity();
        when(authenticator.authenticate(headers, IntegrationOperation.SOLUTION_GOLDEN_REVIEW))
                .thenReturn(identity);
        when(service.list("sol:cancel", "journey:cancel", identity)).thenReturn(Map.of(
                "solutionRef", "sol:cancel", "cases", List.of()));
        when(service.readMaterial("sol:cancel", "journey:cancel", "g1", identity)).thenReturn(
                Map.of("caseId", "g1", "businessIntent", "免除取消费"));
        BusinessGoldenReviewController controller =
                new BusinessGoldenReviewController(authenticator, service);

        var listed = controller.list("sol:cancel", "journey:cancel", headers);
        var material = controller.material("sol:cancel", "g1", "journey:cancel", headers);

        assertThat(listed.getHeaders().getCacheControl()).contains("no-store", "private");
        assertThat(material.getHeaders().getCacheControl()).contains("no-store", "private");
        assertThat(listed.getHeaders().getPragma()).isEqualTo("no-cache");
        assertThat(material.getHeaders().getPragma()).isEqualTo("no-cache");
        verify(authenticator, org.mockito.Mockito.times(2))
                .authenticate(headers, IntegrationOperation.SOLUTION_GOLDEN_REVIEW);
    }

    @Test
    void returnsPayloadFreeNoStoreFailures() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        BusinessGoldenReviewService service = mock(BusinessGoldenReviewService.class);
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers, IntegrationOperation.SOLUTION_GOLDEN_REVIEW))
                .thenReturn(identity());
        when(service.readMaterial("sol:cancel", "journey:cancel", "g1", identity()))
                .thenThrow(new AgentTddToolException(
                        "GOLDEN_REVIEW_CLEARANCE_FORBIDDEN", "Review access is forbidden."));
        BusinessGoldenReviewController controller =
                new BusinessGoldenReviewController(authenticator, service);

        var response = controller.failure((AgentTddToolException) org.assertj.core.api.Assertions
                .catchThrowable(() -> controller.material(
                        "sol:cancel", "g1", "journey:cancel", headers)));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store", "private");
        assertThat(response.getBody().toString()).doesNotContain("免除取消费", "given", "expected");
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "HUMAN", "cx-owner", "", "SOLUTION_GOLDEN_REVIEW", "corr-review",
                java.util.Set.of(), "INTERNAL", "");
    }
}
