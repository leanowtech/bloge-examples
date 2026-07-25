package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable payload-free receipt linking one predecessor to its admitted successor batch.
 *
 * <p>The approval head recursively closes the complete decision chain. Exact command replay
 * returns this same receipt; later successor execution and comparison are separate append-only
 * facts and cannot rewrite admission history.</p>
 *
 * @param schemaVersion exact receipt version
 * @param receiptFingerprint canonical content address with this field blanked
 * @param sourceCommandFingerprint exact accepted submit command address
 * @param scope complete enterprise namespace
 * @param remediationId stable remediation identity
 * @param remediationPlanFingerprint exact frozen preview
 * @param predecessorJobId exact terminal predecessor batch
 * @param successorJobId deterministic admitted successor batch
 * @param successorRequestFingerprint exact successor request address
 * @param approvalGeneration accepted two-person approval generation
 * @param approvalHeadFingerprint exact append-only approval head
 * @param acceptedBy authenticated submitter
 * @param delegatedBy authenticated delegating principal, when present
 * @param acceptedAt trusted server admission time
 */
public record ScenarioRehearsalRemediationReceipt(
        String schemaVersion,
        String receiptFingerprint,
        String sourceCommandFingerprint,
        CapabilitySnapshot.Scope scope,
        String remediationId,
        String remediationPlanFingerprint,
        String predecessorJobId,
        String successorJobId,
        String successorRequestFingerprint,
        long approvalGeneration,
        String approvalHeadFingerprint,
        String acceptedBy,
        String delegatedBy,
        Instant acceptedAt
) {
    /** Current remediation admission receipt version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalRemediationReceipt.v1";
    /** Maximum canonical receipt size. */
    public static final int MAXIMUM_CANONICAL_BYTES = 128 * 1024;
    private static final Pattern REMEDIATION_ID =
            Pattern.compile("scenario-remediation-[a-f0-9]{64}");
    private static final Pattern ACTOR =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,254}");

    /** Enforces complete predecessor, successor, approval, and actor correspondence. */
    public ScenarioRehearsalRemediationReceipt {
        schemaVersion = version(schemaVersion);
        receiptFingerprint =
                MirrorStateProtocolSupport.optionalFingerprint(
                        receiptFingerprint,
                        "receiptFingerprint");
        sourceCommandFingerprint =
                MirrorStateProtocolSupport.fingerprint(
                        sourceCommandFingerprint,
                        "sourceCommandFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        remediationId = remediationId(remediationId);
        remediationPlanFingerprint =
                MirrorStateProtocolSupport.fingerprint(
                        remediationPlanFingerprint,
                        "remediationPlanFingerprint");
        predecessorJobId = batchId(
                predecessorJobId, "predecessorJobId");
        successorJobId = batchId(
                successorJobId, "successorJobId");
        if (predecessorJobId.equals(successorJobId)) {
            throw new IllegalArgumentException(
                    "Scenario remediation successor must differ from predecessor");
        }
        successorRequestFingerprint =
                MirrorStateProtocolSupport.fingerprint(
                        successorRequestFingerprint,
                        "successorRequestFingerprint");
        if (approvalGeneration < 2) {
            throw new IllegalArgumentException(
                    "Scenario remediation receipt requires two approval generations");
        }
        approvalHeadFingerprint =
                MirrorStateProtocolSupport.fingerprint(
                        approvalHeadFingerprint,
                        "approvalHeadFingerprint");
        acceptedBy = actor(acceptedBy, "acceptedBy");
        delegatedBy = optionalActor(
                delegatedBy, "delegatedBy");
        acceptedAt = Objects.requireNonNull(
                acceptedAt, "acceptedAt");
    }

    /** Returns this receipt carrying its canonical content address. */
    public ScenarioRehearsalRemediationReceipt withFingerprint(
            String value) {
        return new ScenarioRehearsalRemediationReceipt(
                schemaVersion,
                value,
                sourceCommandFingerprint,
                scope,
                remediationId,
                remediationPlanFingerprint,
                predecessorJobId,
                successorJobId,
                successorRequestFingerprint,
                approvalGeneration,
                approvalHeadFingerprint,
                acceptedBy,
                delegatedBy,
                acceptedAt);
    }

    /** Seals one server-authored immutable remediation admission receipt. */
    public static ScenarioRehearsalRemediationReceipt seal(
            ObjectMapper mapper,
            ScenarioRehearsalRemediationReceipt value) {
        ScenarioRehearsalRemediationReceipt material =
                Objects.requireNonNull(value, "value")
                        .withFingerprint("");
        return material.withFingerprint(
                ProtocolFingerprint.ofBounded(
                        Objects.requireNonNull(mapper, "mapper"),
                        material,
                        MAXIMUM_CANONICAL_BYTES));
    }

    /** Recomputes and verifies this receipt's exact content address. */
    public void verify(ObjectMapper mapper) {
        if (receiptFingerprint.isBlank()
                || !receiptFingerprint.equals(
                seal(mapper, this).receiptFingerprint())) {
            throw new IllegalArgumentException(
                    "Scenario remediation receipt fingerprint mismatch");
        }
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario remediation receipt schemaVersion");
        }
        return exact;
    }

    private static String remediationId(String value) {
        String exact =
                MirrorStateProtocolSupport.required(
                        value, "remediationId");
        if (!REMEDIATION_ID.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "remediationId is invalid");
        }
        return exact;
    }

    private static String batchId(
            String value,
            String field) {
        String exact =
                MirrorStateProtocolSupport.required(value, field);
        if (!ScenarioRehearsalBatchIdentity
                .hasCanonicalShape(exact)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String actor(
            String value,
            String field) {
        String exact =
                MirrorStateProtocolSupport.required(value, field);
        if (!ACTOR.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String optionalActor(
            String value,
            String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.isBlank()
                && !ACTOR.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }
}
