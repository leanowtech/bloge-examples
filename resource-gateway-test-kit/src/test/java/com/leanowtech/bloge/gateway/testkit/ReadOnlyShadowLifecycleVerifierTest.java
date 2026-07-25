package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlyShadowLifecycleVerifierTest {
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-26T00:00:00Z");
    private static final Instant DEADLINE_AT =
            CREATED_AT.plusSeconds(3_600);
    private static final String JOB_ID =
            "shadow-" + "a".repeat(64);
    private static final String REQUEST_FINGERPRINT =
            fingerprint('b');
    private static final String OWNER_FINGERPRINT =
            fingerprint('c');

    private ReadOnlyShadowLifecycleVerifier verifier;
    private ObjectNode job;
    private ObjectNode page;

    @BeforeEach
    void setUp() {
        verifier =
                new ReadOnlyShadowLifecycleVerifier();
        job = failedJob();
        page = completePage(job);
    }

    @Test
    void verifiesACompleteAdmissionRetryAndFailureClosure() {
        ReadOnlyShadowLifecycleVerifier
                .VerificationResult result =
                verifier.verify(job, page);

        assertThat(result.outcome()).isEqualTo(
                ReadOnlyShadowLifecycleVerifier
                        .Outcome.VERIFIED_COMPLETE);
        assertThat(result.verified()).isTrue();
        assertThat(result.complete()).isTrue();
        assertThat(result.jobId()).isEqualTo(JOB_ID);
        assertThat(result.nextSequence())
                .isEqualTo(50);
    }

    @Test
    void rejectsOutOfOrderAppendSequences() {
        ((ObjectNode) page.withArray("events")
                .get(1))
                .put("sequence", 10);

        ReadOnlyShadowLifecycleVerifier
                .VerificationResult result =
                verifier.verify(job, page);

        assertThat(result.outcome()).isEqualTo(
                ReadOnlyShadowLifecycleVerifier
                        .Outcome.INVALID);
        assertThat(result.reasonCode())
                .isEqualTo(
                        "SHADOW_LIFECYCLE_EVENT_CLOSURE_INVALID");
    }

    @Test
    void rejectsAHeadThatDoesNotCloseTheCurrentJob() {
        ((ObjectNode) page.withArray("events")
                .get(4))
                .put(
                        "recordFingerprint",
                        fingerprint('f'));

        ReadOnlyShadowLifecycleVerifier
                .VerificationResult result =
                verifier.verify(job, page);

        assertThat(result.reasonCode())
                .isEqualTo(
                        "SHADOW_LIFECYCLE_HEAD_MISMATCH");
        assertThat(result.verified()).isFalse();
    }

    @Test
    void reportsATruncatedValidPrefixWithoutClaimingCompleteClosure() {
        ArrayNode events = page.withArray("events");
        while (events.size() > 2) {
            events.remove(events.size() - 1);
        }
        page.put("nextSequence", 20);
        page.put("hasMore", true);

        ReadOnlyShadowLifecycleVerifier
                .VerificationResult result =
                verifier.verify(job, page);

        assertThat(result.outcome()).isEqualTo(
                ReadOnlyShadowLifecycleVerifier
                        .Outcome.VERIFIED_PAGE);
        assertThat(result.verified()).isTrue();
        assertThat(result.complete()).isFalse();
    }

    @Test
    void rejectsLocallyImpossibleTransitionChains() {
        ObjectNode renewed =
                event(
                        20,
                        CREATED_AT.plusSeconds(1),
                        "LEASE_RENEWED",
                        "RUNNING",
                        1,
                        1,
                        OWNER_FINGERPRINT,
                        "",
                        "",
                        CREATED_AT,
                        CREATED_AT.plusSeconds(61),
                        fingerprint('e'));
        ArrayNode events =
                page.withArray("events");
        events.removeAll();
        events.add(
                admittedEvent());
        events.add(renewed);
        page.put("nextSequence", 20);
        page.put("hasMore", true);

        ReadOnlyShadowLifecycleVerifier
                .VerificationResult result =
                verifier.verify(job, page);

        assertThat(result.reasonCode())
                .isEqualTo(
                        "SHADOW_LIFECYCLE_TRANSITION_INVALID");
    }

    private static ObjectNode failedJob() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_JOB_V1);
        value.put("jobId", JOB_ID);
        value.put("requestId", "shadow-request-1");
        value.put(
                "requestFingerprint",
                REQUEST_FINGERPRINT);
        value.set("scope", scope());
        value.put("status", "FAILED");
        value.put("attemptCount", 2);
        value.put("maximumAttempts", 3);
        value.put(
                "nextEligibleAt",
                CREATED_AT.plusSeconds(30)
                        .toString());
        value.put(
                "deadlineAt",
                DEADLINE_AT.toString());
        value.put("leaseEpoch", 2);
        value.put(
                "leaseExpiresAt",
                CREATED_AT.plusSeconds(63)
                        .toString());
        value.putNull("comparisonRef");
        value.put(
                "failureCode",
                "RG.MIRROR.SHADOW.UPSTREAM_UNAVAILABLE");
        value.put(
                "createdAt",
                CREATED_AT.toString());
        value.put(
                "updatedAt",
                CREATED_AT.plusSeconds(4)
                        .toString());
        value.put(
                "completedAt",
                CREATED_AT.plusSeconds(4)
                        .toString());
        value.put("recordFingerprint", "");
        value.put(
                "recordFingerprint",
                EvidenceVerificationSupport
                        .sha256Bounded(
                                value,
                                ReadOnlyShadowLifecycleVerifier
                                        .MAXIMUM_JOB_BYTES));
        return value;
    }

    private static ObjectNode completePage(
            ObjectNode currentJob) {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_JOB_LIFECYCLE_PAGE_V1);
        value.put("jobId", JOB_ID);
        value.put("afterSequence", 0);
        value.put("nextSequence", 50);
        value.put("hasMore", false);
        ArrayNode events = value.putArray("events");
        events.add(admittedEvent());
        events.add(event(
                20,
                CREATED_AT.plusSeconds(1),
                "CLAIMED",
                "RUNNING",
                1,
                1,
                OWNER_FINGERPRINT,
                "",
                "",
                CREATED_AT,
                CREATED_AT.plusSeconds(61),
                fingerprint('e')));
        events.add(event(
                30,
                CREATED_AT.plusSeconds(2),
                "RETRY_SCHEDULED",
                "QUEUED",
                1,
                1,
                OWNER_FINGERPRINT,
                "",
                "RG.MIRROR.SHADOW.RETRYABLE",
                CREATED_AT.plusSeconds(30),
                CREATED_AT.plusSeconds(61),
                fingerprint('f')));
        events.add(event(
                40,
                CREATED_AT.plusSeconds(3),
                "CLAIMED",
                "RUNNING",
                2,
                2,
                OWNER_FINGERPRINT,
                "",
                "",
                CREATED_AT.plusSeconds(30),
                CREATED_AT.plusSeconds(63),
                fingerprint('7')));
        events.add(event(
                50,
                CREATED_AT.plusSeconds(4),
                "FAILED",
                "FAILED",
                2,
                2,
                OWNER_FINGERPRINT,
                "",
                "RG.MIRROR.SHADOW.UPSTREAM_UNAVAILABLE",
                CREATED_AT.plusSeconds(30),
                CREATED_AT.plusSeconds(63),
                currentJob.path(
                        "recordFingerprint").asText()));
        return value;
    }

    private static ObjectNode admittedEvent() {
        return event(
                10,
                CREATED_AT,
                "ADMITTED",
                "QUEUED",
                0,
                0,
                "",
                "",
                "",
                CREATED_AT,
                CREATED_AT,
                fingerprint('d'));
    }

    private static ObjectNode event(
            long sequence,
            Instant occurredAt,
            String transition,
            String status,
            int attemptCount,
            long leaseEpoch,
            String ownerFingerprint,
            String comparisonFingerprint,
            String failureCode,
            Instant nextEligibleAt,
            Instant leaseExpiresAt,
            String recordFingerprint) {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .READ_ONLY_SHADOW_JOB_LIFECYCLE_EVENT_V1);
        value.put("sequence", sequence);
        value.put(
                "occurredAt",
                occurredAt.toString());
        value.set("scope", scope());
        value.put("jobId", JOB_ID);
        value.put(
                "requestFingerprint",
                REQUEST_FINGERPRINT);
        value.put("transition", transition);
        value.put("status", status);
        value.put("attemptCount", attemptCount);
        value.put("leaseEpoch", leaseEpoch);
        value.put(
                "ownerFingerprint",
                ownerFingerprint);
        value.put(
                "nextEligibleAt",
                nextEligibleAt.toString());
        value.put(
                "leaseExpiresAt",
                leaseExpiresAt.toString());
        value.put(
                "comparisonFingerprint",
                comparisonFingerprint);
        value.put("failureCode", failureCode);
        value.put(
                "recordFingerprint",
                recordFingerprint);
        return value;
    }

    private static ObjectNode scope() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put("tenantId", "tenant-a");
        value.put("organizationId", "org-a");
        value.put("projectId", "refund");
        value.put(
                "environmentId",
                "shadow-staging");
        value.put("region", "sg");
        return value;
    }

    private static String fingerprint(
            char value) {
        return "sha256:"
                + String.valueOf(value)
                .repeat(64);
    }
}
