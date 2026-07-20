package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Canonical trust boundary for available and payload-free replay-vault records. */
public final class ReplayPayloadIntegrity {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Set<String> CLASSIFICATIONS = Set.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Set<String> STATES = Set.of(
            StoredReplayPayload.AVAILABLE,
            StoredReplayPayload.EXPIRED,
            StoredReplayPayload.PURGED);

    private ReplayPayloadIntegrity() {
    }

    /**
     * Detaches arbitrary caller-owned JSON/bean values through the canonical protocol mapper.
     *
     * @param objectMapper canonical protocol mapper
     * @param payload candidate repository record
     * @return independently owned exact-generation snapshot
     */
    public static StoredReplayPayload canonicalSnapshot(ObjectMapper objectMapper,
                                                        StoredReplayPayload payload) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        if (payload == null) {
            throw new ReplayPayloadIntegrityException();
        }
        try {
            return objectMapper.readValue(objectMapper.writeValueAsBytes(payload),
                    StoredReplayPayload.class);
        } catch (Exception invalid) {
            throw new ReplayPayloadIntegrityException(invalid);
        }
    }

    /**
     * Requires one canonical, structurally valid available value or payload-free tombstone.
     *
     * @param objectMapper canonical protocol mapper
     * @param payload candidate repository record
     * @return detached verified record
     */
    public static StoredReplayPayload verifiedSnapshot(ObjectMapper objectMapper,
                                                       StoredReplayPayload payload) {
        StoredReplayPayload snapshot = canonicalSnapshot(objectMapper, payload);
        verify(snapshot);
        if (snapshot.readable()) {
            String actual = payloadFingerprint(objectMapper, snapshot.descriptor(), snapshot.value());
            if (!same(actual, snapshot.descriptor().fingerprint())) {
                throw new ReplayPayloadIntegrityException();
            }
        }
        return snapshot;
    }

    /**
     * Requires an available value whose descriptor fingerprint commits to its canonical JSON.
     *
     * @param objectMapper canonical protocol mapper
     * @param payload candidate available record
     * @return detached verified available record
     */
    public static StoredReplayPayload verifiedAvailableSnapshot(
            ObjectMapper objectMapper,
            StoredReplayPayload payload) {
        StoredReplayPayload snapshot = verifiedSnapshot(objectMapper, payload);
        if (!snapshot.readable()) {
            throw new ReplayPayloadIntegrityException();
        }
        return snapshot;
    }

    /**
     * Binds one repository result to the complete authorized replay lookup key.
     *
     * @param objectMapper canonical protocol mapper
     * @param payload candidate repository result
     * @param tenantId expected tenant
     * @param environmentId expected test-runtime environment
     * @param replayPayloadId expected payload id
     * @param revision expected immutable revision
     * @return detached verified lookup result
     */
    public static StoredReplayPayload verifiedLookup(
            ObjectMapper objectMapper,
            StoredReplayPayload payload,
            String tenantId,
            String environmentId,
            String replayPayloadId,
            long revision) {
        StoredReplayPayload snapshot = verifiedSnapshot(objectMapper, payload);
        if (!Objects.equals(normalized(tenantId), snapshot.tenantId())
                || !Objects.equals(normalized(environmentId), snapshot.environmentId())
                || !Objects.equals(normalized(replayPayloadId),
                snapshot.descriptor().replayPayloadId())
                || revision != snapshot.descriptor().revision()) {
            throw new ReplayPayloadIntegrityException();
        }
        return snapshot;
    }

    /**
     * Requires an alternate repository to return the exact available value submitted to create.
     *
     * @param objectMapper canonical protocol mapper
     * @param returned repository create receipt
     * @param expected exact value submitted to create
     * @return detached verified receipt
     */
    public static StoredReplayPayload verifiedCreateReceipt(
            ObjectMapper objectMapper,
            StoredReplayPayload returned,
            StoredReplayPayload expected) {
        StoredReplayPayload expectedSnapshot = verifiedAvailableSnapshot(objectMapper, expected);
        StoredReplayPayload returnedSnapshot = verifiedAvailableSnapshot(objectMapper, returned);
        if (!expectedSnapshot.equals(returnedSnapshot)) {
            throw new ReplayPayloadIntegrityException();
        }
        return returnedSnapshot;
    }

    /**
     * Computes the descriptor's value commitment while excluding its self-referential fingerprint.
     *
     * @param objectMapper canonical protocol mapper
     * @param descriptor immutable replay descriptor
     * @param value canonical governed JSON value, including JSON null
     * @return canonical payload commitment
     */
    public static String payloadFingerprint(ObjectMapper objectMapper,
                                            ReplayPayloadDescriptor descriptor,
                                            Object value) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        if (descriptor == null) {
            throw new ReplayPayloadIntegrityException();
        }
        ReplayPayloadDescriptor material = new ReplayPayloadDescriptor("",
                descriptor.replayPayloadId(), descriptor.revision(), "",
                descriptor.classification(), descriptor.source(), descriptor.redaction(),
                descriptor.capturedAt(), descriptor.expiresAt(),
                descriptor.certificationEligible(), descriptor.certificationGaps());
        try {
            return ProtocolFingerprint.of(objectMapper, Map.of("descriptor", material,
                    "value", value == null ? objectMapper.nullNode() : value));
        } catch (RuntimeException invalid) {
            throw new ReplayPayloadIntegrityException(invalid);
        }
    }

    /**
     * Commits immutable envelope, descriptor, and lifecycle state without retaining the value.
     *
     * <p>The descriptor's own fingerprint commits to an available value. This second commitment
     * remains verifiable after retention removes that value and therefore prevents descriptor,
     * scope, provenance, or tombstone-state drift.</p>
     *
     * @param objectMapper canonical protocol mapper
     * @param payload verified replay record
     * @return payload-free record commitment
     */
    public static String recordFingerprint(ObjectMapper objectMapper,
                                           StoredReplayPayload payload) {
        StoredReplayPayload snapshot = canonicalSnapshot(objectMapper, payload);
        try {
            return ProtocolFingerprint.of(objectMapper, new RecordMaterial(
                    snapshot.schemaVersion(), snapshot.tenantId(), snapshot.environmentId(),
                    snapshot.descriptor(), snapshot.state(), snapshot.payloadAvailable(),
                    snapshot.storedAt(), snapshot.storedBy()));
        } catch (RuntimeException invalid) {
            throw new ReplayPayloadIntegrityException(invalid);
        }
    }

    private static void verify(StoredReplayPayload payload) {
        ReplayPayloadDescriptor descriptor = payload.descriptor();
        if (!StoredReplayPayload.SCHEMA_VERSION.equals(payload.schemaVersion())
                || payload.tenantId().isBlank() || payload.environmentId().isBlank()
                || payload.storedBy().isBlank() || payload.storedAt() == null
                || descriptor == null
                || !ReplayPayloadDescriptor.SCHEMA_VERSION.equals(descriptor.schemaVersion())
                || !IDENTIFIER.matcher(descriptor.replayPayloadId()).matches()
                || descriptor.revision() <= 0
                || !FINGERPRINT.matcher(descriptor.fingerprint()).matches()
                || !CLASSIFICATIONS.contains(descriptor.classification())
                || descriptor.capturedAt() == null || descriptor.expiresAt() == null
                || descriptor.capturedAt().equals(Instant.EPOCH)
                || descriptor.capturedAt().isAfter(payload.storedAt())
                || !descriptor.expiresAt().isAfter(payload.storedAt())
                || !STATES.contains(payload.state())
                || !sourceValid(descriptor.source()) || !redactionValid(descriptor.redaction())
                || !gapsValid(descriptor.certificationEligible(), descriptor.certificationGaps())) {
            throw new ReplayPayloadIntegrityException();
        }
        boolean available = StoredReplayPayload.AVAILABLE.equals(payload.state());
        if (available != payload.payloadAvailable()
                || (!available && payload.value() != null)) {
            throw new ReplayPayloadIntegrityException();
        }
    }

    private static boolean sourceValid(ReplayPayloadDescriptor.Source source) {
        return source != null && "GOVERNED_RUN_NODE_ATTEMPT".equals(source.kind())
                && !source.runId().isBlank() && !source.nodeId().isBlank()
                && source.attempt() > 0
                && FINGERPRINT.matcher(source.runEvidenceFingerprint()).matches()
                && FINGERPRINT.matcher(source.sourcePayloadFingerprint()).matches()
                && !source.sourceEnvironment().isBlank();
    }

    private static boolean redactionValid(ReplayPayloadDescriptor.Redaction redaction) {
        if (redaction == null || redaction.sourceProfile().isBlank()
                || redaction.captureProfile().isBlank() || redaction.truncated()) {
            return false;
        }
        return redaction.redactedPaths().stream().noneMatch(path -> path == null || path.isBlank());
    }

    private static boolean gapsValid(boolean eligible, List<String> gaps) {
        if (gaps == null || gaps.size() > 128
                || gaps.stream().anyMatch(gap -> gap == null || gap.isBlank() || gap.length() > 160)
                || new HashSet<>(gaps).size() != gaps.size()) {
            return false;
        }
        return eligible == gaps.isEmpty();
    }

    private static boolean same(String left, String right) {
        return left != null && right != null
                && MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record RecordMaterial(
            String schemaVersion,
            String tenantId,
            String environmentId,
            ReplayPayloadDescriptor descriptor,
            String state,
            boolean payloadAvailable,
            Instant storedAt,
            String storedBy
    ) {
    }
}
