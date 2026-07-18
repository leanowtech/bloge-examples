package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Credential-free, challenge-bound request sent to the current-authority policy decision point.
 *
 * <p>The request deliberately excludes the submission correlation id, transport credentials,
 * execution metadata, fixture values, context and business payloads. The policy decision point
 * receives only the stable identity and immutable suite intent needed to decide whether the
 * durable job may still execute.</p>
 *
 * @param schemaVersion exact authority protocol generation
 * @param requestId unique authorization call identity
 * @param challenge 256-bit or stronger base64url replay challenge
 * @param requestedAt caller wall-clock observation
 * @param action fixed least-privilege policy action
 * @param jobId exact durable stability job
 * @param jobRequestFingerprint immutable submitted request fingerprint
 * @param suiteRef exact suite revision and fingerprint
 * @param classification frozen suite classification
 * @param deadlineAt absolute execution deadline
 * @param principal credential-free policy subject projection
 * @param principalFingerprint canonical subject fingerprint
 * @param authorizationRequestFingerprint canonical fingerprint of every preceding field
 */
public record TestSuiteStabilityAuthorityRequest(
        String schemaVersion,
        String requestId,
        String challenge,
        Instant requestedAt,
        String action,
        String jobId,
        String jobRequestFingerprint,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        String classification,
        Instant deadlineAt,
        Principal principal,
        String principalFingerprint,
        String authorizationRequestFingerprint) {

    /** Current version of the private worker-to-PDP protocol. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteStabilityAuthorityRequest.v1";
    /** Least-privilege action understood by the external PDP. */
    public static final String ACTION = "EXECUTE_SUITE_STABILITY_JOB";

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43,128}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates the complete, already-fingerprinted request envelope. */
    public TestSuiteStabilityAuthorityRequest {
        schemaVersion = normalized(schemaVersion);
        requestId = normalized(requestId);
        challenge = normalized(challenge);
        action = normalized(action).toUpperCase(Locale.ROOT);
        jobId = normalized(jobId);
        jobRequestFingerprint = normalized(jobRequestFingerprint).toLowerCase(Locale.ROOT);
        classification = normalized(classification).toUpperCase(Locale.ROOT);
        principalFingerprint = normalized(principalFingerprint).toLowerCase(Locale.ROOT);
        authorizationRequestFingerprint = normalized(authorizationRequestFingerprint)
                .toLowerCase(Locale.ROOT);
        requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
        deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        suiteRef = Objects.requireNonNull(suiteRef, "suiteRef");
        principal = Objects.requireNonNull(principal, "principal");
        if (!SCHEMA_VERSION.equals(schemaVersion) || !IDENTIFIER.matcher(requestId).matches()
                || !CHALLENGE.matcher(challenge).matches() || !ACTION.equals(action)
                || !IDENTIFIER.matcher(jobId).matches()
                || !FINGERPRINT.matcher(jobRequestFingerprint).matches()
                || !validSuiteRef(suiteRef)
                || !Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED")
                .contains(classification)
                || !FINGERPRINT.matcher(principalFingerprint).matches()
                || !FINGERPRINT.matcher(authorizationRequestFingerprint).matches()) {
            throw new IllegalArgumentException("Invalid suite-stability authority request");
        }
    }

    /**
     * Creates a content-addressed request from an integrity-verified durable job.
     *
     * @param objectMapper canonical JSON mapper
     * @param job exact claimed durable job
     * @param requestId fresh authorization-call identity
     * @param challenge fresh 256-bit or stronger base64url challenge
     * @param requestedAt current caller time
     * @return immutable minimal current-authority request
     */
    public static TestSuiteStabilityAuthorityRequest create(
            ObjectMapper objectMapper,
            TestSuiteStabilityJobRecord job,
            String requestId,
            String challenge,
            Instant requestedAt) {
        ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
        TestSuiteStabilityJobRecord source = Objects.requireNonNull(job, "job");
        Principal subject = Principal.from(source.principal());
        String subjectFingerprint = ProtocolFingerprint.ofBounded(mapper, subject, 32 * 1024);
        FingerprintMaterial material = new FingerprintMaterial(
                SCHEMA_VERSION, normalized(requestId), normalized(challenge),
                Objects.requireNonNull(requestedAt, "requestedAt"), ACTION, source.jobId(),
                source.requestFingerprint(), source.request().suiteRef(), source.classification(),
                source.deadlineAt(), subject, subjectFingerprint);
        return new TestSuiteStabilityAuthorityRequest(
                material.schemaVersion(), material.requestId(), material.challenge(),
                material.requestedAt(), material.action(), material.jobId(),
                material.jobRequestFingerprint(), material.suiteRef(), material.classification(),
                material.deadlineAt(), material.principal(), material.principalFingerprint(),
                ProtocolFingerprint.ofBounded(mapper, material, 64 * 1024));
    }

    /**
     * Verifies that neither the principal projection nor request binding changed in transit.
     *
     * @param objectMapper canonical JSON mapper
     * @return true only when both nested fingerprints match this exact request
     */
    public boolean fingerprintsVerified(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        if (!principalFingerprint.equals(
                ProtocolFingerprint.ofBounded(objectMapper, principal, 32 * 1024))) {
            return false;
        }
        return authorizationRequestFingerprint.equals(ProtocolFingerprint.ofBounded(
                objectMapper, fingerprintMaterial(), 64 * 1024));
    }

    /** @return canonical material covered by {@link #authorizationRequestFingerprint()} */
    public FingerprintMaterial fingerprintMaterial() {
        return new FingerprintMaterial(schemaVersion, requestId, challenge, requestedAt, action,
                jobId, jobRequestFingerprint, suiteRef, classification, deadlineAt, principal,
                principalFingerprint);
    }

    /**
     * Stable policy subject without a transport credential or per-request correlation identity.
     *
     * @param tenantId verified tenant
     * @param organizationId verified organization
     * @param projectId verified project, possibly blank
     * @param environmentId verified non-production environment
     * @param region verified region, possibly blank
     * @param actorType authenticated actor type
     * @param actorId authenticated stable actor identity
     * @param delegatedBy delegating actor, possibly blank
     * @param purpose authorized testing purpose
     * @param groups sorted bounded governance groups
     * @param clearance maximum data classification clearance
     * @param delegationGrantId delegated grant identity, possibly blank
     */
    public record Principal(
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String region,
            String actorType,
            String actorId,
            String delegatedBy,
            String purpose,
            List<String> groups,
            String clearance,
            String delegationGrantId) {

        /** Normalizes the stable subject and enforces deterministic group ordering. */
        public Principal {
            tenantId = normalized(tenantId);
            organizationId = normalized(organizationId);
            projectId = normalized(projectId);
            environmentId = normalized(environmentId).toLowerCase(Locale.ROOT);
            region = normalized(region);
            actorType = normalized(actorType).toUpperCase(Locale.ROOT);
            actorId = normalized(actorId);
            delegatedBy = normalized(delegatedBy);
            purpose = normalized(purpose).toUpperCase(Locale.ROOT);
            clearance = normalized(clearance).toUpperCase(Locale.ROOT);
            delegationGrantId = normalized(delegationGrantId);
            ArrayList<String> ordered = new ArrayList<>();
            for (String group : groups == null ? List.<String>of() : groups) {
                String value = normalized(group);
                if (!value.isBlank()) {
                    ordered.add(value);
                }
            }
            ordered.sort(Comparator.naturalOrder());
            groups = List.copyOf(ordered);
            if (!validRequired(tenantId) || !validRequired(organizationId)
                    || (!projectId.isBlank() && !validRequired(projectId))
                    || !Set.of("test", "staging").contains(environmentId)
                    || (!region.isBlank() && !validRequired(region))
                    || !validRequired(actorType) || !validRequired(actorId)
                    || (!delegatedBy.isBlank() && !validRequired(delegatedBy))
                    || !Set.of("TEST_EXECUTION", "TEST_REPLAY").contains(purpose)
                    || groups.size() > 64 || groups.stream().anyMatch(value -> !validRequired(value))
                    || groups.stream().distinct().count() != groups.size()
                    || !Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED")
                    .contains(clearance)
                    || (!delegationGrantId.isBlank() && !validRequired(delegationGrantId))) {
                throw new IllegalArgumentException("Invalid suite-stability authority principal");
            }
        }

        /** @return a stable projection of a submission-time authenticated principal */
        public static Principal from(TestSuiteStabilityJobPrincipal source) {
            TestSuiteStabilityJobPrincipal principal = Objects.requireNonNull(source, "source");
            return new Principal(principal.tenantId(), principal.organizationId(),
                    principal.projectId(), principal.environmentId(), principal.region(),
                    principal.actorType(), principal.actorId(), principal.delegatedBy(),
                    principal.purpose(), new ArrayList<>(principal.groups()), principal.clearance(),
                    principal.delegationGrantId());
        }
    }

    /** Canonical request material used by independent policy implementations. */
    public record FingerprintMaterial(
            String schemaVersion,
            String requestId,
            String challenge,
            Instant requestedAt,
            String action,
            String jobId,
            String jobRequestFingerprint,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            String classification,
            Instant deadlineAt,
            Principal principal,
            String principalFingerprint) {
    }

    private static boolean validSuiteRef(TestSuiteExecutionRequest.SuiteRef suiteRef) {
        return IDENTIFIER.matcher(normalized(suiteRef.suiteId())).matches()
                && suiteRef.revision() > 0
                && FINGERPRINT.matcher(normalized(suiteRef.fingerprint()).toLowerCase(Locale.ROOT))
                .matches();
    }

    private static boolean validRequired(String value) {
        return IDENTIFIER.matcher(value).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
