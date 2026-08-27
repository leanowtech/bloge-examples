package com.leanowtech.bloge.gateway.testing.verification;

import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

final class ValidatorVerificationSupport {
    static final String SCHEMA_VERSION = "rg.validator-adversarial-corpus.v1";
    static final String ALGORITHM_VERSION = "validator-adversarial-gate.v1";
    static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

    private ValidatorVerificationSupport() {
    }

    static String text(String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
            throw fail(ValidatorVerificationException.Code.INVALID_INPUT);
        }
        return value;
    }

    static String token(String value) {
        value = text(value);
        if (!SAFE_TOKEN.matcher(value).matches()) {
            throw fail(ValidatorVerificationException.Code.INVALID_INPUT);
        }
        return value;
    }

    static String fingerprint(String value) {
        value = text(value);
        if (!FINGERPRINT.matcher(value).matches()) {
            throw fail(ValidatorVerificationException.Code.INVALID_INPUT);
        }
        return value;
    }

    static List<?> list(List<?> value) {
        if (value == null || value.isEmpty()) {
            throw fail(ValidatorVerificationException.Code.INVALID_INPUT);
        }
        return value;
    }

    static String hash(Object... fields) {
        StringBuilder material = new StringBuilder();
        for (Object field : fields) {
            String value = Objects.toString(field, "");
            material.append(value.length()).append(':').append(value).append('|');
        }
        return ProtocolFingerprint.ofText(material.toString());
    }

    static ValidatorVerificationException fail(ValidatorVerificationException.Code code) {
        return new ValidatorVerificationException(code);
    }
}
