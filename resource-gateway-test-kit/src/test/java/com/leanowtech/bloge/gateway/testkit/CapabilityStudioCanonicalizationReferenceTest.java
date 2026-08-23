package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class CapabilityStudioCanonicalizationReferenceTest {
    private static final String VECTOR_PATH =
            "docs/acceptance/capability-studio/gate-a-wire-v1/canonicalization/canonicalization-vectors-v1.json";
    private static final String PROFILE_PATH =
            "docs/acceptance/capability-studio/gate-a-wire-v1/canonicalization/fingerprint-profile-v1.json";

    @Test
    void reproducesEveryPositiveAndRejectionVector() throws IOException {
        byte[] manifestBytes = Files.readAllBytes(findFromRepositoryRoot(VECTOR_PATH));
        var manifest = CapabilityStudioCanonicalizationReference.parseUtf8(manifestBytes);
        var root = asObject(manifest);

        var positives = asArray(root.values().get("vectors"));
        assertThat(positives.values()).hasSize(7);
        for (var vector : positives.values()) {
            verifyPositive(asObject(vector));
        }

        var rejections = asArray(root.values().get("rejections"));
        assertThat(rejections.values()).hasSize(13);
        for (var vector : rejections.values()) {
            verifyRejection(asObject(vector));
        }
    }

    @Test
    void rejectsMalformedUtf8BeforeJsonParsing() {
        assertRejected(
                new byte[]{0x7b, 0x22, 0x61, 0x22, 0x3a, (byte) 0xc3, 0x28, 0x7d},
                "INVALID_UTF8");
    }

    @Test
    void rejectsUtf8BomAtTheByteBoundary() {
        assertRejected(
                new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 0x7b, 0x7d},
                "UTF8_BOM_REJECTED");
    }

    @Test
    void rejectsTwoExponentSigns() {
        assertRejected("{\"value\":1e+-2}", "INVALID_NUMBER");
    }

    @Test
    void rejectsEmptyFingerprintDomain() {
        try {
            CapabilityStudioCanonicalizationReference.documentFingerprint(
                    "", CapabilityStudioCanonicalizationReference.parseText("{}"), null);
            fail("empty domain was unexpectedly accepted");
        } catch (IllegalArgumentException error) {
            assertThat(reasonOf(error)).isEqualTo("INVALID_ASCII_DOMAIN");
        }
    }

    @Test
    void freezesFingerprintParametersByObjectIdentity() throws IOException {
        var profileRoot = asObject(CapabilityStudioCanonicalizationReference.parseUtf8(
                Files.readAllBytes(findFromRepositoryRoot(PROFILE_PATH))));
        var profiles = asArray(profileRoot.values().get("profiles"));
        assertThat(profiles.values()).hasSize(46);

        var objectKinds = profiles.values().stream()
                .map(CapabilityStudioCanonicalizationReferenceTest::asObject)
                .map(profile -> text(profile, "objectKind"))
                .toList();
        assertThat(objectKinds).doesNotHaveDuplicates().isSorted();
        assertThat(profiles.values().stream()
                .map(CapabilityStudioCanonicalizationReferenceTest::asObject)
                .map(profile -> text(profile, "fingerprintKind"))
                .filter("CANONICAL_DOCUMENT"::equals))
                .hasSize(35);
        assertThat(profiles.values().stream()
                .map(CapabilityStudioCanonicalizationReferenceTest::asObject)
                .map(profile -> text(profile, "fingerprintKind"))
                .filter("TREE_COMMITMENT"::equals))
                .hasSize(3);
        assertThat(profiles.values().stream()
                .map(CapabilityStudioCanonicalizationReferenceTest::asObject)
                .map(profile -> text(profile, "fingerprintKind"))
                .filter("AGGREGATE_COMMITMENT"::equals))
                .hasSize(8);

        assertProfile(
                profiles,
                "GATE_A_INDEPENDENT_PROOF_ENVELOPE",
                "RG-CS-GATE-A1-INDEPENDENT-PROOF-ENVELOPE-v1",
                "envelopeFingerprint");
        assertProfile(
                profiles,
                "GATE_A_PROVIDER_MATERIALIZATION_OBSERVATION",
                "RG-CS-GATE-A-PROVIDER-MATERIALIZATION-OBSERVATION-v1",
                "observationFingerprint");

        var vectors = asObject(CapabilityStudioCanonicalizationReference.parseUtf8(
                Files.readAllBytes(findFromRepositoryRoot(VECTOR_PATH))));
        for (var value : asArray(vectors.values().get("profileRejections")).values()) {
            var rejection = asObject(value);
            var profile = profiles.values().stream()
                    .map(CapabilityStudioCanonicalizationReferenceTest::asObject)
                    .filter(candidate -> text(candidate, "objectKind").equals(text(rejection, "objectKind")))
                    .findFirst()
                    .orElseThrow();
            String expectedReason = text(rejection, "expectedReason");
            String observedReason = !text(profile, "domain").equals(text(rejection, "domain"))
                    ? "FINGERPRINT_PROFILE_DOMAIN_MISMATCH"
                    : !Objects.equals(nullableText(profile, "selfField"), nullableText(rejection, "selfField"))
                            ? "FINGERPRINT_PROFILE_SELF_FIELD_MISMATCH"
                            : "UNEXPECTED_PROFILE_MATCH";
            assertThat(observedReason).isEqualTo(expectedReason);
        }
    }

    private static void assertProfile(
            CapabilityStudioCanonicalizationReference.JsonArray profiles,
            String objectKind,
            String domain,
            String selfField) {
        var profile = profiles.values().stream()
                .map(CapabilityStudioCanonicalizationReferenceTest::asObject)
                .filter(candidate -> objectKind.equals(text(candidate, "objectKind")))
                .findFirst()
                .orElseThrow();
        assertThat(text(profile, "domain")).isEqualTo(domain);
        assertThat(text(profile, "selfField")).isEqualTo(selfField);
        assertThat(text(profile, "fingerprintKind")).isEqualTo("CANONICAL_DOCUMENT");
    }

    private static void verifyPositive(CapabilityStudioCanonicalizationReference.JsonObject vector) {
        String id = text(vector, "id");
        String sourceText = text(vector, "sourceText");
        var parsed = CapabilityStudioCanonicalizationReference.parseText(sourceText);
        var fingerprint = CapabilityStudioCanonicalizationReference.documentFingerprint(
                text(vector, "domain"),
                parsed,
                nullableText(vector, "selfField"));

        assertThat(fingerprint.canonical())
                .as("canonical bytes for %s", id)
                .isEqualTo(text(vector, "expectedCanonical"));
        assertThat(fingerprint.canonical().getBytes(StandardCharsets.UTF_8))
                .as("canonical UTF-8 bytes for %s", id)
                .containsExactly(text(vector, "expectedCanonical").getBytes(StandardCharsets.UTF_8));
        assertThat(fingerprint.documentFingerprint())
                .as("document fingerprint for %s", id)
                .isEqualTo(text(vector, "expectedDocumentFingerprint"));
        assertThat(CapabilityStudioCanonicalizationReference.rawFingerprint(
                sourceText.getBytes(StandardCharsets.UTF_8)))
                .as("raw fingerprint for %s", id)
                .isEqualTo(text(vector, "expectedRawFingerprint"));
    }

    private static void verifyRejection(CapabilityStudioCanonicalizationReference.JsonObject vector) {
        String id = text(vector, "id");
        String expectedReason = text(vector, "expectedReason");
        var sourceBytes = vector.values().get("sourceBytesHex");
        if (sourceBytes != null) {
            assertRejected(HexFormat.of().parseHex(text(vector, "sourceBytesHex")), expectedReason, id);
        } else {
            assertRejected(text(vector, "sourceText"), expectedReason, id);
        }
    }

    private static void assertRejected(String sourceText, String expectedReason) {
        assertRejected(sourceText, expectedReason, "inline vector");
    }

    private static void assertRejected(String sourceText, String expectedReason, String id) {
        try {
            CapabilityStudioCanonicalizationReference.parseText(sourceText);
            fail("%s: input was unexpectedly accepted", id);
        } catch (IllegalArgumentException error) {
            assertThat(reasonOf(error))
                    .as("rejection reason for %s", id)
                    .isEqualTo(expectedReason);
        }
    }

    private static void assertRejected(byte[] sourceBytes, String expectedReason) {
        assertRejected(sourceBytes, expectedReason, "inline vector");
    }

    private static void assertRejected(byte[] sourceBytes, String expectedReason, String id) {
        try {
            CapabilityStudioCanonicalizationReference.parseUtf8(sourceBytes);
            fail("%s: input was unexpectedly accepted", id);
        } catch (IllegalArgumentException error) {
            assertThat(reasonOf(error))
                    .as("rejection reason for %s", id)
                    .isEqualTo(expectedReason);
        }
    }

    private static String reasonOf(IllegalArgumentException error) {
        String message = error.getMessage();
        int separator = message.indexOf(" at offset ");
        return separator < 0 ? message : message.substring(0, separator);
    }

    private static Path findFromRepositoryRoot(String relativePath) throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 8; depth++) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
            if (directory == null) {
                break;
            }
        }
        throw new IOException("Repository vector is absent: " + relativePath);
    }

    private static CapabilityStudioCanonicalizationReference.JsonObject asObject(
            CapabilityStudioCanonicalizationReference.JsonValue value) {
        assertThat(value).isInstanceOf(CapabilityStudioCanonicalizationReference.JsonObject.class);
        return (CapabilityStudioCanonicalizationReference.JsonObject) value;
    }

    private static CapabilityStudioCanonicalizationReference.JsonArray asArray(
            CapabilityStudioCanonicalizationReference.JsonValue value) {
        assertThat(value).isInstanceOf(CapabilityStudioCanonicalizationReference.JsonArray.class);
        return (CapabilityStudioCanonicalizationReference.JsonArray) value;
    }

    private static String text(
            CapabilityStudioCanonicalizationReference.JsonObject object,
            String field) {
        var value = object.values().get(field);
        assertThat(value).as("field %s", field)
                .isInstanceOf(CapabilityStudioCanonicalizationReference.JsonString.class);
        return ((CapabilityStudioCanonicalizationReference.JsonString) value).value();
    }

    private static String nullableText(
            CapabilityStudioCanonicalizationReference.JsonObject object,
            String field) {
        var value = object.values().get(field);
        if (value instanceof CapabilityStudioCanonicalizationReference.JsonNull) {
            return null;
        }
        return text(object, field);
    }
}
