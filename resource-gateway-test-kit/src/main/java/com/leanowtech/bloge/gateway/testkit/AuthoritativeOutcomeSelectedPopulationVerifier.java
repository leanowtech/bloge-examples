package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Independent verifier for selected authoritative-outcome populations and completeness evidence.
 *
 * <p>The verifier links neither Spring nor Resource Gateway server classes. It verifies packaged
 * strict Schemas, all content addresses, exact chunk/member closure, denominator arithmetic,
 * detached Resource Gateway Ed25519 seals, historical assessment-source pagination, and source-set
 * fingerprints before invoking caller-owned selection, deletion, or source authorities. Invalid
 * input therefore cannot amplify traffic to customer governance systems.</p>
 */
public final class AuthoritativeOutcomeSelectedPopulationVerifier {
    /** Maximum canonical population root bytes. */
    public static final int MAXIMUM_MANIFEST_BYTES = 8 * 1024 * 1024;
    /** Maximum canonical member-chunk bytes. */
    public static final int MAXIMUM_CHUNK_BYTES = 4 * 1024 * 1024;
    /** Maximum canonical disposition bytes. */
    public static final int MAXIMUM_DISPOSITION_BYTES = 256 * 1024;
    /** Maximum canonical completeness assessment bytes. */
    public static final int MAXIMUM_ASSESSMENT_BYTES = 8 * 1024 * 1024;
    /** Maximum canonical assessment source-page bytes. */
    public static final int MAXIMUM_SOURCE_PAGE_BYTES = 2 * 1024 * 1024;
    /** Maximum canonical source-set bytes. */
    public static final int MAXIMUM_SOURCE_SET_BYTES = 32 * 1024 * 1024;
    /** Maximum domain-separated signing-material bytes. */
    public static final int MAXIMUM_ATTESTATION_BYTES = 16 * 1024;

    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(2);
    private static final String POPULATION_KIND =
            "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_MANIFEST";
    private static final String CHUNK_KIND =
            "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_CHUNK";
    private static final String ASSESSMENT_KIND =
            "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_COMPLETENESS";
    private static final String OBSERVATION_KIND =
            "AUTHORITATIVE_OUTCOME_OBSERVATION";
    private static final String DISPOSITION_KIND =
            "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_DISPOSITION";

    /** Creates a dependency-light verifier using packaged Schemas and caller-owned trust. */
    public AuthoritativeOutcomeSelectedPopulationVerifier() {
    }

    /** Closed verification outcomes safe for automation. */
    public enum Outcome {
        /** Every local and external verification step passed. */
        VERIFIED,
        /** Structure, closure, content address, signature, or external evidence is invalid. */
        INVALID,
        /** A required customer-owned authority verifier was not supplied. */
        AUTHORITY_UNAVAILABLE,
        /** A Resource Gateway verification key could not be resolved. */
        KEY_UNAVAILABLE,
        /** Key lifecycle, algorithm, or trusted-time policy rejected the artifact. */
        POLICY_REJECTED
    }

    /** Source kinds admitted to an assessment's historical closure. */
    public enum SourceKind {
        /** Exact immutable authoritative outcome observation. */
        OBSERVATION,
        /** Exact immutable independently authorized legal disposition. */
        LEGAL_DISPOSITION
    }

    /** Resolves the exact Resource Gateway verification key named by a detached seal. */
    @FunctionalInterface
    public interface VerificationKeyResolver {
        /**
         * Resolves one key without falling back to a current key.
         *
         * @param keyId exact seal key identity
         * @return exact historical key, or {@code null} when unavailable
         */
        EvidenceVerificationKey resolve(String keyId);
    }

    /** Verifies the complete customer-owned selection authority closure. */
    @FunctionalInterface
    public interface PopulationAuthorityVerifier {
        /**
         * Verifies one locally valid immutable population.
         *
         * @param manifest defensive copy of the population root
         * @param chunks defensive copies of every ordered member chunk
         * @return true only when the exact external selection closure is trusted
         */
        boolean verify(JsonNode manifest, List<JsonNode> chunks);
    }

    /** Verifies the customer-owned deletion approval and authority closure. */
    @FunctionalInterface
    public interface DispositionAuthorityVerifier {
        /**
         * Verifies one locally valid legal disposition.
         *
         * @param disposition defensive copy of the disposition
         * @return true only when its exact external legal closure is trusted
         */
        boolean verify(JsonNode disposition);
    }

    /** Resolves and verifies each immutable source referenced by a completeness assessment. */
    @FunctionalInterface
    public interface AssessmentSourceVerifier {
        /**
         * Verifies one source against the exact selected member it resolves.
         *
         * @param sourceKind observation or legal disposition
         * @param sourceRef defensive copy of the immutable source reference
         * @param selectedMember defensive copy of the selected member coordinate
         * @return true only when the source exists, verifies, and resolves this member
         */
        boolean verify(
                SourceKind sourceKind,
                JsonNode sourceRef,
                JsonNode selectedMember);
    }

    /**
     * Payload-free verification result.
     *
     * @param outcome bounded outcome
     * @param reasonCode stable machine-readable reason
     * @param artifactId artifact identity when structurally available
     * @param artifactFingerprint artifact content address when structurally available
     * @param populationId population identity when structurally available
     * @param revision immutable artifact revision when structurally available
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String artifactId,
            String artifactFingerprint,
            String populationId,
            long revision
    ) {
        /** Normalizes one bounded, payload-free result. */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = bounded(reasonCode, 255);
            artifactId = bounded(artifactId, 512);
            artifactFingerprint = bounded(
                    artifactFingerprint, 128);
            populationId = bounded(populationId, 512);
            revision = Math.max(0, revision);
            if (!reasonCode.matches("[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "Selected-population verification result is invalid");
            }
        }

