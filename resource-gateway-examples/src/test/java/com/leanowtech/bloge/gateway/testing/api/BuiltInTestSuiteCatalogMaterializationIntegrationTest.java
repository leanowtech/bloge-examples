package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end proof that the legacy built-in catalog is governed by the common suite runtime. */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.active-key-id=catalog-test-v1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.key-ring=catalog-test-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id=catalog-request-index-v1",
                "gateway.testing.durable.worker-quarantines.request-key-protection.key-ring=catalog-request-index-v1=HyAdHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "gateway.seed-descriptors=true",
                "gateway.base-url=http://127.0.0.1:1",
                "gateway.integration.identity.environment-id=test",
                "gateway.integration.identity.allowed-purposes=TEST_EXECUTION,TEST_SUITE_READ,TEST_SUITE_WRITE",
                "spring.datasource.url=jdbc:h2:mem:catalog-materialization-main;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "gateway.testing.store.jdbc-url=jdbc:h2:mem:catalog-materialization-control;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        }
)
class BuiltInTestSuiteCatalogMaterializationIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void materializesIdempotentExactAssetsAndRunsAllSevenSuitesWithoutNetworkEscape() {
        HttpHeaders suiteWrite = headers("TEST_SUITE_WRITE");
        var first = restTemplate.exchange(
                "/api/testing/catalogs/gateway-graph-contract-v1",
                HttpMethod.PUT,
                new HttpEntity<>(suiteWrite),
                TestSuiteCatalogMaterializationResponse.class);
        var second = restTemplate.exchange(
                "/api/testing/catalogs/gateway-graph-contract-v1",
                HttpMethod.PUT,
                new HttpEntity<>(suiteWrite),
                TestSuiteCatalogMaterializationResponse.class);

        assertThat(first.getStatusCode())
                .withFailMessage("catalog materialization failed: %s", first.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isEqualTo(first.getBody());
        TestSuiteCatalogMaterializationResponse catalog = first.getBody();
        assertThat(catalog.schemaVersion())
                .isEqualTo(TestSuiteCatalogMaterializationResponse.SCHEMA_VERSION);
        assertThat(catalog.catalogId()).isEqualTo("resource-gateway.built-in-graph-contracts");
        assertThat(catalog.catalogFingerprint()).matches("sha256:[0-9a-f]{64}");
        assertThat(catalog.totalSuites()).isEqualTo(7);
        assertThat(catalog.totalCases()).isEqualTo(14);
        assertThat(catalog.suites()).hasSize(7);

        Set<TestSuite.CaseType> representedTypes = EnumSet.noneOf(TestSuite.CaseType.class);
        for (TestSuiteCatalogMaterializationResponse.SuiteAsset asset : catalog.suites()) {
            assertThat(asset.suiteRef().suiteId()).startsWith("rg-built-in-");
            assertThat(asset.suiteRef().revision()).isPositive();
            assertThat(asset.suiteRef().fingerprint()).matches("sha256:[0-9a-f]{64}");
            assertThat(asset.fixtureBundleRefs()).hasSize(asset.caseCount());
            assertThat(asset.fixtureBundleRefs())
                    .allSatisfy(ref -> {
                        assertThat(ref.revision()).isPositive();
                        assertThat(ref.fingerprint()).matches("sha256:[0-9a-f]{64}");
                    });

            String suitePath = "/api/testing/suites/" + asset.suiteRef().suiteId()
                    + "?revision=" + asset.suiteRef().revision();
            var stored = restTemplate.exchange(suitePath, HttpMethod.GET,
                    new HttpEntity<>(headers("TEST_SUITE_READ")), StoredTestSuite.class);
            assertThat(stored.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(stored.getBody()).isNotNull();
            assertThat(stored.getBody().fingerprint()).isEqualTo(asset.suiteRef().fingerprint());
            assertThat(stored.getBody().suite().target().id()).isEqualTo(asset.graphName());
            assertThat(stored.getBody().suite().metadata())
                    .containsEntry("sourceCatalogId", catalog.catalogId())
                    .containsEntry("sourceSuiteId", asset.sourceSuiteId());
            stored.getBody().suite().cases().forEach(testCase -> representedTypes.add(testCase.caseType()));

            TestSuiteExecutionRequest request = new TestSuiteExecutionRequest("", asset.suiteRef(),
                    "materialize-" + asset.sourceSuiteId(), TestSuiteExecutionRequest.Strategy.COLLECT_ALL,
                    java.util.Map.of("source", "built-in-catalog-integration-test"));
            var execution = restTemplate.exchange(
                    "/api/testing/suites/" + asset.suiteRef().suiteId() + "/executions",
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers("TEST_EXECUTION")),
                    TestSuiteExecutionResponse.class);

            assertThat(execution.getStatusCode())
                    .withFailMessage("suite %s failed: %s", asset.sourceSuiteId(), execution.getBody())
                    .isEqualTo(HttpStatus.OK);
            assertThat(execution.getBody()).isNotNull();
            assertThat(execution.getBody().evidence().status())
                    .withFailMessage("suite %s evidence: %s", asset.sourceSuiteId(),
                            execution.getBody().evidence())
                    .isEqualTo(TestSuiteRunEvidence.Status.PASSED);
            assertThat(execution.getBody().evidence().coverage().status())
                    .isEqualTo(TestSuiteRunEvidence.CoverageStatus.SATISFIED);
            assertThat(execution.getBody().evidence().promotion().status())
                    .isEqualTo(TestSuiteRunEvidence.PromotionStatus.ELIGIBLE);
            assertThat(execution.getBody().evidence().caseResults()).hasSize(asset.caseCount())
                    .allSatisfy(result -> {
                        assertThat(result.status()).isEqualTo(TestSuiteRunEvidence.CaseStatus.PASSED);
                        assertThat(result.evidenceStatus()).isEqualTo(TestRunEvidence.Status.PASSED);
                        assertThat(result.evidenceClass()).isEqualTo(TestRunEvidence.EvidenceClass.CERTIFIABLE);
                    });
        }
        assertThat(representedTypes).containsExactlyInAnyOrderElementsOf(
                EnumSet.allOf(TestSuite.CaseType.class));
    }

    @Test
    void rejectsCatalogMaterializationUnderAnExecutionOnlyPurpose() {
        var response = restTemplate.exchange(
                "/api/testing/catalogs/gateway-graph-contract-v1",
                HttpMethod.PUT,
                new HttpEntity<>(headers("TEST_EXECUTION")),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static HttpHeaders headers(String purpose) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("bloge-aneke-demo-token");
        headers.set("X-Purpose", purpose);
        return headers;
    }
}
