package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Payload-free validation proof for one safely generalizable recorded cluster.
 *
 * <p>This artifact is produced by a payload-bearing data-plane or TEE authority. The Resource
 * Gateway control plane never reads member payloads to infer a cluster. It verifies this
 * content-addressed proof, freezes exact corpus lineage, and requires online source and authority
 * revalidation before publication or serving. Match pointers identify stable request dimensions;
 * identity projections identify the only request values that may be generalized and deterministically
 * copied into response paths.</p>
 *
 * @param schemaVersion validation wire version
 * @param validationFingerprint canonical validation fingerprint
 * @param scope complete enterprise scope
 * @param validationId stable validation identity
 * @param revision positive validation revision
 * @param capabilityRef exact validated capability
 * @param corpusPublicationRef exact corpus publication used for validation
 * @param corpusRevisionRef exact immutable corpus revision used for validation
 * @param representativeSource exact recorded response used as the cluster representative
 * @param members exact admitted sources used to establish cluster support
 * @param matchRequestPointers stable request dimensions that must match exactly
 * @param identityMode identity-safety strategy for the response
 * @param identityProjections deterministic request-to-response identity projections
 * @param distinctIdentityCount independently measured distinct identities
 * @param holdout independent holdout precision assessment
 * @param confidence Wilson precision interval derived from the holdout
 * @param identityCoverageComplete whether the authority proved all response identity paths covered
 * @param validatedBy authenticated data-plane validation authority
 * @param validatedAt trusted validation time
 * @param expiresAt exclusive validation horizon
 */
