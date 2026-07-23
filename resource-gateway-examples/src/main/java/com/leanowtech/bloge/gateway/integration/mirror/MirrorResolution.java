package com.leanowtech.bloge.gateway.integration.mirror;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable provenance for one mirror resolver attempt at one BLOGE invocation coordinate.
 *
 * <p>The protocol distinguishes a resolved null value from an omitted payload, and a hash-only
 * evidence projection from an abstention. Payload visibility is explicit so an evidence exporter
 * cannot silently downgrade redaction while retaining a misleading source or confidence claim.</p>
 *
 * @param schemaVersion mirror-resolution protocol version
 * @param resolutionFingerprint canonical fingerprint with this field blanked
 * @param runId run identity that owns this resolution
 * @param planFingerprint exact sealed mirror plan
 * @param capabilityRef external capability resolved at this site
 * @param invocationSiteId stable structural invocation-site identity
 * @param graphPath stable path of the graph that owns the invocation
 * @param correlationKey foreach, loop, or business correlation coordinate
 * @param occurrence one-based invocation occurrence
 * @param attempt one-based delegate attempt
 * @param requestFingerprint canonical request fingerprint without request payload disclosure
 * @param status resolved, abstained, or policy-rejected outcome
 * @param source fixed-priority source that produced the outcome
 * @param payloadVisibility evidence visibility for a resolved output
 * @param outputIncluded whether {@code output} is present, including an explicit null value
 * @param output resolved output when visibility permits it
 * @param outputFingerprint canonical output fingerprint, including hash-only projections
 * @param error resolved business error or resolver-policy rejection
 * @param matchedArtifactRefs exact governed artifacts used by the resolver
 * @param matchedRuleRefs exact sub-artifact rule identities used by the resolver
 * @param confidence bounded match confidence with a named method
 * @param freshness normalized source freshness in the closed interval [0,1]
 * @param limitations bounded explicit fidelity and governance limitations
 */
