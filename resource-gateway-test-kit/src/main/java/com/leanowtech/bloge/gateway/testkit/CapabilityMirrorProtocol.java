package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Set;

/**
 * Public wire constants and the packaged compatibility baseline for capability-mirror clients.
 *
 * <p>The constants belong to the standalone test kit rather than the Resource Gateway server so a
 * governance consumer can negotiate and verify mirror artifacts without linking Spring or server
 * implementation classes.</p>
 */
public final class CapabilityMirrorProtocol {

    /** Tool Studio integration protocol name required by the Stage 0 baseline. */
    public static final String INTEGRATION_PROTOCOL = "ToolStudioResourceGatewayProtocol";
    /** Tool Studio integration protocol version required by the Stage 0 baseline. */
    public static final String INTEGRATION_PROTOCOL_V1 = "1.0.0";
    /** Capability-mirror compatibility fixture wire version. */
    public static final String COMPATIBILITY_V1 =
            "resourceGateway.capabilityMirrorCompatibility.v1";
    /** Artifact provenance wire version. */
    public static final String ARTIFACT_PROVENANCE_V1 =
            "resourceGateway.artifactProvenance.v1";
    /** Effect contract wire version. */
    public static final String EFFECT_CONTRACT_V1 = "resourceGateway.effectContract.v1";
    /** Capability contract wire version. */
    public static final String CAPABILITY_CONTRACT_V1 =
            "resourceGateway.capabilityContract.v1";
    /** Capability snapshot wire version. */
    public static final String CAPABILITY_SNAPSHOT_V1 =
            "resourceGateway.capabilitySnapshot.v1";
    /** Capability closure wire version. */
    public static final String CAPABILITY_CLOSURE_V1 =
            "resourceGateway.capabilityClosure.v1";
    /** Capability lifecycle transition wire version. */
    public static final String CAPABILITY_LIFECYCLE_TRANSITION_V1 =
            "resourceGateway.capabilityLifecycleTransition.v1";
    /** Per-attempt mirror resolution wire version. */
    public static final String MIRROR_RESOLUTION_V1 = "resourceGateway.mirrorResolution.v1";
    /** Payload-free terminal mirror run evidence wire version. */
    public static final String MIRROR_RUN_EVIDENCE_V1 =
            "resourceGateway.mirrorRunEvidence.v1";
    /** Detached mirror evidence attestation wire version. */
    public static final String MIRROR_EVIDENCE_ATTESTATION_V1 =
            "resourceGateway.mirrorEvidenceAttestation.v1";
    /** Portable signed mirror evidence bundle wire version. */
    public static final String MIRROR_EVIDENCE_BUNDLE_V1 =
            "resourceGateway.mirrorEvidenceBundle.v1";
    /** Signed Stage 1 mirror evidence compatibility fixture version. */
    public static final String MIRROR_EVIDENCE_COMPATIBILITY_V1 =
            "resourceGateway.mirrorEvidenceCompatibility.v1";

    /** Classpath root containing the authoritative mirror schemas and fixtures. */
    public static final String SCHEMA_RESOURCE_ROOT = "/schemas/resource-gateway-mirror/";
    /** Packaged Stage 0 compatibility fixture. */
    public static final String COMPATIBILITY_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-mirror-stage0-v1.fixture.json";
    /** Packaged signed Stage 1 mirror evidence compatibility fixture. */
    public static final String MIRROR_EVIDENCE_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-stage1-v1.fixture.json";
    /** Packaged compatibility fixture schema. */
    public static final String COMPATIBILITY_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-mirror-compatibility-v1.schema.json";
    /** Packaged capability snapshot schema. */
    public static final String CAPABILITY_SNAPSHOT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-snapshot-v1.schema.json";
    /** Packaged capability closure schema. */
    public static final String CAPABILITY_CLOSURE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-closure-v1.schema.json";
    /** Packaged per-attempt mirror resolution schema. */
    public static final String MIRROR_RESOLUTION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-resolution-v1.schema.json";
    /** Packaged payload-free mirror run evidence schema. */
    public static final String MIRROR_RUN_EVIDENCE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-run-evidence-v1.schema.json";
    /** Packaged detached mirror evidence attestation schema. */
    public static final String MIRROR_EVIDENCE_ATTESTATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-attestation-v1.schema.json";
    /** Packaged portable mirror evidence bundle schema. */
    public static final String MIRROR_EVIDENCE_BUNDLE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-bundle-v1.schema.json";

