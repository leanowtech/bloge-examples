package com.leanowtech.bloge.gateway.solution.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies field-level semantic identity decisions and their explanations. */
class BusinessContractMatcherTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BusinessContractMatcher matcher = new BusinessContractMatcher();

    @Test
    void exactRequiresEveryBusinessIdentityDimension() throws Exception {
        JsonNode contract = contract();
        BusinessContractMatcher.Match exact = matcher.match(contract, contract);

        assertThat(exact.type()).isEqualTo(BusinessContractMatcher.MatchType.EXACT);
        assertThat(exact.matchedFacets()).contains("schemaVersion", "semanticKey", "businessObject", "resultDomain",
                "asOf", "unknownPolicy", "acquisitionOwner", "requiredContext");
        assertThat(exact.missingFacets()).isEmpty();
        assertThat(exact.conflicts()).isEmpty();
    }

    @Test
    void missingSemanticKeyIsPartialAndUnknownPolicyMismatchIsConflict() throws Exception {
        JsonNode contract = contract();
        JsonNode incomplete = contract.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) incomplete).remove("semanticKey");
        JsonNode conflict = contract.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) conflict).put("unknownPolicy", "USE_DEFAULT");

        assertThat(matcher.match(incomplete, contract).type())
                .isEqualTo(BusinessContractMatcher.MatchType.PARTIAL);
        assertThat(matcher.match(incomplete, contract).missingFacets()).contains("semanticKey");
        assertThat(matcher.match(conflict, contract).type())
                .isEqualTo(BusinessContractMatcher.MatchType.CONFLICT);
        assertThat(matcher.match(conflict, contract).conflicts()).contains("unknownPolicy");
    }

    @Test
    void sameContextKeyWithDifferentTypeIsConflict() throws Exception {
        JsonNode contract = contract();
        JsonNode query = contract.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) query.at("/requiredContext/0"))
                .put("type", "integer");

        assertThat(matcher.match(query, contract).conflicts()).contains("requiredContext");
    }

    @Test
    void explicitEmptyContextCanMatchExactly() throws Exception {
        JsonNode query = contract().deepCopy();
        JsonNode candidate = contract().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) query).putArray("requiredContext");
        ((com.fasterxml.jackson.databind.node.ObjectNode) candidate).putArray("requiredContext");

        assertThat(matcher.match(query, candidate).type())
                .isEqualTo(BusinessContractMatcher.MatchType.EXACT);
    }

    @Test
    void candidateExtraRequiredContextPreventsExactReuse() throws Exception {
        JsonNode query = contract();
        JsonNode candidate = contract().deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) candidate.path("requiredContext"))
                .addObject().put("semanticKey", "ride-order.region")
                .put("name", "region").put("type", "string").put("required", true);

        BusinessContractMatcher.Match result = matcher.match(query, candidate);

        assertThat(result.type()).isEqualTo(BusinessContractMatcher.MatchType.PARTIAL);
        assertThat(result.missingFacets()).contains("requiredContext");
    }

    @Test
    void differentBusinessProfilesConflictEvenWhenTheirSharedEnvelopeMatches() throws Exception {
        JsonNode feature = contract();
        JsonNode instruction = contract().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) instruction)
                .put("schemaVersion", "rg.businessInstructionSemanticContract.v1");

        BusinessContractMatcher.Match result = matcher.match(instruction, feature);

        assertThat(result.type()).isEqualTo(BusinessContractMatcher.MatchType.CONFLICT);
        assertThat(result.conflicts()).contains("schemaVersion");
    }

    @Test
    void missingBusinessProfileCanRecallButCannotBeExact() throws Exception {
        JsonNode feature = contract();
        JsonNode legacyQuery = feature.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacyQuery).remove("schemaVersion");

        BusinessContractMatcher.Match result = matcher.match(legacyQuery, feature);

        assertThat(result.type()).isEqualTo(BusinessContractMatcher.MatchType.PARTIAL);
        assertThat(result.missingFacets()).contains("schemaVersion");
    }

    private JsonNode contract() throws Exception {
        return mapper.readTree("""
                {
                  "schemaVersion":"rg.businessFactSemanticContract.v1",
                  "semanticKey":"ride.cancel.party",
                  "intent":"判断取消责任",
                  "domain":"ride-cancellation",
                  "businessObject":"ride-order",
                  "requiredContext":[{"semanticKey":"ride-order.id","name":"orderId","type":"string","required":true}],
                  "resultDomain":{"type":"enum","values":["PASSENGER","DRIVER","PLATFORM","UNKNOWN"]},
                  "asOf":"CANCELLATION_OCCURRED_AT",
                  "unknownPolicy":"REQUIRE_HUMAN_REVIEW",
                  "acquisitionOwner":"PLATFORM",
                  "authoritySource":"responsibility-center",
                  "freshness":{"mode":"AS_OF_EVENT"},
                  "effect":"READ"
                }
                """);
    }
}
