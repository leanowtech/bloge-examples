package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Closed Gate A registry: three wire tuples map to three real verifier adapters. */
final class CapabilityStudioTypedEvidenceReplayRegistry {
    static final String FORMAL_INPUT_TREE_KIND = "FORMAL_INPUT_TREE_V1";
    static final String DURABLE_WRAPPER_KIND = "EXECUTION_LEASE_DURABLE_WRAPPER_V1";
    static final String STAGE_RESULT_KIND = "STAGE_ACCEPTANCE_RESULT_V2";
    static final String FORMAL_INPUT_TREE_VERIFIER =
            "CapabilityStudioFormalInputTreeSnapshotter.verify";
    static final String DURABLE_WRAPPER_VERIFIER =
            "CapabilityStudioExecutionLeaseEvidenceBundleVerifier.verify";
    static final String STAGE_RESULT_VERIFIER =
            "CapabilityStudioStageAcceptanceResultV2Verifier.verify";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");

    private CapabilityStudioTypedEvidenceReplayRegistry() {
    }

    /** Fixed adapter slots in the A0 result order. */
    enum Slot {
        FORMAL_INPUT_TREE(
                "FORMAL_INPUT_TREE", FORMAL_INPUT_TREE_KIND,
                FORMAL_INPUT_TREE_VERIFIER, 1, false),
        DURABLE_EVIDENCE_CLOSURE(
                "DURABLE_EVIDENCE_CLOSURE", DURABLE_WRAPPER_KIND,
                DURABLE_WRAPPER_VERIFIER, 1, true),
        STAGE_ACCEPTANCE_RESULT(
                "STAGE_ACCEPTANCE_RESULT", STAGE_RESULT_KIND,
                STAGE_RESULT_VERIFIER, 2, true);

        private final String role;
        private final String kind;
        private final String verifierId;
        private final int revision;
        private final boolean fileSubject;

        Slot(String role, String kind, String verifierId, int revision, boolean fileSubject) {
            this.role = role;
            this.kind = kind;
            this.verifierId = verifierId;
            this.revision = revision;
            this.fileSubject = fileSubject;
        }

        String role() {
            return role;
        }

        String kind() {
            return kind;
        }

        String verifierId() {
            return verifierId;
        }

        int revision() {
            return revision;
        }

        boolean fileSubject() {
            return fileSubject;
        }

        static Slot require(String role, String kind, String verifierId, int revision) {
            return Arrays.stream(values())
                    .filter(slot -> slot.role.equals(role)
                            && slot.kind.equals(kind)
                            && slot.verifierId.equals(verifierId)
                            && slot.revision == revision)
                    .findFirst()
                    .orElseThrow(ReplayException::new);
        }
    }

    enum ReplayStatus {
        VERIFIED,
        INVALID,
        UNAVAILABLE
    }

    sealed interface ReplayInputs permits FormalInputTreeInputs, DurableWrapperInputs,
            StageResultInputs {
    }

    record FormalInputTreeInputs(
            CapabilityStudioFormalInputTreeSnapshotter.TreeKind treeKind,
            String bundleSemanticFingerprint,
            String treeFingerprint,
            String publicationFingerprint,
            String transactionId) implements ReplayInputs {
        FormalInputTreeInputs {
            if (treeKind == null || !fingerprint(bundleSemanticFingerprint)
                    || !fingerprint(treeFingerprint) || !fingerprint(publicationFingerprint)
                    || !fingerprint(transactionId)) {
                throw new ReplayException();
            }
        }
    }

    record DurableWrapperInputs(
            String stageResultRawFingerprint,
            String formalOuterFingerprint,
            String publicationFingerprint) implements ReplayInputs {
        DurableWrapperInputs {
            if (!fingerprint(stageResultRawFingerprint) || !fingerprint(formalOuterFingerprint)
                    || !fingerprint(publicationFingerprint)) {
                throw new ReplayException();
            }
        }
    }

    record StageResultInputs(Instant verificationInstant) implements ReplayInputs {
        StageResultInputs {
            if (verificationInstant == null) {
                throw new ReplayException();
            }
        }
    }

    record ReplayRequest(String id, Slot slot, String subjectPath, ReplayInputs inputs) {
        ReplayRequest {
            if (id == null || slot == null || subjectPath == null || inputs == null) {
                throw new ReplayException();
            }
            if (slot == Slot.DURABLE_EVIDENCE_CLOSURE
                    && subjectPath.lastIndexOf('/') <= 0) {
                throw new ReplayException();
            }
        }
    }

