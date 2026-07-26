package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.List;
import java.util.Objects;

/**
 * Complete immutable selected-population revision returned by the protected API.
 *
 * @param schemaVersion exact bundle version
 * @param manifest signed selected-population root
 * @param chunks complete ordered content-addressed member chunks
 * @param predecessorFingerprint blank for revision one, exact predecessor root otherwise
 */
public record AuthoritativeOutcomeSelectedPopulationBundle(
        String schemaVersion,
        AuthoritativeOutcomeSelectedPopulationManifest
                manifest,
        List<AuthoritativeOutcomeSelectedPopulationChunk>
                chunks,
        String predecessorFingerprint
) {
    /** Current complete population bundle wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSelectedPopulationBundle.v1";

    /** Requires exact root and predecessor lineage. */
    public AuthoritativeOutcomeSelectedPopulationBundle {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported selected-population bundle schemaVersion");
        }
        manifest = Objects.requireNonNull(
                manifest, "manifest");
        chunks = chunks == null
                ? List.of() : List.copyOf(chunks);
        predecessorFingerprint =
                predecessorFingerprint == null
                        ? ""
                        : predecessorFingerprint.trim();
        if (!predecessorFingerprint.isBlank()
                && !predecessorFingerprint.matches(
                "sha256:[a-f0-9]{64}")
                || (manifest.revision() == 1)
                != predecessorFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "selected-population bundle predecessor is invalid");
        }
    }

    /** Creates a public bundle from one verified repository result. */
    public static AuthoritativeOutcomeSelectedPopulationBundle
    from(
            AuthoritativeOutcomeSelectedPopulationRepository
                    .Population population) {
        AuthoritativeOutcomeSelectedPopulationRepository
                .Population exact =
                Objects.requireNonNull(
                        population, "population");
        return new
                AuthoritativeOutcomeSelectedPopulationBundle(
                SCHEMA_VERSION,
                exact.manifest(),
                exact.chunks(),
                exact.predecessorFingerprint());
    }
}
