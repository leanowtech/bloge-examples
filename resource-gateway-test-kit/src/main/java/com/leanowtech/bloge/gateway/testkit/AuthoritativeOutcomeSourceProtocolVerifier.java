package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * Dependency-light verifier for source pages, connector commands, and payload-free checkpoints.
 *
 * <p>The verifier links neither Spring nor Resource Gateway server classes. It validates packaged
 * strict Schemas, recomputes producer content addresses, checks stream and checkpoint closure,
 * and delegates only the customer-owned source seal decision to the caller.</p>
 */
public final class AuthoritativeOutcomeSourceProtocolVerifier {
    /** Maximum canonical source-page material admitted to hashing. */
    public static final int MAXIMUM_PAGE_BYTES = 16 * 1024 * 1024;
    /** Maximum canonical connector-command material admitted to hashing. */
    public static final int MAXIMUM_COMMAND_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Customer-owned trust callback for one already structured external seal. */
    @FunctionalInterface
    public interface ExternalSealVerifier {
        /**
         * Verifies one detached customer source seal.
         *
         * @param seal defensive copy of the detached seal
         * @param artifact defensive copy of the addressed page or command
         * @return true only when source identity, generation, key lifecycle, and signature pass
         */
        boolean verify(JsonNode seal, JsonNode artifact);
    }

    /** Creates a verifier backed by the Schemas packaged in the Test Kit. */
    public AuthoritativeOutcomeSourceProtocolVerifier() {
    }

