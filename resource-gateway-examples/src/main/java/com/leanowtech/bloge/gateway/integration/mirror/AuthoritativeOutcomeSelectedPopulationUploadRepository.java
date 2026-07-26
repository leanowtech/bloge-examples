package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable, quota-bounded, resumable staging boundary for selected populations.
 *
 * <p>This repository owns only upload intent, chunk staging, and finalizer fencing. A claimed
 * complete command must still pass the existing selection-authority and population repository
 * admission boundary before {@link #completeFinalize(FinalizationClaim,
 * AuthoritativeOutcomeSelectedPopulationAdmission)} can mark it terminal.</p>
 */
public interface AuthoritativeOutcomeSelectedPopulationUploadRepository {
    /** Complete upload state plus an optional terminal population admission. */
    record Upload(
            AuthoritativeOutcomeSelectedPopulationUploadRequest
                    request,
            AuthoritativeOutcomeSelectedPopulationUploadStatus
                    status,
            Optional<AuthoritativeOutcomeSelectedPopulationAdmission>
                    admission
    ) {
        /** Requires terminal admission exactly when finalized. */
        public Upload {
            request = Objects.requireNonNull(
                    request, "request");
            status = Objects.requireNonNull(
                    status, "status");
            admission = admission == null
                    ? Optional.empty() : admission;
            if ((status.state()
                    == AuthoritativeOutcomeSelectedPopulationUploadStatus
                    .State.FINALIZED)
                    != admission.isPresent()) {
                throw new IllegalArgumentException(
                        "selected-population upload admission is inconsistent");
            }
        }
    }

    /** Idempotent begin result. */
    record Admission(
            AuthoritativeOutcomeSelectedPopulationUploadStatus
                    status,
            boolean idempotentReplay
    ) {
        /** Requires a concrete durable status. */
        public Admission {
            status = Objects.requireNonNull(
                    status, "status");
        }
    }

    /** Idempotent chunk-staging result. */
    record ChunkAdmission(
            AuthoritativeOutcomeSelectedPopulationUploadStatus
                    status,
            int chunkIndex,
            String chunkFingerprint,
            boolean idempotentReplay
    ) {
        /** Requires exact staged chunk coordinates. */
        public ChunkAdmission {
            status = Objects.requireNonNull(
                    status, "status");
            if (chunkIndex < 0
                    || chunkFingerprint == null
                    || chunkFingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        "selected-population chunk admission is invalid");
            }
        }
    }

    /** Exact immutable finalization lease and complete staged command. */
    record FinalizationClaim(
            Upload upload,
            String owner,
            long epoch,
            Instant leaseUntil,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks,
            boolean requiresExecution
    ) {
        /** Enforces executable versus terminal replay shape. */
        public FinalizationClaim {
            upload = Objects.requireNonNull(
                    upload, "upload");
            owner = owner == null ? "" : owner.trim();
            leaseUntil = leaseUntil == null
                    ? Instant.EPOCH : leaseUntil;
            chunks = chunks == null
                    ? List.of() : List.copyOf(chunks);
            if (requiresExecution
                    && (owner.isBlank()
                    || epoch < 1
                    || leaseUntil.equals(Instant.EPOCH)
                    || chunks.size()
                    != upload.status()
                    .expectedChunkCount())
                    || !requiresExecution
                    && (upload.status().state()
                    != AuthoritativeOutcomeSelectedPopulationUploadStatus
                    .State.FINALIZED
                    || !owner.isBlank()
                    || !chunks.isEmpty())) {
                throw new IllegalArgumentException(
                        "selected-population finalization claim is inconsistent");
            }
        }

        /** @return complete admission command reconstructed from staged chunks */
        public AuthoritativeOutcomeSelectedPopulationAdmissionRequest
        command() {
            if (!requiresExecution) {
                throw new IllegalStateException(
                        "terminal upload replay has no executable command");
            }
            return new
                    AuthoritativeOutcomeSelectedPopulationAdmissionRequest(
                    "",
                    upload.request()
                            .expectedPredecessorFingerprint(),
                    upload.request().manifest(),
                    chunks);
        }
    }

    /** Creates or exactly replays one immutable upload intent. */
    Admission begin(
            AuthoritativeOutcomeSelectedPopulationUploadRequest
                    request);

    /** Stages or exactly replays one manifest-declared content-addressed chunk. */
    ChunkAdmission stageChunk(
            CapabilitySnapshot.Scope scope,
            String uploadId,
            int chunkIndex,
            AuthoritativeOutcomeSelectedPopulationChunk chunk,
            long encodedBytes);

    /** Reads one upload inside an exact enterprise scope. */
    Optional<Upload> find(
            CapabilitySnapshot.Scope scope,
            String uploadId);

    /** Claims a complete upload or returns its terminal admission replay. */
    FinalizationClaim claimFinalize(
            CapabilitySnapshot.Scope scope,
            String uploadId,
            String owner);

    /** Commits the exact terminal admission under finalizer epoch fencing. */
    Upload completeFinalize(
            FinalizationClaim claim,
            AuthoritativeOutcomeSelectedPopulationAdmission
                    admission);

    /** Aborts an open upload and destroys its staged chunks. */
    Upload abort(
            CapabilitySnapshot.Scope scope,
            String uploadId);

    /** Expires abandoned uploads and purges terminal rows past retention. */
    int expireAndPurge(int limit);

    /** Closed payload-free upload rejection vocabulary. */
    enum Reason {
        UPLOAD_NOT_FOUND,
        UPLOAD_CONFLICT,
        UPLOAD_NOT_OPEN,
        UPLOAD_EXPIRED,
        CHUNK_INVALID,
        CHUNK_CONFLICT,
        UPLOAD_INCOMPLETE,
        ACTIVE_UPLOAD_QUOTA_EXCEEDED,
        UPLOAD_BYTE_QUOTA_EXCEEDED,
        FINALIZATION_BUSY,
        FINALIZATION_FENCED,
        STORED_STATE_CORRUPT
    }

    /** Stable upload failure that never carries member data. */
    final class Violation extends RuntimeException {
        private final Reason reason;

        /** Creates one stable upload rejection. */
        public Violation(Reason reason) {
            super("Authoritative outcome selected population upload rejected: "
                    + Objects.requireNonNull(
                    reason, "reason").name());
            this.reason = reason;
        }

        /** @return stable payload-free reason */
        public Reason reason() {
            return reason;
        }
    }
}
