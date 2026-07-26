package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Signed payload-free evidence from one regional online baseline sidecar.
 *
 * <p>The protocol proves which immutable request, data-use grant, read-only workload identity,
 * transport policy, and payload-vault receipt produced the normalized facts. It deliberately has
 * no field capable of carrying an endpoint, credential, request value, response value, or free
 * text failure message.</p>
 *
 * @param schemaVersion exact observation wire version
 * @param observationFingerprint complete observation content address
 * @param observationId deterministic observation identity
 * @param revision immutable artifact revision
 * @param scope complete enterprise scope
 * @param executionId stable source idempotency identity
 * @param requestId durable Resource Gateway request identity
 * @param commandFingerprint exact sidecar command content address
 * @param scenarioCaseRef exact immutable scenario source
 * @param targetCapabilityRef exact baseline capability
 * @param baselineBindingRef exact production read binding
 * @param comparisonPolicyRef exact normalization policy
 * @param samplingGrantRef exact data-use and sampling grant
 * @param egressAuthorityRef exact deployment egress authority
 * @param killSwitchRef exact enabled kill-switch generation
 * @param workloadIdentityRef exact short-lived read-only workload identity generation
 * @param workloadIdentityAttestationRef exact identity capability proof
 * @param payloadVaultReceiptRef opaque regional payload-vault receipt
 * @param transportAttestationRef exact read-only transport policy proof
 * @param requestContextFingerprint canonical resolved request fingerprint
 * @param semanticResultFingerprint canonical baseline result fingerprint
 * @param sourceRequestFingerprint hash-only external request evidence
 * @param sourceResponseFingerprint hash-only external response evidence
 * @param idempotencyKeyFingerprint domain-separated source idempotency proof
 * @param responseSchemaRef exact response schema applied before normalization
 * @param normalizedFactFingerprints canonical facts by fidelity dimension
 * @param accessMode enforced source credential capability
 * @param startedAt source execution start
 * @param completedAt source execution completion
 * @param deadlineAt exact source deadline
 * @param workloadIdentityExpiresAt exclusive workload identity expiry
 * @param evidenceClass exploratory or certifiable source classification
 * @param evidenceComplete whether the source produced all required evidence
 * @param writeCredentialExposed measured write-capable credential exposure
 * @param writeAttemptCount measured external write attempts
 * @param issuedAt trusted sidecar evidence issue time
 * @param observationSeal detached online-baseline authority signature
 */
