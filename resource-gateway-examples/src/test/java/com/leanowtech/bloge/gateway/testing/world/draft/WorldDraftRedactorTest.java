package com.leanowtech.bloge.gateway.testing.world.draft;

import com.leanowtech.bloge.gateway.testing.world.draft.WorldDraftRedactionPolicy.Action;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class WorldDraftRedactorTest {
    @Test
    void finalTreeScanRejectsSensitiveReplacementAndUnknownFieldsAreDropped() {
        WorldDraftRedactionPolicy policy = new WorldDraftRedactionPolicy("policy",
                List.of(new WorldDraftRedactionPolicy.FieldRule("/secret", Action.DROP, null)), List.of());
        SchemaEnvelope requestSchema = WorldDraftTestSupport.classifiedObject(Map.of(
                "safe", WorldDraftTestSupport.publicString(), "secret", WorldDraftTestSupport.publicString()), List.of("safe"));
        SchemaEnvelope responseSchema = WorldDraftTestSupport.classifiedObject(
                Map.of("result", WorldDraftTestSupport.publicString()), List.of("result"));
        WorldDraftSourceAuthority.SourcePayload payload = new WorldDraftSourceAuthority.SourcePayload(
                Map.of("safe", "ok", "secret", "remove", "unknown", "remove"), Map.of("result", "ok"));
        WorldDraftSourceAuthority.SourceMetadata metadata = metadata(payload, requestSchema, responseSchema, policy);

        WorldDraftRedactor.Result result = WorldDraftRedactor.schemaGuided().redact(payload, metadata, policy);

        assertThat(result.report().safe()).isTrue();
        assertThat(result.report().unknownFieldCount()).isEqualTo(1);
        WorldDraftRedactionPolicy unsafe = new WorldDraftRedactionPolicy("policy-unsafe",
                List.of(new WorldDraftRedactionPolicy.FieldRule("/safe", Action.FIXED_REPLACEMENT, "secret-value")), List.of());
        WorldDraftSourceAuthority.SourceMetadata unsafeMetadata = metadata(payload, requestSchema, responseSchema, unsafe);
        assertThat(WorldDraftRedactor.schemaGuided().redact(payload, unsafeMetadata, unsafe).report().safe()).isFalse();
    }

    @Test
    void finalTreeScanChecksSensitiveValuesEvenOnNonSensitivePaths() {
        WorldDraftRedactionPolicy policy = new WorldDraftRedactionPolicy("value-policy",
                List.of(new WorldDraftRedactionPolicy.FieldRule("/safe", Action.FIXED_REPLACEMENT,
                        "customer@example.com")),
                List.of(new WorldDraftRedactionPolicy.FieldRule("/result", Action.FIXED_REPLACEMENT,
                        "+1 555-123-4567"), new WorldDraftRedactionPolicy.FieldRule("/opaque",
                        Action.FIXED_REPLACEMENT, "Bearer secret-token")));
        SchemaEnvelope requestSchema = WorldDraftTestSupport.classifiedObject(
                Map.of("safe", WorldDraftTestSupport.publicString()), List.of("safe"));
        SchemaEnvelope responseSchema = WorldDraftTestSupport.classifiedObject(Map.of(
                "result", WorldDraftTestSupport.publicString(), "opaque", Map.of(
                        "type", "string", "x-data-classification", "CREDENTIAL")), List.of("result", "opaque"));
        WorldDraftSourceAuthority.SourcePayload payload = new WorldDraftSourceAuthority.SourcePayload(
                Map.of("safe", "ok"), Map.of("result", "ok", "opaque", "ok"));
        WorldDraftRedactionReport report = WorldDraftRedactor.schemaGuided().redact(
                payload, metadata(payload, requestSchema, responseSchema, policy), policy).report();

        assertThat(report.safe()).isFalse();
        assertThat(report.findings()).contains(WorldDraftRedactionReport.Finding.CONTACT,
                WorldDraftRedactionReport.Finding.CREDENTIAL);
    }

    @Test
    void formatPreservingTokenIsDeterministicButDifferentForDifferentInputs() {
        WorldDraftRedactionPolicy policy = new WorldDraftRedactionPolicy("email-policy",
                List.of(new WorldDraftRedactionPolicy.FieldRule("/email", Action.FORMAT_PRESERVING_TOKEN, null)), List.of());
        SchemaEnvelope requestSchema = WorldDraftTestSupport.classifiedObject(Map.of("email", Map.of(
                "type", "string", "format", "email", "x-data-classification", "CONTACT")), List.of("email"));
        SchemaEnvelope responseSchema = WorldDraftTestSupport.classifiedObject(
                Map.of("result", WorldDraftTestSupport.publicString()), List.of("result"));
        WorldDraftSourceAuthority.SourcePayload first = new WorldDraftSourceAuthority.SourcePayload(
                Map.of("email", "one@example.com"), Map.of("result", "ok"));
        WorldDraftSourceAuthority.SourcePayload second = new WorldDraftSourceAuthority.SourcePayload(
                Map.of("email", "two@example.com"), Map.of("result", "ok"));
        WorldDraftRedactor redactor = WorldDraftRedactor.schemaGuided();
        WorldDraftRedactor.Result firstResult = redactor.redact(first, metadata(first, requestSchema, responseSchema, policy), policy);
        WorldDraftRedactor.Result repeatResult = redactor.redact(first, metadata(first, requestSchema, responseSchema, policy), policy);
        WorldDraftRedactor.Result secondResult = redactor.redact(second, metadata(second, requestSchema, responseSchema, policy), policy);

        assertThat(firstResult.report().safe()).isTrue();
        assertThat(firstResult.payload().requestFingerprint()).isEqualTo(repeatResult.payload().requestFingerprint());
        assertThat(firstResult.payload().requestFingerprint()).isNotEqualTo(secondResult.payload().requestFingerprint());
    }

    @Test
    void classifiedDlpRejectsNeutralIdentityEncodedCredentialAndUnknownFreeText() {
        Map<String, Object> properties = Map.of(
                "person", Map.of("type", "string", "x-data-classification", "IDENTITY"),
                "numeric", Map.of("type", "string", "x-data-classification", "IDENTITY"),
                "encoded", Map.of("type", "string", "x-data-classification", "CREDENTIAL"));
        SchemaEnvelope requestSchema = WorldDraftTestSupport.classifiedObject(properties,
                List.of("person", "numeric", "encoded"));
        SchemaEnvelope responseSchema = WorldDraftTestSupport.classifiedObject(
                Map.of("result", WorldDraftTestSupport.publicString()), List.of("result"));
        String encoded = Base64.getEncoder().encodeToString(
                "password=secret-token-value".getBytes(StandardCharsets.UTF_8));
        WorldDraftSourceAuthority.SourcePayload payload = new WorldDraftSourceAuthority.SourcePayload(
                Map.of("person", "John Doe", "numeric", "138001380001", "encoded", encoded),
                Map.of("result", "ok"));
        WorldDraftRedactionPolicy policy = WorldDraftRedactionPolicy.identity("dlp-policy");
        WorldDraftRedactionReport report = WorldDraftRedactor.schemaGuided().redact(payload,
                metadata(payload, requestSchema, responseSchema, policy), policy).report();

        assertThat(report.safe()).isFalse();
        assertThat(report.findings()).contains(WorldDraftRedactionReport.Finding.IDENTITY,
                WorldDraftRedactionReport.Finding.CREDENTIAL);

        SchemaEnvelope unknownSchema = WorldDraftTestSupport.classifiedObject(
                Map.of("free", Map.of("type", "string")), List.of("free"));
        WorldDraftSourceAuthority.SourcePayload freeText = new WorldDraftSourceAuthority.SourcePayload(
                Map.of("free", "unclassified free text"), Map.of("result", "ok"));
        WorldDraftRedactionReport unknown = WorldDraftRedactor.schemaGuided().redact(freeText,
                metadata(freeText, unknownSchema, responseSchema, policy), policy).report();
        assertThat(unknown.findings()).contains(WorldDraftRedactionReport.Finding.FREE_TEXT);
    }

    private static WorldDraftSourceAuthority.SourceMetadata metadata(
            WorldDraftSourceAuthority.SourcePayload payload, SchemaEnvelope requestSchema,
            SchemaEnvelope responseSchema, WorldDraftRedactionPolicy policy) {
        return WorldDraftSourceAuthority.SourceMetadata.sealed(WorldDraftSourceRef.exact(
                        WorldDraftSourceRef.Kind.GOLDEN_CAPTURE, "tenant-a", "capture", 1, fp("source")),
                "tenant-a", true, true, java.time.Instant.parse("2030-01-01T00:00:00Z"),
                requestSchema, responseSchema, policy.fingerprint(), payload.requestFingerprint(),
                payload.responseFingerprint());
    }

    private static String fp(String value) {
        return com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint.fromMaterial(Map.of("v", value));
    }
}
