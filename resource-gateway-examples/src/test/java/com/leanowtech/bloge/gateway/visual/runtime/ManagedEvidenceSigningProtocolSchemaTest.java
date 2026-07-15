package com.leanowtech.bloge.gateway.visual.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedEvidenceSigningProtocolSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void machineSchemasMatchEverySerializedProtocolFieldAndForbidPrivateMaterial() throws Exception {
        ManagedEvidenceSigningProvider.ManagedKey key = new ManagedEvidenceSigningProvider.ManagedKey(
                "kms://evidence/1", "Ed25519", "MCowBQYDK2VwAyEAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa=",
                Instant.parse("2026-07-12T00:00:00Z"), "ACTIVE", "provider/version/1",
                Instant.parse("2026-07-12T00:00:00Z"), null);
        ManagedEvidenceSigningProvider.KeyLifecycleEvent created =
                new ManagedEvidenceSigningProvider.KeyLifecycleEvent(1, "event-1", key.keyId(),
                        "CREATED", key.createdAt(), key.createdAt(), "", null, "KEY_CREATED");
        ManagedEvidenceSigningProvider.KeyLifecycleEvent activated =
                new ManagedEvidenceSigningProvider.KeyLifecycleEvent(2, "event-2", key.keyId(),
                        "ACTIVATED", key.createdAt(), key.createdAt(), "", null, "KEY_ACTIVATED");
        ManagedEvidenceSigningProvider.KeySet keySet = new ManagedEvidenceSigningProvider.KeySet(
                ManagedEvidenceSigningProvider.KeySet.SCHEMA_VERSION,
                Instant.parse("2026-07-13T00:00:00Z"), Instant.parse("2026-07-13T00:05:00Z"),
                key.keyId(), List.of(key), "COMPLETE", List.of(created, activated));
        ManagedEvidenceSigningProvider.SignatureRequest request =
                new ManagedEvidenceSigningProvider.SignatureRequest("", "f386bb24-c852-4adc-8e90-e7654d58fd4a",
                        key.keyId(), "Ed25519", "sha256:" + "a".repeat(64));
        ManagedEvidenceSigningProvider.SignatureResult response =
                new ManagedEvidenceSigningProvider.SignatureResult(
                        ManagedEvidenceSigningProvider.SignatureResult.SCHEMA_VERSION, request.requestId(),
                        key.keyId(), "Ed25519", request.materialFingerprint(),
                        Instant.parse("2026-07-13T00:00:01Z"), "A".repeat(86) + "==");
        VisualEvidenceSigner.Descriptor descriptor = new VisualEvidenceSigner.Descriptor(
                "", "MANAGED_KMS_HSM", "corp-hsm", true, "HEALTHY", key.keyId(), true, false, 1,
                Instant.parse("2026-07-13T00:00:00Z"), Instant.parse("2026-07-13T00:05:00Z"),
                1, 0, Map.of("privateMaterialPresent", false));
        InMemoryVisualEvidenceSigner localSigner = new InMemoryVisualEvidenceSigner();
        EvidenceVerificationKeySet publicKeySet = EvidenceVerificationKeySet.publish(mapper,
                localSigner, localSigner.resolveKeySet().keySet());

        JsonNode keySchema = schema("managed-evidence-signing-keys-v2.schema.json");
        JsonNode requestSchema = schema("managed-evidence-sign-request-v1.schema.json");
        JsonNode responseSchema = schema("managed-evidence-sign-response-v1.schema.json");
        JsonNode descriptorSchema = schema("evidence-signer-descriptor-v1.schema.json");
        JsonNode publicKeySetSchema = schema("evidence-verification-key-set-v1.schema.json");

        assertProperties(mapper.valueToTree(keySet), keySchema.path("properties"));
        assertProperties(mapper.valueToTree(key), keySchema.at("/$defs/key/properties"));
        assertProperties(mapper.valueToTree(created),
                keySchema.at("/$defs/lifecycleEvent/properties"));
        assertProperties(mapper.valueToTree(request), requestSchema.path("properties"));
        assertProperties(mapper.valueToTree(response), responseSchema.path("properties"));
        assertProperties(mapper.valueToTree(descriptor), descriptorSchema.path("properties"));
        assertProperties(mapper.valueToTree(publicKeySet), publicKeySetSchema.path("properties"));
        assertProperties(mapper.valueToTree(publicKeySet.keys().getFirst()),
                publicKeySetSchema.at("/$defs/key/properties"));
        assertProperties(mapper.valueToTree(publicKeySet.events().getFirst()),
                publicKeySetSchema.at("/$defs/event/properties"));
        assertProperties(mapper.valueToTree(publicKeySet.attestation()),
                publicKeySetSchema.at("/$defs/attestation/properties"));

        for (String file : List.of("managed-evidence-signing-keys-v1.schema.json",
                "managed-evidence-signing-keys-v2.schema.json",
                "managed-evidence-sign-request-v1.schema.json",
                "managed-evidence-sign-response-v1.schema.json",
                "evidence-signer-descriptor-v1.schema.json",
                "evidence-verification-key-set-v1.schema.json")) {
            String source = Files.readString(schemaPath(file));
            assertThat(source).doesNotContain("\"privateKey\"", "\"encodedPrivateKey\"",
                    "\"privateMaterial\"");
        }
    }

    private JsonNode schema(String file) throws Exception {
        return mapper.readTree(Files.readString(schemaPath(file)));
    }

    private static Path schemaPath(String file) {
        return Path.of("..", "docs", "schemas", "tool-studio-resource-gateway", file);
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
}
