package com.leanowtech.bloge.gateway.solution.board;

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

/** Verifies the reviewer transport never makes payload-bearing board data cacheable. */
class BoardProjectionControllerTest {
    @Test
    void requiresGovernanceIdentityAndReturnsNoStoreResponse() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        BoardProjectionService projections = mock(BoardProjectionService.class);
        HttpHeaders headers = new HttpHeaders();
        IntegrationRequestContext reviewer = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "USER", "reviewer-1",
                "", "AGENT_TDD_GOVERNANCE", "corr-1");
        when(authenticator.authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE))
                .thenReturn(reviewer);
        BoardProjectionService.BoardView view = new BoardProjectionService.BoardView(
                "sol:cancel", "处理取消费争议",
                new BoardProjectionService.RuleMatrixView(List.of(), List.of(), "转人工"),
                List.of(), new BoardProjectionService.RedGreenView("未测试",
                new BoardProjectionService.LayerCount(0, 0), List.of(), List.of()),
                List.of(), new BoardProjectionService.PublishCard(
                new BoardProjectionService.PublishGates(false, false, false, false), false));
        when(projections.project("sol:cancel", reviewer)).thenReturn(view);

        var response = new BoardProjectionController(authenticator, projections)
                .board("sol:cancel", headers);

        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getPragma()).isEqualTo("no-cache");
        assertThat(response.getBody()).isEqualTo(view);
        verify(authenticator).authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE);
    }
}
