package com.leanowtech.bloge.gateway.testing.world;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/** Canonical reversible UTF-8/base64url codec for logical-contract graph tags. */
public final class WorldScenarioContractTagCodec {
    public static final String PREFIX = "bloge.logical-contract:";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    private WorldScenarioContractTagCodec() {
    }

    public static String encode(String contractId, String contractFingerprint) {
        String id = required(contractId);
        String fingerprint = required(contractFingerprint);
        if (!FINGERPRINT.matcher(fingerprint).matches()) {
            throw invalid();
        }
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                id.getBytes(StandardCharsets.UTF_8));
        return PREFIX + encoded + "@" + fingerprint;
    }

    public static Decoded decode(String tag) {
        String value = tag == null ? "" : tag.trim();
        if (!value.startsWith(PREFIX)) {
            throw invalid();
        }
        String body = value.substring(PREFIX.length());
        int separator = body.lastIndexOf('@');
        if (separator <= 0 || separator == body.length() - 1) {
            throw invalid();
        }
        String encodedId = body.substring(0, separator);
        String fingerprint = body.substring(separator + 1);
        if (!FINGERPRINT.matcher(fingerprint).matches()) {
            throw invalid();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encodedId);
            String contractId = decodeUtf8(bytes);
            String canonical = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (!canonical.equals(encodedId) || contractId.isBlank()) {
                throw invalid();
            }
            return new Decoded(contractId, fingerprint);
        } catch (IllegalArgumentException | CharacterCodingException rejected) {
            throw invalid();
        }
    }

    public record Decoded(String contractId, String contractFingerprint) {
        public Decoded {
            contractId = required(contractId);
            contractFingerprint = required(contractFingerprint);
            if (!FINGERPRINT.matcher(contractFingerprint).matches()) {
                throw invalid();
            }
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
        return decoded.toString();
    }

    private static String required(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw invalid();
        }
        return normalized;
    }

    private static WorldScenarioCompilationException invalid() {
        return new WorldScenarioCompilationException(WorldScenarioCompilationException.Code.TAG_INVALID);
    }
}
