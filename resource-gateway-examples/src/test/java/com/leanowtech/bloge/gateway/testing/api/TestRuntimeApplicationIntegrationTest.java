package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.integration.IntegrationCapabilities;
import com.leanowtech.bloge.gateway.integration.IntegrationEnvelope;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Full application proof for profile-gated testing control-plane assembly. */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "gateway.seed-descriptors=true",
                "gateway.base-url=http://127.0.0.1:1",
                "gateway.integration.identity.environment-id=test",
                "gateway.integration.identity.allowed-purposes=TEST_EXECUTION,TEST_FIXTURE_READ,TEST_FIXTURE_WRITE,TEST_SUITE_READ,TEST_SUITE_WRITE",
                "spring.datasource.url=jdbc:h2:mem:testing-app-main;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "gateway.testing.store.jdbc-url=jdbc:h2:mem:testing-app-control;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        }
)
class TestRuntimeApplicationIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FixtureBundleRepository fixtureRepository;

    @Test
    void realApplicationAdvertisesAndServesTheProfileGatedTargetProtocol() throws Exception {
        assertThat(context.getBeansOfType(TestExecutionController.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestRunRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(TestSuiteRunRepository.class)).hasSize(1);

        var capabilities = restTemplate.exchange("/api/integration/capabilities", HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<IntegrationEnvelope<IntegrationCapabilities>>() { });
        assertThat(capabilities.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capabilities.getBody()).isNotNull();
        assertThat(capabilities.getBody().payload().testability().executionEndpointEnabled()).isTrue();
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.path().equals("/api/testing/targets/graphs/{graphName}"));
        assertThat(capabilities.getBody().payload().endpoints()).anyMatch(endpoint ->
                endpoint.path().equals("/api/testing/targets/operators/{operatorRef}"));
        assertThat(capabilities.getBody().payload().features())
                .containsEntry("immutableTestSuiteRegistry", true)
                .containsEntry("immutableTestSuiteExecution", true)
                .containsEntry("suiteSemanticCoverageVerdict", true);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("bloge-aneke-demo-token");
        headers.set("X-Purpose", "TEST_EXECUTION");
        var target = restTemplate.exchange("/api/testing/targets/graphs/loanDecisionPolicy",
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);

        assertThat(target.getStatusCode())
                .withFailMessage("target discovery failed: %s", target.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(target.getBody()).isNotNull();
        TestGraphTargetDescriptor descriptor = objectMapper.treeToValue(
                target.getBody(), TestGraphTargetDescriptor.class);
        assertThat(descriptor.target().id()).isEqualTo("loanDecisionPolicy");
        assertThat(descriptor.target().fingerprint()).startsWith("sha256:");
        assertThat(descriptor.contract().inputSchema().schema()).isNotEmpty();
        assertThat(descriptor.certificationEligible()).isTrue();

        var nestedTarget = restTemplate.exchange("/api/testing/targets/graphs/enrichOrderList",
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(nestedTarget.getStatusCode()).isEqualTo(HttpStatus.OK);
        TestGraphTargetDescriptor nestedDescriptor = objectMapper.treeToValue(
                nestedTarget.getBody(), TestGraphTargetDescriptor.class);
        assertThat(nestedDescriptor.certificationEligible()).isTrue();
        assertThat(nestedDescriptor.certificationGaps()).isEmpty();

        var operatorTarget = restTemplate.exchange("/api/testing/targets/operators/httpResource",
                HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(operatorTarget.getStatusCode())
                .withFailMessage("operator target discovery failed: %s", operatorTarget.getBody())
                .isEqualTo(HttpStatus.OK);
        TestOperatorTargetDescriptor operatorDescriptor = objectMapper.treeToValue(
                operatorTarget.getBody(), TestOperatorTargetDescriptor.class);
        assertThat(operatorDescriptor.target().kind()).isEqualTo("OPERATOR");
        assertThat(operatorDescriptor.target().id()).isEqualTo("httpResource");
        assertThat(operatorDescriptor.implementationFingerprint()).startsWith("sha256:");
        assertThat(operatorDescriptor.composabilityFingerprint()).startsWith("sha256:");
        assertThat(operatorDescriptor.composabilityManifest())
                .containsEntry("dependencyMode", "DECLARED")
                .containsEntry("globalStateFree", true);
        assertThat(operatorDescriptor.testabilityClass()).isEqualTo("CONDITIONAL_TRANSPORT");
        assertThat(operatorDescriptor.certificationEligible()).isTrue();
        assertThat(operatorDescriptor.certificationRequirements())
                .anyMatch(requirement -> requirement.contains("TRANSPORT"));

        FixtureBundle fixture = new FixtureBundle("", "suite-fixture", 1,
                descriptor.target().fingerprint(), "INTERNAL", null, null,
                List.of(new FixtureRule("", "applicant-profile",
                        FixtureRule.Selector.resource("loan-applicant-service.getProfile")
                                .matching(FixtureRule.Match.pathEquals(
                                        "/params", Map.of("applicantId", "prime"))),
                        FixtureRule.Behavior.protocolResponse(
                                "{\"code\":0,\"message\":\"OK\",\"data\":{\"applicantId\":\"prime\",\"score\":780,\"segment\":\"private-bank\"}}",
                                200, Map.of(), FixtureRule.DoubleBoundary.TRANSPORT),
                        new FixtureRule.Consumption(true, 1, 2,
                                FixtureRule.ExhaustedAction.FAIL,
                                FixtureRule.UnmatchedAction.FAIL),
                        FixtureRule.SchemaCheck.strict())),
                List.of(), Map.of());
        String fixtureFingerprint = ProtocolFingerprint.of(objectMapper, fixture);
        fixtureRepository.create(new StoredFixtureBundle("", "tenant-a", "test", "suite-fixture", 1,
                fixtureFingerprint, fixture, Instant.now(), "integration-test"));
        TestSuite suite = new TestSuite("", "suite-integration", 1,
                new TestSuite.Target("GRAPH", descriptor.target().id(), descriptor.target().fingerprint()),
                "INTERNAL", List.of(new TestSuite.TestCase("golden", TestSuite.CaseType.GOLDEN,
                Map.of("applicantId", "prime", "requestedAmount", 450_000.0), new TestSuite.FixtureBundleRef(
                "suite-fixture", 1, fixtureFingerprint), List.of("integration"), Map.of())),
                TestSuite.CoveragePolicy.defaults(), TestSuite.PromotionPolicy.defaults(), Map.of());
        HttpHeaders suiteWriteHeaders = new HttpHeaders();
        suiteWriteHeaders.setBearerAuth("bloge-aneke-demo-token");
        suiteWriteHeaders.set("X-Purpose", "TEST_SUITE_WRITE");
        var registered = restTemplate.exchange("/api/testing/suites/suite-integration", HttpMethod.PUT,
                new HttpEntity<>(new TestSuiteRegistrationRequest("", suite), suiteWriteHeaders),
                StoredTestSuite.class);
        assertThat(registered.getStatusCode())
                .withFailMessage("suite registration failed: %s", registered.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(registered.getBody()).isNotNull();
        assertThat(registered.getBody().fingerprint()).startsWith("sha256:");

        HttpHeaders suiteReadHeaders = new HttpHeaders();
        suiteReadHeaders.setBearerAuth("bloge-aneke-demo-token");
        suiteReadHeaders.set("X-Purpose", "TEST_SUITE_READ");
        var found = restTemplate.exchange("/api/testing/suites/suite-integration?revision=1",
                HttpMethod.GET, new HttpEntity<>(suiteReadHeaders), StoredTestSuite.class);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody()).isEqualTo(registered.getBody());

        TestSuiteExecutionRequest suiteExecution = new TestSuiteExecutionRequest("",
                new TestSuiteExecutionRequest.SuiteRef("suite-integration", 1,
                        registered.getBody().fingerprint()), "integration-suite-run-1",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL, Map.of("source", "spring-http-test"));
        var suiteRun = restTemplate.exchange("/api/testing/suites/suite-integration/executions",
                HttpMethod.POST, new HttpEntity<>(suiteExecution, headers),
                TestSuiteExecutionResponse.class);
        assertThat(suiteRun.getStatusCode())
                .withFailMessage("suite execution failed: %s", suiteRun.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(suiteRun.getBody()).isNotNull();
        String childRunId = suiteRun.getBody().evidence().caseResults().getFirst().runId();
        var childRun = restTemplate.exchange("/api/testing/executions/" + childRunId
                        + "?verbosity=FULL", HttpMethod.GET, new HttpEntity<>(headers),
                TestExecutionApiResponse.class);
        assertThat(childRun.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(suiteRun.getBody().evidence().status())
                .withFailMessage("suite evidence was not passing: %s; child evidence: %s",
                        suiteRun.getBody().evidence(), childRun.getBody().evidence())
                .isEqualTo(com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence.Status.PASSED);
        assertThat(suiteRun.getBody().evidence().promotion().status())
                .isEqualTo(com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence.PromotionStatus.ELIGIBLE);
        assertThat(suiteRun.getBody().evidence().caseResults().getFirst().runId()).isNotBlank();

        var suiteRunRetry = restTemplate.exchange("/api/testing/suites/suite-integration/executions",
                HttpMethod.POST, new HttpEntity<>(suiteExecution, headers),
                TestSuiteExecutionResponse.class);
        assertThat(suiteRunRetry.getBody()).isEqualTo(suiteRun.getBody());
        var suiteRunRead = restTemplate.exchange("/api/testing/suite-executions/"
                        + suiteRun.getBody().suiteRunId(), HttpMethod.GET, new HttpEntity<>(headers),
                TestSuiteExecutionResponse.class);
        assertThat(suiteRunRead.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(suiteRunRead.getBody()).isEqualTo(suiteRun.getBody());
    }
}