        /**
         * Reports whether every local and external verification step passed.
         *
         * @return true only for {@link Outcome#VERIFIED}
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Verifies one complete selected-population bundle at the caller's trusted current time.
     *
     * @param bundle complete immutable population bundle
     * @param keys exact historical Resource Gateway key resolver
     * @param authority external selection authority verifier
     * @param verificationTime caller-owned trusted current time
     * @return bounded payload-free result
     */
    public VerificationResult verifyPopulation(
            JsonNode bundle,
            VerificationKeyResolver keys,
            PopulationAuthorityVerifier authority,
            Instant verificationTime) {
        Coordinates coordinates = Coordinates.population(bundle);
        try {
            requireTime(verificationTime);
            CapabilityMirrorSchemaValidator.require(
                    bundle,
                    CapabilityMirrorProtocol
                            .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_BUNDLE_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.OUTCOME_POPULATION_BUNDLE_SCHEMA_INVALID");
            JsonNode manifest = bundle.path("manifest");
            List<JsonNode> chunks = nodes(bundle.path("chunks"));
            verifyPopulationClosure(manifest, chunks);
            verifySeal(
                    manifest,
                    "manifestFingerprint",
                    "manifestSeal",
                    "attestedAt",
                    populationAttestation(manifest),
                    keys,
                    verificationTime);
            if (authority == null) {
                throw new AuthorityUnavailable(
                        "OUTCOME_POPULATION_AUTHORITY_UNAVAILABLE");
            }
            if (!authority.verify(
                    manifest.deepCopy(),
                    defensive(chunks))) {
                fail("OUTCOME_POPULATION_AUTHORITY_REJECTED");
            }
            return result(Outcome.VERIFIED, "VERIFIED", coordinates);
        } catch (VerificationFailure failure) {
            return result(Outcome.INVALID, failure.reasonCode, coordinates);
        } catch (AuthorityUnavailable failure) {
            return result(
                    Outcome.AUTHORITY_UNAVAILABLE,
                    failure.reasonCode,
                    coordinates);
        } catch (KeyUnavailable failure) {
            return result(
                    Outcome.KEY_UNAVAILABLE,
                    failure.reasonCode,
                    coordinates);
        } catch (PolicyFailure failure) {
            return result(
                    Outcome.POLICY_REJECTED,
                    failure.reasonCode,
                    coordinates);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "OUTCOME_POPULATION_CLOSURE_INVALID",
                    coordinates);
        }
    }

    /**
     * Verifies one signed legal disposition and both trust boundaries.
     *
     * @param disposition signed immutable legal disposition
     * @param keys exact historical Resource Gateway key resolver
     * @param authority external deletion authority verifier
     * @param verificationTime caller-owned trusted current time
     * @return bounded payload-free result
     */
    public VerificationResult verifyDisposition(
            JsonNode disposition,
            VerificationKeyResolver keys,
            DispositionAuthorityVerifier authority,
            Instant verificationTime) {
        Coordinates coordinates =
                Coordinates.disposition(disposition);
        try {
            requireTime(verificationTime);
            CapabilityMirrorSchemaValidator.require(
                    disposition,
                    CapabilityMirrorProtocol
                            .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_DISPOSITION_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.OUTCOME_DISPOSITION_SCHEMA_INVALID");
            Instant effectiveAt = instant(
                    disposition.path("effectiveAt"),
                    "OUTCOME_DISPOSITION_TIME_INVALID");
            Instant attestedAt = instant(
                    disposition.path("attestedAt"),
                    "OUTCOME_DISPOSITION_TIME_INVALID");
            if (attestedAt.isBefore(effectiveAt)
                    || !POPULATION_KIND.equals(text(
                    disposition.path("populationRef"), "kind"))
                    || !"OUTCOME_DATA_RETENTION_POLICY".equals(text(
                    disposition.path("retentionPolicyRef"), "kind"))
                    || !"OUTCOME_MEMBER_DELETION_APPROVAL".equals(text(
                    disposition.path("deletionApprovalRef"), "kind"))
                    || !"OUTCOME_DELETION_AUTHORITY_SET".equals(text(
                    disposition.path("deletionAuthoritySetRef"), "kind"))) {
                fail("OUTCOME_DISPOSITION_CLOSURE_INVALID");
            }
            verifyAddress(
                    disposition,
                    "dispositionFingerprint",
                    "dispositionSeal",
                    MAXIMUM_DISPOSITION_BYTES,
                    "OUTCOME_DISPOSITION_FINGERPRINT_INVALID");
            verifySeal(
                    disposition,
                    "dispositionFingerprint",
                    "dispositionSeal",
                    "attestedAt",
                    dispositionAttestation(disposition),
                    keys,
                    verificationTime);
            if (authority == null) {
                throw new AuthorityUnavailable(
                        "OUTCOME_DISPOSITION_AUTHORITY_UNAVAILABLE");
            }
            if (!authority.verify(disposition.deepCopy())) {
                fail("OUTCOME_DISPOSITION_AUTHORITY_REJECTED");
            }
            return result(Outcome.VERIFIED, "VERIFIED", coordinates);
        } catch (VerificationFailure failure) {
            return result(Outcome.INVALID, failure.reasonCode, coordinates);
        } catch (AuthorityUnavailable failure) {
            return result(
                    Outcome.AUTHORITY_UNAVAILABLE,
                    failure.reasonCode,
                    coordinates);
        } catch (KeyUnavailable failure) {
            return result(
                    Outcome.KEY_UNAVAILABLE,
                    failure.reasonCode,
                    coordinates);
        } catch (PolicyFailure failure) {
            return result(
                    Outcome.POLICY_REJECTED,
                    failure.reasonCode,
                    coordinates);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "OUTCOME_DISPOSITION_CLOSURE_INVALID",
                    coordinates);
        }
    }

