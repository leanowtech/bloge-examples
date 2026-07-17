package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;

import java.util.Objects;

/**
 * Authoritative version dispatch for immutable test-suite canonical values.
 *
 * <p>Every operation retains the concrete wire generation. In particular, an older generation is
 * never converted before fingerprinting or persistence, which preserves historical content
 * addresses.</p>
 */
public final class TestSuiteProtocolCodec {
    private final ObjectMapper objectMapper;

    /** @param objectMapper protocol JSON mapper */
    public TestSuiteProtocolCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Computes the canonical fingerprint of the concrete suite generation.
     *
     * @param suite exact supported suite generation
     * @return lowercase SHA-256 content address
     */
    public String fingerprint(TestSuiteProtocol suite) {
        requireGeneration(suite);
        return ProtocolFingerprint.of(objectMapper, suite);
    }

    /**
     * Serializes the exact suite generation for durable storage.
     *
     * @param suite exact supported suite generation
     * @return JSON retaining the original schema version
     */
    public String write(TestSuiteProtocol suite) {
        requireGeneration(suite);
        try {
            return objectMapper.writeValueAsString(suite);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize test suite", failure);
        }
    }

    /**
     * Deserializes only explicitly supported suite generations.
     *
     * @param value stored suite JSON
     * @return exact concrete generation
     */
    public TestSuiteProtocol read(String value) {
        try {
            JsonNode tree = objectMapper.readTree(value);
            String schemaVersion = tree.path("schemaVersion").asText();
            TestSuiteProtocol suite = switch (schemaVersion) {
                case TestSuite.SCHEMA_VERSION -> objectMapper.treeToValue(tree, TestSuite.class);
                case TestSuiteV2.SCHEMA_VERSION -> objectMapper.treeToValue(tree, TestSuiteV2.class);
                case TestSuiteV3.SCHEMA_VERSION -> objectMapper.treeToValue(tree, TestSuiteV3.class);
                default -> throw new IllegalStateException(
                        "Stored test suite uses unsupported schemaVersion: " + schemaVersion);
            };
            requireGeneration(suite);
            return suite;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored test suite is corrupt", failure);
        }
    }

    private static void requireGeneration(TestSuiteProtocol suite) {
        if (suite instanceof TestSuite v1 && TestSuite.SCHEMA_VERSION.equals(v1.schemaVersion())) {
            return;
        }
        if (suite instanceof TestSuiteV2 v2 && TestSuiteV2.SCHEMA_VERSION.equals(v2.schemaVersion())) {
            return;
        }
        if (suite instanceof TestSuiteV3 v3 && TestSuiteV3.SCHEMA_VERSION.equals(v3.schemaVersion())) {
            return;
        }
        throw new IllegalArgumentException("Suite class and schemaVersion do not identify one generation");
    }
}