public record CapabilityCorpusClusterValidation(
        String schemaVersion,
        String validationFingerprint,
        CapabilitySnapshot.Scope scope,
        String validationId,
        long revision,
        MirrorArtifactRef capabilityRef,
        MirrorArtifactRef corpusPublicationRef,
        MirrorArtifactRef corpusRevisionRef,
        SourceCoordinate representativeSource,
        List<SourceCoordinate> members,
        List<String> matchRequestPointers,
        IdentityMode identityMode,
        List<IdentityProjection> identityProjections,
        int distinctIdentityCount,
        HoldoutAssessment holdout,
        ArtifactProvenance.Confidence confidence,
        boolean identityCoverageComplete,
        String validatedBy,
        Instant validatedAt,
        Instant expiresAt
) {
    /** Current cluster-validation wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityCorpusClusterValidation.v1";
    /** Artifact kind used by exact validation references. */
    public static final String ARTIFACT_KIND =
            "CAPABILITY_CORPUS_CLUSTER_VALIDATION";
    /** Exact confidence algorithm required by v1. */
    public static final String CONFIDENCE_METHOD = "WILSON_PRECISION_95_V1";
    /** Hard bound on supporting sources. */
    public static final int MAXIMUM_MEMBERS = 1_000;
    /** Hard bound on exact match dimensions. */
    public static final int MAXIMUM_MATCH_POINTERS = 32;
    /** Hard bound on identity projections. */
    public static final int MAXIMUM_IDENTITY_PROJECTIONS = 16;

    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final double CONFIDENCE_TOLERANCE = 1.0e-9d;

    /** Validates exact lineage, deterministic pointers, support, and confidence evidence. */
    public CapabilityCorpusClusterValidation {
        schemaVersion = version(schemaVersion);
        validationFingerprint = fingerprint(
                validationFingerprint, "validationFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        validationId = identifier(validationId, "validationId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        capabilityRef = ref(capabilityRef, "CAPABILITY", "capabilityRef");
        corpusPublicationRef = ref(
                corpusPublicationRef,
                CapabilityCorpusPublication.ARTIFACT_KIND,
                "corpusPublicationRef");
        corpusRevisionRef = ref(
                corpusRevisionRef,
                CapabilityCorpusRevision.ARTIFACT_KIND,
                "corpusRevisionRef");
        if (!corpusPublicationRef.id().equals(corpusRevisionRef.id())) {
            throw new IllegalArgumentException(
                    "corpus publication and revision must belong to one corpus");
        }
        representativeSource = Objects.requireNonNull(
                representativeSource, "representativeSource");
        members = members == null ? List.of() : List.copyOf(members);
        if (members.size() < 2 || members.size() > MAXIMUM_MEMBERS) {
            throw new IllegalArgumentException(
                    "members must contain between 2 and 1000 sources");
        }
        String previousObservationId = "";
        Set<SourceCoordinate> distinctMembers = new HashSet<>();
        for (SourceCoordinate member : members) {
            SourceCoordinate exact = Objects.requireNonNull(member, "member");
            if (!distinctMembers.add(exact)
                    || exact.observationRef().id()
                    .compareTo(previousObservationId) <= 0) {
                throw new IllegalArgumentException(
                        "members must be distinct and ordered by observation id");
            }
            previousObservationId = exact.observationRef().id();
        }
        if (!distinctMembers.contains(representativeSource)) {
            throw new IllegalArgumentException(
                    "representativeSource must belong to members");
        }
        matchRequestPointers = pointers(
                matchRequestPointers, 1, MAXIMUM_MATCH_POINTERS,
                "matchRequestPointers");
        identityMode = Objects.requireNonNull(identityMode, "identityMode");
        identityProjections = identityProjections == null
                ? List.of() : List.copyOf(identityProjections);
        if (identityProjections.size() > MAXIMUM_IDENTITY_PROJECTIONS) {
            throw new IllegalArgumentException(
                    "identityProjections exceeds the v1 bound");
        }
        if (identityMode == IdentityMode.IDENTITY_FREE_RESPONSE
                && !identityProjections.isEmpty()
                || identityMode == IdentityMode.REQUEST_PROJECTION
                && identityProjections.isEmpty()) {
            throw new IllegalArgumentException(
                    "identityMode and identityProjections are inconsistent");
        }
        requireDisjointPointers(matchRequestPointers, identityProjections);
        if (distinctIdentityCount < 1
                || distinctIdentityCount > members.size()) {
            throw new IllegalArgumentException(
                    "distinctIdentityCount is outside member support");
        }
        holdout = Objects.requireNonNull(holdout, "holdout");
        confidence = Objects.requireNonNull(confidence, "confidence");
        requireWilsonConfidence(holdout, confidence);
        if (!identityCoverageComplete) {
            throw new IllegalArgumentException(
                    "identityCoverageComplete must be proven");
        }
        validatedBy = identifier(validatedBy, "validatedBy");
        validatedAt = Objects.requireNonNull(validatedAt, "validatedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(validatedAt)) {
            throw new IllegalArgumentException(
                    "validation expiresAt must be after validatedAt");
        }
    }

    /**
     * Returns the exact validation reference.
     *
     * @return immutable content-addressed validation reference
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(
                ARTIFACT_KIND, validationId, revision, validationFingerprint);
    }

    /**
     * Exact admitted source coordinate without business payload.
     *
     * @param observationRef exact signed observation
     * @param admissionRef exact admitted decision
     */
    public record SourceCoordinate(
            MirrorArtifactRef observationRef,
            MirrorArtifactRef admissionRef
    ) {
        /** Validates observation-to-admission ownership. */
        public SourceCoordinate {
            observationRef = ref(
                    observationRef,
                    CapabilityObservationEnvelope.ARTIFACT_KIND,
                    "observationRef");
            admissionRef = ref(
                    admissionRef,
                    CapabilityObservationAdmission.ARTIFACT_KIND,
                    "admissionRef");
            if (!admissionRef.id().equals(
                    observationRef.id() + ":admission")) {
                throw new IllegalArgumentException(
                        "admissionRef must belong to observationRef");
            }
        }
    }

    /**
     * Deterministic identity replacement from one request value to response paths.
     *
     * @param requestPointer source request JSON Pointer
     * @param responsePointers response JSON Pointers overwritten with the source value
     */
    public record IdentityProjection(
            String requestPointer,
            List<String> responsePointers
    ) {
        /** Validates bounded, non-overlapping RFC 6901 pointer coordinates. */
        public IdentityProjection {
            requestPointer = pointer(requestPointer, "requestPointer");
            responsePointers = pointers(
                    responsePointers, 1, 16, "responsePointers");
            requireNonOverlapping(responsePointers, "responsePointers");
        }
    }

    /**
     * Independent holdout precision counts.
     *
     * @param sampleCount total holdout requests considered
     * @param acceptedCount holdout requests selected by the cluster matcher
     * @param correctCount accepted requests with owner-validated correct output shape
     * @param falsePositiveCount accepted requests that were unsafe or incorrect
     */
    public record HoldoutAssessment(
            int sampleCount,
            int acceptedCount,
            int correctCount,
            int falsePositiveCount
    ) {
        /** Validates a complete precision denominator without hidden outcomes. */
        public HoldoutAssessment {
            if (sampleCount < 1 || acceptedCount < 1
                    || acceptedCount > sampleCount
                    || correctCount < 0 || falsePositiveCount < 0
                    || correctCount + falsePositiveCount != acceptedCount) {
                throw new IllegalArgumentException(
                        "holdout counts do not form a complete precision sample");
            }
        }

        /**
         * Returns false-positive rate in basis points over accepted matches.
         *
         * @return integer basis points rounded up to remain conservative
         */
        public int falsePositiveBasisPoints() {
            return (int) Math.ceil(
                    falsePositiveCount * 10_000.0d / acceptedCount);
        }
    }

    /** Closed v1 identity-safety strategies. */
    public enum IdentityMode {
        /** Data-plane validation proved the response has no entity identity fields. */
        IDENTITY_FREE_RESPONSE,
        /** Every response identity field is overwritten from approved request paths. */
        REQUEST_PROJECTION
    }

    private static void requireWilsonConfidence(
            HoldoutAssessment holdout,
            ArtifactProvenance.Confidence confidence) {
        if (!CONFIDENCE_METHOD.equals(confidence.method())) {
            throw new IllegalArgumentException(
                    "confidence method must be " + CONFIDENCE_METHOD);
        }
        double point = (double) holdout.correctCount()
                / holdout.acceptedCount();
        double z = 1.959963984540054d;
        double denominator = 1.0d
                + z * z / holdout.acceptedCount();
        double center = point
                + z * z / (2.0d * holdout.acceptedCount());
        double spread = z * Math.sqrt(
                point * (1.0d - point) / holdout.acceptedCount()
                        + z * z
                        / (4.0d * holdout.acceptedCount()
                        * holdout.acceptedCount()));
        double lower = Math.max(0.0d, (center - spread) / denominator);
        double upper = Math.min(1.0d, (center + spread) / denominator);
        if (!near(point, confidence.point())
                || !near(lower, confidence.lowerBound())
                || !near(upper, confidence.upperBound())) {
            throw new IllegalArgumentException(
                    "confidence does not match the v1 Wilson calculation");
        }
    }

    private static boolean near(double expected, double actual) {
        return Math.abs(expected - actual) <= CONFIDENCE_TOLERANCE;
    }

    private static void requireDisjointPointers(
            List<String> matchPointers,
            List<IdentityProjection> identityProjections) {
        List<String> requestPointers = new ArrayList<>(matchPointers);
        List<String> responsePointers = new ArrayList<>();
        for (IdentityProjection projection : identityProjections) {
            requestPointers.add(projection.requestPointer());
            responsePointers.addAll(projection.responsePointers());
        }
        requireNonOverlapping(requestPointers, "request projection pointers");
        requireNonOverlapping(responsePointers, "response projection pointers");
    }

    private static List<String> pointers(
            List<String> values, int minimum, int maximum, String field) {
        List<String> exact = values == null ? List.of()
                : values.stream().map(value -> pointer(value, field)).toList();
        if (exact.size() < minimum || exact.size() > maximum) {
            throw new IllegalArgumentException(field + " size is invalid");
        }
        String previous = "";
        for (String value : exact) {
            if (value.compareTo(previous) <= 0) {
                throw new IllegalArgumentException(
                        field + " must be unique and lexicographically ordered");
            }
            previous = value;
        }
        return List.copyOf(exact);
    }

    private static void requireNonOverlapping(
            List<String> values, String field) {
        for (int left = 0; left < values.size(); left++) {
            for (int right = left + 1; right < values.size(); right++) {
                String first = values.get(left);
                String second = values.get(right);
                if (first.equals(second)
                        || first.startsWith(second + "/")
                        || second.startsWith(first + "/")) {
                    throw new IllegalArgumentException(
                            field + " must not overlap");
                }
            }
        }
    }

    private static String pointer(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isEmpty() || exact.length() > 512
                || exact.charAt(0) != '/'
                || exact.contains("*")) {
            throw new IllegalArgumentException(field + " is not a safe JSON Pointer");
        }
        for (int index = 0; index < exact.length(); index++) {
            if (exact.charAt(index) == '~'
                    && (index + 1 >= exact.length()
                    || exact.charAt(index + 1) != '0'
                    && exact.charAt(index + 1) != '1')) {
                throw new IllegalArgumentException(
                        field + " contains an invalid JSON Pointer escape");
            }
            if (exact.charAt(index) == '~') {
                index++;
            }
        }
        return exact;
    }

    private static MirrorArtifactRef ref(
            MirrorArtifactRef value, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return exact;
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported corpus cluster validation schemaVersion");
        }
        return exact;
    }

    private static String fingerprint(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static String identifier(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }
}
