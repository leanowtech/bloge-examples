package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleMaterial;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AtomicAdmissionLifecycleCommitReceipt;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseCommitStatus;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseReceipt;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseTransitionWitness;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceExecutionLeaseCommitResult;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.RevocationAuthoritySnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builder and independent verifier for the complete payload-free execution-lease transcript.
 *
 * <p>The transcript serializes every stable request and receipt coordinate plus the attempt-local
 * trusted verification time. Its fingerprint proves canonical consistency, not deployment
 * authenticity. Authenticity remains anchored by the independently pinned formal Provider outer
 * fingerprint and deployment evidence handling.</p>
 */
public final class CapabilityStudioExecutionLeaseTranscript {
    /** Fixed strict wire and fingerprint domain. */
    public static final String MESSAGE_VERSION =
            "resource-gateway.capability-studio.execution-lease-transcript.v1";
    /** The only successful Stage Acceptance CLI reason represented by v1. */
    public static final String ACCEPTED_REASON_CODE =
            "RG.CAPABILITY_STUDIO.STAGE_ACCEPTANCE_CLI.ACCEPTED";
    /** Maximum accepted transcript bytes. */
    public static final int MAXIMUM_BYTES = 256 * 1024;

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory().rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());
    private static final Set<String> TOP_FIELDS = Set.of(
            "messageVersion", "evidenceTransactionId", "evidencePublicationStatus",
            "beforeStateObservation", "afterStateObservation",
            "executionLeaseRequest", "commitIdentityFingerprint",
            "semanticVerificationTime", "trustedVerificationTime", "commitStatus",
            "reasonCode", "admissionLifecycleMaterial",
            "atomicAdmissionLifecycleCommitReceipt", "executionLeaseReceipt",
            "executionLeaseTransitionWitness", "transcriptFingerprint");
    private static final Set<String> REQUEST_FIELDS = Set.of(
            "resultId", "resultRevision", "stageResultRawFingerprint",
            "evidenceClosureFingerprint", "contractId", "contractRevision",
            "executionLeaseId", "providerOuterFingerprint", "targetRawFingerprint",
            "targetCanonicalFingerprint", "lifecycleMaterial",
            "deploymentAdmissionAuthorityMaterialFingerprint", "trustedVerificationTime");
    private static final Set<String> LIFECYCLE_FIELDS = Set.of(
            "bundleFingerprint", "bundleId", "revision", "lifecycleState",
            "predecessorBundleFingerprint", "revocationAuthority",
            "lifecycleMaterialFingerprint");
    private static final Set<String> REVOCATION_FIELDS = Set.of(
            "registryRef", "revision", "snapshotFingerprint", "observedAt", "expiresAt");
    private static final Set<String> ATOMIC_FIELDS = Set.of(
            "fingerprint", "deploymentAdmissionAuthorityMaterialFingerprint",
            "lifecycleMaterialFingerprint", "revocationRegistryRef",
            "revocationRegistryRevision", "revocationSnapshotFingerprint",
            "fencingSequence", "committedAt", "requestFingerprint");
    private static final Set<String> RECEIPT_FIELDS = Set.of(
            "fingerprint", "requestFingerprint", "lifecycleMaterial",
            "lifecycleCommitReceipt");
    private static final Set<String> WITNESS_FIELDS = Set.of(
            "fingerprint", "materialFingerprint", "storeDescriptorFingerprint",
            "requestFingerprint",
            "receiptFingerprint", "preStateFingerprint", "preGeneration",
            "preFencingSequence", "preCheckpointFingerprint",
            "preRevocationHeadSequence", "preRevocationHeadFingerprint",
            "postStateCoreFingerprint", "postStateFingerprint", "postGeneration",
            "postFencingSequence", "postCheckpointFingerprint",
            "postRevocationHeadSequence",
            "postRevocationHeadFingerprint");

    private CapabilityStudioExecutionLeaseTranscript() {
    }

    /** Closed evidence publication result shared by durable wire and CLI invocation output. */
    public enum EvidencePublicationStatus {
        /** This invocation published the fresh final transcript. */
        COMMITTED,
        /** An invocation verified and recovered the exact final transcript; not persisted. */
        RECOVERED
    }

    /**
     * Complete immutable execution-lease transcript.
     *
     * @param messageVersion fixed v1 message version
     * @param evidenceTransactionId stable evidence transaction identity
     * @param evidencePublicationStatus durable initial publication status, always COMMITTED
     * @param beforeStateObservation existing-only observation captured before lease commit
     * @param afterStateObservation existing-only observation captured after lease commit
     * @param executionLeaseRequest exact formal commit request
     * @param commitIdentityFingerprint stable request identity
     * @param semanticVerificationTime local Stage Result semantic verification time
     * @param trustedVerificationTime deployment trusted verification time for this attempt
     * @param commitStatus invocation-local committed or recovered status
     * @param reasonCode closed CLI-owned accepted reason
     * @param admissionLifecycleMaterial exact lifecycle material verified and committed
     * @param atomicAdmissionLifecycleCommitReceipt atomic lifecycle/revocation receipt
     * @param executionLeaseReceipt immutable durable lease receipt
     * @param executionLeaseTransitionWitness persistent exact state transition witness
     * @param transcriptFingerprint canonical top-level fingerprint
     */
    public record Transcript(
            String messageVersion,
            String evidenceTransactionId,
            EvidencePublicationStatus evidencePublicationStatus,
            CapabilityStudioDeploymentStateObservation.Observation beforeStateObservation,
            CapabilityStudioDeploymentStateObservation.Observation afterStateObservation,
            ExecutionLeaseRequest executionLeaseRequest,
            String commitIdentityFingerprint,
            Instant semanticVerificationTime,
            Instant trustedVerificationTime,
            ExecutionLeaseCommitStatus commitStatus,
            String reasonCode,
            AdmissionLifecycleMaterial admissionLifecycleMaterial,
            AtomicAdmissionLifecycleCommitReceipt atomicAdmissionLifecycleCommitReceipt,
            ExecutionLeaseReceipt executionLeaseReceipt,
            ExecutionLeaseTransitionWitness executionLeaseTransitionWitness,
            String transcriptFingerprint) {
        /** Validates all nested static fingerprints and cross-record bindings. */
        public Transcript {
            if (!MESSAGE_VERSION.equals(messageVersion)
                    || !FINGERPRINT.matcher(String.valueOf(evidenceTransactionId)).matches()
                    || evidencePublicationStatus != EvidencePublicationStatus.COMMITTED
                    || beforeStateObservation == null
                    || afterStateObservation == null
                    || executionLeaseRequest == null
                    || semanticVerificationTime == null
                    || trustedVerificationTime == null
                    || (commitStatus != ExecutionLeaseCommitStatus.COMMITTED
                    && commitStatus != ExecutionLeaseCommitStatus.RECOVERED)
                    || !ACCEPTED_REASON_CODE.equals(reasonCode)
                    || admissionLifecycleMaterial == null
                    || atomicAdmissionLifecycleCommitReceipt == null
                    || executionLeaseReceipt == null
                    || executionLeaseTransitionWitness == null) {
                throw invalid();
            }
            String identity = executionLeaseRequest.commitIdentityFingerprint();
            if (beforeStateObservation.phase()
                    != CapabilityStudioDeploymentStateObservation.Phase.BEFORE
                    || afterStateObservation.phase()
                    != CapabilityStudioDeploymentStateObservation.Phase.AFTER
                    || !evidenceTransactionId.equals(
                    beforeStateObservation.evidenceTransactionId())
                    || !evidenceTransactionId.equals(
                    afterStateObservation.evidenceTransactionId())
                    || !identity.equals(commitIdentityFingerprint)
                    || !trustedVerificationTime.equals(
                    executionLeaseRequest.trustedVerificationTime())
                    || !admissionLifecycleMaterial.equals(
                    executionLeaseRequest.lifecycleMaterial())
                    || !admissionLifecycleMaterial.equals(
                    executionLeaseReceipt.lifecycleMaterial())
                    || !atomicAdmissionLifecycleCommitReceipt.equals(
                    executionLeaseReceipt.lifecycleCommitReceipt())
                    || !identity.equals(executionLeaseReceipt.requestFingerprint())
                    || !identity.equals(
                    atomicAdmissionLifecycleCommitReceipt.requestFingerprint())
                    || !executionLeaseRequest
                    .deploymentAdmissionAuthorityMaterialFingerprint().equals(
                            atomicAdmissionLifecycleCommitReceipt
                                    .deploymentAdmissionAuthorityMaterialFingerprint())
                    || !identity.equals(executionLeaseTransitionWitness.requestFingerprint())
                    || !executionLeaseReceipt.fingerprint().equals(
                    executionLeaseTransitionWitness.receiptFingerprint())
                    || !observationsCrossCheck(beforeStateObservation,
                    afterStateObservation, executionLeaseTransitionWitness, commitStatus)
                    || atomicAdmissionLifecycleCommitReceipt.committedAt().isBefore(
                    executionLeaseRequest.trustedVerificationTime())) {
                throw invalid();
            }
            String expected = fingerprint(evidenceTransactionId,
                    evidencePublicationStatus, beforeStateObservation, afterStateObservation,
                    executionLeaseRequest, commitIdentityFingerprint,
                    semanticVerificationTime, trustedVerificationTime, commitStatus, reasonCode,
                    admissionLifecycleMaterial, atomicAdmissionLifecycleCommitReceipt,
                    executionLeaseReceipt, executionLeaseTransitionWitness);
            if (!expected.equals(transcriptFingerprint)) {
                throw invalid();
            }
        }

        /**
         * Returns exact canonical UTF-8 wire bytes.
         *
         * @return defensive canonical transcript bytes
         */
        public byte[] bytes() {
            byte[] bytes = write(transcriptNode(this, transcriptFingerprint));
            if (bytes.length > MAXIMUM_BYTES
                    || !CapabilityStudioSchemaSupport.validate(read(bytes),
                    CapabilityStudioSchemaSupport.EXECUTION_LEASE_TRANSCRIPT_V1_RESOURCE)
                    .isEmpty()) {
                throw invalid();
            }
            return bytes;
        }

        /**
         * Returns the canonical message with {@code transcriptFingerprint=null}.
         *
         * @return canonical fingerprint message
         */
        public String canonicalMessage() {
            return new String(write(transcriptNode(this, null)), StandardCharsets.UTF_8);
        }

        /** Redacted representation. */
        @Override
        public String toString() {
            return "Transcript[status=" + commitStatus + ", material=REDACTED]";
        }
    }

    /**
     * Builds a transcript from one already validated successful commit invocation.
     *
     * @param evidenceTransactionId stable evidence transaction identity
     * @param publicationStatus invocation-local evidence publication status
     * @param beforeObservation existing-only pre-commit observation
     * @param afterObservation existing-only post-commit observation
     * @param request exact request passed to the authority
     * @param result successful committed or recovered result
     * @param semanticVerificationTime local semantic verification time
     * @return complete verified transcript
     */
    public static Transcript create(
            String evidenceTransactionId,
            EvidencePublicationStatus publicationStatus,
            CapabilityStudioDeploymentStateObservation.Observation beforeObservation,
            CapabilityStudioDeploymentStateObservation.Observation afterObservation,
            ExecutionLeaseRequest request,
            EvidenceExecutionLeaseCommitResult result,
            Instant semanticVerificationTime) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(result, "result is required");
        ExecutionLeaseReceipt receipt = result.receipt();
        if ((result.status() != ExecutionLeaseCommitStatus.COMMITTED
                && result.status() != ExecutionLeaseCommitStatus.RECOVERED)
                || receipt == null) {
            throw invalid();
        }
        AdmissionLifecycleMaterial lifecycle = request.lifecycleMaterial();
        AtomicAdmissionLifecycleCommitReceipt atomic = receipt.lifecycleCommitReceipt();
        String identity = request.commitIdentityFingerprint();
        ExecutionLeaseTransitionWitness witness = result.transitionWitness();
        Instant semanticTime = Objects.requireNonNull(
                semanticVerificationTime, "semanticVerificationTime is required");
        String fingerprint = fingerprint(evidenceTransactionId, publicationStatus,
                beforeObservation, afterObservation, request, identity, semanticTime,
                request.trustedVerificationTime(), result.status(), ACCEPTED_REASON_CODE,
                lifecycle, atomic, receipt, witness);
        return new Transcript(MESSAGE_VERSION, evidenceTransactionId, publicationStatus,
                beforeObservation, afterObservation, request, identity, semanticTime,
                request.trustedVerificationTime(), result.status(), ACCEPTED_REASON_CODE,
                lifecycle, atomic, receipt, witness, fingerprint);
    }

    /**
     * Strictly parses and independently verifies one canonical transcript.
     *
     * @param bytes exact bounded UTF-8 transcript bytes
     * @return verified immutable transcript
     */
    public static Transcript verify(byte[] bytes) {
        if (bytes == null || bytes.length < 1 || bytes.length > MAXIMUM_BYTES) {
            throw invalid();
        }
        JsonNode parsed = read(bytes);
        if (!CapabilityStudioSchemaSupport.validate(parsed,
                CapabilityStudioSchemaSupport.EXECUTION_LEASE_TRANSCRIPT_V1_RESOURCE)
                .isEmpty() || !(parsed instanceof ObjectNode node)
                || !fields(node).equals(TOP_FIELDS)) {
            throw invalid();
        }
        try {
            ExecutionLeaseRequest request = request(object(node, "executionLeaseRequest"));
            CapabilityStudioDeploymentStateObservation.Observation before = observation(
                    object(node, "beforeStateObservation"));
            CapabilityStudioDeploymentStateObservation.Observation after = observation(
                    object(node, "afterStateObservation"));
            AdmissionLifecycleMaterial lifecycle = lifecycle(
                    object(node, "admissionLifecycleMaterial"));
            AtomicAdmissionLifecycleCommitReceipt atomic = atomic(
                    object(node, "atomicAdmissionLifecycleCommitReceipt"));
            ExecutionLeaseReceipt receipt = receipt(
                    object(node, "executionLeaseReceipt"));
            Transcript transcript = new Transcript(text(node, "messageVersion"),
                    fingerprint(node, "evidenceTransactionId"),
                    EvidencePublicationStatus.valueOf(
                            text(node, "evidencePublicationStatus")), before, after, request,
                    fingerprint(node, "commitIdentityFingerprint"),
                    instant(node, "semanticVerificationTime"),
                    instant(node, "trustedVerificationTime"),
                    ExecutionLeaseCommitStatus.valueOf(text(node, "commitStatus")),
                    text(node, "reasonCode"), lifecycle, atomic, receipt,
                    witness(object(node, "executionLeaseTransitionWitness")),
                    fingerprint(node, "transcriptFingerprint"));
            if (!Arrays.equals(bytes, transcript.bytes())) {
                throw invalid();
            }
            return transcript;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    static byte[] requestBytes(ExecutionLeaseRequest request) {
        return write(requestNode(Objects.requireNonNull(request, "request is required")));
    }

    static ExecutionLeaseRequest verifyRequestBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 1 || bytes.length > MAXIMUM_BYTES) {
            throw invalid();
        }
        try {
            JsonNode parsed = read(bytes);
            if (!(parsed instanceof ObjectNode node)
                    || !fields(node).equals(REQUEST_FIELDS)) {
                throw invalid();
            }
            ExecutionLeaseRequest request = request(node);
            if (!Arrays.equals(bytes, requestBytes(request))) {
                throw invalid();
            }
            return request;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * Computes the canonical transcript fingerprint for explicit nested values.
     *
     * @param evidenceTransactionId stable evidence transaction identity
     * @param publicationStatus invocation-local publication status
     * @param beforeObservation existing-only pre-commit observation
     * @param afterObservation existing-only post-commit observation
     * @param request exact lease request
     * @param commitIdentityFingerprint stable request identity
     * @param semanticVerificationTime local semantic verification time
     * @param trustedVerificationTime deployment trusted attempt time
     * @param commitStatus invocation-local lease status
     * @param reasonCode closed CLI-owned reason
     * @param lifecycle exact lifecycle material
     * @param atomic atomic lifecycle commit receipt
     * @param receipt immutable lease receipt
     * @param witness persistent state transition witness
     * @return lowercase SHA-256 transcript fingerprint
     */
    public static String fingerprint(
            String evidenceTransactionId,
            EvidencePublicationStatus publicationStatus,
            CapabilityStudioDeploymentStateObservation.Observation beforeObservation,
            CapabilityStudioDeploymentStateObservation.Observation afterObservation,
            ExecutionLeaseRequest request,
            String commitIdentityFingerprint,
            Instant semanticVerificationTime,
            Instant trustedVerificationTime,
            ExecutionLeaseCommitStatus commitStatus,
            String reasonCode,
            AdmissionLifecycleMaterial lifecycle,
            AtomicAdmissionLifecycleCommitReceipt atomic,
            ExecutionLeaseReceipt receipt,
            ExecutionLeaseTransitionWitness witness) {
        requireFingerprint(commitIdentityFingerprint);
        TranscriptView view = new TranscriptView(evidenceTransactionId, publicationStatus,
                beforeObservation, afterObservation, request, commitIdentityFingerprint,
                semanticVerificationTime, trustedVerificationTime, commitStatus, reasonCode,
                lifecycle, atomic, receipt, witness);
        return sha256(write(transcriptNode(view, null)));
    }

    private static ObjectNode transcriptNode(Transcript value, String fingerprint) {
        return transcriptNode(new TranscriptView(value.evidenceTransactionId(),
                value.evidencePublicationStatus(),
                value.beforeStateObservation(), value.afterStateObservation(),
                value.executionLeaseRequest(), value.commitIdentityFingerprint(),
                value.semanticVerificationTime(),
                value.trustedVerificationTime(), value.commitStatus(), value.reasonCode(),
                value.admissionLifecycleMaterial(),
                value.atomicAdmissionLifecycleCommitReceipt(),
                value.executionLeaseReceipt(), value.executionLeaseTransitionWitness()),
                fingerprint);
    }

    private static ObjectNode transcriptNode(TranscriptView value, String fingerprint) {
        ObjectNode node = JSON.createObjectNode();
        node.put("messageVersion", MESSAGE_VERSION);
        node.put("evidenceTransactionId", value.transactionId);
        node.put("evidencePublicationStatus", value.publicationStatus.name());
        node.set("beforeStateObservation", observationNode(value.beforeObservation));
        node.set("afterStateObservation", observationNode(value.afterObservation));
        node.set("executionLeaseRequest", requestNode(value.request));
        node.put("commitIdentityFingerprint", value.identity);
        node.put("semanticVerificationTime", value.semanticTime.toString());
        node.put("trustedVerificationTime", value.trustedTime.toString());
        node.put("commitStatus", value.status.name());
        node.put("reasonCode", value.reason);
        node.set("admissionLifecycleMaterial", lifecycleNode(value.lifecycle));
        node.set("atomicAdmissionLifecycleCommitReceipt", atomicNode(value.atomic));
        node.set("executionLeaseReceipt", receiptNode(value.receipt));
        node.set("executionLeaseTransitionWitness", witnessNode(value.witness));
        if (fingerprint == null) {
            node.putNull("transcriptFingerprint");
        } else {
            node.put("transcriptFingerprint", fingerprint);
        }
        return node;
    }

    private static ObjectNode requestNode(ExecutionLeaseRequest request) {
        Objects.requireNonNull(request, "request is required");
        ObjectNode node = JSON.createObjectNode();
        node.put("resultId", request.resultId());
        node.put("resultRevision", request.resultRevision());
        node.put("stageResultRawFingerprint", request.stageResultRawFingerprint());
        node.put("evidenceClosureFingerprint", request.evidenceClosureFingerprint());
        node.put("contractId", request.contractId());
        node.put("contractRevision", request.contractRevision());
        node.put("executionLeaseId", request.executionLeaseId());
        node.put("providerOuterFingerprint", request.providerOuterFingerprint());
        node.put("targetRawFingerprint", request.targetRawFingerprint());
        node.put("targetCanonicalFingerprint", request.targetCanonicalFingerprint());
        node.set("lifecycleMaterial", lifecycleNode(request.lifecycleMaterial()));
        node.put("deploymentAdmissionAuthorityMaterialFingerprint",
                request.deploymentAdmissionAuthorityMaterialFingerprint());
        node.put("trustedVerificationTime", request.trustedVerificationTime().toString());
        return node;
    }

    private static ObjectNode lifecycleNode(AdmissionLifecycleMaterial lifecycle) {
        Objects.requireNonNull(lifecycle, "lifecycle is required");
        ObjectNode node = JSON.createObjectNode();
        node.put("bundleFingerprint", lifecycle.bundleFingerprint());
        node.put("bundleId", lifecycle.bundleId());
        node.put("revision", lifecycle.revision());
        node.put("lifecycleState", lifecycle.lifecycleState());
        if (lifecycle.predecessorBundleFingerprint() == null) {
            node.putNull("predecessorBundleFingerprint");
        } else {
            node.put("predecessorBundleFingerprint", lifecycle.predecessorBundleFingerprint());
        }
        node.set("revocationAuthority", revocationNode(lifecycle.revocationAuthority()));
        node.put("lifecycleMaterialFingerprint", lifecycle.fingerprint());
        return node;
    }

    private static ObjectNode revocationNode(RevocationAuthoritySnapshot revocation) {
        ObjectNode node = JSON.createObjectNode();
        node.put("registryRef", revocation.registryRef());
        node.put("revision", revocation.revision());
        node.put("snapshotFingerprint", revocation.snapshotFingerprint());
        node.put("observedAt", revocation.observedAt().toString());
        node.put("expiresAt", revocation.expiresAt().toString());
        return node;
    }

    private static ObjectNode atomicNode(AtomicAdmissionLifecycleCommitReceipt atomic) {
        Objects.requireNonNull(atomic, "atomic receipt is required");
        ObjectNode node = JSON.createObjectNode();
        node.put("fingerprint", atomic.fingerprint());
        node.put("deploymentAdmissionAuthorityMaterialFingerprint",
                atomic.deploymentAdmissionAuthorityMaterialFingerprint());
        node.put("lifecycleMaterialFingerprint", atomic.lifecycleMaterialFingerprint());
        node.put("revocationRegistryRef", atomic.revocationRegistryRef());
        node.put("revocationRegistryRevision", atomic.revocationRegistryRevision());
        node.put("revocationSnapshotFingerprint", atomic.revocationSnapshotFingerprint());
        node.put("fencingSequence", atomic.fencingSequence());
        node.put("committedAt", atomic.committedAt().toString());
        node.put("requestFingerprint", atomic.requestFingerprint());
        return node;
    }

    private static ObjectNode receiptNode(ExecutionLeaseReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt is required");
        ObjectNode node = JSON.createObjectNode();
        node.put("fingerprint", receipt.fingerprint());
        node.put("requestFingerprint", receipt.requestFingerprint());
        node.set("lifecycleMaterial", lifecycleNode(receipt.lifecycleMaterial()));
        node.set("lifecycleCommitReceipt", atomicNode(receipt.lifecycleCommitReceipt()));
        return node;
    }

    private static ObjectNode witnessNode(ExecutionLeaseTransitionWitness witness) {
        Objects.requireNonNull(witness, "transition witness is required");
        ObjectNode node = JSON.createObjectNode();
        node.put("fingerprint", witness.fingerprint());
        node.put("materialFingerprint", witness.materialFingerprint());
        node.put("storeDescriptorFingerprint", witness.storeDescriptorFingerprint());
        node.put("requestFingerprint", witness.requestFingerprint());
        node.put("receiptFingerprint", witness.receiptFingerprint());
        node.put("preStateFingerprint", witness.preStateFingerprint());
        node.put("preGeneration", witness.preGeneration());
        node.put("preFencingSequence", witness.preFencingSequence());
        node.put("preCheckpointFingerprint", witness.preCheckpointFingerprint());
        node.put("preRevocationHeadSequence", witness.preRevocationHeadSequence());
        node.put("preRevocationHeadFingerprint",
                witness.preRevocationHeadFingerprint());
        node.put("postStateCoreFingerprint", witness.postStateCoreFingerprint());
        node.put("postStateFingerprint", witness.postStateFingerprint());
        node.put("postGeneration", witness.postGeneration());
        node.put("postFencingSequence", witness.postFencingSequence());
        node.put("postCheckpointFingerprint", witness.postCheckpointFingerprint());
        node.put("postRevocationHeadSequence", witness.postRevocationHeadSequence());
        node.put("postRevocationHeadFingerprint",
                witness.postRevocationHeadFingerprint());
        return node;
    }

    private static ObjectNode observationNode(
            CapabilityStudioDeploymentStateObservation.Observation observation) {
        JsonNode value = read(observation.bytes());
        if (!(value instanceof ObjectNode object)) {
            throw invalid();
        }
        return object;
    }

    private static ExecutionLeaseRequest request(ObjectNode node) {
        requireFields(node, REQUEST_FIELDS);
        return new ExecutionLeaseRequest(text(node, "resultId"),
                positive(node, "resultRevision"), fingerprint(node, "stageResultRawFingerprint"),
                fingerprint(node, "evidenceClosureFingerprint"), text(node, "contractId"),
                text(node, "contractRevision"), text(node, "executionLeaseId"),
                fingerprint(node, "providerOuterFingerprint"),
                fingerprint(node, "targetRawFingerprint"),
                fingerprint(node, "targetCanonicalFingerprint"),
                lifecycle(object(node, "lifecycleMaterial")),
                fingerprint(node, "deploymentAdmissionAuthorityMaterialFingerprint"),
                instant(node, "trustedVerificationTime"));
    }

    private static AdmissionLifecycleMaterial lifecycle(ObjectNode node) {
        requireFields(node, LIFECYCLE_FIELDS);
        AdmissionLifecycleMaterial material = new AdmissionLifecycleMaterial(
                fingerprint(node, "bundleFingerprint"), text(node, "bundleId"),
                positive(node, "revision"), text(node, "lifecycleState"),
                nullableFingerprint(node, "predecessorBundleFingerprint"),
                revocation(object(node, "revocationAuthority")));
        if (!material.fingerprint().equals(fingerprint(node, "lifecycleMaterialFingerprint"))) {
            throw invalid();
        }
        return material;
    }

    private static RevocationAuthoritySnapshot revocation(ObjectNode node) {
        requireFields(node, REVOCATION_FIELDS);
        return new RevocationAuthoritySnapshot(text(node, "registryRef"),
                positive(node, "revision"), fingerprint(node, "snapshotFingerprint"),
                instant(node, "observedAt"), instant(node, "expiresAt"));
    }

    private static AtomicAdmissionLifecycleCommitReceipt atomic(ObjectNode node) {
        requireFields(node, ATOMIC_FIELDS);
        return new AtomicAdmissionLifecycleCommitReceipt(fingerprint(node, "fingerprint"),
                fingerprint(node, "deploymentAdmissionAuthorityMaterialFingerprint"),
                fingerprint(node, "lifecycleMaterialFingerprint"),
                text(node, "revocationRegistryRef"),
                positive(node, "revocationRegistryRevision"),
                fingerprint(node, "revocationSnapshotFingerprint"),
                positive(node, "fencingSequence"), instant(node, "committedAt"),
                fingerprint(node, "requestFingerprint"));
    }

    private static ExecutionLeaseReceipt receipt(ObjectNode node) {
        requireFields(node, RECEIPT_FIELDS);
        return new ExecutionLeaseReceipt(fingerprint(node, "fingerprint"),
                fingerprint(node, "requestFingerprint"),
                lifecycle(object(node, "lifecycleMaterial")),
                atomic(object(node, "lifecycleCommitReceipt")));
    }

    private static ExecutionLeaseTransitionWitness witness(ObjectNode node) {
        requireFields(node, WITNESS_FIELDS);
        return new ExecutionLeaseTransitionWitness(fingerprint(node, "fingerprint"),
                fingerprint(node, "materialFingerprint"),
                fingerprint(node, "storeDescriptorFingerprint"),
                fingerprint(node, "requestFingerprint"),
                fingerprint(node, "receiptFingerprint"),
                fingerprint(node, "preStateFingerprint"),
                nonNegative(node, "preGeneration"),
                nonNegative(node, "preFencingSequence"),
                fingerprint(node, "preCheckpointFingerprint"),
                nonNegative(node, "preRevocationHeadSequence"),
                fingerprint(node, "preRevocationHeadFingerprint"),
                fingerprint(node, "postStateCoreFingerprint"),
                fingerprint(node, "postStateFingerprint"),
                positive(node, "postGeneration"),
                positive(node, "postFencingSequence"),
                fingerprint(node, "postCheckpointFingerprint"),
                nonNegative(node, "postRevocationHeadSequence"),
                fingerprint(node, "postRevocationHeadFingerprint"));
    }

    private static CapabilityStudioDeploymentStateObservation.Observation observation(
            ObjectNode node) {
        return CapabilityStudioDeploymentStateObservation.verify(write(node));
    }

    private static boolean observationsCrossCheck(
            CapabilityStudioDeploymentStateObservation.Observation before,
            CapabilityStudioDeploymentStateObservation.Observation after,
            ExecutionLeaseTransitionWitness witness,
            ExecutionLeaseCommitStatus commitStatus) {
        return before.storeDescriptorFingerprint().equals(
                witness.storeDescriptorFingerprint())
                && after.storeDescriptorFingerprint().equals(
                witness.storeDescriptorFingerprint())
                && before.generation() == witness.preGeneration()
                && before.fencingSequence() == witness.preFencingSequence()
                && before.revocationHeadSequence()
                == witness.preRevocationHeadSequence()
                && before.stateFingerprint().equals(witness.preStateFingerprint())
                && before.checkpointFingerprint().equals(
                witness.preCheckpointFingerprint())
                && before.revocationHeadFingerprint().equals(
                witness.preRevocationHeadFingerprint())
                && after.generation() == witness.postGeneration()
                && after.fencingSequence() == witness.postFencingSequence()
                && after.revocationHeadSequence()
                == witness.postRevocationHeadSequence()
                && after.stateFingerprint().equals(witness.postStateFingerprint())
                && after.checkpointFingerprint().equals(
                witness.postCheckpointFingerprint())
                && after.revocationHeadFingerprint().equals(
                witness.postRevocationHeadFingerprint());
    }

    private static ObjectNode object(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (!(value instanceof ObjectNode object)) {
            throw invalid();
        }
        return object;
    }

    private static void requireFields(ObjectNode node, Set<String> expected) {
        if (!fields(node).equals(expected)) {
            throw invalid();
        }
    }

    private static Set<String> fields(ObjectNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static String text(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static String fingerprint(ObjectNode node, String field) {
        String value = text(node, field);
        requireFingerprint(value);
        return value;
    }

    private static String nullableFingerprint(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && value.isNull()) {
            return null;
        }
        return fingerprint(node, field);
    }

    private static long positive(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 1) {
            throw invalid();
        }
        return value.longValue();
    }

    private static long nonNegative(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0) {
            throw invalid();
        }
        return value.longValue();
    }

    private static Instant instant(ObjectNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (DateTimeParseException failure) {
            throw invalid();
        }
    }

    private static void requireFingerprint(String value) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw invalid();
        }
    }

    private static byte[] write(JsonNode node) {
        try {
            return JSON.writeValueAsBytes(node);
        } catch (IOException impossible) {
            throw new IllegalStateException("transcript serialization unavailable");
        }
    }

    private static JsonNode read(byte[] bytes) {
        try {
            JsonNode node = JSON.readTree(bytes);
            if (node == null) {
                throw invalid();
            }
            return node;
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "RG.CAPABILITY_STUDIO.EXECUTION_LEASE_TRANSCRIPT_INVALID");
    }

    private record TranscriptView(
            String transactionId,
            EvidencePublicationStatus publicationStatus,
            CapabilityStudioDeploymentStateObservation.Observation beforeObservation,
            CapabilityStudioDeploymentStateObservation.Observation afterObservation,
            ExecutionLeaseRequest request,
            String identity,
            Instant semanticTime,
            Instant trustedTime,
            ExecutionLeaseCommitStatus status,
            String reason,
            AdmissionLifecycleMaterial lifecycle,
            AtomicAdmissionLifecycleCommitReceipt atomic,
            ExecutionLeaseReceipt receipt,
            ExecutionLeaseTransitionWitness witness) {
        private TranscriptView {
            requireFingerprint(transactionId);
            Objects.requireNonNull(publicationStatus, "publicationStatus is required");
            Objects.requireNonNull(beforeObservation, "beforeObservation is required");
            Objects.requireNonNull(afterObservation, "afterObservation is required");
            Objects.requireNonNull(request, "request is required");
            requireFingerprint(identity);
            Objects.requireNonNull(semanticTime, "semanticTime is required");
            Objects.requireNonNull(trustedTime, "trustedTime is required");
            if ((status != ExecutionLeaseCommitStatus.COMMITTED
                    && status != ExecutionLeaseCommitStatus.RECOVERED)
                    || !ACCEPTED_REASON_CODE.equals(reason)
                    || lifecycle == null || atomic == null || receipt == null
                    || witness == null) {
                throw invalid();
            }
        }
    }
}
