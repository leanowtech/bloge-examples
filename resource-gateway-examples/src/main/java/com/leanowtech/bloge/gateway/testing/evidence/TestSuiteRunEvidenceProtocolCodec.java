package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2;

import java.util.Objects;

/** Exact version dispatch for independently fingerprinted suite-run evidence generations. */
public final class TestSuiteRunEvidenceProtocolCodec {
    private final ObjectMapper objectMapper;

    /** @param objectMapper protocol JSON mapper */
    public TestSuiteRunEvidenceProtocolCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** @return canonical fingerprint of the supplied concrete evidence generation */
    public String fingerprint(TestSuiteRunEvidenceProtocol evidence) {
        requireGeneration(evidence);
        return ProtocolFingerprint.of(objectMapper, evidence);
    }

    /** @return durable JSON retaining the supplied concrete evidence generation */
    public String write(TestSuiteRunEvidenceProtocol evidence) {
        requireGeneration(evidence);
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize suite-run evidence", failure);
        }
    }

    /** @return exact v1 or v2 evidence decoded from stored JSON */
    public TestSuiteRunEvidenceProtocol read(String value) {
        try {
            JsonNode tree = objectMapper.readTree(value);
            String schemaVersion = tree.path("schemaVersion").asText();
            TestSuiteRunEvidenceProtocol evidence = switch (schemaVersion) {
                case TestSuiteRunEvidence.SCHEMA_VERSION ->
                        objectMapper.treeToValue(tree, TestSuiteRunEvidence.class);
                case TestSuiteRunEvidenceV2.SCHEMA_VERSION ->
                        objectMapper.treeToValue(tree, TestSuiteRunEvidenceV2.class);
                default -> throw new IllegalStateException(
                        "Stored suite-run evidence uses unsupported schemaVersion: " + schemaVersion);
            };
            requireGeneration(evidence);
            return evidence;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored suite-run evidence is corrupt", failure);
        }
    }

    private static void requireGeneration(TestSuiteRunEvidenceProtocol evidence) {
        if (evidence instanceof TestSuiteRunEvidence v1
                && TestSuiteRunEvidence.SCHEMA_VERSION.equals(v1.schemaVersion())) {
            return;
        }
        if (evidence instanceof TestSuiteRunEvidenceV2 v2
                && TestSuiteRunEvidenceV2.SCHEMA_VERSION.equals(v2.schemaVersion())) {
            return;
        }
        throw new IllegalArgumentException(
                "Suite-run evidence class and schemaVersion do not identify one generation");
    }
}
