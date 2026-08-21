package com.leanowtech.bloge.gateway.testkit.mounted;

import com.leanowtech.bloge.gateway.testkit.CapabilityStudioDeploymentStateObservation;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AdmissionLifecycleMaterial;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.AtomicAdmissionLifecycleCommitReceipt;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseReceipt;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider.ExecutionLeaseTransitionWitness;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

final class EvidenceCapacityStoreFixtureBuilder {
    private static final Class<?> AUTHORITY = FilesystemDeploymentAdmissionAuthority.class;
    private static final Class<?> DESCRIPTOR = nested("StoreDescriptor");
    private static final Class<?> STATE = nested("State");
    private static final Class<?> STORED_LEASE = nested("StoredLease");
    private static final Class<?> CHECKPOINT = nested("Checkpoint");
    private static final Class<?> REVOCATION_HEAD = nested("RevocationHead");
    private static final Class<?> TRANSITION_EVIDENCE = nested("TransitionEvidence");
    private static final String STATE_VERSION =
            "resource-gateway.capability-studio.execution-lease-state.v4";

    private EvidenceCapacityStoreFixtureBuilder() {
    }

    static CapacityFixture build(
            Path stateRoot,
            String descriptorFingerprint,
            ExecutionLeaseRequest template,
            int transitions) throws Exception {
        if (transitions != FilesystemDeploymentAdmissionAuthority.MAX_LEASES) {
            throw new IllegalArgumentException("capacity fixture requires the protocol limit");
        }
        byte[] descriptorBytes = Files.readAllBytes(stateRoot.resolve(
                FilesystemDeploymentAdmissionAuthority.LOCK_FILE));
        byte[] headBytes = Files.readAllBytes(stateRoot.resolve(
                FilesystemDeploymentAdmissionAuthority.REVOCATION_HEAD_FILE));
        Object descriptor = invokeStatic(AUTHORITY, "parseDescriptor",
                new Class<?>[]{byte[].class}, descriptorBytes);
        Object head = invokeStatic(REVOCATION_HEAD, "parse",
                new Class<?>[]{byte[].class, String.class}, headBytes,
                descriptorFingerprint);
        Object state = invokeStatic(STATE, "genesis",
                new Class<?>[]{String.class, String.class}, descriptorFingerprint,
                STATE_VERSION);
        Object checkpoint = checkpoint(state, head);
        byte[] stateBytes = bytes(state);
        byte[] checkpointBytes = bytes(checkpoint);
        if (!Arrays.equals(stateBytes, Files.readAllBytes(stateRoot.resolve(
                FilesystemDeploymentAdmissionAuthority.STATE_FILE)))
                || !Arrays.equals(checkpointBytes, Files.readAllBytes(stateRoot.resolve(
                FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE)))) {
            throw new IllegalStateException("capacity fixture genesis mismatch");
        }

        String headFingerprint = stringField(head, "headFingerprint");
        long headSequence = longField(head, "sequence");
        String headRawFingerprint = sha256(headBytes);
        TreeMap<Long, byte[]> sidecars = new TreeMap<>();
        ExecutionLeaseRequest recoverableRequest = null;
        String recoverableTransaction = null;
        ExecutionLeaseReceipt recoverableReceipt = null;
        ExecutionLeaseTransitionWitness recoverableWitness = null;
        String recoverableAfterObservationFingerprint = null;

        for (int index = 0; index < transitions; index++) {
            long sequence = index + 1L;
            boolean evidence = (index & 1) == 1;
            ExecutionLeaseRequest request = request(template, index);
            String transactionId = evidence
                    ? fingerprint("capacity-transaction:" + index)
                    : request.commitIdentityFingerprint();
            CapabilityStudioDeploymentStateObservation.Observation before = observation(
                    CapabilityStudioDeploymentStateObservation.Phase.BEFORE,
                    transactionId, descriptor, state, checkpoint, head,
                    descriptorBytes, stateBytes, checkpointBytes, headBytes);
            AtomicAdmissionLifecycleCommitReceipt lifecycleReceipt =
                    new AtomicAdmissionLifecycleCommitReceipt(
                            request.deploymentAdmissionAuthorityMaterialFingerprint(),
                            request.lifecycleMaterial().fingerprint(),
                            request.lifecycleMaterial().revocationAuthority().registryRef(),
                            request.lifecycleMaterial().revocationAuthority().revision(),
                            request.lifecycleMaterial().revocationAuthority()
                                    .snapshotFingerprint(),
                            sequence, request.trustedVerificationTime(),
                            request.commitIdentityFingerprint());
            ExecutionLeaseReceipt receipt = new ExecutionLeaseReceipt(
                    request.commitIdentityFingerprint(), request.lifecycleMaterial(),
                    lifecycleReceipt);
            Object stored = storedLease(request.commitIdentityFingerprint(), receipt,
                    before.stateMaterialFingerprint());
            String leaseKey = (String) invokeStatic(AUTHORITY, "leaseKey",
                    new Class<?>[]{String.class}, request.executionLeaseId());
            Object core = invoke(state, "withEvidenceCommitCore",
                    new Class<?>[]{AdmissionLifecycleMaterial.class, long.class,
                            String.class, STORED_LEASE},
                    request.lifecycleMaterial(), sequence, leaseKey, stored);
            String materialFingerprint = ExecutionLeaseTransitionWitness.materialFingerprint(
                    descriptorFingerprint, request.commitIdentityFingerprint(),
                    receipt.fingerprint(), stringField(state, "stateFingerprint"),
                    longField(state, "generation"), longField(state, "fencingSequence"),
                    stringField(checkpoint, "checkpointFingerprint"), headSequence,
                    headFingerprint, stringField(core, "stateCoreFingerprint"),
                    sequence, sequence, headSequence, headFingerprint);
            String closureMaterial = evidence
                    ? (String) invokeStatic(AUTHORITY,
                    "transitionEvidenceMaterialFingerprint",
                    new Class<?>[]{String.class, String.class, String.class,
                            CapabilityStudioDeploymentStateObservation.Observation.class,
                            long.class, String.class, String.class, long.class, long.class},
                    transactionId, receipt.fingerprint(), materialFingerprint, before,
                    headSequence, headFingerprint, headRawFingerprint, sequence, sequence)
                    : null;
            Object materialized = invoke(core, "withEvidenceMaterials",
                    new Class<?>[]{String.class, String.class, String.class, String.class},
                    leaseKey, materialFingerprint, closureMaterial, headRawFingerprint);
            Object postCheckpoint = checkpoint(materialized, head);
            ExecutionLeaseTransitionWitness witness = new ExecutionLeaseTransitionWitness(
                    descriptorFingerprint, request.commitIdentityFingerprint(),
                    receipt.fingerprint(), stringField(state, "stateFingerprint"),
                    longField(state, "generation"), longField(state, "fencingSequence"),
                    stringField(checkpoint, "checkpointFingerprint"), headSequence,
                    headFingerprint, stringField(materialized, "stateCoreFingerprint"),
                    stringField(materialized, "stateFingerprint"), sequence, sequence,
                    stringField(postCheckpoint, "checkpointFingerprint"), headSequence,
                    headFingerprint);
            Object updated = invoke(materialized, "withWitness",
                    new Class<?>[]{String.class, ExecutionLeaseTransitionWitness.class},
                    leaseKey, witness);
            byte[] updatedStateBytes = bytes(updated);
            byte[] updatedCheckpointBytes = bytes(postCheckpoint);
            if (evidence) {
                CapabilityStudioDeploymentStateObservation.Observation after = observation(
                        CapabilityStudioDeploymentStateObservation.Phase.AFTER,
                        transactionId, descriptor, updated, postCheckpoint, head,
                        descriptorBytes, updatedStateBytes, updatedCheckpointBytes, headBytes);
                Object transition = invokeStatic(TRANSITION_EVIDENCE, "create",
                        new Class<?>[]{String.class, ExecutionLeaseReceipt.class,
                                ExecutionLeaseTransitionWitness.class,
                                CapabilityStudioDeploymentStateObservation.Observation.class,
                                CapabilityStudioDeploymentStateObservation.Observation.class},
                        transactionId, receipt, witness, before, after);
                if (!closureMaterial.equals(stringField(transition, "materialFingerprint"))) {
                    throw new IllegalStateException("capacity transition material mismatch");
                }
                sidecars.put(sequence, bytes(transition));
                if (recoverableRequest == null) {
                    recoverableRequest = request;
                    recoverableTransaction = transactionId;
                    recoverableReceipt = receipt;
                    recoverableWitness = witness;
                    recoverableAfterObservationFingerprint =
                            after.observationFingerprint();
                }
            }
            state = updated;
            checkpoint = postCheckpoint;
            stateBytes = updatedStateBytes;
            checkpointBytes = updatedCheckpointBytes;
        }

        write(stateRoot.resolve(FilesystemDeploymentAdmissionAuthority.STATE_FILE), stateBytes,
                false);
        write(stateRoot.resolve(FilesystemDeploymentAdmissionAuthority.CHECKPOINT_FILE),
                checkpointBytes, false);
        for (Map.Entry<Long, byte[]> sidecar : sidecars.entrySet()) {
            Path target = stateRoot.resolve(
                    FilesystemDeploymentAdmissionAuthority.TRANSITION_EVIDENCE_PREFIX
                            + String.format("%020d", sidecar.getKey())
                            + FilesystemDeploymentAdmissionAuthority
                            .TRANSITION_EVIDENCE_SUFFIX);
            write(target, sidecar.getValue(), true);
        }
        return new CapacityFixture(recoverableRequest, recoverableTransaction,
                recoverableReceipt, recoverableWitness,
                recoverableAfterObservationFingerprint);
    }

