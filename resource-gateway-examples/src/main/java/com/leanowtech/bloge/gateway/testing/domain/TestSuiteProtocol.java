package com.leanowtech.bloge.gateway.testing.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

/**
 * Read-only protocol boundary shared by immutable test-suite generations.
 *
 * <p>The interface intentionally declares only fields already present in v1. Implementations own
 * their exact canonical shape; callers must fingerprint the concrete value through the versioned
 * codec and must never convert an old generation before verification.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "schemaVersion", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TestSuite.class, name = TestSuite.SCHEMA_VERSION),
        @JsonSubTypes.Type(value = TestSuiteV2.class, name = TestSuiteV2.SCHEMA_VERSION)
})
public sealed interface TestSuiteProtocol permits TestSuite, TestSuiteV2 {
    /** @return exact wire schema version */
    String schemaVersion();

    /** @return stable suite identifier */
    String suiteId();

    /** @return immutable positive revision */
    long revision();

    /** @return exact target identity */
    TestSuite.Target target();

    /** @return governed data classification */
    String classification();

    /** @return ordered test cases */
    List<TestSuite.TestCase> cases();

    /** @return structural coverage policy */
    TestSuite.CoveragePolicy coveragePolicy();

    /** @return promotion eligibility policy */
    TestSuite.PromotionPolicy promotionPolicy();

    /** @return bounded provenance metadata */
    Map<String, Object> metadata();
}
