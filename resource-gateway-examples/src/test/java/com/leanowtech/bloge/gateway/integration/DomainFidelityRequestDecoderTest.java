package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityInventory;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityInventoryRegistrationRequest;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityPolicy;
import com.leanowtech.bloge.gateway.integration.mirror.DomainFidelityProfile;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioCase;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainFidelityRequestDecoderTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final DomainFidelityRequestDecoder decoder =
            new DomainFidelityRequestDecoder(mapper);
    private final IntegrationRequestContext identity =
            new IntegrationRequestContext(
                    "tenant-a",
                    "support",
                    "refunds",
                    "staging",
                    "sg",
                    "HUMAN",
                    "owner-a",
                    "",
                    DomainFidelityPolicy.GOVERNANCE_PURPOSE,
                    "correlation-a",
                    Set.of(
                            DomainFidelityPolicy.DEFAULT_OWNER_GROUP),
                    "CONFIDENTIAL",
                    "");

    @Test
    void decodesOnlyTheExactVersionedOwnerCommand()
            throws Exception {
        DomainFidelityInventoryRegistrationRequest request =
                request();

        assertThat(decoder.decodeInventoryRegistration(
                mapper.writeValueAsBytes(request),
                identity)).isEqualTo(request);
    }

    @Test
    void rejectsUnknownMissingAndDuplicateFields()
            throws Exception {
        ObjectNode unknown =
                mapper.valueToTree(request());
        unknown.put("approvedBy", "forged-owner");
        assertMalformed(
                mapper.writeValueAsBytes(unknown));

        ObjectNode missing =
                mapper.valueToTree(request());
        missing.remove("expectedPredecessorFingerprint");
        assertMalformed(
                mapper.writeValueAsBytes(missing));

        String json =
                mapper.writeValueAsString(request());
        String duplicate = json.replaceFirst(
                "\"inventoryId\":\"refund-support\"",
                "\"inventoryId\":\"forged\","
                        + "\"inventoryId\":\"refund-support\"");
        assertMalformed(
                duplicate.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsUnsupportedVersionAndRevisionPredecessorMismatch()
            throws Exception {
        ObjectNode version =
                mapper.valueToTree(request());
        version.put("schemaVersion", "future.v2");
        assertMalformed(
                mapper.writeValueAsBytes(version));

        ObjectNode predecessor =
                mapper.valueToTree(request());
        predecessor.put(
                "expectedPredecessorFingerprint",
                "sha256:" + "a".repeat(64));
        assertMalformed(
                mapper.writeValueAsBytes(predecessor));
    }

    @Test
    void rejectsRawAndStructuralBoundsBeforeBinding()
            throws Exception {
        assertMalformed(
                new byte[
                        DomainFidelityRequestDecoder
                                .MAXIMUM_REQUEST_BYTES + 1]);

        ObjectNode deep =
                mapper.valueToTree(request());
        ObjectNode nested = mapper.createObjectNode();
        ObjectNode cursor = nested;
        for (int depth = 0;
             depth
                     < DomainFidelityRequestDecoder.MAXIMUM_DEPTH
                     + 2;
             depth++) {
            ObjectNode next = mapper.createObjectNode();
            cursor.set("child", next);
            cursor = next;
        }
        deep.set("taxonomyRef", nested);
        assertMalformed(mapper.writeValueAsBytes(deep));
    }

    private void assertMalformed(byte[] body) {
        assertThatThrownBy(() ->
                decoder.decodeInventoryRegistration(
                        body, identity))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(
                                failure.problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.FIDELITY.REQUEST_MALFORMED"));
    }

    private static DomainFidelityInventoryRegistrationRequest
    request() {
        return new DomainFidelityInventoryRegistrationRequest(
                "",
                "refund-support",
                1,
                "",
                "refund-domain",
                ref(
                        "DOMAIN_FIDELITY_TAXONOMY",
                        "refund-taxonomy",
                        'f'),
                List.of(
                        new DomainFidelityInventory
                                .CoverageUnit(
                                "refund-golden",
                                ref(
                                        "SCENARIO_CASE",
                                        "refund-golden",
                                        'a'),
                                ref(
                                        "CAPABILITY",
                                        "refund",
                                        'b'),
                                ScenarioCase.CaseType.GOLDEN,
                                List.of(
                                        DomainFidelityProfile
                                                .Dimension.BEHAVIOR,
                                        DomainFidelityProfile
                                                .Dimension.CONTRACT))),
                Instant.parse("2026-07-26T04:00:00Z"),
                Instant.parse("2027-07-26T04:00:00Z"));
    }

    private static MirrorArtifactRef ref(
            String kind, String id, char material) {
        return new MirrorArtifactRef(
                kind,
                id,
                1,
                "sha256:" + String.valueOf(material)
                        .repeat(64));
    }
}
