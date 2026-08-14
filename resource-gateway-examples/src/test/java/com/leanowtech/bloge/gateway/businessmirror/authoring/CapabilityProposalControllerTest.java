package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityProposalControllerTest {
    @Test
    void authenticatesWritesAndReturnsExactReceiptWithReplayMetadata() {
        var service = mock(CapabilityProposalAuthoringService.class);
        var authenticator = mock(IntegrationRequestAuthenticator.class);
        var identity = BusinessMirrorAuthoringFixtures.identity();
        var draft = BusinessMirrorAuthoringFixtures.proposal("trip-query", 0, "v1");
        var stored = new StoredCapabilityProposalDraft("", fingerprint('a'),
                draft.withRevision(1), Instant.parse("2026-08-14T02:00:00Z"),
                Instant.parse("2026-08-14T02:00:00Z"), "alice");
        var receipt = new CapabilityProposalSaveReceipt("", fingerprint('b'), stored,
                Instant.parse("2026-08-14T02:00:00Z"));
        var outcome = new CapabilityProposalSaveCoordinator.Outcome(receipt, true);
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers, IntegrationOperation.BUSINESS_MIRROR_PROPOSAL_WRITE))
                .thenReturn(identity);
        when(service.create(draft, "proposal:create:1", identity)).thenReturn(outcome);
        var controller = new CapabilityProposalController(service, authenticator);

        var response = controller.create(draft, "proposal:create:1", headers);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isSameAs(receipt);
        assertThat(response.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(response.getHeaders().getETag()).isEqualTo('"' + stored.draftFingerprint() + '"');
        verify(authenticator).authenticate(headers, IntegrationOperation.BUSINESS_MIRROR_PROPOSAL_WRITE);
    }

    @Test
    void authenticatesBoundedIndexAsAProposalRead() {
        var service = mock(CapabilityProposalAuthoringService.class);
        var authenticator = mock(IntegrationRequestAuthenticator.class);
        var identity = BusinessMirrorAuthoringFixtures.identity();
        var page = new CapabilityProposalPage("", List.of(), "");
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers, IntegrationOperation.BUSINESS_MIRROR_PROPOSAL_READ))
                .thenReturn(identity);
        when(service.list("cursor", 25, identity)).thenReturn(page);
        var controller = new CapabilityProposalController(service, authenticator);

        assertThat(controller.list("cursor", 25, headers)).isSameAs(page);
        verify(authenticator).authenticate(headers, IntegrationOperation.BUSINESS_MIRROR_PROPOSAL_READ);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
