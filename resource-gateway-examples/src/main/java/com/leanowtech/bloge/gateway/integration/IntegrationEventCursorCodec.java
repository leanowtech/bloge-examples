package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/** Encodes scope-bound, signed and expiring event continuation tokens. */
public final class IntegrationEventCursorCodec {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.eventCursor.v1";
    public static final Duration DEFAULT_TTL = Duration.ofDays(7);

    private final ObjectMapper objectMapper;
    private final VisualEvidenceSigner signer;
    private final Clock clock;
    private final Duration ttl;

    public IntegrationEventCursorCodec(ObjectMapper objectMapper, VisualEvidenceSigner signer) {
        this(objectMapper, signer, Clock.systemUTC(), DEFAULT_TTL);
    }

    IntegrationEventCursorCodec(ObjectMapper objectMapper,
                                VisualEvidenceSigner signer,
                                Clock clock,
                                Duration ttl) {
        this.objectMapper = objectMapper;
        this.signer = signer == null ? VisualEvidenceSigner.unavailable() : signer;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
    }

    public CursorPayload issue(String tenantId, String environmentId, long afterSequence, long throughSequence) {
        Instant issuedAt = clock.instant();
        return new CursorPayload("", tenantId, environmentId, afterSequence, throughSequence, issuedAt,
                issuedAt.plus(ttl));
    }

    public CursorPayload advance(CursorPayload base, long afterSequence, long throughSequence) {
        return new CursorPayload("", base.tenantId(), base.environmentId(), afterSequence, throughSequence,
                base.issuedAt(), base.expiresAt());
    }

    public String encode(CursorPayload cursor) {
        requireSigner();
        try {
            byte[] payload = objectMapper.writeValueAsBytes(cursor);
            String materialFingerprint = fingerprint(cursor);
            VisualRunEvidenceSeal generated = signer.seal(materialFingerprint);
            VisualRunEvidenceSeal stable = new VisualRunEvidenceSeal(generated.schemaVersion(),
                    generated.materialFingerprint(), generated.algorithm(), generated.keyId(), cursor.issuedAt(),
                    generated.signature());
            byte[] seal = objectMapper.writeValueAsBytes(stable);
            return encodePart(payload) + "." + encodePart(seal);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode integration event cursor", exception);
        }
    }

    public CursorPayload decode(String token, IntegrationRequestContext context) {
        context.requireComplete();
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw invalid(context);
            }
            CursorPayload cursor = objectMapper.readValue(decodePart(parts[0]), CursorPayload.class);
            VisualRunEvidenceSeal seal = objectMapper.readValue(decodePart(parts[1]), VisualRunEvidenceSeal.class);
            if (!SCHEMA_VERSION.equals(cursor.schemaVersion())
                    || cursor.throughSequence() < cursor.afterSequence()
                    || !context.tenantId().equals(cursor.tenantId())
                    || !context.environmentId().equals(cursor.environmentId())
                    || !signer.verify(seal, fingerprint(cursor)).valid()) {
                throw invalid(context);
            }
            if (!cursor.expiresAt().isAfter(clock.instant())) {
                throw new IntegrationProblemException(IntegrationProblem.gone(
                        "RG.INTEGRATION.CURSOR_EXPIRED",
                        "The event cursor expired; rebuild from the reconciliation snapshot.",
                        context.correlationId(),
                        Map.of("recovery", "/api/integration/reconciliation")
                ));
            }
            return cursor;
        } catch (IntegrationProblemException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid(context);
        }
    }

    private void requireSigner() {
        if (!signer.available()) {
            throw new IllegalStateException("A signing authority is required for integration event cursors");
        }
    }

    private static String fingerprint(CursorPayload cursor) {
        return VisualBundleFingerprint.fromMaterial(Map.of("eventCursor", cursor));
    }

    private static String encodePart(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decodePart(String value) {
        return Base64.getUrlDecoder().decode(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static IntegrationProblemException invalid(IntegrationRequestContext context) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                "RG.INTEGRATION.CURSOR_INVALID",
                "The event cursor is invalid for this integration scope.",
                context == null ? "" : context.correlationId(),
                Map.of()
        ));
    }

    /** Signed cursor material. Numeric positions never appear outside the opaque token. */
    public record CursorPayload(String schemaVersion,
                                String tenantId,
                                String environmentId,
                                long afterSequence,
                                long throughSequence,
                                Instant issuedAt,
                                Instant expiresAt) {
        public CursorPayload {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
            tenantId = tenantId == null ? "" : tenantId.trim();
            environmentId = environmentId == null ? "" : environmentId.trim();
            afterSequence = Math.max(0, afterSequence);
            throughSequence = Math.max(0, throughSequence);
            issuedAt = issuedAt == null ? Instant.EPOCH : issuedAt;
            expiresAt = expiresAt == null ? Instant.EPOCH : expiresAt;
        }
    }
}
