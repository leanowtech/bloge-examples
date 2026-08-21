package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioEvidenceCliBuildIdentityTest {
    private static final String RESOURCE =
            "/META-INF/bloge/"
                    + "capability-studio-execution-lease-evidence-cli-build-identity-v1.json";

    @Test
    void buildIdentityStrictlyBindsCurrentSourceAndProductionClass() throws Exception {
        byte[] bytes = CapabilityStudioEvidenceCliBuildIdentityTest.class
                .getResourceAsStream(RESOURCE).readAllBytes();
        JsonNode identity = new ObjectMapper().readTree(bytes);
        assertThat(identity.fieldNames()).toIterable().containsExactly(
                "messageVersion", "sourcePath", "sourceFingerprint", "className",
                "classFingerprint", "identityFingerprint");
        assertThat(identity.path("messageVersion").asText()).isEqualTo(
                "bloge.capability-studio.execution-lease-evidence-cli-build-identity.v1");
        assertThat(identity.path("sourcePath").asText()).isEqualTo(
                "src/main/java/com/leanowtech/bloge/gateway/testkit/"
                        + "CapabilityStudioExecutionLeaseEvidenceCli.java");
        assertThat(identity.path("className").asText()).isEqualTo(
                CapabilityStudioExecutionLeaseEvidenceCli.class.getName());

        Path module = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        assertThat(identity.path("sourceFingerprint").asText()).isEqualTo(
                sha256(Files.readAllBytes(module.resolve(identity.path("sourcePath").asText()))));
        Path productionClass = module.resolve("target/classes/")
                .resolve(identity.path("className").asText().replace('.', '/') + ".class");
        assertThat(identity.path("classFingerprint").asText()).isEqualTo(
                sha256(Files.readAllBytes(productionClass)));

        String canonical = json(identity, null);
        assertThat(identity.path("identityFingerprint").asText()).isEqualTo(
                sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(
                json(identity, identity.path("identityFingerprint").asText()) + "\n");
    }

    private static String json(JsonNode identity, String fingerprint) {
        List<String> values = List.of("messageVersion", "sourcePath", "sourceFingerprint",
                "className", "classFingerprint");
        StringBuilder json = new StringBuilder("{");
        for (int index = 0; index < values.size(); index++) {
            String name = values.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(name).append("\":\"")
                    .append(identity.path(name).asText()).append('"');
        }
        return json.append(",\"identityFingerprint\":")
                .append(fingerprint == null ? "null" : "\"" + fingerprint + "\"")
                .append('}').toString();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