    /**
     * Verifies one addressed source page and its caller-owned source authority.
     *
     * @param page decoded source page
     * @param externalSealVerifier customer source trust callback
     * @return defensive copy of the verified page
     */
    public JsonNode requirePage(
            JsonNode page, ExternalSealVerifier externalSealVerifier) {
        JsonNode exact = copy(page, "RG.MIRROR.CLIENT.OUTCOME_SOURCE_PAGE_INVALID");
        CapabilityMirrorSchemaValidator.require(
                exact,
                CapabilityMirrorProtocol.AUTHORITATIVE_OUTCOME_SOURCE_PAGE_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.OUTCOME_SOURCE_PAGE_SCHEMA_INVALID");
        String fingerprint = exact.path("pageFingerprint").asText();
        if (!fingerprint.equals(EvidenceVerificationSupport.sha256Bounded(
                producerMaterial(exact, "pageFingerprint", "sourceSeal"),
                MAXIMUM_PAGE_BYTES))) {
            throw invalid("RG.MIRROR.CLIENT.OUTCOME_SOURCE_PAGE_FINGERPRINT_INVALID");
        }
        String streamKind = exact.path("streamKind").asText();
        JsonNode commandRef = exact.path("controlCommandRef");
        if ("LIVE".equals(streamKind)
                && (!"live".equals(exact.path("streamId").asText())
                || !commandRef.isNull())
                || "BACKFILL".equals(streamKind) && commandRef.isNull()
                || exact.path("previousCursorRef").equals(exact.path("nextCursorRef"))) {
            throw invalid("RG.MIRROR.CLIENT.OUTCOME_SOURCE_PAGE_CLOSURE_INVALID");
        }
        int ordinal = 0;
        for (JsonNode entry : exact.path("entries")) {
            ordinal++;
            JsonNode observation = entry.path("observation");
            JsonNode observationSeal = observation.path("observationSeal");
            if (entry.path("ordinal").asInt() != ordinal
                    || observation.path("observationFingerprint").asText().isBlank()
                    || signed(observationSeal)
                    || "UPSERT".equals(entry.path("operation").asText())
                    && !entry.path("affectedSourceRef").isNull()
                    || "REVOKE".equals(entry.path("operation").asText())
                    && entry.path("affectedSourceRef").isNull()) {
                throw invalid("RG.MIRROR.CLIENT.OUTCOME_SOURCE_PAGE_CLOSURE_INVALID");
            }
        }
        JsonNode sourceSeal = exact.path("sourceSeal");
        if (!fingerprint.equals(sourceSeal.path("materialFingerprint").asText())
                || externalSealVerifier == null
                || !externalSealVerifier.verify(sourceSeal.deepCopy(), exact.deepCopy())) {
            throw invalid("RG.MIRROR.CLIENT.OUTCOME_SOURCE_AUTHORITY_REJECTED");
        }
        return exact.deepCopy();
    }

    /**
     * Verifies one addressed backfill or generation-revocation command.
     *
     * @param command decoded connector command
     * @param externalSealVerifier customer data-owner trust callback
     * @return defensive copy of the verified command
     */
    public JsonNode requireCommand(
            JsonNode command, ExternalSealVerifier externalSealVerifier) {
        JsonNode exact = copy(command, "RG.MIRROR.CLIENT.OUTCOME_SOURCE_COMMAND_INVALID");
        CapabilityMirrorSchemaValidator.require(
                exact,
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONNECTOR_CONTROL_COMMAND_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.OUTCOME_SOURCE_COMMAND_SCHEMA_INVALID");
        String fingerprint = exact.path("commandFingerprint").asText();
        if (!fingerprint.equals(EvidenceVerificationSupport.sha256Bounded(
                producerMaterial(exact, "commandFingerprint", "authoritySeal"),
                MAXIMUM_COMMAND_BYTES))) {
            throw invalid("RG.MIRROR.CLIENT.OUTCOME_SOURCE_COMMAND_FINGERPRINT_INVALID");
        }
        Instant requested = instant(exact.path("requestedAt").asText());
        Instant expires = instant(exact.path("expiresAt").asText());
        if (!expires.isAfter(requested)) {
            throw invalid("RG.MIRROR.CLIENT.OUTCOME_SOURCE_COMMAND_TIME_INVALID");
        }
        JsonNode authoritySeal = exact.path("authoritySeal");
        if (!fingerprint.equals(authoritySeal.path("materialFingerprint").asText())
                || externalSealVerifier == null
                || !externalSealVerifier.verify(authoritySeal.deepCopy(), exact.deepCopy())) {
            throw invalid("RG.MIRROR.CLIENT.OUTCOME_SOURCE_AUTHORITY_REJECTED");
        }
        return exact.deepCopy();
    }

    /**
     * Verifies one payload-free checkpoint projection without contacting the source.
     *
     * @param checkpoint decoded checkpoint
     * @return defensive copy of the verified checkpoint
     */
    public JsonNode requireCheckpoint(JsonNode checkpoint) {
        JsonNode exact = copy(
                checkpoint, "RG.MIRROR.CLIENT.OUTCOME_SOURCE_CHECKPOINT_INVALID");
        CapabilityMirrorSchemaValidator.require(
                exact,
                CapabilityMirrorProtocol.AUTHORITATIVE_OUTCOME_SOURCE_CHECKPOINT_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.OUTCOME_SOURCE_CHECKPOINT_SCHEMA_INVALID");
        JsonNode key = exact.path("key");
        boolean baseline = exact.path("committedSequence").asLong() == 0;
        if ("LIVE".equals(key.path("streamKind").asText())
                && (!"live".equals(key.path("streamId").asText())
                || !exact.path("controlCommandRef").isNull())
                || "BACKFILL".equals(key.path("streamKind").asText())
                && exact.path("controlCommandRef").isNull()
                || baseline && (!exact.path("baselinePageFingerprint").asText().equals(
                        exact.path("committedPageFingerprint").asText())
                || !exact.path("baselineCursorRef").equals(
                        exact.path("committedCursorRef"))
                || !exact.path("committedWatermarkRef").isNull()
                || !Instant.EPOCH.equals(instant(exact.path("eventTimeThrough").asText())))
                || !baseline && exact.path("committedWatermarkRef").isNull()
                || ("COMPLETE".equals(exact.path("status").asText())
                || "REVOKED".equals(exact.path("status").asText()))
                && !exact.path("stagedPageFingerprint").asText().isBlank()
                || instant(exact.path("updatedAt").asText()).isBefore(
                        instant(exact.path("createdAt").asText()))) {
            throw invalid("RG.MIRROR.CLIENT.OUTCOME_SOURCE_CHECKPOINT_CLOSURE_INVALID");
        }
        return exact.deepCopy();
    }

    private static JsonNode producerMaterial(
            JsonNode value, String fingerprintField, String sealField) {
        ObjectNode material = (ObjectNode) value.deepCopy();
        material.put(fingerprintField, "");
        ObjectNode seal = JSON.createObjectNode();
        seal.put("schemaVersion", "bloge.visualRunEvidenceSeal.v1");
        seal.put("materialFingerprint", "");
        seal.put("algorithm", "");
        seal.put("keyId", "");
        seal.put("signedAt", "1970-01-01T00:00:00Z");
        seal.put("signature", "");
        material.set(sealField, seal);
        return material;
    }

    private static boolean signed(JsonNode seal) {
        return !seal.path("materialFingerprint").asText().isBlank()
                || !seal.path("algorithm").asText().isBlank()
                || !seal.path("keyId").asText().isBlank()
                || !seal.path("signature").asText().isBlank();
    }

    private static JsonNode copy(JsonNode value, String code) {
        if (value == null || !value.isObject()) {
            throw invalid(code);
        }
        return value.deepCopy();
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException malformed) {
            throw invalid("RG.MIRROR.CLIENT.OUTCOME_SOURCE_TIME_INVALID");
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
