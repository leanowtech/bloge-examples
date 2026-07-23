package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One bounded source in the fixed-priority capability-mirror resolution chain.
 *
 * <p>A resolver may inspect the current input but must not retain it. It either abstains or returns
 * one declarative rule plus explicit confidence, freshness, and limitations. The chain, rather
 * than an individual resolver, owns cross-source precedence and the terminal abstention.</p>
 */
public interface MirrorResolver {
    /** Canonical payload-free request identity accepted by the resolver boundary. */
    Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** @return the one concrete protocol source implemented by this resolver */
    MirrorPlan.MirrorSource source();

    /**
     * Attempts to resolve one invocation from this source.
     *
     * @param request exact run-scoped invocation and already matched candidate rules
     * @return a governed match, or empty when this source abstains
     */
    Optional<Match> resolve(Request request);

    /**
     * Ephemeral invocation facts supplied to one resolver.
     *
     * @param site exact runtime invocation site including correlation key
     * @param occurrence one-based occurrence at this site and correlation coordinate
     * @param attempt one-based delegate attempt
     * @param requestFingerprint canonical payload-free request identity
     * @param input ephemeral business input; resolvers must not retain or log it
     * @param matchedRules preflight-ordered rules whose selectors match this invocation
     * @param recordedExactCorpus optional site-bound immutable corpus generation
     */
    record Request(
            InvocationSite site,
            int occurrence,
            int attempt,
            String requestFingerprint,
            Object input,
            List<FixtureRule> matchedRules,
            ResolvedCorpusPayloads.CapabilityCorpus recordedExactCorpus
    ) {
        /** Validates exact coordinates and detaches the candidate list. */
        public Request {
            site = Objects.requireNonNull(site, "site");
            if (occurrence < 1 || attempt < 1) {
                throw new IllegalArgumentException("occurrence and attempt must be positive");
            }
            requestFingerprint = required(requestFingerprint, "requestFingerprint");
            if (!FINGERPRINT.matcher(requestFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "requestFingerprint must be a canonical SHA-256 value");
            }
            matchedRules = matchedRules == null ? List.of() : List.copyOf(matchedRules);
        }

        /** Backward-compatible request without a site-bound recorded corpus. */
        public Request(
                InvocationSite site,
                int occurrence,
                int attempt,
                String requestFingerprint,
                Object input,
                List<FixtureRule> matchedRules) {
            this(site, occurrence, attempt, requestFingerprint,
                    input, matchedRules, null);
        }

        /** Prevents ephemeral business input from entering ordinary logs. */
        @Override
        public String toString() {
            return "Request[invocationSiteId=" + site.invocationSiteId()
                    + ", occurrence=" + occurrence
                    + ", attempt=" + attempt
                    + ", requestFingerprint=" + requestFingerprint
                    + ", matchedRules=" + matchedRules.size()
                    + ", recordedExactCorpus=" + (recordedExactCorpus != null) + "]";
        }
    }

