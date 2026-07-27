package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoritativeOutcomeContinuousAssessmentLifecycleVerifierTest {
    private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules();
    private static final String FINGERPRINT =
            "sha256:" + "a".repeat(64);
    private final AuthoritativeOutcomeContinuousAssessmentLifecycleVerifier
            verifier =
            new AuthoritativeOutcomeContinuousAssessmentLifecycleVerifier();

    @Test
    void verifiesContiguousProjectionAndEventClosure() {
        ObjectNode page = page();

        var result = verifier.verify(
                page, 0, "");

        assertThat(result.verified()).isTrue();
        assertThat(result.projectionId())
                .isEqualTo("refund-completeness");
        assertThat(result.nextOrdinal())
                .isEqualTo(2);
        assertThat(result.eventFingerprint())
                .isEqualTo(page.path("events")
                        .get(1)
                        .path("eventFingerprint")
                        .asText());
        assertThat(result.projectionFingerprint())
                .isEqualTo(page.path("events")
                        .get(1)
                        .path("projection")
                        .path("recordFingerprint")
                        .asText());
    }

    @Test
    void rejectsProducerSelectedCursorAndBrokenPredecessor() {
        ObjectNode page = page();

        assertThat(verifier.verify(
                page, 1, FINGERPRINT)
                .reasonCode()).isEqualTo(
                "OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_CURSOR_INVALID");

        ((ObjectNode) page.path("events")
                .get(1))
                .put(
                        "previousEventFingerprint",
                        FINGERPRINT);
        assertThat(verifier.verify(
                page, 0, "")
                .reasonCode()).isEqualTo(
                "OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_CHAIN_INVALID");
    }

    @Test
    void rejectsProjectionAndEventMutationAfterAddressing() {
        ObjectNode page = page();
        ((ObjectNode) page.path("events")
                .get(1)
                .path("projection"))
                .put("attemptCount", 2);

        assertThat(verifier.verify(
                page, 0, "")
                .verified()).isFalse();

        page = page();
        ((ObjectNode) page.path("events")
                .get(1))
                .put("actorFingerprint",
                        "sha256:" + "b".repeat(64));
        assertThat(verifier.verify(
                page, 0, "")
                .reasonCode()).isEqualTo(
                "OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_FINGERPRINT_INVALID");
    }

    @Test
    void rejectsReaddressedTransitionActorMismatch() {
        ObjectNode page = page();
        ObjectNode claimed =
                (ObjectNode) page.path("events")
                        .get(1);
        claimed.put(
                "actorFingerprint",
                "sha256:" + "b".repeat(64));
        claimed.put(
                "eventFingerprint", "");
        claimed.put(
                "eventFingerprint",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                claimed,
                                AuthoritativeOutcomeContinuousAssessmentLifecycleVerifier
                                        .MAXIMUM_EVENT_BYTES));

        assertThat(verifier.verify(
                page, 0, "")
                .reasonCode()).isEqualTo(
                "OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_TRANSITION_INVALID");
    }

    private static ObjectNode page() {
        ObjectNode initial = (ObjectNode)
                AuthoritativeOutcomeContinuousAssessmentVerifierTest
                        .uninitializedStatus()
                        .path("projection")
                        .deepCopy();
        ObjectNode registered = event(
                1,
                "REGISTERED",
                "",
                initial,
                "");

        ObjectNode running =
                initial.deepCopy();
        running.put("status", "RUNNING");
        running.put("attemptCount", 1);
        running.put(
                "leaseOwnerFingerprint",
                FINGERPRINT);
        running.put("leaseEpoch", 1);
        running.put(
                "leaseExpiresAt",
                "2026-07-27T00:00:11Z");
        running.put(
                "updatedAt",
                "2026-07-27T00:00:01Z");
        AuthoritativeOutcomeContinuousAssessmentVerifierTest
                .seal(running);
        ObjectNode claimed = event(
                2,
                "CLAIMED",
                FINGERPRINT,
                running,
                registered.path(
                        "eventFingerprint")
                        .asText());

        ObjectNode page =
                JSON.createObjectNode();
        page.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_PAGE_V1);
        page.put(
                "projectionId",
                "refund-completeness");
        page.put("afterOrdinal", 0);
        page.put(
                "predecessorFingerprint", "");
        page.put("nextOrdinal", 2);
        page.put("hasMore", false);
        ArrayNode events =
                page.putArray("events");
        events.add(registered);
        events.add(claimed);
        return page;
    }

    private static ObjectNode event(
            long ordinal,
            String transition,
            String actorFingerprint,
            ObjectNode projection,
            String previousEventFingerprint) {
        ObjectNode event =
                JSON.createObjectNode();
        event.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_EVENT_V1);
        event.put("eventOrdinal", ordinal);
        event.put("transition", transition);
        event.put(
                "occurredAt",
                projection.path("updatedAt")
                        .asText());
        event.put(
                "actorFingerprint",
                actorFingerprint);
        event.set(
                "projection",
                projection.deepCopy());
        event.put(
                "previousEventFingerprint",
                previousEventFingerprint);
        event.put("eventFingerprint", "");
        event.put(
                "eventFingerprint",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                event,
                                AuthoritativeOutcomeContinuousAssessmentLifecycleVerifier
                                        .MAXIMUM_EVENT_BYTES));
        return event;
    }
}
