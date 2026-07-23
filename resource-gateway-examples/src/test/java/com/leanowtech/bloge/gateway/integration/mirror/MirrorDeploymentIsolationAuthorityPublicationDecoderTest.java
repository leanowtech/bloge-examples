package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.MirrorDeploymentIsolationAuthorityPublicationDecoder;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorDeploymentIsolationAuthorityPublicationDecoderTest {
    private final MirrorDeploymentIsolationAuthorityPublicationTestFixtures fixtures =
            new MirrorDeploymentIsolationAuthorityPublicationTestFixtures();
    private final MirrorDeploymentIsolationAuthorityPublicationDecoder decoder =
            new MirrorDeploymentIsolationAuthorityPublicationDecoder(fixtures.mapper);

    @Test
    void decodesTheExactClosedCanonicalPublication() throws Exception {
        var publication = fixtures.publication(1, "");

        assertThat(decoder.decode(fixtures.mapper.writeValueAsBytes(publication), identity()))
                .isEqualTo(publication);
    }

    @Test
    void rejectsDuplicateKeysBeforeTreeMaterialization() throws Exception {
        String json = fixtures.mapper.writeValueAsString(fixtures.publication(1, ""));
        String duplicate = json.replaceFirst("\\{", "{\"schemaVersion\":\"duplicate\",");

        assertMalformed(duplicate.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void rejectsUnknownFieldsAtEveryNestedRecordBoundary() {
        ObjectNode root = fixtures.mapper.valueToTree(fixtures.publication(1, ""));
        ((ObjectNode) root.path("material")).put("callerSelectedRoot", "forbidden");

        assertMalformed(bytes(root));
    }

    @Test
    void rejectsWrongVersionOversizedAndExcessivelyDeepBodies() {
        ObjectNode wrongVersion = fixtures.mapper.valueToTree(fixtures.publication(1, ""));
        wrongVersion.put("schemaVersion", "resourceGateway.mirrorAuthorityKeySet.v2");
        assertMalformed(bytes(wrongVersion));

        assertMalformed(new byte[
                MirrorDeploymentIsolationAuthorityPublicationDecoder.MAXIMUM_REQUEST_BYTES + 1]);

        ObjectNode deep = fixtures.mapper.valueToTree(fixtures.publication(1, ""));
        ObjectNode cursor = fixtures.mapper.createObjectNode();
        ((ObjectNode) deep.path("material")).set("unexpected", cursor);
        for (int index = 0;
             index < MirrorDeploymentIsolationAuthorityPublicationDecoder.MAXIMUM_DEPTH + 2;
             index++) {
            ObjectNode next = fixtures.mapper.createObjectNode();
            cursor.set("next", next);
            cursor = next;
        }
        assertMalformed(bytes(deep));
    }

    private byte[] bytes(ObjectNode value) {
        try {
            return fixtures.mapper.writeValueAsBytes(value);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void assertMalformed(byte[] value) {
        assertThatThrownBy(() -> decoder.decode(value, identity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(400);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.MIRROR.AUTHORITY_PUBLICATION_MALFORMED");
                    assertThat(failure.problem().details())
                            .containsEntry("maximumBytes",
                                    MirrorDeploymentIsolationAuthorityPublicationDecoder
                                            .MAXIMUM_REQUEST_BYTES)
                            .containsEntry("maximumDepth",
                                    MirrorDeploymentIsolationAuthorityPublicationDecoder
                                            .MAXIMUM_DEPTH);
                });
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "staging",
                "ap-southeast-1", "SERVICE", "trust-admin", "", "MIRROR_TRUST_ADMIN",
                "corr-decoder", Set.of(), "RESTRICTED", "");
    }
}
