package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoritativeOutcomeInboxLifecycleVerifierTest {
    private static final String OBSERVATION =
            "sha256:" + "a".repeat(64);
    private static final String OWNER =
            "sha256:" + "b".repeat(64);
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AuthoritativeOutcomeInboxLifecycleVerifier verifier =
            new AuthoritativeOutcomeInboxLifecycleVerifier();

    @Test
    void verifiesCompleteContentAddressedLifecycleAgainstCurrentHead()
            throws Exception {
        Fixture fixture = fixture();

        var result = verifier.verify(
                fixture.entry(), fixture.page());

        assertThat(result.verified())
                .as(result.toString())
                .isTrue();
        assertThat(result.complete()).isTrue();
        assertThat(result.reasonCode())
                .isEqualTo("VERIFIED_COMPLETE");
        assertThat(result.observationId())
                .isEqualTo("outcome-refund");
        assertThat(result.nextOrdinal()).isEqualTo(2);
    }

    @Test
    void acceptsAValidSuffixWithoutClaimingCompleteClosure()
            throws Exception {
        Fixture fixture = fixture();
        ObjectNode suffix = fixture.page().deepCopy();
        suffix.put("afterOrdinal", 1);
        suffix.put("nextOrdinal", 2);
        ArrayNode events = mapper.createArrayNode();
        events.add(fixture.page().path("events").get(1));
        suffix.set("events", events);

        var result = verifier.verify(
                fixture.entry(), suffix);

        assertThat(result.verified())
                .as(result.toString())
                .isTrue();
        assertThat(result.complete()).isFalse();
        assertThat(result.reasonCode())
                .isEqualTo("VERIFIED_PAGE");
    }

    @Test
    void rejectsBrokenEventPredecessorEvenWhenShapeRemainsValid()
            throws Exception {
        Fixture fixture = fixture();
        ObjectNode tampered = fixture.page().deepCopy();
        ((ObjectNode) tampered.path("events").get(1))
                .put(
                        "previousEventFingerprint",
                        "sha256:" + "c".repeat(64));
        reseal(
                (ObjectNode) tampered.path("events").get(1),
                "eventFingerprint",
                AuthoritativeOutcomeInboxLifecycleVerifier
                        .MAXIMUM_EVENT_BYTES);

        var result = verifier.verify(
                fixture.entry(), tampered);

        assertThat(result.verified()).isFalse();
        assertThat(result.reasonCode())
                .isEqualTo(
                        "OUTCOME_LIFECYCLE_PREDECESSOR_INVALID");
    }

    private Fixture fixture() throws Exception {
        ObjectNode first = (ObjectNode) mapper.readTree("""
                {
                  "schemaVersion":"resourceGateway.authoritativeOutcomeInboxLifecycle.v1",
                  "scope":{
                    "tenantId":"tenant-a",
                    "organizationId":"support",
                    "projectId":"refunds",
                    "environmentId":"staging",
                    "region":"sg"
                  },
                  "observationId":"outcome-refund",
                  "eventOrdinal":1,
                  "transition":"OBSERVATION_APPENDED",
                  "status":"QUEUED",
                  "observationRevision":1,
                  "observationFingerprint":"%s",
                  "predecessorObservationFingerprint":"",
                  "reconciliation":"PENDING",
                  "attemptCount":0,
                  "consecutiveFailures":0,
                  "leaseEpoch":0,
                  "ownerFingerprint":"",
                  "occurredAt":"2026-07-26T04:00:00Z",
                  "failureCode":"",
                  "previousEventFingerprint":"",
                  "eventFingerprint":""
                }
                """.formatted(OBSERVATION));
        reseal(
                first,
                "eventFingerprint",
                AuthoritativeOutcomeInboxLifecycleVerifier
                        .MAXIMUM_EVENT_BYTES);
        ObjectNode second = (ObjectNode) mapper.readTree("""
                {
                  "schemaVersion":"resourceGateway.authoritativeOutcomeInboxLifecycle.v1",
                  "scope":{
                    "tenantId":"tenant-a",
                    "organizationId":"support",
                    "projectId":"refunds",
                    "environmentId":"staging",
                    "region":"sg"
                  },
                  "observationId":"outcome-refund",
                  "eventOrdinal":2,
                  "transition":"CLAIMED",
                  "status":"RUNNING",
                  "observationRevision":1,
                  "observationFingerprint":"%s",
                  "predecessorObservationFingerprint":"%s",
                  "reconciliation":"PENDING",
                  "attemptCount":1,
                  "consecutiveFailures":0,
                  "leaseEpoch":1,
                  "ownerFingerprint":"%s",
                  "occurredAt":"2026-07-26T04:00:01Z",
                  "failureCode":"",
                  "previousEventFingerprint":"%s",
                  "eventFingerprint":""
                }
                """.formatted(
                OBSERVATION,
                OBSERVATION,
                OWNER,
                first.path("eventFingerprint").asText()));
        reseal(
                second,
                "eventFingerprint",
                AuthoritativeOutcomeInboxLifecycleVerifier
                        .MAXIMUM_EVENT_BYTES);
        ObjectNode entry = (ObjectNode) mapper.readTree("""
                {
                  "schemaVersion":"resourceGateway.authoritativeOutcomeInboxEntry.v1",
                  "scope":{
                    "tenantId":"tenant-a",
                    "organizationId":"support",
                    "projectId":"refunds",
                    "environmentId":"staging",
                    "region":"sg"
                  },
                  "observationId":"outcome-refund",
                  "currentRevision":1,
                  "currentObservationFingerprint":"%s",
                  "inventoryRef":{
                    "kind":"DOMAIN_FIDELITY_INVENTORY",
                    "id":"refund-support",
                    "revision":1,
                    "fingerprint":"%s"
                  },
                  "unitId":"refund-boundary",
                  "cohortRef":{
                    "kind":"OUTCOME_CALIBRATION_COHORT",
                    "id":"refunds-2026-07",
                    "revision":1,
                    "fingerprint":"%s"
                  },
                  "reconciliation":"PENDING",
                  "status":"RUNNING",
                  "attemptCount":1,
                  "consecutiveFailures":0,
                  "nextEligibleAt":"2026-07-26T04:00:00Z",
                  "leaseOwnerFingerprint":"%s",
                  "leaseEpoch":1,
                  "leaseExpiresAt":"2026-07-26T04:00:31Z",
                  "failureCode":"",
                  "createdAt":"2026-07-26T04:00:00Z",
                  "updatedAt":"2026-07-26T04:00:01Z",
                  "terminalAt":null,
                  "recordFingerprint":""
                }
                """.formatted(
                OBSERVATION,
                "sha256:" + "d".repeat(64),
                "sha256:" + "e".repeat(64),
                OWNER));
        reseal(
                entry,
                "recordFingerprint",
                AuthoritativeOutcomeInboxLifecycleVerifier
                        .MAXIMUM_ENTRY_BYTES);
        ObjectNode page = mapper.createObjectNode();
        page.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_INBOX_LIFECYCLE_PAGE_V1);
        page.put("observationId", "outcome-refund");
        page.put("afterOrdinal", 0);
        page.put("nextOrdinal", 2);
        page.put("hasMore", false);
        ArrayNode events = mapper.createArrayNode();
        events.add(first);
        events.add(second);
        page.set("events", events);
        return new Fixture(entry, page);
    }

    private static void reseal(
            ObjectNode value,
            String field,
            int maximumBytes) {
        value.put(field, "");
        value.put(
                field,
                EvidenceVerificationSupport.sha256Bounded(
                        value, maximumBytes));
    }

    private record Fixture(
            ObjectNode entry,
            ObjectNode page) {
    }
}