    /**
     * One source-local resolution claim.
     *
     * @param rule exact declarative behavior selected by the source
     * @param confidence bounded match confidence and named method
     * @param freshness normalized source freshness in the closed interval [0,1]
     * @param limitations bounded payload-free fidelity or governance limitations
     * @param artifactRefs exact governed artifacts used by this source
     * @param ruleRefs exact source-local rule identities used by this source
     * @param errorDetailsFingerprint exact normalized error-details identity, or blank
     * @param retryableOutcome whether this governed source authorizes BLOGE retry progression
     */
    record Match(
            FixtureRule rule,
            ArtifactProvenance.Confidence confidence,
            double freshness,
            List<String> limitations,
            List<com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef> artifactRefs,
            List<String> ruleRefs,
            String errorDetailsFingerprint,
            boolean retryableOutcome
    ) {
        /** Validates the claim without inferring absent governance facts. */
        public Match {
            rule = Objects.requireNonNull(rule, "rule");
            confidence = Objects.requireNonNull(confidence, "confidence");
            if (!Double.isFinite(freshness) || freshness < 0 || freshness > 1) {
                throw new IllegalArgumentException("freshness must be in the closed interval [0,1]");
            }
            limitations = limitations == null ? List.of() : limitations.stream()
                    .map(value -> bounded(value, "limitation", 512))
                    .distinct().sorted().toList();
            if (limitations.size() > 64) {
                throw new IllegalArgumentException("limitations exceeds its item limit");
            }
            artifactRefs = artifactRefs == null ? List.of() : artifactRefs.stream()
                    .map(value -> Objects.requireNonNull(value, "artifactRef"))
                    .distinct().sorted(java.util.Comparator
                            .comparing(com.leanowtech.bloge.gateway.integration.mirror
                                    .MirrorArtifactRef::kind)
                            .thenComparing(com.leanowtech.bloge.gateway.integration.mirror
                                    .MirrorArtifactRef::id)
                            .thenComparingLong(com.leanowtech.bloge.gateway.integration.mirror
                                    .MirrorArtifactRef::revision)
                            .thenComparing(com.leanowtech.bloge.gateway.integration.mirror
                                    .MirrorArtifactRef::fingerprint))
                    .toList();
            ruleRefs = ruleRefs == null ? List.of() : ruleRefs.stream()
                    .map(value -> bounded(value, "ruleRef", 512))
                    .distinct().sorted().toList();
            if (artifactRefs.size() > 1_000 || ruleRefs.size() > 1_000) {
                throw new IllegalArgumentException(
                        "mirror match provenance exceeds its item limit");
            }
            errorDetailsFingerprint = errorDetailsFingerprint == null
                    ? "" : errorDetailsFingerprint.trim();
            if (!errorDetailsFingerprint.isBlank()
                    && !FINGERPRINT.matcher(errorDetailsFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "errorDetailsFingerprint must be blank or canonical SHA-256");
            }
            if (retryableOutcome
                    && rule.behavior().kind()
                    != FixtureRule.BehaviorKind.THROW) {
                throw new IllegalArgumentException(
                        "only a normalized THROW outcome may advance BLOGE retry");
            }
        }

        /** Backward-compatible exact match whose provenance is derived from its fixture rule. */
        public Match(
                FixtureRule rule,
                ArtifactProvenance.Confidence confidence,
                double freshness,
                List<String> limitations) {
            this(rule, confidence, freshness, limitations,
                    List.of(), List.of(), "", false);
        }

        /** Backward-compatible source match without normalized error-detail provenance. */
        public Match(
                FixtureRule rule,
                ArtifactProvenance.Confidence confidence,
                double freshness,
                List<String> limitations,
                List<com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef>
                        artifactRefs,
                List<String> ruleRefs) {
            this(rule, confidence, freshness, limitations,
                    artifactRefs, ruleRefs, "", false);
        }

        /** Source match with normalized error provenance and no retry authority. */
        public Match(
                FixtureRule rule,
                ArtifactProvenance.Confidence confidence,
                double freshness,
                List<String> limitations,
                List<com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef>
                        artifactRefs,
                List<String> ruleRefs,
                String errorDetailsFingerprint) {
            this(rule, confidence, freshness, limitations,
                    artifactRefs, ruleRefs, errorDetailsFingerprint, false);
        }

        /** Prevents a resolved rule's response value or diagnostic text from entering logs. */
        @Override
        public String toString() {
            return "Match[ruleId=" + rule.ruleId()
                    + ", behavior=" + rule.behavior().kind()
                    + ", confidence=" + confidence.method()
                    + ", freshness=" + freshness
                    + ", limitations=" + limitations.size()
                    + ", artifactRefs=" + artifactRefs.size()
                    + ", ruleRefs=" + ruleRefs.size()
                    + ", errorDetailsFingerprint=" + errorDetailsFingerprint
                    + ", retryableOutcome=" + retryableOutcome + "]";
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String bounded(String value, String field, int maximumLength) {
        String normalized = required(value, field);
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(field + " exceeds its length limit");
        }
        return normalized;
    }
}