    /** Sealed subject facts supplied by the filesystem collector. */
    record Subject(
            Path path,
            String relativePath,
            boolean fileSubject,
            long byteSize,
            String rawFingerprint) {
        Subject {
            if (path == null || !path.isAbsolute() || !path.equals(path.normalize())
                    || relativePath == null || byteSize < 0
                    || fileSubject && !fingerprint(rawFingerprint)
                    || !fileSubject && rawFingerprint != null) {
                throw new ReplayException();
            }
        }
    }

    record ReplayObservation(
            String id,
            Slot slot,
            ReplayStatus status,
            String verifierId,
            int verifierRevision,
            String hardlinkScope) {
        ReplayObservation {
            if (id == null || slot == null || status == null
                    || !slot.verifierId().equals(verifierId)
                    || slot.revision() != verifierRevision) {
                throw new ReplayException();
            }
        }
    }

    /** Package-private mutation seam for deterministic adapter TOCTOU tests. */
    interface ReplayObserver {
        ReplayObserver NONE = (slot, subject) -> { };

        void beforeAdapter(Slot slot, Path subject);
    }

    /** Compiles mutable wire JSON into one closed, immutable replay request. */
    static ReplayRequest compile(JsonNode replay) {
        if (replay == null || !replay.isObject()) {
            throw new ReplayException();
        }
        try {
            String id = text(replay, "id");
            Slot slot = Slot.require(
                    text(replay, "role"), text(replay, "kind"), text(replay, "verifierId"),
                    replay.path("verifierRevision").intValue());
            String subjectPath = text(replay, "subjectPath");
            JsonNode inputs = replay.path("inputs");
            ReplayInputs typedInputs = switch (slot) {
                case FORMAL_INPUT_TREE -> new FormalInputTreeInputs(
                        CapabilityStudioFormalInputTreeSnapshotter.TreeKind.valueOf(
                                text(inputs, "treeKind")),
                        text(inputs, "bundleSemanticFingerprint"),
                        text(inputs, "treeFingerprint"),
                        text(inputs, "publicationFingerprint"),
                        text(inputs, "transactionId"));
                case DURABLE_EVIDENCE_CLOSURE -> new DurableWrapperInputs(
                        text(inputs, "stageResultRawFingerprint"),
                        text(inputs, "formalOuterFingerprint"),
                        text(inputs, "publicationFingerprint"));
                case STAGE_ACCEPTANCE_RESULT -> new StageResultInputs(
                        Instant.parse(text(inputs, "verificationInstant")));
            };
            return new ReplayRequest(id, slot, subjectPath, typedInputs);
        } catch (ReplayException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ReplayException();
        }
    }

    /** Runs exactly the adapter selected during compilation. */
    static ReplayObservation replay(ReplayRequest request, Subject subject) {
        return replay(request, subject, ReplayObserver.NONE);
    }

    static ReplayObservation replay(
            ReplayRequest request, Subject subject, ReplayObserver observer) {
        if (request == null || subject == null
                || observer == null
                || request.slot().fileSubject() != subject.fileSubject()
                || !request.subjectPath().equals(subject.relativePath())) {
            throw new ReplayException();
        }
        ReplayStatus subjectStatus = subjectStatus(subject);
        if (subjectStatus != ReplayStatus.VERIFIED) {
            return new ReplayObservation(request.id(), request.slot(), subjectStatus,
                    request.slot().verifierId(), request.slot().revision(), null);
        }
        try {
            observer.beforeAdapter(request.slot(), subject.path());
        } catch (RuntimeException failure) {
            return new ReplayObservation(request.id(), request.slot(), ReplayStatus.UNAVAILABLE,
                    request.slot().verifierId(), request.slot().revision(), null);
        }
        ReplayStatus status = switch (request.slot()) {
            case FORMAL_INPUT_TREE -> replayFormalInputTree(
                    subject.path(), (FormalInputTreeInputs) request.inputs());
            case DURABLE_EVIDENCE_CLOSURE -> replayDurableWrapper(
                    subject.path(), (DurableWrapperInputs) request.inputs());
            case STAGE_ACCEPTANCE_RESULT -> replayStageResult(
                    subject, (StageResultInputs) request.inputs());
        };
        String hardlinkScope = status == ReplayStatus.VERIFIED
                && request.slot() == Slot.DURABLE_EVIDENCE_CLOSURE
                ? parent(request.subjectPath()) : null;
        return new ReplayObservation(request.id(), request.slot(), status,
                request.slot().verifierId(), request.slot().revision(), hardlinkScope);
    }

