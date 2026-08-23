package com.leanowtech.bloge.gateway.testkit.ept;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Closed idempotent publication request for CapabilityStudioEvidencePublicationTransaction.
 *
 * <p>Identity derivation (§E.2):
 * <pre>
 * stableRequestId = SHA256(
 *     EPT_DOMAIN
 *   || authorityInputTreeFingerprint
 *   || targetInputTreeFingerprint
 *   || planFingerprint
 *   || targetBindingFingerprint
 *   || declarationFingerprint
 *   || candidateFingerprint
 * )
 *
 * transactionId = SHA256(
 *     EPT_DOMAIN
 *   || stableRequestId
 *   || publicationNonce
 * )
 * </pre>
 *
 * <p>No JVM command, classpath, storeAdapter URL, recover flag, or checkMode
 * is exposed in the public request.  All execution semantics are injected.</p>
 *
 * <p>The evidenceRoot may be null; if so, the producer's returned evidence path is used.
 * This allows the producer full control over evidence placement.</p>
 *
 * @param expectedStableRequestId caller-provided expected stable identity; module recomputes internally and verifies match (mismatch = INVALID)
 * @param publicationNonce deployment-generated high-entropy nonce for transaction derivation
 * @param authorityInputTreeFingerprint authority input tree fingerprint (SHA256 hex)
 * @param targetInputTreeFingerprint target input tree fingerprint (SHA256 hex)
 * @param planFingerprint plan fingerprint (SHA256 hex)
 * @param targetBindingFingerprint target binding fingerprint (SHA256 hex)
 * @param declarationFingerprint declaration fingerprint (SHA256 hex)
 * @param candidateFingerprint candidate artifact fingerprint (SHA256 hex); replaces boundedChildDigest
 * @param evidenceRoot URI of the evidence root (optional; producer's path used if null)
 * @param privateParent URI of the private parent
 * @param workingDirectory absolute normalized private working directory (0700 parent)
 * @param committedRoot absolute normalized output root for committed/ directory
 */
public record Request(
        String expectedStableRequestId,
        String publicationNonce,
        String authorityInputTreeFingerprint,
        String targetInputTreeFingerprint,
        String planFingerprint,
        String targetBindingFingerprint,
        String declarationFingerprint,
        String candidateFingerprint,
        URI evidenceRoot,
        URI privateParent,
        Path workingDirectory,
        Path committedRoot) {

    public static final String MESSAGE_VERSION =
            "resource-gateway.capability-studio.evidence-publication-transaction.v1";

    /** SHA256 hex fingerprint pattern (64 hex chars after prefix). */
    private static final Pattern FINGERPRINT =
            Pattern.compile("^sha256:[0-9a-f]{64}$");

    /** Validates all nine fingerprint fields and three non-null path/URI fields. */
    public Request {
        requireFingerprint(expectedStableRequestId, "expectedStableRequestId");
        requireFingerprint(publicationNonce, "publicationNonce");
        requireFingerprint(authorityInputTreeFingerprint, "authorityInputTreeFingerprint");
        requireFingerprint(targetInputTreeFingerprint, "targetInputTreeFingerprint");
        requireFingerprint(planFingerprint, "planFingerprint");
        requireFingerprint(targetBindingFingerprint, "targetBindingFingerprint");
        requireFingerprint(declarationFingerprint, "declarationFingerprint");
        requireFingerprint(candidateFingerprint, "candidateFingerprint");
        // evidenceRoot is optional (producer's path used if null)
        Objects.requireNonNull(privateParent, "privateParent required");
        Objects.requireNonNull(workingDirectory, "workingDirectory required");
        Objects.requireNonNull(committedRoot, "committedRoot required");
    }

    private static void requireFingerprint(String value, String field) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    field + " must be a valid sha256:fingerprint (64 hex chars)");
        }
    }
}