    /**
     * Verifies a signed assessment, its denominator, and its complete historical source closure.
     *
     * <p>The caller must supply all pages from cursor zero through the page carrying
     * {@code complete=true}. The verifier rejects suffix-only views because they cannot prove the
     * assessment's source-set fingerprints.</p>
     *
     * @param assessment signed completeness assessment
     * @param sourcePages complete ordered source-page sequence
     * @param populationBundle complete immutable denominator
     * @param keys exact historical Resource Gateway key resolver
     * @param populationAuthority external selection authority verifier
     * @param sourceAuthority exact immutable observation/disposition resolver
     * @param verificationTime caller-owned trusted current time
     * @return bounded payload-free result
     */
    public VerificationResult verifyAssessment(
            JsonNode assessment,
            List<? extends JsonNode> sourcePages,
            JsonNode populationBundle,
            VerificationKeyResolver keys,
            PopulationAuthorityVerifier populationAuthority,
            AssessmentSourceVerifier sourceAuthority,
            Instant verificationTime) {
        Coordinates coordinates =
                Coordinates.assessment(assessment);
        try {
            requireTime(verificationTime);
            VerificationResult populationResult =
                    verifyPopulation(
                            populationBundle,
                            keys,
                            populationAuthority,
                            verificationTime);
            if (!populationResult.verified()) {
                return result(
                        populationResult.outcome(),
                        populationResult.reasonCode(),
                        coordinates);
            }
            CapabilityMirrorSchemaValidator.require(
                    assessment,
                    CapabilityMirrorProtocol
                            .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_COMPLETENESS_ASSESSMENT_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.OUTCOME_ASSESSMENT_SCHEMA_INVALID");
            verifyAssessmentArithmetic(
                    assessment,
                    populationBundle.path("manifest"));
            verifyAddress(
                    assessment,
                    "assessmentFingerprint",
                    "assessmentSeal",
                    MAXIMUM_ASSESSMENT_BYTES,
                    "OUTCOME_ASSESSMENT_FINGERPRINT_INVALID");
            verifySeal(
                    assessment,
                    "assessmentFingerprint",
                    "assessmentSeal",
                    "assessedAt",
                    assessmentAttestation(assessment),
                    keys,
                    verificationTime);
            if (sourceAuthority == null) {
                throw new AuthorityUnavailable(
                        "OUTCOME_ASSESSMENT_SOURCE_AUTHORITY_UNAVAILABLE");
            }
            verifySourceClosure(
                    assessment,
                    sourcePages,
                    populationBundle,
                    sourceAuthority);
            return result(Outcome.VERIFIED, "VERIFIED", coordinates);
        } catch (VerificationFailure failure) {
            return result(Outcome.INVALID, failure.reasonCode, coordinates);
        } catch (AuthorityUnavailable failure) {
            return result(
                    Outcome.AUTHORITY_UNAVAILABLE,
                    failure.reasonCode,
                    coordinates);
        } catch (KeyUnavailable failure) {
            return result(
                    Outcome.KEY_UNAVAILABLE,
                    failure.reasonCode,
                    coordinates);
        } catch (PolicyFailure failure) {
            return result(
                    Outcome.POLICY_REJECTED,
                    failure.reasonCode,
                    coordinates);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "OUTCOME_ASSESSMENT_CLOSURE_INVALID",
                    coordinates);
        }
    }