    private static ReplayStatus subjectStatus(Subject subject) {
        try {
            boolean expectedType = subject.fileSubject()
                    ? Files.isRegularFile(subject.path(), LinkOption.NOFOLLOW_LINKS)
                    : Files.isDirectory(subject.path(), LinkOption.NOFOLLOW_LINKS);
            if (expectedType) {
                return ReplayStatus.VERIFIED;
            }
            return Files.exists(subject.path(), LinkOption.NOFOLLOW_LINKS)
                    ? ReplayStatus.INVALID : ReplayStatus.UNAVAILABLE;
        } catch (RuntimeException failure) {
            return ReplayStatus.UNAVAILABLE;
        }
    }

    private static ReplayStatus replayFormalInputTree(
            Path subject, FormalInputTreeInputs inputs) {
        try {
            new CapabilityStudioFormalInputTreeSnapshotter().verify(
                    subject, inputs.treeKind(), inputs.bundleSemanticFingerprint(),
                    inputs.treeFingerprint(), inputs.publicationFingerprint(),
                    inputs.transactionId());
            return ReplayStatus.VERIFIED;
        } catch (CapabilityStudioFormalInputTreeSnapshotter.FormalInputTreeException failure) {
            if (!Files.exists(subject, LinkOption.NOFOLLOW_LINKS)) {
                return ReplayStatus.UNAVAILABLE;
            }
            return failure.failureKind()
                    == CapabilityStudioFormalInputTreeSnapshotter.FailureKind.UNAVAILABLE
                    ? ReplayStatus.UNAVAILABLE : ReplayStatus.INVALID;
        } catch (RuntimeException failure) {
            return ReplayStatus.UNAVAILABLE;
        }
    }

    private static ReplayStatus replayDurableWrapper(
            Path subject, DurableWrapperInputs inputs) {
        try {
            CapabilityStudioExecutionLeaseEvidenceBundleVerifier.verify(
                    subject, inputs.stageResultRawFingerprint(),
                    inputs.formalOuterFingerprint(), inputs.publicationFingerprint());
            return ReplayStatus.VERIFIED;
        } catch (CapabilityStudioExecutionLeaseEvidenceBundleVerifier.VerificationException failure) {
            if (!Files.exists(subject, LinkOption.NOFOLLOW_LINKS)) {
                return ReplayStatus.UNAVAILABLE;
            }
            return failure.failureKind()
                    == CapabilityStudioStageAcceptanceAuthorityProvider.EvidenceFailureKind.UNAVAILABLE
                    ? ReplayStatus.UNAVAILABLE : ReplayStatus.INVALID;
        } catch (RuntimeException failure) {
            return ReplayStatus.UNAVAILABLE;
        }
    }

    private static ReplayStatus replayStageResult(Subject subject, StageResultInputs inputs) {
        if (subject.byteSize() > CapabilityStudioStageAcceptanceResultV2Verifier.MAXIMUM_RESULT_BYTES) {
            return ReplayStatus.INVALID;
        }
        try {
            byte[] bytes = CapabilityStudioBoundedFileReader.read(
                    subject.path(), CapabilityStudioStageAcceptanceResultV2Verifier.MAXIMUM_RESULT_BYTES);
            if (bytes == null) {
                return ReplayStatus.UNAVAILABLE;
            }
            if (bytes.length != subject.byteSize()
                    || !CapabilityStudioFormalEvidenceRunManifest.sha256(bytes)
                    .equals(subject.rawFingerprint())) {
                return ReplayStatus.INVALID;
            }
            return stageReplayStatus(new CapabilityStudioStageAcceptanceResultV2Verifier()
                    .verify(bytes, inputs.verificationInstant()));
        } catch (RuntimeException failure) {
            return ReplayStatus.UNAVAILABLE;
        }
    }

    static ReplayStatus stageReplayStatus(
            CapabilityStudioStageAcceptanceResultV2Verifier.VerificationResult result) {
        if (result == null) {
            return ReplayStatus.UNAVAILABLE;
        }
        if (result.verified()) {
            return ReplayStatus.VERIFIED;
        }
        return result.errorCode() != null && result.errorCode().endsWith("_SCHEMA_UNAVAILABLE")
                ? ReplayStatus.UNAVAILABLE : ReplayStatus.INVALID;
    }

    private static String parent(String relativePath) {
        int separator = relativePath.lastIndexOf('/');
        if (separator <= 0) {
            throw new ReplayException();
        }
        return relativePath.substring(0, separator);
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).textValue();
        if (value == null || value.isEmpty()) {
            throw new ReplayException();
        }
        return value;
    }

    private static boolean fingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    /** Payload-free rejection of a request outside the closed registry. */
    static final class ReplayException extends RuntimeException {
        private ReplayException() {
            super("typed evidence replay is invalid");
        }
    }
}
