package com.leanowtech.bloge.gateway.visual.fixture;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the public HTTP seam for graph-node Fixture promotion. */
class GraphNodeFixturePromotionControllerTest {

    @Test
    void createsPayloadFreeReceiptWithNoStoreCachePolicy() throws Exception {
        GraphNodeFixturePromotionService service = mock(GraphNodeFixturePromotionService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = identity();
        GraphNodeFixturePromotionRequest request = request();
        GraphNodeFixturePromotionService.PromotionResult receipt =
                new GraphNodeFixturePromotionService.PromotionResult(
                        "fixture-1", 1, "DRAFT", null, null, "governed");
        when(authenticator.authenticate(any(), eq(IntegrationOperation.CORRECTNESS_FIXTURE_MATERIAL_WRITE)))
                .thenReturn(identity);
        when(service.promote("draft-1", "node-1", request, identity)).thenReturn(receipt);

        ResponseEntity<GraphNodeFixturePromotionService.PromotionResult> response =
                new GraphNodeFixturePromotionController(service, authenticator)
                        .promote("draft-1", "node-1", request, new HttpHeaders());

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getBody()).isSameAs(receipt);
        String serializedReceipt = new ObjectMapper().writeValueAsString(response.getBody());
        assertThat(serializedReceipt).doesNotContain("payload")
                .doesNotContain("output")
                .doesNotContain("expectedInput")
                .doesNotContain("material");
        verify(service).promote("draft-1", "node-1", request, identity);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409, 422})
    void mapsPromotionFailuresToTheirStableHttpStatus(int status) {
        GraphNodeFixturePromotionService service = mock(GraphNodeFixturePromotionService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = identity();
        GraphNodeFixturePromotionRequest request = request();
        when(authenticator.authenticate(any(), eq(IntegrationOperation.CORRECTNESS_FIXTURE_MATERIAL_WRITE)))
                .thenReturn(identity);
        when(service.promote("draft-1", "node-1", request, identity))
                .thenThrow(new GraphNodeFixturePromotionException(status, "RG.TEST.STATUS", "safe failure"));

        assertThatThrownBy(() -> new GraphNodeFixturePromotionController(service, authenticator)
                .promote("draft-1", "node-1", request, new HttpHeaders()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(status);
                    assertThat(failure.problem().code()).isEqualTo("RG.TEST.STATUS");
                });
    }

    @Test
    void mapsMissingBodyToBadRequestWithoutCallingService() {
        GraphNodeFixturePromotionService service = mock(GraphNodeFixturePromotionService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        IntegrationRequestContext identity = identity();
        when(authenticator.authenticate(any(), eq(IntegrationOperation.CORRECTNESS_FIXTURE_MATERIAL_WRITE)))
                .thenReturn(identity);

        assertThatThrownBy(() -> new GraphNodeFixturePromotionController(service, authenticator)
                .promote("draft-1", "node-1", null, new HttpHeaders()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(400);
                    assertThat(failure.problem().code()).isEqualTo("RG.VISUAL.PROMOTION.REQUEST_INVALID");
                });
        verify(service, never()).promote(any(), any(), any(), any(IntegrationRequestContext.class));
    }

    @Test
    void preservesAuthenticationFailureBeforeServiceDispatch() {
        GraphNodeFixturePromotionService service = mock(GraphNodeFixturePromotionService.class);
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        IntegrationProblemException unauthorized = new IntegrationProblemException(
                IntegrationProblem.unauthorized(
                        "RG.INTEGRATION.AUTHENTICATION_REQUIRED", "credential required", "corr-1", java.util.Map.of()));
        when(authenticator.authenticate(any(), eq(IntegrationOperation.CORRECTNESS_FIXTURE_MATERIAL_WRITE)))
                .thenThrow(unauthorized);

        assertThatThrownBy(() -> new GraphNodeFixturePromotionController(service, authenticator)
                .promote("draft-1", "node-1", request(), new HttpHeaders()))
                .isSameAs(unauthorized);
        verify(service, never()).promote(any(), any(), any(), any(IntegrationRequestContext.class));
    }

    private static GraphNodeFixturePromotionRequest request() {
        return new GraphNodeFixturePromotionRequest(
                GraphNodeFixturePromotionRequest.SCHEMA_VERSION,
                "fixture-1", "INTERNAL", 3, List.of("$.secret"));
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "USER", "author-1",
                "", "TEST_FIXTURE_MATERIAL_WRITE", "corr-1");
    }
}