    private static void verifyPopulationClosure(
            JsonNode manifest,
            List<JsonNode> chunks) {
        CapabilityMirrorSchemaValidator.require(
                manifest,
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_MANIFEST_SCHEMA_RESOURCE,
                "RG.MIRROR.CLIENT.OUTCOME_POPULATION_MANIFEST_SCHEMA_INVALID");
        verifyAddress(
                manifest,
                "manifestFingerprint",
                "manifestSeal",
                MAXIMUM_MANIFEST_BYTES,
                "OUTCOME_POPULATION_MANIFEST_FINGERPRINT_INVALID");
        if (!"DOMAIN_FIDELITY_INVENTORY".equals(
                text(manifest.path("inventoryRef"), "kind"))
                || !"OUTCOME_CALIBRATION_COHORT".equals(
                text(manifest.path("cohortRef"), "kind"))
                || !"OUTCOME_SAMPLING_FRAME".equals(
                text(manifest.path("samplingFrameRef"), "kind"))
                || !"OUTCOME_SELECTION_POLICY".equals(
                text(manifest.path("selectionPolicyRef"), "kind"))
                || !"OUTCOME_SELECTION_AUTHORITY_SET".equals(
                text(manifest.path("selectionAuthoritySetRef"), "kind"))
                || !"OUTCOME_SELECTION_ATTESTATION".equals(
                text(manifest.path("selectionAttestationRef"), "kind"))
                || instant(
                manifest.path("attestedAt"),
                "OUTCOME_POPULATION_TIME_INVALID").isBefore(
                instant(
                        manifest.path("selectedAt"),
                        "OUTCOME_POPULATION_TIME_INVALID"))) {
            fail("OUTCOME_POPULATION_ROOT_CLOSURE_INVALID");
        }
        Map<String, Long> expected = verifyStrata(manifest);
        JsonNode descriptors = manifest.path("chunks");
        if (chunks.size() != descriptors.size()) {
            fail("OUTCOME_POPULATION_CHUNK_CLOSURE_INVALID");
        }
        Map<String, Long> actual = new HashMap<>();
        Set<String> positions = new HashSet<>();
        Set<String> inclusions = new HashSet<>();
        Set<String> attributions = new HashSet<>();
        long nextGlobalOrdinal = 1;
        for (int index = 0; index < chunks.size(); index++) {
            JsonNode chunk = chunks.get(index);
            JsonNode descriptor = descriptors.get(index);
            CapabilityMirrorSchemaValidator.require(
                    chunk,
                    CapabilityMirrorProtocol
                            .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_CHUNK_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.OUTCOME_POPULATION_CHUNK_SCHEMA_INVALID");
            verifySimpleAddress(
                    chunk,
                    "chunkFingerprint",
                    MAXIMUM_CHUNK_BYTES,
                    "OUTCOME_POPULATION_CHUNK_FINGERPRINT_INVALID");
            long first = chunk.path("firstGlobalOrdinal").asLong(-1);
            JsonNode members = chunk.path("members");
            long last = first + members.size() - 1L;
            if (chunk.path("chunkIndex").asInt(-1) != index
                    || descriptor.path("chunkIndex").asInt(-1) != index
                    || descriptor.path("firstGlobalOrdinal").asLong(-1) != first
                    || descriptor.path("lastGlobalOrdinal").asLong(-1) != last
                    || descriptor.path("memberCount").asInt(-1) != members.size()
                    || first != nextGlobalOrdinal) {
                fail("OUTCOME_POPULATION_CHUNK_CURSOR_INVALID");
            }
            JsonNode expectedChunkRef = artifactRef(
                    CHUNK_KIND,
                    text(chunk, "chunkId"),
                    1,
                    text(chunk, "chunkFingerprint"));
            if (!sameArtifactReference(
                    expectedChunkRef,
                    descriptor.path("chunkRef"))) {
                fail("OUTCOME_POPULATION_CHUNK_REFERENCE_INVALID");
            }
            if (!samePopulationCoordinates(manifest, chunk)) {
                fail("OUTCOME_POPULATION_CHUNK_COORDINATE_INVALID");
            }
            for (int memberIndex = 0;
                 memberIndex < members.size();
                 memberIndex++) {
                JsonNode member = members.get(memberIndex);
                long global = member.path("globalOrdinal").asLong(-1);
                String key = stratumKey(member);
                String position = key + "\u0000"
                        + member.path("sampleOrdinal").asLong(-1);
                if (global != first + memberIndex
                        || !positions.add(position)
                        || !inclusions.add(text(
                        member, "inclusionFingerprint"))
                        || !attributions.add(text(
                        member, "attributionKeyFingerprint"))) {
                    fail("OUTCOME_POPULATION_MEMBER_EQUIVOCATION");
                }
                actual.merge(key, 1L, Math::addExact);
            }
            nextGlobalOrdinal = last + 1;
        }
        if (!expected.equals(actual)
                || nextGlobalOrdinal
                != manifest.path("totalSelectedPopulation").asLong() + 1) {
            fail("OUTCOME_POPULATION_DENOMINATOR_INVALID");
        }
        for (JsonNode chunk : chunks) {
            for (JsonNode member : chunk.path("members")) {
                Long bound = expected.get(stratumKey(member));
                if (bound == null
                        || member.path("sampleOrdinal").asLong() > bound) {
                    fail("OUTCOME_POPULATION_DENOMINATOR_INVALID");
                }
            }
        }
    }

    private static Map<String, Long> verifyStrata(
            JsonNode manifest) {
        Map<String, Long> expected = new HashMap<>();
        String previous = null;
        long eligible = 0;
        long selected = 0;
        for (JsonNode stratum : manifest.path("strata")) {
            String key = stratumKey(stratum);
            long stratumEligible =
                    stratum.path("eligiblePopulationSize").asLong(-1);
            long stratumSelected =
                    stratum.path("selectedPopulationSize").asLong(-1);
            if (previous != null && previous.compareTo(key) >= 0
                    || expected.put(key, stratumSelected) != null
                    || stratumSelected > stratumEligible
                    || "CENSUS".equals(text(
                    stratum, "selectionMode"))
                    && stratumSelected != stratumEligible) {
                fail("OUTCOME_POPULATION_DENOMINATOR_INVALID");
            }
            eligible = Math.addExact(eligible, stratumEligible);
            selected = Math.addExact(selected, stratumSelected);
            previous = key;
        }
        if (eligible
                != manifest.path("totalEligiblePopulation").asLong(-1)
                || selected
                != manifest.path("totalSelectedPopulation").asLong(-1)) {
            fail("OUTCOME_POPULATION_DENOMINATOR_INVALID");
        }
        return expected;
    }

    private static boolean samePopulationCoordinates(
            JsonNode manifest,
            JsonNode chunk) {
        return text(manifest, "populationId").equals(
                text(chunk, "populationId"))
                && manifest.path("revision").asLong()
                == chunk.path("populationRevision").asLong()
                && manifest.path("scope").equals(
                chunk.path("scope"))
                && manifest.path("inventoryRef").equals(
                chunk.path("inventoryRef"))
                && manifest.path("cohortRef").equals(
                chunk.path("cohortRef"))
                && manifest.path("samplingFrameRef").equals(
                chunk.path("samplingFrameRef"))
                && manifest.path("selectedAt").equals(
                chunk.path("selectedAt"));
    }

    private static void verifyAssessmentArithmetic(
            JsonNode assessment,
            JsonNode manifest) {
        JsonNode expectedPopulationRef = artifactRef(
                POPULATION_KIND,
                text(manifest, "populationId"),
                manifest.path("revision").asLong(),
                text(manifest, "manifestFingerprint"));
        if (!sameArtifactReference(
                expectedPopulationRef,
                assessment.path("populationRef"))
                || !assessment.path("scope").equals(
                manifest.path("scope"))
                || instant(
                assessment.path("assessedAt"),
                "OUTCOME_ASSESSMENT_TIME_INVALID").isBefore(
                instant(
                        manifest.path("attestedAt"),
                        "OUTCOME_ASSESSMENT_TIME_INVALID"))) {
            fail("OUTCOME_ASSESSMENT_POPULATION_MISMATCH");
        }
        JsonNode strata = assessment.path("strata");
        if (strata.size() != manifest.path("strata").size()) {
            fail("OUTCOME_ASSESSMENT_DENOMINATOR_INVALID");
        }
        long[] aggregate = new long[8];
        String previous = null;
        for (int index = 0; index < strata.size(); index++) {
            JsonNode stratum = strata.get(index);
            JsonNode denominator = manifest.path("strata").get(index);
            String key = stratumKey(stratum);
            if (previous != null && previous.compareTo(key) >= 0
                    || !key.equals(stratumKey(denominator))) {
                fail("OUTCOME_ASSESSMENT_DENOMINATOR_INVALID");
            }
            long[] counts = counts(stratum.path("counts"));
            if (counts[0]
                    != denominator.path(
                    "selectedPopulationSize").asLong()) {
                fail("OUTCOME_ASSESSMENT_DENOMINATOR_INVALID");
            }
            for (int position = 0;
                 position < aggregate.length;
                 position++) {
                aggregate[position] =
                        Math.addExact(
                                aggregate[position],
                                counts[position]);
            }
            previous = key;
        }
        long[] totals = counts(assessment.path("totals"));
        for (int index = 0; index < totals.length; index++) {
            if (totals[index] != aggregate[index]) {
                fail("OUTCOME_ASSESSMENT_TOTALS_INVALID");
            }
        }
        if (totals[0]
                != manifest.path("totalSelectedPopulation").asLong()
                || assessment.path("submissionComplete").asBoolean()
                != (totals[7] == 0)
                || assessment.path("terminalComplete").asBoolean()
                != (totals[7] == 0 && totals[3] == 0)) {
            fail("OUTCOME_ASSESSMENT_TOTALS_INVALID");
        }
    }

    private static long[] counts(JsonNode value) {
        long[] counts = {
                value.path("expected").asLong(-1),
                value.path("matched").asLong(-1),
                value.path("mismatched").asLong(-1),
                value.path("pending").asLong(-1),
                value.path("censored").asLong(-1),
                value.path("conflicting").asLong(-1),
                value.path("legallyDeleted").asLong(-1),
                value.path("missing").asLong(-1)
        };
        long resolved = 0;
        for (int index = 1; index < counts.length; index++) {
            resolved = Math.addExact(resolved, counts[index]);
        }
        if (counts[0] != resolved) {
            fail("OUTCOME_ASSESSMENT_PARTITION_INVALID");
        }
        return counts;
    }

    private static void verifySourceClosure(
            JsonNode assessment,
            List<? extends JsonNode> untrustedPages,
            JsonNode populationBundle,
            AssessmentSourceVerifier sourceAuthority) {
        List<? extends JsonNode> pages = untrustedPages == null
                ? List.of() : List.copyOf(untrustedPages);
        if (pages.isEmpty()) {
            fail("OUTCOME_ASSESSMENT_SOURCE_CLOSURE_INCOMPLETE");
        }
        JsonNode manifest = populationBundle.path("manifest");
        JsonNode populationRef = assessment.path("populationRef");
        JsonNode assessmentRef = artifactRef(
                ASSESSMENT_KIND,
                text(assessment, "assessmentId"),
                assessment.path("revision").asLong(),
                text(assessment, "assessmentFingerprint"));
        Map<Long, JsonNode> members = members(populationBundle);
        ArrayNode observations = JsonNodeFactory.instance.arrayNode();
        ArrayNode dispositions = JsonNodeFactory.instance.arrayNode();
        long cursor = 0;
        boolean complete = false;
        long observationCount = 0;
        long dispositionCount = 0;
        for (JsonNode page : pages) {
            if (complete) {
                fail("OUTCOME_ASSESSMENT_SOURCE_CURSOR_INVALID");
            }
            CapabilityMirrorSchemaValidator.require(
                    page,
                    CapabilityMirrorProtocol
                            .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_ASSESSMENT_SOURCE_PAGE_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.OUTCOME_ASSESSMENT_SOURCE_PAGE_SCHEMA_INVALID");
            verifySimpleAddress(
                    page,
                    "pageFingerprint",
                    MAXIMUM_SOURCE_PAGE_BYTES,
                    "OUTCOME_ASSESSMENT_SOURCE_PAGE_FINGERPRINT_INVALID");
            if (page.path("afterGlobalOrdinal").asLong(-1) != cursor
                    || !page.path("scope").equals(
                    assessment.path("scope"))
                    || !sameArtifactReference(
                    page.path("assessmentRef"),
                    assessmentRef)
                    || !sameArtifactReference(
                    page.path("populationRef"),
                    populationRef)) {
                fail("OUTCOME_ASSESSMENT_SOURCE_CURSOR_INVALID");
            }
            long previous = cursor;
            for (JsonNode entry : page.path("entries")) {
                long ordinal =
                        entry.path("globalOrdinal").asLong(-1);
                JsonNode member = members.get(ordinal);
                SourceKind kind;
                try {
                    kind = SourceKind.valueOf(
                            text(entry, "sourceKind"));
                } catch (IllegalArgumentException invalid) {
                    fail("OUTCOME_ASSESSMENT_SOURCE_KIND_INVALID");
                    return;
                }
                JsonNode reference = entry.path("sourceRef");
                String expectedKind = kind == SourceKind.OBSERVATION
                        ? OBSERVATION_KIND : DISPOSITION_KIND;
                if (ordinal <= previous
                        || member == null
                        || !expectedKind.equals(
                        text(reference, "kind"))) {
                    fail("OUTCOME_ASSESSMENT_SOURCE_CLOSURE_INVALID");
                }
                ObjectNode sourceSetEntry =
                        JsonNodeFactory.instance.objectNode();
                sourceSetEntry.put("globalOrdinal", ordinal);
                sourceSetEntry.set(
                        "reference", reference.deepCopy());
                if (kind == SourceKind.OBSERVATION) {
                    observations.add(sourceSetEntry);
                    observationCount++;
                } else {
                    dispositions.add(sourceSetEntry);
                    dispositionCount++;
                }
                if (!sourceAuthority.verify(
                        kind,
                        reference.deepCopy(),
                        member.deepCopy())) {
                    fail("OUTCOME_ASSESSMENT_SOURCE_AUTHORITY_REJECTED");
                }
                previous = ordinal;
            }
            if (page.path("nextGlobalOrdinal").asLong(-1)
                    != previous
                    || !page.path("complete").asBoolean()
                    && page.path("entries").isEmpty()) {
                fail("OUTCOME_ASSESSMENT_SOURCE_CURSOR_INVALID");
            }
            cursor = previous;
            complete = page.path("complete").asBoolean();
        }
        if (!complete) {
            fail("OUTCOME_ASSESSMENT_SOURCE_CLOSURE_INCOMPLETE");
        }
        JsonNode totals = assessment.path("totals");
        long expectedObservations =
                totals.path("matched").asLong()
                        + totals.path("mismatched").asLong()
                        + totals.path("pending").asLong()
                        + totals.path("censored").asLong()
                        + totals.path("conflicting").asLong();
        if (observationCount != expectedObservations
                || dispositionCount
                != totals.path("legallyDeleted").asLong()
                || observationCount + dispositionCount
                != totals.path("expected").asLong()
                - totals.path("missing").asLong()) {
            fail("OUTCOME_ASSESSMENT_SOURCE_COUNT_INVALID");
        }
        if (!sourceSetFingerprint(
                "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_CURRENT_HEAD_SET_V1",
                populationRef,
                observations).equals(
                text(assessment, "observationSetFingerprint"))
                || !sourceSetFingerprint(
                "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_LEGAL_DISPOSITION_SET_V1",
                populationRef,
                dispositions).equals(
                text(assessment, "dispositionSetFingerprint"))) {
            fail("OUTCOME_ASSESSMENT_SOURCE_SET_FINGERPRINT_INVALID");
        }
        if (!manifest.path("scope").equals(
                assessment.path("scope"))) {
            fail("OUTCOME_ASSESSMENT_SOURCE_CLOSURE_INVALID");
        }
    }

    private static Map<Long, JsonNode> members(
            JsonNode populationBundle) {
        Map<Long, JsonNode> members = new HashMap<>();
        for (JsonNode chunk : populationBundle.path("chunks")) {
            for (JsonNode member : chunk.path("members")) {
                long ordinal =
                        member.path("globalOrdinal").asLong(-1);
                if (members.put(
                        ordinal, member.deepCopy()) != null) {
                    fail("OUTCOME_POPULATION_MEMBER_EQUIVOCATION");
                }
            }
        }
        return members;
    }

    static String sourceSetFingerprint(
            String domain,
            JsonNode populationRef,
            ArrayNode entries) {
        ObjectNode sourceSet =
                JsonNodeFactory.instance.objectNode();
        sourceSet.put("domain", domain);
        sourceSet.set(
                "populationRef", populationRef.deepCopy());
        sourceSet.set("entries", entries.deepCopy());
        return EvidenceVerificationSupport.sha256Bounded(
                sourceSet, MAXIMUM_SOURCE_SET_BYTES);
    }

    private static void verifyAddress(
            JsonNode value,
            String fingerprintField,
            String sealField,
            int maximumBytes,
            String reason) {
        ObjectNode material = object(value);
        material.put(fingerprintField, "");
        material.set(sealField, unsignedSeal());
        if (!EvidenceVerificationSupport.sha256Bounded(
                material, maximumBytes).equals(
                text(value, fingerprintField))) {
            fail(reason);
        }
    }

    private static void verifySimpleAddress(
            JsonNode value,
            String fingerprintField,
            int maximumBytes,
            String reason) {
        ObjectNode material = object(value);
        material.put(fingerprintField, "");
        if (!EvidenceVerificationSupport.sha256Bounded(
                material, maximumBytes).equals(
                text(value, fingerprintField))) {
            fail(reason);
        }
    }

    private static void verifySeal(
            JsonNode artifact,
            String fingerprintField,
            String sealField,
            String attestedAtField,
            JsonNode attestationMaterial,
            VerificationKeyResolver keys,
            Instant verificationTime) {
        JsonNode seal = artifact.path(sealField);
        String keyId = text(seal, "keyId");
        EvidenceVerificationKey key;
        try {
            key = keys == null ? null : keys.resolve(keyId);
        } catch (RuntimeException unavailable) {
            key = null;
        }
        if (key == null) {
            throw new KeyUnavailable(
                    "OUTCOME_SELECTED_POPULATION_KEY_UNAVAILABLE");
        }
        if (!keyId.equals(key.keyId())) {
            fail("OUTCOME_SELECTED_POPULATION_KEY_ID_MISMATCH");
        }
        if (!"Ed25519".equals(key.algorithm())
                || !key.algorithm().equals(
                text(seal, "algorithm"))) {
            throw new PolicyFailure(
                    "OUTCOME_SELECTED_POPULATION_SIGNATURE_ALGORITHM_REJECTED");
        }
        Instant attestedAt = instant(
                artifact.path(attestedAtField),
                "OUTCOME_SELECTED_POPULATION_ATTESTATION_TIME_INVALID");
        Instant signedAt = instant(
                seal.path("signedAt"),
                "OUTCOME_SELECTED_POPULATION_SEAL_TIME_INVALID");
        if (!key.verificationAllowed()
                || attestedAt.isBefore(
                key.createdAt().minus(
                        EvidenceVerificationSupport
                                .KEY_CREATION_SKEW))
                || attestedAt.isAfter(
                verificationTime.plus(
                        MAXIMUM_CLOCK_SKEW))) {
            throw new PolicyFailure(
                    "OUTCOME_SELECTED_POPULATION_KEY_POLICY_REJECTED");
        }
        if (signedAt.isBefore(
                attestedAt.minus(MAXIMUM_CLOCK_SKEW))
                || signedAt.isAfter(
                attestedAt.plus(MAXIMUM_CLOCK_SKEW))) {
            fail("OUTCOME_SELECTED_POPULATION_SEAL_TIME_INVALID");
        }
        try {
            String materialFingerprint =
                    EvidenceVerificationSupport
                            .sha256Bounded(
                                    attestationMaterial,
                                    MAXIMUM_ATTESTATION_BYTES);
            if (!materialFingerprint.equals(
                    text(seal, "materialFingerprint"))
                    || !text(artifact, fingerprintField)
                    .startsWith("sha256:")
                    || !EvidenceVerificationSupport
                    .verifyEd25519(
                            materialFingerprint,
                            text(seal, "signature"),
                            key.encodedPublicKey())) {
                fail("OUTCOME_SELECTED_POPULATION_SIGNATURE_INVALID");
            }
        } catch (GeneralSecurityException
                 | IllegalArgumentException invalid) {
            fail("OUTCOME_SELECTED_POPULATION_SIGNATURE_INVALID");
        }
    }

    static ObjectNode populationAttestation(
            JsonNode manifest) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put(
                "domain",
                "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_V1");
        copy(value, manifest, "schemaVersion", "populationId", "revision");
        value.set(
                "inventoryRef",
                manifest.path("inventoryRef").deepCopy());
        value.set(
                "cohortRef",
                manifest.path("cohortRef").deepCopy());
        value.set(
                "samplingFrameRef",
                manifest.path("samplingFrameRef").deepCopy());
        copy(
                value,
                manifest,
                "selectedAt",
                "attestedAt",
                "manifestFingerprint");
        return value;
    }

    static ObjectNode dispositionAttestation(
            JsonNode disposition) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put(
                "domain",
                "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_MEMBER_DISPOSITION_V1");
        copy(
                value,
                disposition,
                "schemaVersion",
                "dispositionId",
                "revision");
        value.set(
                "populationRef",
                disposition.path("populationRef").deepCopy());
        copy(
                value,
                disposition,
                "unitId",
                "stratumId",
                "sampleOrdinal",
                "effectiveAt",
                "attestedAt",
                "dispositionFingerprint");
        return value;
    }