public record MirrorResolution(
        String schemaVersion,
        String resolutionFingerprint,
        String runId,
        String planFingerprint,
        MirrorArtifactRef capabilityRef,
        String invocationSiteId,
        String graphPath,
        String correlationKey,
        int occurrence,
        int attempt,
        String requestFingerprint,
        Status status,
        MirrorPlan.MirrorSource source,
        PayloadVisibility payloadVisibility,
        boolean outputIncluded,
        Object output,
        String outputFingerprint,
        MirrorError error,
        List<MirrorArtifactRef> matchedArtifactRefs,
        List<String> matchedRuleRefs,
        ArtifactProvenance.Confidence confidence,
        double freshness,
        List<String> limitations
) {
    /** Current mirror-resolution protocol version. */
    public static final String SCHEMA_VERSION = "resourceGateway.mirrorResolution.v1";
    /** Maximum number of exact artifacts attached to one resolution. */
    public static final int MAXIMUM_ARTIFACT_REFS = 12_000;
    /** Maximum number of rule identities attached to one resolution. */
    public static final int MAXIMUM_RULE_REFS = 1_000;
    /** Maximum number of explicit limitations attached to one resolution. */
    public static final int MAXIMUM_LIMITATIONS = 64;
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Terminal resolver outcome. */
    public enum Status {
        RESOLVED,
        ABSTAINED,
        REJECTED
    }

    /** Visibility of a resolved output in this artifact. */
    public enum PayloadVisibility {
        FULL,
        REDACTED,
        HASH_ONLY,
        NONE
    }

    /** Validates cross-field outcome invariants and recursively detaches visible JSON output. */
    public MirrorResolution {
        schemaVersion = version(schemaVersion);
        resolutionFingerprint = optionalFingerprint(resolutionFingerprint,
                "resolutionFingerprint");
        runId = required(runId, "runId", 512);
        planFingerprint = fingerprint(planFingerprint, "planFingerprint");
        capabilityRef = requireCapability(capabilityRef);
        invocationSiteId = required(invocationSiteId, "invocationSiteId", 2_048);
        graphPath = required(graphPath, "graphPath", 2_048);
        if (!graphPath.startsWith("/")) {
            throw new IllegalArgumentException("graphPath must start with /");
        }
        correlationKey = bounded(correlationKey, "correlationKey", 1_024);
        if (occurrence < 1 || attempt < 1) {
            throw new IllegalArgumentException("occurrence and attempt must be positive");
        }
        requestFingerprint = fingerprint(requestFingerprint, "requestFingerprint");
        status = Objects.requireNonNull(status, "status");
        source = Objects.requireNonNull(source, "source");
        payloadVisibility = payloadVisibility == null ? PayloadVisibility.NONE : payloadVisibility;
        output = freeze(output, new IdentityHashMap<>(), 0);
        outputFingerprint = optionalFingerprint(outputFingerprint, "outputFingerprint");
        matchedArtifactRefs = artifactRefs(matchedArtifactRefs);
        matchedRuleRefs = strings(matchedRuleRefs, "matchedRuleRefs", MAXIMUM_RULE_REFS, 512);
        confidence = Objects.requireNonNull(confidence, "confidence");
        if (!Double.isFinite(freshness) || freshness < 0 || freshness > 1) {
            throw new IllegalArgumentException("freshness must be in the closed interval [0,1]");
        }
        limitations = strings(limitations, "limitations", MAXIMUM_LIMITATIONS, 512);
        validateOutcome(status, source, payloadVisibility, outputIncluded, output,
                outputFingerprint, error, matchedArtifactRefs, matchedRuleRefs, confidence);
    }

    /**
     * Returns the same resolution with canonical output and resolution fingerprints.
     *
     * @param resolutionValue canonical resolution fingerprint, or blank before sealing
     * @param outputValue canonical output fingerprint, or blank when no output exists
     * @return copied immutable resolution
     */
    public MirrorResolution withFingerprints(String resolutionValue, String outputValue) {
        return new MirrorResolution(schemaVersion, resolutionValue, runId, planFingerprint,
                capabilityRef, invocationSiteId, graphPath, correlationKey, occurrence, attempt,
                requestFingerprint, status, source, payloadVisibility, outputIncluded, output,
                outputValue, error, matchedArtifactRefs, matchedRuleRefs, confidence, freshness,
                limitations);
    }

    /** Prevents visible output or error diagnostics from entering ordinary application logs. */
    @Override
    public String toString() {
        return "MirrorResolution[runId=" + runId + ", invocationSiteId=" + invocationSiteId
                + ", occurrence=" + occurrence + ", attempt=" + attempt + ", status=" + status
                + ", source=" + source + ", payloadVisibility=" + payloadVisibility
                + ", outputFingerprint=" + outputFingerprint + "]";
    }

    private static void validateOutcome(
            Status status,
            MirrorPlan.MirrorSource source,
            PayloadVisibility visibility,
            boolean outputIncluded,
            Object output,
            String outputFingerprint,
            MirrorError error,
            List<MirrorArtifactRef> artifacts,
            List<String> rules,
            ArtifactProvenance.Confidence confidence) {
        if (!outputIncluded && output != null) {
            throw new IllegalArgumentException("output must be null when outputIncluded is false");
        }
        switch (visibility) {
            case FULL, REDACTED -> {
                if (!outputIncluded) {
                    throw new IllegalArgumentException(
                            "FULL and REDACTED outputs must be included");
                }
            }
            case HASH_ONLY -> {
                if (outputIncluded || output != null || outputFingerprint.isBlank()) {
                    throw new IllegalArgumentException(
                            "HASH_ONLY output requires a fingerprint and no included value");
                }
            }
            case NONE -> {
                if (outputIncluded || output != null || !outputFingerprint.isBlank()) {
                    throw new IllegalArgumentException(
                            "NONE output must not carry a value or fingerprint");
                }
            }
        }
        if (status == Status.ABSTAINED) {
            if (source != MirrorPlan.MirrorSource.ABSTAINED || visibility != PayloadVisibility.NONE
                    || error != null || !artifacts.isEmpty() || !rules.isEmpty()
                    || confidence.point() != 0 || confidence.lowerBound() != 0
                    || confidence.upperBound() != 0) {
                throw new IllegalArgumentException(
                        "ABSTAINED must carry no payload, error, match, or confidence claim");
            }
            return;
        }
        if (source == MirrorPlan.MirrorSource.ABSTAINED) {
            throw new IllegalArgumentException("non-abstained outcomes require a concrete source");
        }
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException(
                    "non-abstained outcomes require exact matched artifact provenance");
        }
        if (status == Status.REJECTED) {
            if (visibility != PayloadVisibility.NONE || error == null) {
                throw new IllegalArgumentException(
                        "REJECTED requires an error and must not carry output");
            }
            return;
        }
        boolean hasOutput = visibility != PayloadVisibility.NONE;
        if (hasOutput == (error != null)) {
            throw new IllegalArgumentException(
                    "RESOLVED must carry exactly one output or resolved error");
        }
    }

    private static MirrorArtifactRef requireCapability(MirrorArtifactRef ref) {
        Objects.requireNonNull(ref, "capabilityRef");
        if (!"CAPABILITY".equals(ref.kind())) {
            throw new IllegalArgumentException("capabilityRef must reference CAPABILITY");
        }
        return ref;
    }

    private static List<MirrorArtifactRef> artifactRefs(List<MirrorArtifactRef> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAXIMUM_ARTIFACT_REFS) {
            throw new IllegalArgumentException("matchedArtifactRefs exceeds its limit");
        }
        List<MirrorArtifactRef> result = values.stream().map(value ->
                        Objects.requireNonNull(value, "matchedArtifactRef"))
                .sorted(Comparator.comparing(MirrorArtifactRef::kind)
                        .thenComparing(MirrorArtifactRef::id)
                        .thenComparingLong(MirrorArtifactRef::revision)
                        .thenComparing(MirrorArtifactRef::fingerprint))
                .toList();
        if (new LinkedHashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException("matchedArtifactRefs must be unique");
        }
        return result;
    }

    private static List<String> strings(
            List<String> values, String field, int maximumItems, int maximumLength) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > maximumItems) {
            throw new IllegalArgumentException(field + " exceeds its limit");
        }
        Set<String> result = new java.util.TreeSet<>();
        for (String value : values) {
            String normalized = required(value, field + " item", maximumLength);
            if (!result.add(normalized)) {
                throw new IllegalArgumentException(field + " must be unique");
            }
        }
        return List.copyOf(result);
    }

    private static String version(String value) {
        String normalized = value == null || value.isBlank() ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException("unsupported mirror-resolution schemaVersion");
        }
        return normalized;
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field, 71);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 value");
        }
        return normalized;
    }

    private static String optionalFingerprint(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be blank or canonical SHA-256");
        }
        return normalized;
    }

    private static String required(String value, String field, int maximumLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must be non-blank and at most " + maximumLength + " characters");
        }
        return normalized;
    }

    private static String bounded(String value, String field, int maximumLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds its length limit");
        }
        return normalized;
    }

    private static Object freeze(Object value, IdentityHashMap<Object, Boolean> path, int depth) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (depth >= 128) {
            throw new IllegalArgumentException("output exceeds maximum JSON nesting depth");
        }
        if (path.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("output contains a cycle");
        }
        try {
            if (value instanceof Map<?, ?> source) {
                Map<String, Object> copy = new LinkedHashMap<>();
                source.forEach((key, nested) -> {
                    if (!(key instanceof String text)) {
                        throw new IllegalArgumentException("output object keys must be strings");
                    }
                    copy.put(text, freeze(nested, path, depth + 1));
                });
                return Collections.unmodifiableMap(copy);
            }
            if (value instanceof Collection<?> source) {
                List<Object> copy = new ArrayList<>(source.size());
                source.forEach(item -> copy.add(freeze(item, path, depth + 1)));
                return Collections.unmodifiableList(copy);
            }
            if (value.getClass().isArray()) {
                List<Object> copy = new ArrayList<>(Array.getLength(value));
                for (int index = 0; index < Array.getLength(value); index++) {
                    copy.add(freeze(Array.get(value, index), path, depth + 1));
                }
                return Collections.unmodifiableList(copy);
            }
            throw new IllegalArgumentException(
                    "output must contain JSON-compatible scalar, object, or array values");
        } finally {
            path.remove(value);
        }
    }

    /**
     * Bounded payload-free error fact. Message is diagnostic text and must never contain business
     * request or response values.
     *
     * @param code stable machine-readable error code
     * @param type normalized error category
     * @param message bounded payload-free diagnostic
     */
    public record MirrorError(String code, String type, String message) {
        /** Normalizes and bounds every error field. */
        public MirrorError {
            code = required(code, "error.code", 256);
            type = required(type, "error.type", 256);
            message = bounded(message, "error.message", 512);
        }

        /** Avoids copying diagnostics into generic record logging. */
        @Override
        public String toString() {
            return "MirrorError[code=" + code + ", type=" + type + "]";
        }
    }
}
