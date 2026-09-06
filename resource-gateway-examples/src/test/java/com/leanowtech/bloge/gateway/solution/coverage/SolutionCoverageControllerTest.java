package com.leanowtech.bloge.gateway.solution.coverage;

import com.leanowtech.bloge.gateway.agenttdd.AgentTddToolException;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CoverageInventory.ObligationDimension;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the human-only, no-store transport for the exact Solution coverage matrix. */
class SolutionCoverageControllerTest {
    @Test
    void returnsHumanCoordinatesWithNoStorePrivateTransport() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        SolutionCoverageService coverage = mock(SolutionCoverageService.class);
        HttpHeaders headers = new HttpHeaders();
        IntegrationRequestContext reviewer = reviewer();
        when(authenticator.authenticate(headers, IntegrationOperation.SOLUTION_GOLDEN_REVIEW))
                .thenReturn(reviewer);
        var status = new SolutionCoverageService.CoverageStatus(
                "solution-coverage:sol:cancel", 3, "sha256:" + "a".repeat(64),
                List.of(new SolutionCoverageService.CoverageItem(
                        "rule:scn:cancel:R1", "sha256:" + "b".repeat(64),
                        ObligationDimension.RULE, RiskLevel.HIGH, true, List.of("G1"))),
                new SolutionCoverageService.CoverageSummary(1, 1, 0, 0));
        when(coverage.status(reviewer, "sol:cancel")).thenReturn(status);

        var response = new SolutionCoverageController(authenticator, coverage)
                .status("sol:cancel", headers);

        assertThat(response.getHeaders().getCacheControl()).contains("no-store", "private");
        assertThat(response.getHeaders().getPragma()).isEqualTo("no-cache");
        assertThat(response.getBody().solutionRef()).isEqualTo("sol:cancel");
        assertThat(response.getBody().obligations().getFirst().id())
                .isEqualTo("rule:scn:cancel:R1");
        assertThat(response.getBody().obligations().getFirst().byCaseIds())
                .containsExactly("G1");
        verify(authenticator).authenticate(headers, IntegrationOperation.SOLUTION_GOLDEN_REVIEW);
    }

    @Test
    void rejectsNonHumanOrNonReviewerIdentitiesBeforeCoverageMaterialIsRead() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        SolutionCoverageService coverage = mock(SolutionCoverageService.class);
        HttpHeaders headers = new HttpHeaders();
        IntegrationRequestContext workload = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "codex", "", "SOLUTION_GOLDEN_REVIEW", "corr-1",
                Set.of("solution-golden-reviewers"), "RESTRICTED", "");
        when(authenticator.authenticate(headers, IntegrationOperation.SOLUTION_GOLDEN_REVIEW))
                .thenReturn(workload);

        assertThatThrownBy(() -> new SolutionCoverageController(authenticator, coverage)
                .status("sol:cancel", headers))
                .isInstanceOfSatisfying(IntegrationProblemException.class,
                        failure -> assertThat(failure.problem().code())
                                .isEqualTo("RG.SOLUTION.COVERAGE.HUMAN_REVIEW_FORBIDDEN"));
        verify(coverage, never()).status(workload, "sol:cancel");
    }

    @Test
    void mapsCoverageFailuresWithoutCachingOrReturningCoordinates() {
        SolutionCoverageController controller = new SolutionCoverageController(
                mock(IntegrationRequestAuthenticator.class), mock(SolutionCoverageService.class));

        var response = controller.coverageFailure(new AgentTddToolException(
                "FIXTURE_MATERIAL_UNAVAILABLE", "Coverage material is unavailable."));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store", "private");
        assertThat(response.getBody().details()).isEmpty();
        assertThat(response.getBody().toString()).doesNotContain("G1", "rule:scn", "byCaseIds");
    }

    private static IntegrationRequestContext reviewer() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg",
                "HUMAN", "reviewer", "", "SOLUTION_GOLDEN_REVIEW", "corr-1",
                Set.of("solution-golden-reviewers"), "RESTRICTED", "");
    }
}
