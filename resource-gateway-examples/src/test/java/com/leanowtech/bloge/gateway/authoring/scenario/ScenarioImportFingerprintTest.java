package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioImportFingerprintTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void recursivelySortsTreePropertiesIndependentlyOfInsertionOrder() {
        ObjectNode left = mapper.createObjectNode();
        left.putObject("nested").put("z", 2).put("a", 1);
        left.put("alpha", true);

        ObjectNode right = mapper.createObjectNode();
        right.put("alpha", true);
        right.putObject("nested").put("a", 1).put("z", 2);

        assertThat(fingerprint(left)).isEqualTo(fingerprint(right));
        assertThat(fingerprint(left)).isEqualTo(fingerprint(Map.of(
                "alpha", true,
                "nested", Map.of("a", 1, "z", 2))));
    }

    @Test
    void matchesTheBrowserCanonicalJsonGoldenVector() {
        ObjectNode material = mapper.createObjectNode();
        material.put("kind", "CSV");
        material.put("encoding", "UTF-8");
        material.put("delimiter", ",");
        material.put("parser", "papaparse-v5");
        material.put("text", "id,name\nA,Case A");

        assertThat(fingerprint(material)).isEqualTo(
                "sha256:1bd05be8e4c511fd47b1d52c21f4d689e96e1fc413ca36d8e07a0f4e55a70997");
    }

    @Test
    void enforcesTheCanonicalByteBudget() {
        assertThatThrownBy(() -> ScenarioImportFingerprint.of(mapper, Map.of("value", "too long"), 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds 4 bytes");
    }

    private String fingerprint(Object value) {
        return ScenarioImportFingerprint.of(mapper, value, 1_048_576);
    }
}
