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
        assertThat(exact.matchedFacets()).contains("semanticKey", "businessObject", "resultDomain",
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

    private JsonNode contract() throws Exception {
        return mapper.readTree("""
                {
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
