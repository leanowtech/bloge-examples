package com.leanowtech.bloge.gateway.testing.security;

import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationService;
import com.leanowtech.bloge.gateway.visual.simulation.VisualSimulationProductionAdmissionException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves the real Spring servlet chain rejects control headers before DTO parsing. */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.testing.mirror.enabled=true",
                "gateway.seed-descriptors=false",
                "gateway.integration.identity.region=region-a",
                "gateway.integration.identity.groups=resource-gateway-test-runtime-operators",
                "gateway.integration.identity.clearance=RESTRICTED",
                "gateway.integration.identity.allowed-purposes=TEST_EXECUTION,TEST_TARGET_READ",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.active-key-id=integration-test-v1",
                "gateway.testing.durable.worker-quarantines.claim-token-protection.key-ring=integration-test-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.active-key-id=integration-request-index-v1",
                "gateway.testing.durable.worker-quarantines.request-key-protection.key-ring=integration-request-index-v1=HyAdHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "gateway.testing.durable.worker-quarantines.request-key-protection.write-mode=KEYED_ONLY",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.instance-id=integration-replica-a",
                "gateway.testing.durable.worker-quarantines.request-index-rollout.artifact-fingerprint=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "spring.datasource.url=jdbc:h2:mem:execution-control-boundary-application;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "gateway.testing.store.jdbc-url=jdbc:h2:mem:execution-control-boundary-control;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                "server.servlet.context-path=/rg"
        })
class ExecutionControlBoundaryGuardApplicationIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private IntegrationAccessAuditRepository audit;

    @Autowired
    private VisualGraphSimulationService visualSimulationService;

    @Test
    void defaultProductionPolicyRejectsVisualSimulationBeforeMalformedDtoParsing() throws Exception {
        assertThat(applicationContext.getBeansOfType(ExecutionControlBoundaryGuardFilter.class))
                .hasSize(1);

        String correlationId = "s0-d-real-chain";
        String headerValue = "real-chain-sensitive-header-value";
        String businessPayload = "real-chain-sensitive-body-value";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.set("x-bloge-test-inline", headerValue);
        headers.set("X-Environment-Id", "staging");
        headers.set("X-Correlation-Id", correlationId);
        headers.set("X-Tenant-Id", "untrusted-tenant-header");

        ResponseEntity<String> response = restTemplate.exchange(
                "/rg/api/visual/graphs/simulate",
                HttpMethod.POST,
                new HttpEntity<>("not-json " + businessPayload, headers),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody())
                .doesNotContain(headerValue, businessPayload, "x-bloge-test-inline")
                .contains("RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN");

        List<IntegrationAccessAuditRecord> records = audit.recent(100).stream()
                .filter(record -> correlationId.equals(record.correlationId()))
                .toList();
        assertThat(records).hasSize(1);
        IntegrationAccessAuditRecord record = records.getFirst();
        assertThat(record.operation()).isEqualTo("PRODUCTION_RUN_CONTROL_GUARD");
        assertThat(record.outcome()).isEqualTo("DENIED");
        assertThat(record.reasonCode()).isEqualTo("RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN");
        assertThat(record.environmentId()).isEqualTo("prod");
        assertThat(record.tenantId()).isBlank();
        assertThat(record.toString()).doesNotContain(headerValue, businessPayload, "x-bloge-test-inline");
    }

    @Test
    void defaultProductionPolicyAlsoProtectsVisualServiceInTheRealApplicationContext() {
        assertThatThrownBy(() -> visualSimulationService.simulate(
                null, java.util.Map.of("secretBusinessPayload", "must-not-leak"), ""))
                .isInstanceOf(VisualSimulationProductionAdmissionException.class)
                .hasMessage(VisualSimulationProductionAdmissionException.TITLE);
    }
}
