package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioGoldenDemoPackLoaderTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityStudioGoldenDemoPackLoader loader = new CapabilityStudioGoldenDemoPackLoader();

    @Test
    void loadsTheCanonicalFourApiOneFeatureOneToolNineScenarioPack() {
        CapabilityStudioGoldenDemoPack pack = loader.load(mapper);

        assertThat(pack.displayName()).isEqualTo("取消费用争议能力演示包");
        assertThat(pack.owner().name()).isEqualTo("客服技术平台");
        assertThat(pack.apiCapabilities()).hasSize(4);
        assertThat(pack.featureCapabilities()).hasSize(1);
        assertThat(pack.toolCapabilities()).hasSize(1);
        assertThat(pack.scenarios()).hasSize(9);
        assertThat(pack.canonicalBaseline().immutable()).isTrue();
        assertThat(pack.tutorialBranch().behaviorOverrides()).singleElement()
                .extracting(CapabilityStudioGoldenDemoPack.BehaviorOverride::behavior)
                .isEqualTo("TIMEOUT");

        CapabilityStudioGoldenDemoPack.Capability order = pack.apiCapabilities().getFirst();
        assertThat(order.ref().fingerprint())
                .isEqualTo("sha256:6366e6f8ebe08567f51394e22af001e61e765dcbfafbf53f1ccb81d95cacb7e2");
        assertThat(order.contract().inputs()).hasSize(2);
        assertThat(order.contract().inputs().getFirst().name()).isEqualTo("orderId");
        assertThat(order.contract().inputs().getFirst().sensitive()).isFalse();
        assertThat(order.contract().successOutputs()).contains("order.status");
        assertThat(order.contract().errors()).extracting(
                CapabilityStudioGoldenDemoPack.ErrorSummary::code)
                .contains("ORDER_NOT_FOUND", "ORDER_QUERY_TIMEOUT");
        assertThat(order.sideEffect()).isEqualTo("READ_ONLY");
        assertThat(order.sla()).contains("300ms");
        assertThat(pack.scenarios()).allSatisfy(scenario -> {
            assertThat(scenario.source().displayName()).isNotBlank();
            assertThat(scenario.oracle().summary()).isNotBlank();
            assertThat(scenario.lifecycle()).isEqualTo("ACTIVE");
            assertThat(scenario.qualityState()).isEqualTo("DESIGNED_NOT_RUN");
            assertThat(scenario.applicableContractRefs()).isNotEmpty();
        });
    }

    @Test
    void computesTheSamePackFingerprintOnEveryLoad() {
        CapabilityStudioGoldenDemoPack first = loader.load(mapper);
        CapabilityStudioGoldenDemoPack second = loader.load(mapper);

        assertThat(first.packFingerprint()).isEqualTo(second.packFingerprint())
                .matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void rejectsUnknownFields() throws Exception {
        ObjectNode root = readRoot();
        root.put("unexpected", true);

        assertThatThrownBy(() -> loader.load(bytes(root), mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Capability Studio golden demo pack");
    }

    @Test
    void rejectsDuplicateFieldsBeforeProjection() throws Exception {
        byte[] canonical;
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("capability-studio/golden-demo-pack-v1.json")) {
            assertThat(input).isNotNull();
            canonical = input.readAllBytes();
        }
        String duplicate = new String(canonical, StandardCharsets.UTF_8)
                .replaceFirst("\"packId\"", "\"packId\": \"shadow-pack\", \"packId\"");

        assertThatThrownBy(() -> loader.load(
                new ByteArrayInputStream(duplicate.getBytes(StandardCharsets.UTF_8)), mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Capability Studio golden demo pack");
    }

    @Test
    void rejectsCardinalityAndUnclosedReferences() throws Exception {
        ObjectNode missingScenario = readRoot();
        ((com.fasterxml.jackson.databind.node.ArrayNode) missingScenario.get("scenarios")).remove(0);
        assertThatThrownBy(() -> loader.load(bytes(missingScenario), mapper))
                .isInstanceOf(IllegalArgumentException.class);

        ObjectNode missingReference = readRoot();
        ObjectNode sourceRef = (ObjectNode) missingReference.get("scenarios").get(0).get("sourceRef");
        sourceRef.put("fingerprint", "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        assertThatThrownBy(() -> loader.load(bytes(missingReference), mapper))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidFingerprintsAndMissingRequiredContractMetadata() throws Exception {
        ObjectNode invalidFingerprint = readRoot();
        invalidFingerprint.put("packFingerprint", "sha256:not-a-fingerprint");
        assertThatThrownBy(() -> loader.load(bytes(invalidFingerprint), mapper))
                .isInstanceOf(IllegalArgumentException.class);

        ObjectNode missingContractField = readRoot();
        ((ObjectNode) missingContractField.get("apiCapabilities").get(0).get("contract"))
                .remove("successOutputs");
        assertThatThrownBy(() -> loader.load(bytes(missingContractField), mapper))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ObjectNode readRoot() throws IOException {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("capability-studio/golden-demo-pack-v1.json")) {
            assertThat(input).isNotNull();
            return (ObjectNode) mapper.readTree(input);
        }
    }

    private InputStream bytes(ObjectNode root) {
        try {
            return new ByteArrayInputStream(mapper.writeValueAsBytes(root));
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
