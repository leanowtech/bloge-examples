package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.AssertionSetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.BusinessOracleRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.CoverageInventoryRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.FixtureAssetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.ScenarioDraftSetV2Repository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredCoverageInventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorrectnessAuthoringAssetQueryControllerTest {

    private final CoverageInventoryRepository inventories =
            mock(CoverageInventoryRepository.class);
    private final BusinessOracleRepository oracles = mock(BusinessOracleRepository.class);
    private final AssertionSetRepository assertions = mock(AssertionSetRepository.class);
    private final ScenarioDraftSetV2Repository scenarios =
            mock(ScenarioDraftSetV2Repository.class);
    private final FixtureAssetRepository fixtures = mock(FixtureAssetRepository.class);
    private final IntegrationRequestAuthenticator authenticator =
            mock(IntegrationRequestAuthenticator.class);
    private final IntegrationRequestContext identity = new IntegrationRequestContext(
            "tenant-a", "org-a", "credit", "test", "sg", "USER", "author-a", "",
            "CORRECTNESS_READ", "corr-query");
    private final EnterpriseScope scope =
            new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg");

    private CorrectnessAuthoringAssetQueryController controller;
    private HttpHeaders headers;

    @BeforeEach
    void setUp() {
        controller = new CorrectnessAuthoringAssetQueryController(
                inventories, oracles, assertions, scenarios, fixtures, authenticator);
        headers = new HttpHeaders();
        when(authenticator.authenticate(headers, IntegrationOperation.CORRECTNESS_WORKSPACE_READ))
                .thenReturn(identity);
    }

    @Test
    void readsOneExactRevisionWithoutCachingOrScopeFallback() {
        StoredCoverageInventory stored = mock(StoredCoverageInventory.class);
        when(inventories.findRevision(scope, "inventory-a", 3L))
                .thenReturn(Optional.of(stored));

        var response = controller.inventory("inventory-a", 3L, headers);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().correlationId()).isEqualTo("corr-query");
        assertThat(response.getBody().scope()).isEqualTo(scope);
        assertThat(response.getBody().capabilities())
                .containsExactly("COVERAGE_INVENTORY_READ_V1");
        assertThat(response.getBody().data()).isSameAs(stored);
        verify(inventories).findRevision(scope, "inventory-a", 3L);
    }

    @Test
    void usesHeadOnlyWhenRevisionIsOmitted() {
        StoredCoverageInventory stored = mock(StoredCoverageInventory.class);
        when(inventories.findHead(scope, "inventory-a")).thenReturn(Optional.of(stored));

        assertThat(controller.inventory("inventory-a", null, headers).getBody().data())
                .isSameAs(stored);
        verify(inventories).findHead(scope, "inventory-a");
    }

    @Test
    void failsClosedForInvalidOrMissingExactRevision() {
        when(inventories.findRevision(scope, "inventory-a", 9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.inventory("inventory-a", 0L, headers))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.CORRECTNESS.REVISION_INVALID"));
        assertThatThrownBy(() -> controller.inventory("inventory-a", 9L, headers))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(404);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.CORRECTNESS.AUTHORING_ASSET_NOT_FOUND");
                    assertThat(failure.problem().correlationId()).isEqualTo("corr-query");
                });
    }
}