    private static final ObjectMapper JSON = new ObjectMapper();

    private CapabilityMirrorProtocol() {
    }

    /**
     * Returns an independent copy of the machine-readable Stage 0 compatibility baseline.
     *
     * <p>The fixture is validated against its packaged strict JSON Schema before it is exposed. A
     * deep copy prevents one caller from changing the process-wide baseline seen by another.</p>
     *
     * @return validated mutable copy of the packaged compatibility fixture
     * @throws IllegalStateException when the test-kit artifact is incomplete or corrupt
     */
    public static JsonNode compatibilityBaseline() {
        return BaselineHolder.BASELINE.deepCopy();
    }

    /**
     * Returns the fixed, independently verified Stage 1 evidence compatibility fixture.
     *
     * <p>Consumers can run this fixture in packaging, upgrade, and startup probes to prove that
     * their canonicalization, closure checks, public-key parsing, and Ed25519 provider remain
     * compatible with the Resource Gateway producer contract.</p>
     *
     * @return detached signed bundle and immutable public verification key
     * @throws IllegalStateException when the packaged fixture is absent, malformed, or unverifiable
     */
    public static MirrorEvidenceCompatibilityFixture mirrorEvidenceCompatibilityFixture() {
        return MirrorFixtureHolder.FIXTURE.detachedCopy();
    }

    private static final class BaselineHolder {
        private static final JsonNode BASELINE = load();

        private static JsonNode load() {
            try (InputStream input = CapabilityMirrorProtocol.class.getResourceAsStream(
                    COMPATIBILITY_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException("Compatibility fixture is absent");
                }
                JsonNode baseline = JSON.readTree(input);
                CapabilityMirrorSchemaValidator.require(baseline, COMPATIBILITY_SCHEMA_RESOURCE,
                        "RG.MIRROR.CLIENT.COMPATIBILITY_BASELINE_INVALID");
                return baseline;
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.COMPATIBILITY_BASELINE_UNAVAILABLE");
            }
        }
    }

    private static final class MirrorFixtureHolder {
        private static final MirrorEvidenceCompatibilityFixture FIXTURE = load();

        private static MirrorEvidenceCompatibilityFixture load() {
            try (InputStream input = CapabilityMirrorProtocol.class.getResourceAsStream(
                    MIRROR_EVIDENCE_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException("Mirror evidence fixture is absent");
                }
                JsonNode value = JSON.readTree(input);
                if (!value.isObject() || value.size() != 3
                        || !Set.of("schemaVersion", "verificationKey", "bundle")
                        .equals(fieldNames(value))
                        || !MIRROR_EVIDENCE_COMPATIBILITY_V1.equals(
                        value.path("schemaVersion").asText())) {
                    throw new IOException("Mirror evidence fixture envelope is invalid");
                }
                JsonNode keyValue = value.path("verificationKey");
                if (!keyValue.isObject() || keyValue.size() != 7
                        || !Set.of("schemaVersion", "keyId", "algorithm", "encodedPublicKey",
                        "createdAt", "state", "provider").equals(fieldNames(keyValue))) {
                    throw new IOException("Mirror evidence fixture key is invalid");
                }
                EvidenceVerificationKey key = new EvidenceVerificationKey(
                        keyValue.path("schemaVersion").asText(), keyValue.path("keyId").asText(),
                        keyValue.path("algorithm").asText(),
                        keyValue.path("encodedPublicKey").asText(),
                        Instant.parse(keyValue.path("createdAt").asText()),
                        keyValue.path("state").asText(), keyValue.path("provider").asText());
                JsonNode bundle = value.path("bundle");
                MirrorEvidenceVerifier.VerificationResult verification =
                        new MirrorEvidenceVerifier().verify(bundle, key);
                if (!verification.verified()) {
                    throw new IOException("Mirror evidence fixture cannot be verified");
                }
                return new MirrorEvidenceCompatibilityFixture(bundle, key);
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.EVIDENCE_FIXTURE_UNAVAILABLE");
            }
        }

        private static Set<String> fieldNames(JsonNode value) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            value.fieldNames().forEachRemaining(names::add);
            return Set.copyOf(names);
        }
    }
}