public record OnlineReadOnlyShadowBaselineObservation(
        String schemaVersion,
        String observationFingerprint,
        String observationId,
        long revision,
        CapabilitySnapshot.Scope scope,
        String executionId,
        String requestId,
        String commandFingerprint,
        MirrorArtifactRef scenarioCaseRef,
        MirrorArtifactRef targetCapabilityRef,
        MirrorArtifactRef baselineBindingRef,
        MirrorArtifactRef comparisonPolicyRef,
        MirrorArtifactRef samplingGrantRef,
        MirrorArtifactRef egressAuthorityRef,
        MirrorArtifactRef killSwitchRef,
        MirrorArtifactRef workloadIdentityRef,
        MirrorArtifactRef workloadIdentityAttestationRef,
        MirrorArtifactRef payloadVaultReceiptRef,
        MirrorArtifactRef transportAttestationRef,
        String requestContextFingerprint,
        String semanticResultFingerprint,
        String sourceRequestFingerprint,
        String sourceResponseFingerprint,
        String idempotencyKeyFingerprint,
        MirrorArtifactRef responseSchemaRef,
        Map<DomainFidelityProfile.Dimension, String>
                normalizedFactFingerprints,
        AccessMode accessMode,
        Instant startedAt,
        Instant completedAt,
        Instant deadlineAt,
        Instant workloadIdentityExpiresAt,
        MirrorRunEvidence.EvidenceClass evidenceClass,
        boolean evidenceComplete,
        boolean writeCredentialExposed,
        long writeAttemptCount,
        Instant issuedAt,
        VisualRunEvidenceSeal observationSeal
) {
    /** Current online baseline observation protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.onlineReadOnlyShadowBaselineObservation.v1";
    /** Immutable artifact kind referenced by paired source evidence. */
    public static final String ARTIFACT_KIND =
            "SHADOW_BASELINE_OBSERVATION";
    /** Maximum canonical observation admitted to hashing. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            512 * 1024;
    /** Maximum domain-separated signature material. */
    public static final int MAXIMUM_SIGNATURE_BYTES =
            16 * 1024;
    private static final int MAXIMUM_IDENTITY_BYTES =
            16 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile(
                    "[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    /** Validates immutable coordinates while preserving measured write violations. */
    public OnlineReadOnlyShadowBaselineObservation {
        schemaVersion = required(
                schemaVersion, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported online baseline observation schemaVersion");
        }
        observationFingerprint =
                optionalFingerprint(
                        observationFingerprint,
                        "observationFingerprint");
        observationId = identifier(
                observationId, "observationId");
        if (revision != 1) {
            throw new IllegalArgumentException(
                    "online baseline observation revision must be one");
        }
        scope = Objects.requireNonNull(scope, "scope");
        executionId = identifier(
                executionId, "executionId");
        requestId = identifier(
                requestId, "requestId");
        commandFingerprint = fingerprint(
                commandFingerprint,
                "commandFingerprint");
        scenarioCaseRef = kind(
                scenarioCaseRef,
                "SCENARIO_CASE",
                "scenarioCaseRef");
        targetCapabilityRef = kind(
                targetCapabilityRef,
                "CAPABILITY",
                "targetCapabilityRef");
        baselineBindingRef = kind(
                baselineBindingRef,
                "SHADOW_BASELINE_BINDING",
                "baselineBindingRef");
        comparisonPolicyRef = kind(
                comparisonPolicyRef,
                "SHADOW_COMPARISON_POLICY",
                "comparisonPolicyRef");
        samplingGrantRef = kind(
                samplingGrantRef,
                "SHADOW_SAMPLING_GRANT",
                "samplingGrantRef");
        egressAuthorityRef = kind(
                egressAuthorityRef,
                MirrorDeploymentIsolationAttestation
                        .ARTIFACT_KIND,
                "egressAuthorityRef");
        killSwitchRef = kind(
                killSwitchRef,
                "SHADOW_KILL_SWITCH_STATE",
                "killSwitchRef");
        workloadIdentityRef = kind(
                workloadIdentityRef,
                "WORKLOAD_IDENTITY",
                "workloadIdentityRef");
        workloadIdentityAttestationRef = kind(
                workloadIdentityAttestationRef,
                "WORKLOAD_IDENTITY_ATTESTATION",
                "workloadIdentityAttestationRef");
        payloadVaultReceiptRef = kind(
                payloadVaultReceiptRef,
                "PAYLOAD_VAULT_RECEIPT",
                "payloadVaultReceiptRef");
        transportAttestationRef = kind(
                transportAttestationRef,
                "READ_ONLY_TRANSPORT_ATTESTATION",
                "transportAttestationRef");
        requestContextFingerprint = fingerprint(
                requestContextFingerprint,
                "requestContextFingerprint");
        semanticResultFingerprint = fingerprint(
                semanticResultFingerprint,
                "semanticResultFingerprint");
        sourceRequestFingerprint = fingerprint(
                sourceRequestFingerprint,
                "sourceRequestFingerprint");
        sourceResponseFingerprint = fingerprint(
                sourceResponseFingerprint,
                "sourceResponseFingerprint");
        idempotencyKeyFingerprint = fingerprint(
                idempotencyKeyFingerprint,
                "idempotencyKeyFingerprint");
        responseSchemaRef = kind(
                responseSchemaRef,
                "JSON_SCHEMA",
                "responseSchemaRef");
        normalizedFactFingerprints =
                facts(normalizedFactFingerprints);
        accessMode = Objects.requireNonNull(
                accessMode, "accessMode");
        startedAt = Objects.requireNonNull(
                startedAt, "startedAt");
        completedAt = Objects.requireNonNull(
                completedAt, "completedAt");
        deadlineAt = Objects.requireNonNull(
                deadlineAt, "deadlineAt");
        workloadIdentityExpiresAt =
                Objects.requireNonNull(
                        workloadIdentityExpiresAt,
                        "workloadIdentityExpiresAt");
        evidenceClass = Objects.requireNonNull(
                evidenceClass, "evidenceClass");
        issuedAt = Objects.requireNonNull(
                issuedAt, "issuedAt");
        observationSeal = Objects.requireNonNullElse(
                observationSeal,
                VisualRunEvidenceSeal.unsigned());
        if (completedAt.isBefore(startedAt)
                || !deadlineAt.isAfter(completedAt)
                || !workloadIdentityExpiresAt
                .isAfter(completedAt)
                || issuedAt.isBefore(completedAt)
                || writeAttemptCount < 0
                || writeAttemptCount > 1_000_000_000L) {
            throw new IllegalArgumentException(
                    "online baseline observation timing or counters are invalid");
        }
    }

    /** Source credentials accepted by the online baseline protocol. */
    public enum AccessMode {
        /** Short-lived identity can invoke only externally certified read operations. */
        READ_ONLY
    }

    /**
     * Builds the immutable content-addressed observation reference.
     *
     * @return exact online baseline observation coordinates
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(
                ARTIFACT_KIND,
                observationId,
                revision,
                fingerprint(
                        observationFingerprint,
                        "observationFingerprint"));
    }

    /**
     * Derives the protocol-defined immutable observation identity.
     *
     * <p>Both independently deployed sidecars and Resource Gateway consumers use this function.
     * The identity therefore depends only on immutable scope, execution, command, and baseline
     * binding coordinates and never on a deployment-local sequence.</p>
     *
     * @param mapper canonical protocol mapper
     * @param scope complete enterprise scope
     * @param executionId stable source idempotency identity
     * @param commandFingerprint exact sidecar command content address
     * @param baselineBindingRef exact production read binding
     * @return deterministic path-safe observation identity
     */
    public static String deterministicObservationId(
            ObjectMapper mapper,
            CapabilitySnapshot.Scope scope,
            String executionId,
            String commandFingerprint,
            MirrorArtifactRef baselineBindingRef) {
        String fingerprint = ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new IdentityMaterial(
                        "RESOURCE_GATEWAY_ONLINE_READ_ONLY_SHADOW_BASELINE_OBSERVATION_ID_V1",
                        Objects.requireNonNull(scope, "scope"),
                        identifier(executionId, "executionId"),
                        fingerprint(
                                commandFingerprint,
                                "commandFingerprint"),
                        kind(
                                baselineBindingRef,
                                "SHADOW_BASELINE_BINDING",
                                "baselineBindingRef")),
                MAXIMUM_IDENTITY_BYTES);
        return "online-baseline-"
                + fingerprint.substring(
                "sha256:".length());
    }

    /**
     * Recomputes the complete observation content address.
     *
     * @param mapper canonical protocol mapper
     */
    public void verify(ObjectMapper mapper) {
        if (!calculateFingerprint(mapper).equals(
                observationFingerprint)) {
            throw new IllegalArgumentException(
                    "online baseline observation fingerprint mismatch");
        }
    }

    /**
     * Returns domain-separated material signed by the regional sidecar authority.
     *
     * @param mapper canonical protocol mapper
     * @return exact signature material fingerprint
     */
    public String observationMaterialFingerprint(
            ObjectMapper mapper) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new SignatureMaterial(
                        "RESOURCE_GATEWAY_ONLINE_READ_ONLY_SHADOW_BASELINE_OBSERVATION_V1",
                        schemaVersion,
                        observationId,
                        revision,
                        scope,
                        issuedAt,
                        fingerprint(
                                observationFingerprint,
                                "observationFingerprint")),
                MAXIMUM_SIGNATURE_BYTES);
    }

    String calculateFingerprint(
            ObjectMapper mapper) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new FingerprintMaterial(
                        schemaVersion,
                        "",
                        observationId,
                        revision,
                        scope,
                        executionId,
                        requestId,
                        commandFingerprint,
                        scenarioCaseRef,
                        targetCapabilityRef,
                        baselineBindingRef,
                        comparisonPolicyRef,
                        samplingGrantRef,
                        egressAuthorityRef,
                        killSwitchRef,
                        workloadIdentityRef,
                        workloadIdentityAttestationRef,
                        payloadVaultReceiptRef,
                        transportAttestationRef,
                        requestContextFingerprint,
                        semanticResultFingerprint,
                        sourceRequestFingerprint,
                        sourceResponseFingerprint,
                        idempotencyKeyFingerprint,
                        responseSchemaRef,
                        normalizedFactFingerprints,
                        accessMode,
                        startedAt,
                        completedAt,
                        deadlineAt,
                        workloadIdentityExpiresAt,
                        evidenceClass,
                        evidenceComplete,
                        writeCredentialExposed,
                        writeAttemptCount,
                        issuedAt),
                MAXIMUM_CANONICAL_BYTES);
    }

    OnlineReadOnlyShadowBaselineObservation
    withFingerprint(String value) {
        return copy(
                value,
                VisualRunEvidenceSeal.unsigned());
    }

    /**
     * Copies the addressed observation with a detached authority signature.
     *
     * @param seal regional online baseline authority seal
     * @return sealed immutable observation
     */
    public OnlineReadOnlyShadowBaselineObservation
    withSeal(VisualRunEvidenceSeal seal) {
        return copy(
                observationFingerprint,
                Objects.requireNonNull(seal, "seal"));
    }

    private OnlineReadOnlyShadowBaselineObservation copy(
            String fingerprint,
            VisualRunEvidenceSeal seal) {
        return new OnlineReadOnlyShadowBaselineObservation(
                schemaVersion,
                fingerprint,
                observationId,
                revision,
                scope,
                executionId,
                requestId,
                commandFingerprint,
                scenarioCaseRef,
                targetCapabilityRef,
                baselineBindingRef,
                comparisonPolicyRef,
                samplingGrantRef,
                egressAuthorityRef,
                killSwitchRef,
                workloadIdentityRef,
                workloadIdentityAttestationRef,
                payloadVaultReceiptRef,
                transportAttestationRef,
                requestContextFingerprint,
                semanticResultFingerprint,
                sourceRequestFingerprint,
                sourceResponseFingerprint,
                idempotencyKeyFingerprint,
                responseSchemaRef,
                normalizedFactFingerprints,
                accessMode,
                startedAt,
                completedAt,
                deadlineAt,
                workloadIdentityExpiresAt,
                evidenceClass,
                evidenceComplete,
                writeCredentialExposed,
                writeAttemptCount,
                issuedAt,
                seal);
    }

    private static Map<DomainFidelityProfile.Dimension, String>
    facts(
            Map<DomainFidelityProfile.Dimension, String>
                    supplied) {
        Map<DomainFidelityProfile.Dimension, String>
                source = supplied == null
                ? Map.of() : supplied;
        LinkedHashMap<DomainFidelityProfile.Dimension, String>
                canonical = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(Enum::name)))
                .forEach(entry -> canonical.put(
                        Objects.requireNonNull(
                                entry.getKey(),
                                "dimension"),
                        fingerprint(
                                entry.getValue(),
                                "normalizedFactFingerprint")));
        if (canonical.isEmpty()
                || canonical.size() > 16) {
            throw new IllegalArgumentException(
                    "online baseline facts are empty or unbounded");
        }
        return Collections.unmodifiableMap(canonical);
    }

    private static MirrorArtifactRef kind(
            MirrorArtifactRef value,
            String expected,
            String field) {
        MirrorArtifactRef exact =
                Objects.requireNonNull(value, field);
        if (!expected.equals(exact.kind())) {
            throw new IllegalArgumentException(
                    field + " has an invalid artifact kind");
        }
        return exact;
    }

    private static String identifier(
            String value,
            String field) {
        String exact = required(value, field);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String optionalFingerprint(
            String value,
            String field) {
        String exact = value == null
                ? "" : value.trim();
        return exact.isEmpty()
                ? "" : fingerprint(exact, field);
    }

    private static String fingerprint(
            String value,
            String field) {
        String exact = required(value, field);
        if (!exact.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String required(
            String value,
            String field) {
        String exact = value == null
                ? "" : value.trim();
        if (exact.isBlank() || exact.length() > 512) {
            throw new IllegalArgumentException(
                    field + " is blank or unbounded");
        }
        return exact;
    }

    private record SignatureMaterial(
            String domain,
            String schemaVersion,
            String observationId,
            long revision,
            CapabilitySnapshot.Scope scope,
            Instant issuedAt,
            String observationFingerprint
    ) {
    }

    private record IdentityMaterial(
            String domain,
            CapabilitySnapshot.Scope scope,
            String executionId,
            String commandFingerprint,
            MirrorArtifactRef baselineBindingRef
    ) {
    }

    private record FingerprintMaterial(
            String schemaVersion,
            String observationFingerprint,
            String observationId,
            long revision,
            CapabilitySnapshot.Scope scope,
            String executionId,
            String requestId,
            String commandFingerprint,
            MirrorArtifactRef scenarioCaseRef,
            MirrorArtifactRef targetCapabilityRef,
            MirrorArtifactRef baselineBindingRef,
            MirrorArtifactRef comparisonPolicyRef,
            MirrorArtifactRef samplingGrantRef,
            MirrorArtifactRef egressAuthorityRef,
            MirrorArtifactRef killSwitchRef,
            MirrorArtifactRef workloadIdentityRef,
            MirrorArtifactRef workloadIdentityAttestationRef,
            MirrorArtifactRef payloadVaultReceiptRef,
            MirrorArtifactRef transportAttestationRef,
            String requestContextFingerprint,
            String semanticResultFingerprint,
            String sourceRequestFingerprint,
            String sourceResponseFingerprint,
            String idempotencyKeyFingerprint,
            MirrorArtifactRef responseSchemaRef,
            Map<DomainFidelityProfile.Dimension, String>
                    normalizedFactFingerprints,
            AccessMode accessMode,
            Instant startedAt,
            Instant completedAt,
            Instant deadlineAt,
            Instant workloadIdentityExpiresAt,
            MirrorRunEvidence.EvidenceClass evidenceClass,
            boolean evidenceComplete,
            boolean writeCredentialExposed,
            long writeAttemptCount,
            Instant issuedAt
    ) {
    }
}
