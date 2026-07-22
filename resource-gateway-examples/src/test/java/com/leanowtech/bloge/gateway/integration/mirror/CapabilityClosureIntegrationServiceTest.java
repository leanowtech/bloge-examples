package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CapabilityClosureIntegrationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");
    private final CapabilityClosureIntegrationService service = new CapabilityClosureIntegrationService(
            mock(GraphDraftCapabilityClosureService.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void rejectsClassificationAboveAuthenticatedClearance() {
        CapabilityClosureProjectionRequest request = request(
                CapabilityContract.DataClassification.RESTRICTED, NOW);

        assertProblem(() -> service.project(request, context("CONFIDENTIAL")),
                403, "RG.MIRROR.CLASSIFICATION_FORBIDDEN");
    }

    @Test
    void rejectsMateriallyFutureCreationTime() {
        CapabilityClosureProjectionRequest request = request(
                CapabilityContract.DataClassification.CONFIDENTIAL, NOW.plusSeconds(301));

        assertProblem(() -> service.project(request, context("CONFIDENTIAL")),
                400, "RG.MIRROR.CREATED_AT_IN_FUTURE");
    }

    private static CapabilityClosureProjectionRequest request(
            CapabilityContract.DataClassification classification,
            Instant createdAt) {
        GraphDraft draft = new GraphDraft("", "", 0, "portable", "foreign", "foreign", "foreign",
                "DRAFT", null, null, null, null, null, null, null, null, null, null);
        return new CapabilityClosureProjectionRequest("", draft, 1, createdAt, classification);
    }

    private static IntegrationRequestContext context(String clearance) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "actor-a", "", "CAPABILITY_PROJECTION", "corr-a",
                java.util.Set.of(), clearance, "");
    }

    private static void assertProblem(Runnable action, int status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(status);
                    assertThat(failure.problem().code()).isEqualTo(code);
                    assertThat(failure.problem().correlationId()).isEqualTo("corr-a");
                });
    }
}