    private static ExecutionLeaseRequest request(
            ExecutionLeaseRequest template, int index) {
        String suffix = String.format("%04d", index);
        return new ExecutionLeaseRequest("result:capacity:" + suffix, 1,
                fingerprint("capacity-stage:" + suffix),
                fingerprint("capacity-evidence:" + suffix),
                template.contractId(), template.contractRevision(),
                "lease:capacity:" + suffix, template.providerOuterFingerprint(),
                template.targetRawFingerprint(), template.targetCanonicalFingerprint(),
                template.lifecycleMaterial(),
                template.deploymentAdmissionAuthorityMaterialFingerprint(),
                template.trustedVerificationTime());
    }

    private static Object storedLease(
            String requestFingerprint,
            ExecutionLeaseReceipt receipt,
            String preStateMaterialFingerprint) throws Exception {
        Constructor<?> constructor = STORED_LEASE.getDeclaredConstructor(String.class,
                ExecutionLeaseReceipt.class, ExecutionLeaseTransitionWitness.class,
                String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(requestFingerprint, receipt, null,
                preStateMaterialFingerprint);
    }

    private static CapabilityStudioDeploymentStateObservation.Observation observation(
            CapabilityStudioDeploymentStateObservation.Phase phase,
            String transactionId,
            Object descriptor,
            Object state,
            Object checkpoint,
            Object head,
            byte[] descriptorBytes,
            byte[] stateBytes,
            byte[] checkpointBytes,
            byte[] headBytes) throws Exception {
        return (CapabilityStudioDeploymentStateObservation.Observation) invokeStatic(
                AUTHORITY, "observation",
                new Class<?>[]{CapabilityStudioDeploymentStateObservation.Phase.class,
                        String.class, DESCRIPTOR, STATE, CHECKPOINT, REVOCATION_HEAD,
                        byte[].class, byte[].class, byte[].class, byte[].class},
                phase, transactionId, descriptor, state, checkpoint, head,
                descriptorBytes, stateBytes, checkpointBytes, headBytes);
    }

    private static Object checkpoint(Object state, Object head) throws Exception {
        return invokeStatic(CHECKPOINT, "forSnapshot",
                new Class<?>[]{STATE, REVOCATION_HEAD}, state, head);
    }

    private static byte[] bytes(Object value) throws Exception {
        return (byte[]) invoke(value, "bytes", new Class<?>[0]);
    }

    private static Object invoke(
            Object target, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, values);
    }

    private static Object invokeStatic(
            Class<?> type, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = type.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, values);
    }

    private static String stringField(Object target, String name) throws Exception {
        return (String) field(target, name).get(target);
    }

    private static long longField(Object target, String name) throws Exception {
        return ((Number) field(target, name).get(target)).longValue();
    }

    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Class<?> nested(String name) {
        try {
            return Class.forName(AUTHORITY.getName() + "$" + name);
        } catch (ClassNotFoundException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static void write(Path path, byte[] bytes, boolean create) throws Exception {
        if (create) {
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        } else {
            Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        }
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    private static String fingerprint(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    record CapacityFixture(
            ExecutionLeaseRequest recoverableRequest,
            String evidenceTransactionId,
            ExecutionLeaseReceipt receipt,
            ExecutionLeaseTransitionWitness witness,
            String historicalAfterObservationFingerprint) {
    }
}
