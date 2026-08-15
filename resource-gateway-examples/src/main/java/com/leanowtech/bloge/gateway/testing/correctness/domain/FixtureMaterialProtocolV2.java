package com.leanowtech.bloge.gateway.testing.correctness.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.FixtureSource;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RedactionDescriptor;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor.RetentionDescriptor;
import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.exactFingerprint;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.mutableRevision;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.protocolVersion;
import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.required;

/** Dedicated payload-bearing write contract and payload-free receipt for protected Fixture material. */
public final class FixtureMaterialProtocolV2 {

    private FixtureMaterialProtocolV2() {
    }

    public enum FixtureSubject { GRAPH, OPERATOR, FUNCTION, SCENARIO }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WriteRequest(
            String schemaVersion,
            String fixtureAssetId,
            long expectedRevision,
            FixtureSource source,
            FixtureSubject subject,
            ExactTargetRef target,
            ExactSchemaRef schemaRef,
            String classification,
            RetentionDescriptor retention,
            RedactionDescriptor redaction,
            Object payload
    ) {
        public static final String SCHEMA_VERSION = "bloge.fixtureMaterialWriteRequest.v2";

        public WriteRequest {
            schemaVersion = protocolVersion(schemaVersion, SCHEMA_VERSION);
            fixtureAssetId = required(fixtureAssetId, "fixtureAssetId");
            expectedRevision = mutableRevision(expectedRevision);
            source = required(source, "source");
            subject = required(subject, "subject");
            target = required(target, "target");
            schemaRef = required(schemaRef, "schemaRef");
            classification = required(classification, "classification").toUpperCase(Locale.ROOT);
            retention = required(retention, "retention");
            redaction = required(redaction, "redaction");
            payload = ProtocolJsonValue.freeze(payload);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Receipt(
            String schemaVersion,
            String fixtureAssetId,
            ExactAssetRef materialRef,
            String payloadFingerprint,
            FixtureSource source,
            FixtureSubject subject,
            ExactTargetRef target,
            ExactSchemaRef schemaRef,
            String classification,
            RetentionDescriptor retention,
            RedactionDescriptor redaction,
            List<ExactAssetRef> lineageRefs,
            boolean payloadPersisted,
            boolean payloadReturned
    ) {
        public static final String SCHEMA_VERSION = "bloge.fixtureMaterialReceipt.v2";

        public Receipt {
            schemaVersion = protocolVersion(schemaVersion, SCHEMA_VERSION);
            fixtureAssetId = required(fixtureAssetId, "fixtureAssetId");
            materialRef = required(materialRef, "materialRef");
            payloadFingerprint = exactFingerprint(payloadFingerprint, "payloadFingerprint");
            source = required(source, "source");
            subject = required(subject, "subject");
            target = required(target, "target");
            schemaRef = required(schemaRef, "schemaRef");
            classification = required(classification, "classification").toUpperCase(Locale.ROOT);
            retention = required(retention, "retention");
            redaction = required(redaction, "redaction");
            lineageRefs = lineageRefs == null ? List.of() : lineageRefs.stream()
                    .distinct()
                    .sorted(Comparator.comparing(ExactAssetRef::kind)
                            .thenComparing(ExactAssetRef::id)
                            .thenComparingLong(ExactAssetRef::revision))
                    .toList();
            if (!payloadPersisted || payloadReturned) {
                throw new IllegalArgumentException(
                        "Fixture material receipt must confirm persistence without returning payload");
            }
        }
    }
}
