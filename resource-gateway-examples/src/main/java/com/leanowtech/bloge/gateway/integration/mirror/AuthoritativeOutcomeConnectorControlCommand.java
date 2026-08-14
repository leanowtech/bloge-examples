package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Externally authorized backfill or connector-generation revocation command.
 *
 * <p>A backfill creates an independent historical stream and cannot move the live cursor. A
 * revocation fences the exact connector generation; a replacement must use a greater generation
 * and a separately approved baseline. Resource Gateway verifies and journals these commands but
 * does not originate customer data-authority decisions.</p>
 */
public record AuthoritativeOutcomeConnectorControlCommand(
        String schemaVersion,
        String commandId,
        long revision,
        String commandFingerprint,
        CapabilitySnapshot.Scope scope,
        String connectorId,
        long connectorGeneration,
        CommandType commandType,
        String streamId,
        EventTimeRange eventTimeRange,
        String baselinePageFingerprint,
        MirrorArtifactRef baselineCursorRef,
        String reasonCode,
        Instant requestedAt,
        Instant expiresAt,
        VisualRunEvidenceSeal authoritySeal
) {
    /** Current connector-control command wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeConnectorControlCommand.v1";
    /** Artifact kind referenced by source pages. */
    public static final String ARTIFACT_KIND =
            AuthoritativeOutcomeSourcePage.CONTROL_COMMAND_KIND;
    /** Maximum canonical command bytes. */
    public static final int MAXIMUM_CANONICAL_BYTES = 64 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}");
    private static final Pattern REASON =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Enforces command-type-specific material and a bounded authority window. */
    public AuthoritativeOutcomeConnectorControlCommand {
        schemaVersion = version(schemaVersion);
        commandId = identifier(commandId, "commandId");
        if (revision < 1 || connectorGeneration < 1) {
            throw invalid("command revision and connector generation must be positive");
        }
        commandFingerprint = optionalFingerprint(commandFingerprint);
        scope = Objects.requireNonNull(scope, "scope");
        connectorId = identifier(connectorId, "connectorId");
        commandType = Objects.requireNonNull(commandType, "commandType");
        reasonCode = Objects.requireNonNullElse(reasonCode, "").trim();
        if (!REASON.matcher(reasonCode).matches()) {
            throw invalid("connector control reasonCode is invalid");
        }
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(requestedAt)) {
            throw invalid("connector control command expiry must follow request time");
        }
        if (commandType == CommandType.BACKFILL) {
            streamId = identifier(streamId, "streamId");
            if ("live".equals(streamId)) {
                throw invalid("backfill cannot reuse the live stream id");
            }
            eventTimeRange = Objects.requireNonNull(eventTimeRange, "eventTimeRange");
            baselinePageFingerprint = requiredFingerprint(
                    baselinePageFingerprint, "baselinePageFingerprint");
            baselineCursorRef = requireKind(
                    baselineCursorRef,
                    AuthoritativeOutcomeSourcePage.CURSOR_KIND,
                    "baselineCursorRef");
        } else {
            streamId = Objects.requireNonNullElse(streamId, "").trim();
            baselinePageFingerprint = Objects.requireNonNullElse(
                    baselinePageFingerprint, "").trim();
            if (!streamId.isBlank() || eventTimeRange != null
                    || !baselinePageFingerprint.isBlank() || baselineCursorRef != null) {
                throw invalid("revocation cannot carry backfill stream material");
            }
        }
        authoritySeal = authoritySeal == null
                ? VisualRunEvidenceSeal.unsigned() : authoritySeal;
    }

    /** Closed externally governed control operations. */
    public enum CommandType {
        BACKFILL,
        REVOKE_GENERATION
    }

    /** Inclusive historical event-time interval authorized for one backfill. */
    public record EventTimeRange(Instant startsAt, Instant endsAt) {
        /** Requires a positive ordered interval. */
        public EventTimeRange {
            startsAt = Objects.requireNonNull(startsAt, "startsAt");
            endsAt = Objects.requireNonNull(endsAt, "endsAt");
            if (!endsAt.isAfter(startsAt)) {
                throw invalid("backfill event-time range must be positive");
            }
        }
    }

    /** Content-addresses command material while preserving an external authority seal. */
    public AuthoritativeOutcomeConnectorControlCommand seal(ObjectMapper mapper) {
        AuthoritativeOutcomeConnectorControlCommand material =
                withFingerprintAndSeal("", VisualRunEvidenceSeal.unsigned());
        return withFingerprintAndSeal(
                ProtocolFingerprint.ofBounded(
                        Objects.requireNonNull(mapper, "mapper"),
                        material,
                        MAXIMUM_CANONICAL_BYTES),
                authoritySeal);
    }

    /** Recomputes the immutable command content address. */
    public void verify(ObjectMapper mapper) {
        if (commandFingerprint.isBlank()
                || !commandFingerprint.equals(seal(mapper).commandFingerprint())) {
            throw invalid("authoritative outcome connector command fingerprint mismatch");
        }
    }

    /** Returns identical command material with an externally produced detached seal. */
    public AuthoritativeOutcomeConnectorControlCommand withAuthoritySeal(
            VisualRunEvidenceSeal seal) {
        return withFingerprintAndSeal(
                commandFingerprint, Objects.requireNonNull(seal, "seal"));
    }

    /** @return immutable artifact reference after the command has been addressed */
    public MirrorArtifactRef artifactRef() {
        if (commandFingerprint.isBlank()) {
            throw invalid("unaddressed connector control command has no artifact reference");
        }
        return new MirrorArtifactRef(
                ARTIFACT_KIND, commandId, revision, commandFingerprint);
    }

    private AuthoritativeOutcomeConnectorControlCommand withFingerprintAndSeal(
            String fingerprint,
            VisualRunEvidenceSeal seal) {
        return new AuthoritativeOutcomeConnectorControlCommand(
                schemaVersion, commandId, revision, fingerprint, scope,
                connectorId, connectorGeneration, commandType, streamId,
                eventTimeRange, baselinePageFingerprint, baselineCursorRef,
                reasonCode, requestedAt,
                expiresAt, seal);
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw invalid("unsupported connector control command schemaVersion");
        }
        return exact;
    }

    private static String identifier(String value, String field) {
        String exact = Objects.requireNonNullElse(value, "").trim();
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw invalid(field + " is invalid");
        }
        return exact;
    }

    private static String optionalFingerprint(String value) {
        String exact = Objects.requireNonNullElse(value, "").trim();
        if (!exact.isBlank() && !FINGERPRINT.matcher(exact).matches()) {
            throw invalid("commandFingerprint is invalid");
        }
        return exact;
    }

    private static String requiredFingerprint(String value, String field) {
        String exact = optionalFingerprint(value);
        if (exact.isBlank()) {
            throw invalid(field + " is invalid");
        }
        return exact;
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef ref, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(ref, field);
        if (!kind.equals(exact.kind())) {
            throw invalid(field + " must reference " + kind);
        }
        return exact;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