    static ObjectNode assessmentAttestation(
            JsonNode assessment) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put(
                "domain",
                "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_POPULATION_COMPLETENESS_V1");
        copy(
                value,
                assessment,
                "schemaVersion",
                "assessmentId",
                "revision");
        value.set(
                "populationRef",
                assessment.path("populationRef").deepCopy());
        copy(
                value,
                assessment,
                "assessedAt",
                "observationSetFingerprint",
                "dispositionSetFingerprint",
                "assessmentFingerprint");
        return value;
    }

    private static ObjectNode artifactRef(
            String kind,
            String id,
            long revision,
            String fingerprint) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", revision);
        value.put("fingerprint", fingerprint);
        return value;
    }

    private static boolean sameArtifactReference(
            JsonNode left,
            JsonNode right) {
        return left != null
                && left.isObject()
                && right != null
                && right.isObject()
                && text(left, "kind").equals(
                text(right, "kind"))
                && text(left, "id").equals(
                text(right, "id"))
                && left.path("revision").isIntegralNumber()
                && right.path("revision").isIntegralNumber()
                && left.path("revision").asLong()
                == right.path("revision").asLong()
                && text(left, "fingerprint").equals(
                text(right, "fingerprint"));
    }

    private static ObjectNode unsignedSeal() {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put(
                "schemaVersion",
                "bloge.visualRunEvidenceSeal.v1");
        value.put("materialFingerprint", "");
        value.put("algorithm", "");
        value.put("keyId", "");
        value.put("signedAt", Instant.EPOCH.toString());
        value.put("signature", "");
        return value;
    }

    private static ObjectNode object(JsonNode value) {
        if (value == null || !value.isObject()) {
            fail("OUTCOME_SELECTED_POPULATION_OBJECT_REQUIRED");
        }
        return ((ObjectNode) value).deepCopy();
    }

    private static List<JsonNode> nodes(JsonNode value) {
        if (!value.isArray()) {
            fail("OUTCOME_SELECTED_POPULATION_ARRAY_REQUIRED");
        }
        List<JsonNode> values = new ArrayList<>();
        value.forEach(item -> values.add(item.deepCopy()));
        return List.copyOf(values);
    }

    private static List<JsonNode> defensive(
            List<JsonNode> values) {
        return values.stream()
                .map(value -> (JsonNode) value.deepCopy())
                .toList();
    }

    private static String stratumKey(JsonNode value) {
        return text(value, "unitId") + "\u0000"
                + text(value, "stratumId");
    }

    private static Instant instant(
            JsonNode value,
            String reason) {
        try {
            return Instant.parse(
                    value.isTextual()
                            ? value.textValue() : "");
        } catch (DateTimeParseException invalid) {
            fail(reason);
            return Instant.EPOCH;
        }
    }

    private static void requireTime(Instant value) {
        if (value == null) {
            throw new PolicyFailure(
                    "OUTCOME_SELECTED_POPULATION_VERIFICATION_TIME_INVALID");
        }
    }

    private static String text(
            JsonNode value,
            String field) {
        JsonNode child = value == null
                ? null : value.get(field);
        return child != null && child.isTextual()
                ? child.textValue() : "";
    }

    private static void copy(
            ObjectNode target,
            JsonNode source,
            String... fields) {
        for (String field : fields) {
            target.set(field, source.path(field).deepCopy());
        }
    }

    private static VerificationResult result(
            Outcome outcome,
            String reason,
            Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reason,
                coordinates.artifactId,
                coordinates.artifactFingerprint,
                coordinates.populationId,
                coordinates.revision);
    }

    private static String bounded(
            String value,
            int maximumLength) {
        String exact = value == null ? "" : value.trim();
        return exact.length() <= maximumLength
                ? exact : exact.substring(0, maximumLength);
    }

    private static void fail(String reason) {
        throw new VerificationFailure(reason);
    }

    private record Coordinates(
            String artifactId,
            String artifactFingerprint,
            String populationId,
            long revision
    ) {
        private static Coordinates population(JsonNode bundle) {
            JsonNode manifest = bundle == null
                    ? JsonNodeFactory.instance.objectNode()
                    : bundle.path("manifest");
            return new Coordinates(
                    text(manifest, "populationId"),
                    text(manifest, "manifestFingerprint"),
                    text(manifest, "populationId"),
                    manifest.path("revision").asLong(0));
        }

        private static Coordinates disposition(
                JsonNode disposition) {
            JsonNode value = disposition == null
                    ? JsonNodeFactory.instance.objectNode()
                    : disposition;
            return new Coordinates(
                    text(value, "dispositionId"),
                    text(value, "dispositionFingerprint"),
                    text(value.path("populationRef"), "id"),
                    value.path("revision").asLong(0));
        }

        private static Coordinates assessment(
                JsonNode assessment) {
            JsonNode value = assessment == null
                    ? JsonNodeFactory.instance.objectNode()
                    : assessment;
            return new Coordinates(
                    text(value, "assessmentId"),
                    text(value, "assessmentFingerprint"),
                    text(value.path("populationRef"), "id"),
                    value.path("revision").asLong(0));
        }
    }

    private static final class VerificationFailure
            extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(String reasonCode) {
            this.reasonCode = reasonCode;
        }
    }

    private static final class AuthorityUnavailable
            extends RuntimeException {
        private final String reasonCode;

        private AuthorityUnavailable(String reasonCode) {
            this.reasonCode = reasonCode;
        }
    }

    private static final class KeyUnavailable
            extends RuntimeException {
        private final String reasonCode;

        private KeyUnavailable(String reasonCode) {
            this.reasonCode = reasonCode;
        }
    }

    private static final class PolicyFailure
            extends RuntimeException {
        private final String reasonCode;

        private PolicyFailure(String reasonCode) {
            this.reasonCode = reasonCode;
        }
    }
}
