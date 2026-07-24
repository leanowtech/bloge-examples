package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** Canonical content-addressing boundary for immutable Scenario batch manifests. */
public final class ScenarioRehearsalBatchManifestIntegrity {
    /** Maximum canonical manifest bytes admitted by compiler and repository. */
    public static final int MAXIMUM_BYTES = 4 * 1024 * 1024;

    private ScenarioRehearsalBatchManifestIntegrity() {
    }

    /** @return sealed immutable manifest */
    public static ScenarioRehearsalBatchManifest seal(
            ObjectMapper mapper,
            ScenarioRehearsalBatchManifest manifest) {
        ScenarioRehearsalBatchManifest material =
                Objects.requireNonNull(
                        manifest, "manifest")
                        .withFingerprint("");
        return material.withFingerprint(
                ProtocolFingerprint.ofBounded(
                        Objects.requireNonNull(mapper, "mapper"),
                        material,
                        MAXIMUM_BYTES));
    }

    /** Verifies content address and stable full-scope batch/run identities. */
    public static void verify(
            ObjectMapper mapper,
            ScenarioRehearsalBatchManifest manifest) {
        ScenarioRehearsalBatchManifest exact =
                Objects.requireNonNull(manifest, "manifest");
        if (exact.manifestFingerprint().isBlank()
                || !constantTimeEquals(
                exact.manifestFingerprint(),
                seal(mapper, exact).manifestFingerprint())
                || !ScenarioRehearsalBatchIdentity.derive(
                mapper, exact.scope(), exact.requestId())
                .equals(exact.batchId())) {
            throw new IllegalArgumentException(
                    "Scenario rehearsal batch manifest integrity mismatch");
        }
        for (ScenarioRehearsalBatchManifest.Entry entry
                : exact.entries()) {
            if (!ScenarioRehearsalRunIdentity.derive(
                    mapper,
                    exact.scope(),
                    entry.aggregateRequestId())
                    .equals(entry.aggregateRunId())) {
                throw new IllegalArgumentException(
                        "Scenario batch aggregate run identity mismatch");
            }
        }
    }

    private static boolean constantTimeEquals(
            String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }
}
