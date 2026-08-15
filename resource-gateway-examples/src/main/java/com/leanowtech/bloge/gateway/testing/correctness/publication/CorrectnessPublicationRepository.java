package com.leanowtech.bloge.gateway.testing.correctness.publication;

import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;

import java.util.List;
import java.util.Optional;

/** Scope-exact immutable manifests and CAS publication Saga state. */
public interface CorrectnessPublicationRepository {

    Optional<StoredCorrectnessPublication> findPublication(
            EnterpriseScope scope, String publicationId);

    default Optional<StoredCorrectnessPublication> findLatestPublication(
            EnterpriseScope scope,
            ExactAssetRef definitionRef,
            ExactTargetRef target
    ) {
        return Optional.empty();
    }

    Optional<StoredCorrectnessPublicationAttempt> findAttempt(
            EnterpriseScope scope, String attemptId);

    Optional<StoredCorrectnessPublicationAttempt> findAttemptByIdempotencyFingerprint(
            EnterpriseScope scope, String idempotencyKeyFingerprint);

    List<StoredCorrectnessPublicationAttempt> attemptHistory(
            EnterpriseScope scope, String attemptId);

    Optional<StoredCorrectnessPublicationAttempt> saveAttemptIfVersion(
            EnterpriseScope scope,
            long expectedStateVersion,
            StoredCorrectnessPublicationAttempt candidate);

    Optional<CommitResult> commitIfVersion(
            EnterpriseScope scope,
            long expectedStateVersion,
            StoredCorrectnessPublicationAttempt committedAttempt,
            StoredCorrectnessPublication publication,
            CorrectnessPublicationCompleted event);

    record CommitResult(
            StoredCorrectnessPublicationAttempt attempt,
            StoredCorrectnessPublication publication
    ) {
        public CommitResult {
            if (attempt == null || publication == null) {
                throw new IllegalArgumentException("Committed attempt and Publication are required");
            }
        }
    }
}
