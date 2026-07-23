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
    /** Protected mirror execution-command wire version. */
    public static final String MIRROR_EXECUTION_REQUEST_V1 =
            "resourceGateway.mirrorExecutionRequest.v1";
    /** Payload-free terminal mirror run-summary wire version. */
    public static final String MIRROR_RUN_SUMMARY_V1 =
            "resourceGateway.mirrorRunSummary.v1";
    /** Per-attempt mirror resolution wire version. */
    public static final String MIRROR_RESOLUTION_V1 = "resourceGateway.mirrorResolution.v1";
    /** Payload-free terminal mirror run evidence wire version. */
    public static final String MIRROR_RUN_EVIDENCE_V1 =
            "resourceGateway.mirrorRunEvidence.v1";
    /** Current payload-free evidence version carrying double-observed deployment trust. */
    public static final String MIRROR_RUN_EVIDENCE_V2 =
            "resourceGateway.mirrorRunEvidence.v2";
    /** Detached mirror evidence attestation wire version. */
    public static final String MIRROR_EVIDENCE_ATTESTATION_V1 =
            "resourceGateway.mirrorEvidenceAttestation.v1";
    /** Current detached evidence attestation wire version. */
    public static final String MIRROR_EVIDENCE_ATTESTATION_V2 =
            "resourceGateway.mirrorEvidenceAttestation.v2";
    /** Portable signed mirror evidence bundle wire version. */
    public static final String MIRROR_EVIDENCE_BUNDLE_V1 =
            "resourceGateway.mirrorEvidenceBundle.v1";
    /** Current portable evidence bundle wire version. */
    public static final String MIRROR_EVIDENCE_BUNDLE_V2 =
            "resourceGateway.mirrorEvidenceBundle.v2";
    /** Externally signed deployment-isolation attestation wire version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_V1 =
            "resourceGateway.mirrorDeploymentIsolationAttestation.v1";
    /** Append-only local deployment-isolation attestation status wire version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_STATUS_V1 =
            "resourceGateway.mirrorDeploymentIsolationAttestationStatus.v1";
    /** Atomic deployment-isolation attestation and current-status bundle wire version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_BUNDLE_V1 =
            "resourceGateway.mirrorDeploymentIsolationAttestationBundle.v1";
    /** Atomic deployment-agent read-only cache snapshot wire version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_AGENT_SNAPSHOT_V1 =
            "resourceGateway.mirrorDeploymentIsolationAgentSnapshot.v1";
    /** Double-observed deployment-isolation run-trust binding wire version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_RUN_TRUST_V1 =
            "resourceGateway.mirrorDeploymentIsolationRunTrust.v1";
    /** Optimistically fenced irreversible attestation revocation command wire version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_REVOCATION_REQUEST_V1 =
            "resourceGateway.mirrorDeploymentIsolationAttestationRevocationRequest.v1";
    /** Signed deployment-isolation compatibility fixture version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_COMPATIBILITY_V1 =
            "resourceGateway.mirrorDeploymentIsolationCompatibility.v1";
    /** Threshold-signed deployment-isolation authority key-set publication wire version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_V1 =
            "resourceGateway.mirrorDeploymentIsolationAuthorityKeySetPublication.v1";
    /** Signed deployment-isolation authority key-set compatibility fixture version. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_COMPATIBILITY_V1 =
            "resourceGateway.mirrorDeploymentIsolationAuthorityKeySetCompatibility.v1";
    /** Signed Stage 1 mirror evidence compatibility fixture version. */
    public static final String MIRROR_EVIDENCE_COMPATIBILITY_V1 =
            "resourceGateway.mirrorEvidenceCompatibility.v1";
    /** Signed capability-observation wire version. */
    public static final String CAPABILITY_OBSERVATION_V1 =
            "resourceGateway.capabilityObservation.v1";
    /** Immutable capability-observation admission wire version. */
    public static final String CAPABILITY_OBSERVATION_ADMISSION_V1 =
            "resourceGateway.capabilityObservationAdmission.v1";
    /** Atomic capability-observation receipt wire version. */
    public static final String CAPABILITY_OBSERVATION_RECEIPT_V1 =
            "resourceGateway.capabilityObservationReceipt.v1";
    /** Fixed observation compatibility-fixture wire version. */
    public static final String CAPABILITY_OBSERVATION_COMPATIBILITY_V1 =
            "resourceGateway.capabilityObservationCompatibility.v1";
    /** Terminal quarantine-review command wire version. */
    public static final String CAPABILITY_OBSERVATION_REVIEW_REQUEST_V1 =
            "resourceGateway.capabilityObservationReviewRequest.v1";
    /** Immutable terminal quarantine-review wire version. */
    public static final String CAPABILITY_OBSERVATION_REVIEW_V1 =
            "resourceGateway.capabilityObservationReview.v1";
    /** Immutable corpus-candidate command wire version. */
    public static final String CAPABILITY_CORPUS_CANDIDATE_REQUEST_V1 =
            "resourceGateway.capabilityCorpusCandidateRequest.v1";
    /** Immutable payload-free corpus revision wire version. */
    public static final String CAPABILITY_CORPUS_REVISION_V1 =
            "resourceGateway.capabilityCorpusRevision.v1";
    /** Owner-reviewed corpus-publication command wire version. */
    public static final String CAPABILITY_CORPUS_PUBLISH_REQUEST_V1 =
            "resourceGateway.capabilityCorpusPublishRequest.v1";
    /** Immutable serving-publication fact wire version. */
    public static final String CAPABILITY_CORPUS_PUBLICATION_V1 =
            "resourceGateway.capabilityCorpusPublication.v1";
    /** Owner-reviewed recorded-trajectory command wire version. */
    public static final String CAPABILITY_CORPUS_TRAJECTORY_PUBLISH_REQUEST_V1 =
            "resourceGateway.capabilityCorpusTrajectoryPublishRequest.v1";
    /** Immutable recorded-trajectory publication wire version. */
    public static final String CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION_V1 =
            "resourceGateway.capabilityCorpusTrajectoryPublication.v1";
    /** Fixed corpus-governance compatibility-fixture wire version. */
    public static final String CAPABILITY_CORPUS_COMPATIBILITY_V1 =
            "resourceGateway.capabilityCorpusCompatibility.v1";
    /** Fixture metadata contract selecting exact corpus serving publications. */
    public static final String FIXTURE_MIRROR_CORPUS_BINDINGS_V1 =
            "resourceGateway.fixtureMirrorCorpusBindings.v1";
    /** Fixture metadata contract selecting exact reviewed retry trajectories. */
    public static final String FIXTURE_MIRROR_TRAJECTORY_BINDINGS_V1 =
            "resourceGateway.fixtureMirrorTrajectoryBindings.v1";

    /** Classpath root containing the authoritative mirror schemas and fixtures. */
    public static final String SCHEMA_RESOURCE_ROOT = "/schemas/resource-gateway-mirror/";
    /** Packaged Stage 0 compatibility fixture. */
    public static final String COMPATIBILITY_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-mirror-stage0-v1.fixture.json";
    /** Packaged signed Stage 1 mirror evidence compatibility fixture. */
    public static final String MIRROR_EVIDENCE_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-stage1-v1.fixture.json";
    /** Packaged signed deployment-isolation compatibility fixture. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-deployment-isolation-stage1-v1.fixture.json";
    /** Packaged threshold-signed deployment-isolation authority key-set fixture. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-deployment-isolation-authority-key-set-stage1-v1.fixture.json";
    /** Packaged signed capability-observation compatibility fixture. */
    public static final String CAPABILITY_OBSERVATION_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-observation-stage2-v1.fixture.json";
    /** Packaged payload-free corpus-governance compatibility fixture. */
    public static final String CAPABILITY_CORPUS_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-corpus-stage2-v1.fixture.json";
    /** Packaged fixed fixture-level corpus-binding example. */
    public static final String FIXTURE_MIRROR_CORPUS_BINDINGS_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "fixture-mirror-corpus-bindings-v1.fixture.json";
    /** Packaged fixed fixture-level trajectory-binding example. */
    public static final String
    FIXTURE_MIRROR_TRAJECTORY_BINDINGS_FIXTURE_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "fixture-mirror-trajectory-bindings-v1.fixture.json";
    /** Packaged compatibility fixture schema. */
    public static final String COMPATIBILITY_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-mirror-compatibility-v1.schema.json";
    /** Packaged capability snapshot schema. */
    public static final String CAPABILITY_SNAPSHOT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-snapshot-v1.schema.json";
    /** Packaged capability closure schema. */
    public static final String CAPABILITY_CLOSURE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-closure-v1.schema.json";
    /** Packaged protected mirror execution-command schema. */
    public static final String MIRROR_EXECUTION_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-execution-request-v1.schema.json";
    /** Packaged payload-free terminal run-summary schema. */
    public static final String MIRROR_RUN_SUMMARY_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-run-summary-v1.schema.json";
    /** Packaged per-attempt mirror resolution schema. */
    public static final String MIRROR_RESOLUTION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-resolution-v1.schema.json";
    /** Packaged payload-free mirror run evidence schema. */
    public static final String MIRROR_RUN_EVIDENCE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-run-evidence-v1.schema.json";
    /** Packaged current mirror run evidence schema. */
    public static final String MIRROR_RUN_EVIDENCE_V2_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-run-evidence-v2.schema.json";
    /** Packaged detached mirror evidence attestation schema. */
    public static final String MIRROR_EVIDENCE_ATTESTATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-attestation-v1.schema.json";
    /** Packaged current detached mirror evidence attestation schema. */
    public static final String MIRROR_EVIDENCE_ATTESTATION_V2_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-attestation-v2.schema.json";
    /** Packaged portable mirror evidence bundle schema. */
    public static final String MIRROR_EVIDENCE_BUNDLE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-bundle-v1.schema.json";
    /** Packaged current portable mirror evidence bundle schema. */
    public static final String MIRROR_EVIDENCE_BUNDLE_V2_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "mirror-evidence-bundle-v2.schema.json";
    /** Packaged deployment-isolation attestation schema. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-deployment-isolation-attestation-v1.schema.json";
    /** Packaged append-only local attestation status schema. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_STATUS_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-deployment-isolation-attestation-status-v1.schema.json";
    /** Packaged atomic attestation and current-status bundle schema. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_BUNDLE_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-deployment-isolation-attestation-bundle-v1.schema.json";
    /** Packaged atomic deployment-agent read-only cache snapshot schema. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_AGENT_SNAPSHOT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-deployment-isolation-agent-snapshot-v1.schema.json";
    /** Packaged double-observed deployment-isolation run-trust schema. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_RUN_TRUST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-deployment-isolation-run-trust-v1.schema.json";
    /** Packaged optimistic irreversible attestation revocation-command schema. */
    public static final String
    MIRROR_DEPLOYMENT_ISOLATION_ATTESTATION_REVOCATION_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-deployment-isolation-attestation-revocation-request-v1.schema.json";
    /** Packaged threshold-signed deployment-isolation authority key-set schema. */
    public static final String MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "mirror-deployment-isolation-authority-key-set-publication-v1.schema.json";
    /** Packaged signed capability-observation schema. */
    public static final String CAPABILITY_OBSERVATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-observation-v1.schema.json";
    /** Packaged immutable capability-observation admission schema. */
    public static final String CAPABILITY_OBSERVATION_ADMISSION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-observation-admission-v1.schema.json";
    /** Packaged atomic capability-observation receipt schema. */
    public static final String CAPABILITY_OBSERVATION_RECEIPT_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-observation-receipt-v1.schema.json";
    /** Packaged terminal quarantine-review command schema. */
    public static final String CAPABILITY_OBSERVATION_REVIEW_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "capability-observation-review-request-v1.schema.json";
    /** Packaged immutable terminal quarantine-review schema. */
    public static final String CAPABILITY_OBSERVATION_REVIEW_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-observation-review-v1.schema.json";
    /** Packaged immutable corpus-candidate command schema. */
    public static final String CAPABILITY_CORPUS_CANDIDATE_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "capability-corpus-candidate-request-v1.schema.json";
    /** Packaged immutable payload-free corpus revision schema. */
    public static final String CAPABILITY_CORPUS_REVISION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-corpus-revision-v1.schema.json";
    /** Packaged owner-reviewed corpus-publication command schema. */
    public static final String CAPABILITY_CORPUS_PUBLISH_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "capability-corpus-publish-request-v1.schema.json";
    /** Packaged immutable serving-publication fact schema. */
    public static final String CAPABILITY_CORPUS_PUBLICATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT + "capability-corpus-publication-v1.schema.json";
    /** Packaged owner-reviewed recorded-trajectory command schema. */
    public static final String
    CAPABILITY_CORPUS_TRAJECTORY_PUBLISH_REQUEST_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "capability-corpus-trajectory-publish-request-v1.schema.json";
    /** Packaged immutable recorded-trajectory publication schema. */
    public static final String
    CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "capability-corpus-trajectory-publication-v1.schema.json";
    /** Packaged strict fixture-level corpus-binding schema. */
    public static final String FIXTURE_MIRROR_CORPUS_BINDINGS_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "fixture-mirror-corpus-bindings-v1.schema.json";
    /** Packaged strict fixture-level trajectory-binding schema. */
    public static final String
    FIXTURE_MIRROR_TRAJECTORY_BINDINGS_SCHEMA_RESOURCE =
            SCHEMA_RESOURCE_ROOT
                    + "fixture-mirror-trajectory-bindings-v1.schema.json";

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

    /**
     * Returns the fixed independently verified deployment-isolation compatibility fixture.
     *
     * <p>The fixture proves strict-schema loading, canonical nested fingerprints, immutable local
     * identity comparison, validity-window handling, public-key parsing, and Ed25519 verification
     * without contacting a Resource Gateway service.</p>
     *
     * @return detached signed attestation, pinned authority key, and expected execution window
     * @throws IllegalStateException when the packaged fixture is absent, malformed, or unverifiable
     */
    public static MirrorDeploymentIsolationCompatibilityFixture
    mirrorDeploymentIsolationCompatibilityFixture() {
        return IsolationFixtureHolder.FIXTURE.detachedCopy();
    }

    /**
     * Returns the fixed public-only isolation-authority key-set compatibility fixture.
     *
     * <p>The fixture proves strict-schema loading, nested canonical fingerprints, exact full-scope
     * binding, M-of-N public-root verification, and bootstrap generation handling without a server
     * process or any private key.</p>
     *
     * @return detached publication, local binding, bootstrap roots, and verification time
     * @throws IllegalStateException when the packaged fixture is absent, malformed, or unverifiable
     */
    public static MirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture
    mirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture() {
        return IsolationAuthorityFixtureHolder.FIXTURE.detachedCopy();
    }

    /**
     * Returns the fixed independently verified capability-observation fixture.
     *
     * <p>The fixture proves strict-schema loading, canonical content addressing, full-scope
     * comparison, purpose-window checks, public-key parsing, and Ed25519 verification without
     * contacting Resource Gateway or a payload vault.</p>
     *
     * @return detached signed observation, public key, expected scope, and verification time
     * @throws IllegalStateException when the packaged fixture is absent or unverifiable
     */
    public static CapabilityObservationCompatibilityFixture
            capabilityObservationCompatibilityFixture() {
        return ObservationFixtureHolder.FIXTURE.detachedCopy();
    }

    /**
     * Returns the fixed independently verified corpus-governance fixture.
     *
     * <p>The fixture proves strict-schema loading, canonical command and artifact fingerprints,
     * complete-scope closure, command-to-fact binding, policy-independent risk statistics,
     * lineage, and use horizons without linking Resource Gateway or contacting payload and policy
     * authorities.</p>
     *
     * @return detached payload-free review, candidate, and publication lifecycle
     * @throws IllegalStateException when the packaged fixture is absent or unverifiable
     */
    public static CapabilityCorpusCompatibilityFixture
            capabilityCorpusCompatibilityFixture() {
        return CorpusFixtureHolder.FIXTURE.detachedCopy();
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

    private static final class ObservationFixtureHolder {
        private static final CapabilityObservationCompatibilityFixture FIXTURE = load();

        private static CapabilityObservationCompatibilityFixture load() {
            try (InputStream input = CapabilityMirrorProtocol.class.getResourceAsStream(
                    CAPABILITY_OBSERVATION_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException("Capability observation fixture is absent");
                }
                JsonNode value = JSON.readTree(input);
                if (!value.isObject() || value.size() != 5
                        || !Set.of("schemaVersion", "verificationKey", "expectedScope",
                        "verificationTime", "observation").equals(fieldNames(value))
                        || !CAPABILITY_OBSERVATION_COMPATIBILITY_V1.equals(
                        value.path("schemaVersion").asText())) {
                    throw new IOException(
                            "Capability observation fixture envelope is invalid");
                }
                CapabilityMirrorSchemaValidator.require(
                        value.path("observation"),
                        CAPABILITY_OBSERVATION_SCHEMA_RESOURCE,
                        "RG.MIRROR.CLIENT.OBSERVATION_FIXTURE_SCHEMA_INVALID");
                CapabilityObservationCompatibilityFixture fixture =
                        CapabilityObservationCompatibilityFixture.from(value);
                CapabilityObservationVerifier.VerificationResult result =
                        new CapabilityObservationVerifier().verify(
                                fixture.observation(),
                                fixture.verificationKey(),
                                fixture.expectedScope(),
                                fixture.verificationTime());
                if (!result.verified()) {
                    throw new IOException(
                            "Capability observation fixture verification failed");
                }
                return fixture;
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.OBSERVATION_FIXTURE_UNAVAILABLE");
            }
        }

        private static Set<String> fieldNames(JsonNode value) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            value.fieldNames().forEachRemaining(names::add);
            return Set.copyOf(names);
        }
    }

    private static final class CorpusFixtureHolder {
        private static final CapabilityCorpusCompatibilityFixture FIXTURE = load();

        private static CapabilityCorpusCompatibilityFixture load() {
            try (InputStream input = CapabilityMirrorProtocol.class.getResourceAsStream(
                    CAPABILITY_CORPUS_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException(
                            "Capability corpus fixture is absent");
                }
                JsonNode value = JSON.readTree(input);
                if (!value.isObject() || value.size() != 9
                        || !Set.of(
                        "schemaVersion",
                        "verificationTime",
                        "expectedScope",
                        "reviewRequest",
                        "review",
                        "candidateRequest",
                        "revision",
                        "publishRequest",
                        "publication").equals(fieldNames(value))
                        || !CAPABILITY_CORPUS_COMPATIBILITY_V1.equals(
                        value.path("schemaVersion").asText())) {
                    throw new IOException(
                            "Capability corpus fixture envelope is invalid");
                }
                CapabilityCorpusCompatibilityFixture fixture =
                        CapabilityCorpusCompatibilityFixture.from(value);
                CapabilityCorpusVerifier.VerificationResult result =
                        new CapabilityCorpusVerifier().verify(fixture);
                if (!result.verified()) {
                    throw new IOException(
                            "Capability corpus fixture verification failed: "
                                    + result.reasonCode());
                }
                return fixture;
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.CORPUS_FIXTURE_UNAVAILABLE",
                        failure);
            }
        }

        private static Set<String> fieldNames(JsonNode value) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            value.fieldNames().forEachRemaining(names::add);
            return Set.copyOf(names);
        }
    }

    private static final class IsolationFixtureHolder {
        private static final MirrorDeploymentIsolationCompatibilityFixture FIXTURE = load();

        private static MirrorDeploymentIsolationCompatibilityFixture load() {
            try (InputStream input = CapabilityMirrorProtocol.class.getResourceAsStream(
                    MIRROR_DEPLOYMENT_ISOLATION_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException("Deployment isolation fixture is absent");
                }
                JsonNode value = JSON.readTree(input);
                if (!value.isObject() || value.size() != 5
                        || !Set.of("schemaVersion", "verificationKey", "expectedDeployment",
                        "executionWindow", "attestation").equals(fieldNames(value))
                        || !MIRROR_DEPLOYMENT_ISOLATION_COMPATIBILITY_V1.equals(
                        value.path("schemaVersion").asText())) {
                    throw new IOException("Deployment isolation fixture envelope is invalid");
                }
                MirrorDeploymentIsolationVerificationKey key =
                        MirrorDeploymentIsolationVerificationKey.from(
                                value.path("verificationKey"));
                MirrorDeploymentIdentity expected = MirrorDeploymentIdentity.from(
                                value.path("expectedDeployment"));
                JsonNode executionWindow = value.path("executionWindow");
                if (!executionWindow.isObject() || executionWindow.size() != 2
                        || !Set.of("startedAt", "completedAt")
                        .equals(fieldNames(executionWindow))) {
                    throw new IOException("Deployment isolation execution window is invalid");
                }
                Instant startedAt = Instant.parse(
                        executionWindow.path("startedAt").asText());
                Instant completedAt = Instant.parse(
                        executionWindow.path("completedAt").asText());
                JsonNode attestation = value.path("attestation");
                var verification = new MirrorDeploymentIsolationAttestationVerifier().verify(
                        attestation, key, expected, startedAt, completedAt);
                if (!verification.verified()) {
                    throw new IOException("Deployment isolation fixture cannot be verified: "
                            + verification.reasonCode());
                }
                return new MirrorDeploymentIsolationCompatibilityFixture(attestation, key,
                        expected, startedAt, completedAt);
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.DEPLOYMENT_ISOLATION_FIXTURE_UNAVAILABLE", failure);
            }
        }

        private static Set<String> fieldNames(JsonNode value) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            value.fieldNames().forEachRemaining(names::add);
            return Set.copyOf(names);
        }
    }

    private static final class IsolationAuthorityFixtureHolder {
        private static final MirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture FIXTURE =
                load();

        private static MirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture load() {
            try (InputStream input = CapabilityMirrorProtocol.class.getResourceAsStream(
                    MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_FIXTURE_RESOURCE)) {
                if (input == null) {
                    throw new IOException("Deployment isolation authority fixture is absent");
                }
                JsonNode value = JSON.readTree(input);
                if (!value.isObject() || value.size() != 5
                        || !Set.of("schemaVersion", "verificationTime", "expectedBinding",
                        "bootstrapRoots", "publication").equals(fieldNames(value))
                        || !MIRROR_DEPLOYMENT_ISOLATION_AUTHORITY_KEY_SET_COMPATIBILITY_V1.equals(
                        value.path("schemaVersion").asText())) {
                    throw new IOException(
                            "Deployment isolation authority fixture envelope is invalid");
                }
                MirrorDeploymentIsolationAuthorityKeySetBinding binding =
                        MirrorDeploymentIsolationAuthorityKeySetBinding.from(
                                value.path("expectedBinding"));
                java.util.ArrayList<MirrorDeploymentIsolationRootVerificationKey> roots =
                        new java.util.ArrayList<>();
                value.path("bootstrapRoots").forEach(root -> roots.add(
                        MirrorDeploymentIsolationRootVerificationKey.from(root)));
                Instant verificationTime = Instant.parse(
                        value.path("verificationTime").asText());
                JsonNode publication = value.path("publication");
                var verification = new MirrorDeploymentIsolationAuthorityKeySetVerifier().verify(
                        publication, binding, roots, null, verificationTime);
                if (!verification.verified()) {
                    throw new IOException(
                            "Deployment isolation authority fixture cannot be verified: "
                                    + verification.reasonCode());
                }
                return new MirrorDeploymentIsolationAuthorityKeySetCompatibilityFixture(
                        publication, binding, roots, verificationTime);
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException(
                        "RG.MIRROR.CLIENT.DEPLOYMENT_ISOLATION_AUTHORITY_FIXTURE_UNAVAILABLE",
                        failure);
            }
        }

        private static Set<String> fieldNames(JsonNode value) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            value.fieldNames().forEachRemaining(names::add);
            return Set.copyOf(names);
        }
    }
}
