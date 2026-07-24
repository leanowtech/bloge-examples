package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Objects;

/**
 * Canonical sealing and local semantic verification for scenario protocol artifacts.
 *
 * <p>Closure-level checks against TestSuite, FixtureBundle, MirrorPlan, Session checkpoint, and
 * artifact registries belong to the rehearsal compiler. These methods establish the portable
 * content identity and local lifecycle/scope invariants that every producer and consumer can
 * verify without registry access.</p>
 */
public final class ScenarioPackIntegrity {
    /** Maximum canonical bytes admitted for one ScenarioPack. */
    public static final int MAXIMUM_PACK_BYTES = 2 * 1024 * 1024;
    /** Maximum canonical bytes admitted for one ScenarioCase. */
    public static final int MAXIMUM_CASE_BYTES = 512 * 1024;
    /** Maximum canonical bytes admitted for one CaseHandlingAssertion. */
    public static final int MAXIMUM_ASSERTION_BYTES = 128 * 1024;

    private ScenarioPackIntegrity() {
    }

    /**
     * Validates and content-addresses one scenario pack.
     *
     * @param mapper canonical protocol mapper
     * @param pack unsealed or resealed pack
     * @return sealed immutable pack
     */
    public static ScenarioPack seal(ObjectMapper mapper, ScenarioPack pack) {
        Objects.requireNonNull(mapper, "mapper");
        validateLifecycle(
                Objects.requireNonNull(pack, "pack").scope(),
                pack.provenance(), pack.lifecycle(), pack.createdAt());
        ScenarioPack material = pack.withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                mapper, material, MAXIMUM_PACK_BYTES));
    }

    /**
     * Validates and content-addresses one scenario case.
     *
     * @param mapper canonical protocol mapper
     * @param scenarioCase unsealed or resealed case
     * @return sealed immutable case
     */
    public static ScenarioCase sealCase(ObjectMapper mapper, ScenarioCase scenarioCase) {
        Objects.requireNonNull(mapper, "mapper");
        validateLifecycle(
                Objects.requireNonNull(scenarioCase, "scenarioCase").scope(),
                scenarioCase.provenance(), scenarioCase.lifecycle(), scenarioCase.createdAt());
        ScenarioCase material = scenarioCase.withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                mapper, material, MAXIMUM_CASE_BYTES));
    }

    /**
     * Validates and content-addresses one handling assertion.
     *
     * @param mapper canonical protocol mapper
     * @param assertion unsealed or resealed assertion
     * @return sealed immutable assertion
     */
    public static CaseHandlingAssertion sealAssertion(
            ObjectMapper mapper, CaseHandlingAssertion assertion) {
        Objects.requireNonNull(mapper, "mapper");
        validateLifecycle(
                Objects.requireNonNull(assertion, "assertion").scope(),
                assertion.provenance(), assertion.lifecycle(), assertion.createdAt());
        CaseHandlingAssertion material = assertion.withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                mapper, material, MAXIMUM_ASSERTION_BYTES));
    }

    /** Recomputes and verifies one exact scenario pack. */
    public static void verify(ObjectMapper mapper, ScenarioPack pack) {
        if (pack == null || pack.fingerprint().isBlank()
                || !pack.fingerprint().equals(seal(mapper, pack).fingerprint())) {
            throw new IllegalArgumentException("scenario pack fingerprint mismatch");
        }
    }

    /** Recomputes and verifies one exact scenario case. */
    public static void verifyCase(ObjectMapper mapper, ScenarioCase scenarioCase) {
        if (scenarioCase == null || scenarioCase.fingerprint().isBlank()
                || !scenarioCase.fingerprint()
                .equals(sealCase(mapper, scenarioCase).fingerprint())) {
            throw new IllegalArgumentException("scenario case fingerprint mismatch");
        }
    }

    /** Recomputes and verifies one exact handling assertion. */
    public static void verifyAssertion(
            ObjectMapper mapper, CaseHandlingAssertion assertion) {
        if (assertion == null || assertion.fingerprint().isBlank()
                || !assertion.fingerprint()
                .equals(sealAssertion(mapper, assertion).fingerprint())) {
            throw new IllegalArgumentException("handling assertion fingerprint mismatch");
        }
    }

    /** @return exact SCENARIO_PACK reference for a sealed pack */
    public static MirrorArtifactRef reference(ScenarioPack pack) {
        requireSealed(pack == null ? "" : pack.fingerprint(), "scenario pack");
        return new MirrorArtifactRef(
                "SCENARIO_PACK", pack.packId(), pack.revision(), pack.fingerprint());
    }

    /** @return exact SCENARIO_CASE reference for a sealed case */
    public static MirrorArtifactRef reference(ScenarioCase scenarioCase) {
        requireSealed(scenarioCase == null ? "" : scenarioCase.fingerprint(), "scenario case");
        return new MirrorArtifactRef(
                "SCENARIO_CASE", scenarioCase.caseId(), scenarioCase.revision(),
                scenarioCase.fingerprint());
    }

    /** @return exact CASE_HANDLING_ASSERTION reference for a sealed assertion */
    public static MirrorArtifactRef reference(CaseHandlingAssertion assertion) {
        requireSealed(
                assertion == null ? "" : assertion.fingerprint(), "handling assertion");
        return new MirrorArtifactRef(
                "CASE_HANDLING_ASSERTION", assertion.assertionId(), assertion.revision(),
                assertion.fingerprint());
    }

    private static void validateLifecycle(
            CapabilitySnapshot.Scope scope,
            ArtifactProvenance provenance,
            CapabilitySnapshot.Lifecycle lifecycle,
            Instant createdAt) {
        if (!scope.tenantId().equals(provenance.tenantId())) {
            throw new IllegalArgumentException(
                    "scenario artifact scope tenant must match provenance tenant");
        }
        if (provenance.approvedAt() != null && createdAt.isAfter(provenance.approvedAt())) {
            throw new IllegalArgumentException(
                    "scenario artifact approval cannot predate creation");
        }
        boolean serving = lifecycle == CapabilitySnapshot.Lifecycle.ACTIVE;
        if (serving && (provenance.approvedAt() == null
                || !provenance.revocationRef().isBlank()
                || provenance.expiresAt() != null
                && !provenance.expiresAt().isAfter(createdAt))) {
            throw new IllegalArgumentException(
                    "active scenario artifact requires live owner approval");
        }
    }

    private static void requireSealed(String fingerprint, String artifact) {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException(artifact + " must be sealed before reference");
        }
    }
}
