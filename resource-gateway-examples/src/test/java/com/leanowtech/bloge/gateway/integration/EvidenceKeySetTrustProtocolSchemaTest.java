package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.EvidenceVerificationKeySet;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

import static com.leanowtech.bloge.gateway.integration.EvidenceKeySetTrustTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceKeySetTrustProtocolSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void machineSchemasMatchEverySerializedProtocolFieldAndExcludeTrustKeys() throws Exception {
        Authority authority = new Authority("security-a", keyPair());
        EvidenceKeySetTrustPublication publication = publication(mapper, 1, "", 0,
                PUBLISHED_AT, List.of(active(SNAPSHOT_A)), List.of(authority));
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        EvidenceVerificationKeySet keySet = EvidenceVerificationKeySet.publish(
                mapper, signer, signer.resolveKeySet().keySet());
        EvidenceKeySetTrustBundle bundle = new EvidenceKeySetTrustBundle("", PUBLISHED_AT,
                TRUST_DOMAIN, LOG_ID, 0, 1, 1, publication.publicationFingerprint(), publication,
                false, List.of(publication), keySet);
        EvidenceKeySetTrustStore.Descriptor descriptor =
                new ConfiguredEvidenceKeySetTrustStore(mapper, TRUST_DOMAIN, LOG_ID, 1,
                        List.of(new ConfiguredEvidenceKeySetTrustStore.AuthorityKey(
                                authority.authorityId(), authority.keyPair().getPublic(),
                                java.time.Instant.MIN, java.time.Instant.MAX, true, false)))
                        .descriptor();

        JsonNode publicationSchema = schema("evidence-key-set-trust-publication-v1.schema.json");
        JsonNode bundleSchema = schema("evidence-key-set-trust-bundle-v1.schema.json");
        JsonNode descriptorSchema = schema("evidence-trust-store-descriptor-v1.schema.json");
        assertProperties(mapper.valueToTree(publication),
                publicationSchema.at("/$defs/publication/properties"));
        assertProperties(mapper.valueToTree(publication.pins().getFirst()),
                publicationSchema.at("/$defs/pin/properties"));
        assertProperties(mapper.valueToTree(publication.signatures().getFirst()),
                publicationSchema.at("/$defs/signature/properties"));
        assertProperties(mapper.valueToTree(bundle), bundleSchema.path("properties"));
        assertProperties(mapper.valueToTree(descriptor), descriptorSchema.path("properties"));

        String wire = mapper.writeValueAsString(publication) + mapper.writeValueAsString(descriptor);
        assertThat(wire).doesNotContain("publicKey", "privateKey", "encodedPublicKey",
                "trustedAuthorities");
        assertThat(publicationSchema.at("/$defs/publication/additionalProperties").asBoolean(true))
                .isFalse();
        assertThat(bundleSchema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(bundleSchema.at("/properties/publications/maxItems").asInt())
                .isEqualTo(EvidenceKeySetTrustBundle.MAX_PUBLICATIONS);
    }

    private JsonNode schema(String file) throws Exception {
        return mapper.readTree(Files.readString(Path.of("..", "docs", "schemas",
                "tool-studio-resource-gateway", file)));
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }
}
