package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeSelectedPopulationVerifierTest {
    private static final Instant CREATED_AT =
            Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant SELECTED_AT =
            Instant.parse("2026-07-02T00:00:00Z");
    private static final Instant ATTESTED_AT =
            Instant.parse("2026-07-03T00:00:00Z");
    private static final Instant ASSESSED_AT =
            Instant.parse("2026-07-04T00:00:00Z");
    private static final Instant VERIFICATION_TIME =
            Instant.parse("2026-07-05T00:00:00Z");

    private AuthoritativeOutcomeSelectedPopulationVerifier verifier;
    private KeyPair keyPair;
    private EvidenceVerificationKey key;
    private ObjectNode bundle;
    private ObjectNode disposition;
    private ObjectNode assessment;
    private ObjectNode sourcePage;

    @BeforeEach
    void setUp() throws Exception {
        verifier =
                new AuthoritativeOutcomeSelectedPopulationVerifier();
        keyPair = KeyPairGenerator.getInstance(
                "Ed25519").generateKeyPair();
        key = new EvidenceVerificationKey(
                TestingProtocol.EVIDENCE_VERIFICATION_KEY_V1,
                "selected-population-key-1",
                "Ed25519",
                Base64.getEncoder().encodeToString(
                        keyPair.getPublic().getEncoded()),
                CREATED_AT,
                "ACTIVE",
                "test");
        bundle = populationBundle();
        disposition = disposition();
        assessment = assessment();
        sourcePage = sourcePage();
    }

    @Test
    void verifiesPopulationDispositionAndCompleteHistoricalAssessmentClosure() {
        AuthoritativeOutcomeSelectedPopulationVerifier
                .VerificationResult populationResult =
                verifier.verifyPopulation(
                        bundle,
                        this::resolve,
                        (manifest, chunks) -> true,
                        VERIFICATION_TIME);
        AuthoritativeOutcomeSelectedPopulationVerifier
                .VerificationResult dispositionResult =
                verifier.verifyDisposition(
                        disposition,
                        this::resolve,
                        candidate -> true,
                        VERIFICATION_TIME);
        AuthoritativeOutcomeSelectedPopulationVerifier
                .VerificationResult assessmentResult =
                verifier.verifyAssessment(
                        assessment,
                        List.of(sourcePage),
                        bundle,
                        this::resolve,
                        (manifest, chunks) -> true,
                        (kind, sourceRef, member) -> true,
                        VERIFICATION_TIME);

        assertThat(populationResult.verified())
                .as(populationResult.reasonCode())
                .isTrue();
        assertThat(dispositionResult.verified())
                .as(dispositionResult.reasonCode())
                .isTrue();
        assertThat(assessmentResult.verified())
                .as(assessmentResult.reasonCode())
                .isTrue();
        assertThat(assessmentResult.populationId())
                .isEqualTo("refund-outcomes");
    }

    @Test
    void rejectsTamperedChunkBeforeCallingSelectionAuthority() {
        ((ObjectNode) bundle.path("chunks")
                .get(0).path("members").get(0))
                .put("subjectFingerprint", fingerprint('f'));
        AtomicBoolean authorityCalled =
                new AtomicBoolean();

        AuthoritativeOutcomeSelectedPopulationVerifier
                .VerificationResult result =
                verifier.verifyPopulation(
                        bundle,
                        this::resolve,
                        (manifest, chunks) -> {
                            authorityCalled.set(true);
                            return true;
                        },
                        VERIFICATION_TIME);

        assertThat(result.reasonCode())
                .isEqualTo(
                        "OUTCOME_POPULATION_CHUNK_FINGERPRINT_INVALID");
        assertThat(authorityCalled).isFalse();
    }

    @Test
    void keepsGatewaySignatureSeparateFromCustomerSelectionAuthority() {
        assertThat(verifier.verifyPopulation(
                bundle,
                this::resolve,
                null,
                VERIFICATION_TIME).outcome())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationVerifier
                                .Outcome.AUTHORITY_UNAVAILABLE);
        assertThat(verifier.verifyPopulation(
                bundle,
                this::resolve,
                (manifest, chunks) -> false,
                VERIFICATION_TIME).reasonCode())
                .isEqualTo(
                        "OUTCOME_POPULATION_AUTHORITY_REJECTED");
    }

    @Test
    void rejectsIncompleteAndSubstitutedAssessmentSourceClosures() {
        sourcePage.put("complete", false);
        addressSourcePage(sourcePage);
        assertThat(verifier.verifyAssessment(
                assessment,
                List.of(sourcePage),
                bundle,
                this::resolve,
                (manifest, chunks) -> true,
                (kind, sourceRef, member) -> true,
                VERIFICATION_TIME).reasonCode())
                .isEqualTo(
                        "OUTCOME_ASSESSMENT_SOURCE_CLOSURE_INCOMPLETE");

        sourcePage = sourcePage();
        ((ObjectNode) sourcePage.path("entries")
                .get(0).path("sourceRef"))
                .put("fingerprint", fingerprint('f'));
        addressSourcePage(sourcePage);
        assertThat(verifier.verifyAssessment(
                assessment,
                List.of(sourcePage),
                bundle,
                this::resolve,
                (manifest, chunks) -> true,
                (kind, sourceRef, member) -> true,
                VERIFICATION_TIME).reasonCode())
                .isEqualTo(
                        "OUTCOME_ASSESSMENT_SOURCE_SET_FINGERPRINT_INVALID");
    }

    @Test
    void rejectsSourceAuthorityOutageAndMemberResolutionFailure() {
        assertThat(verifier.verifyAssessment(
                assessment,
                List.of(sourcePage),
                bundle,
                this::resolve,
                (manifest, chunks) -> true,
                null,
                VERIFICATION_TIME).outcome())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationVerifier
                                .Outcome.AUTHORITY_UNAVAILABLE);
        assertThat(verifier.verifyAssessment(
                assessment,
                List.of(sourcePage),
                bundle,
                this::resolve,
                (manifest, chunks) -> true,
                (kind, sourceRef, member) -> false,
                VERIFICATION_TIME).reasonCode())
                .isEqualTo(
                        "OUTCOME_ASSESSMENT_SOURCE_AUTHORITY_REJECTED");
    }

    @Test
    void rejectsInvalidDispositionSignatureBeforeCallingDeletionAuthority() {
        disposition.withObject("/dispositionSeal")
                .put(
                        "signature",
                        Base64.getEncoder().encodeToString(
                                new byte[64]));
        AtomicBoolean authorityCalled =
                new AtomicBoolean();

        AuthoritativeOutcomeSelectedPopulationVerifier
                .VerificationResult result =
                verifier.verifyDisposition(
                        disposition,
                        this::resolve,
                        candidate -> {
                            authorityCalled.set(true);
                            return true;
                        },
                        VERIFICATION_TIME);

        assertThat(result.reasonCode())
                .isEqualTo(
                        "OUTCOME_SELECTED_POPULATION_SIGNATURE_INVALID");
        assertThat(authorityCalled).isFalse();
    }

    @Test
    void distinguishesUnavailableKeysFromRejectedKeyPolicy() {
        assertThat(verifier.verifyPopulation(
                bundle,
                ignored -> null,
                (manifest, chunks) -> true,
                VERIFICATION_TIME).outcome())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationVerifier
                                .Outcome.KEY_UNAVAILABLE);

        EvidenceVerificationKey disabled =
                new EvidenceVerificationKey(
                        key.schemaVersion(),
                        key.keyId(),
                        key.algorithm(),
                        key.encodedPublicKey(),
                        key.createdAt(),
                        "DISABLED",
                        key.provider());
        assertThat(verifier.verifyPopulation(
                bundle,
                ignored -> disabled,
                (manifest, chunks) -> true,
                VERIFICATION_TIME).outcome())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationVerifier
                                .Outcome.POLICY_REJECTED);
    }

    @Test
    void commandSchemasAdmitUnsignedCandidatesButRejectFieldDrift() {
        ObjectNode populationCommand =
                JsonNodeFactory.instance.objectNode();
        populationCommand.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_ADMISSION_REQUEST_V1);
        populationCommand.put(
                "expectedPredecessorFingerprint", "");
        ObjectNode unsignedManifest =
                ((ObjectNode) bundle.path("manifest"))
                        .deepCopy();
        unsignedManifest.put("manifestFingerprint", "");
        unsignedManifest.set("manifestSeal", unsignedSeal());
        populationCommand.set("manifest", unsignedManifest);
        populationCommand.set(
                "chunks", bundle.path("chunks").deepCopy());

        ObjectNode dispositionCommand =
                JsonNodeFactory.instance.objectNode();
        dispositionCommand.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_DISPOSITION_ADMISSION_REQUEST_V1);
        dispositionCommand.put(
                "expectedPredecessorFingerprint", "");
        ObjectNode unsignedDisposition =
                disposition.deepCopy();
        unsignedDisposition.put(
                "dispositionFingerprint", "");
        unsignedDisposition.set(
                "dispositionSeal", unsignedSeal());
        dispositionCommand.set(
                "disposition", unsignedDisposition);

        assertThatNoException().isThrownBy(() -> {
            CapabilityMirrorSchemaValidator.require(
                    populationCommand,
                    CapabilityMirrorProtocol
                            .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_ADMISSION_REQUEST_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.TEST_INVALID");
            CapabilityMirrorSchemaValidator.require(
                    dispositionCommand,
                    CapabilityMirrorProtocol
                            .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_DISPOSITION_ADMISSION_REQUEST_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.TEST_INVALID");
        });

        populationCommand.put("drift", true);
        assertThatThrownBy(() ->
                CapabilityMirrorSchemaValidator.require(
                        populationCommand,
                        CapabilityMirrorProtocol
                                .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_ADMISSION_REQUEST_SCHEMA_RESOURCE,
                        "RG.MIRROR.CLIENT.TEST_INVALID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "RG.MIRROR.CLIENT.TEST_INVALID");
    }

    private ObjectNode populationBundle() throws Exception {
        ObjectNode chunk =
                JsonNodeFactory.instance.objectNode();
        chunk.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_CHUNK_V1);
        chunk.put("chunkId", "refund-outcomes-chunk-0");
        chunk.put("chunkFingerprint", "");
        chunk.put("populationId", "refund-outcomes");
        chunk.put("populationRevision", 1);
        chunk.set("scope", scope());
        chunk.set(
                "inventoryRef",
                ref("DOMAIN_FIDELITY_INVENTORY", "refund-inventory", 'a'));
        chunk.set(
                "cohortRef",
                ref("OUTCOME_CALIBRATION_COHORT", "refund-cohort", 'b'));
        chunk.set(
                "samplingFrameRef",
                ref("OUTCOME_SAMPLING_FRAME", "refund-frame", 'c'));
        chunk.put("selectedAt", SELECTED_AT.toString());
        chunk.put("chunkIndex", 0);
        chunk.put("firstGlobalOrdinal", 1);
        ArrayNode members = chunk.putArray("members");
        members.add(member(1, 1, 'a', 'b', 'c'));
        members.add(member(2, 2, 'd', 'e', 'f'));
        chunk.put(
                "chunkFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        chunk,
                        AuthoritativeOutcomeSelectedPopulationVerifier
                                .MAXIMUM_CHUNK_BYTES));

        ObjectNode manifest =
                JsonNodeFactory.instance.objectNode();
        manifest.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_MANIFEST_V1);
        manifest.put("populationId", "refund-outcomes");
        manifest.put("revision", 1);
        manifest.put("manifestFingerprint", "");
        manifest.set("scope", scope());
        manifest.set(
                "inventoryRef",
                ref("DOMAIN_FIDELITY_INVENTORY", "refund-inventory", 'a'));
        manifest.set(
                "cohortRef",
                ref("OUTCOME_CALIBRATION_COHORT", "refund-cohort", 'b'));
        manifest.set(
                "samplingFrameRef",
                ref("OUTCOME_SAMPLING_FRAME", "refund-frame", 'c'));
        manifest.set(
                "selectionPolicyRef",
                ref("OUTCOME_SELECTION_POLICY", "refund-policy", 'd'));
        manifest.set(
                "selectionAuthoritySetRef",
                ref(
                        "OUTCOME_SELECTION_AUTHORITY_SET",
                        "refund-selection-authorities",
                        'e'));
        manifest.set(
                "selectionAttestationRef",
                ref(
                        "OUTCOME_SELECTION_ATTESTATION",
                        "refund-selection-attestation",
                        'f'));
        manifest.put("selectedAt", SELECTED_AT.toString());
        ObjectNode stratum =
                manifest.putArray("strata")
                        .addObject();
        stratum.put("unitId", "refund-unit");
        stratum.put("stratumId", "approved-refunds");
        stratum.put("eligiblePopulationSize", 10);
        stratum.put("selectedPopulationSize", 2);
        stratum.put("selectionMode", "HASH_PARTITION");
        ObjectNode descriptor =
                manifest.putArray("chunks")
                        .addObject();
        descriptor.put("chunkIndex", 0);
        descriptor.set(
                "chunkRef",
                ref(
                        "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_CHUNK",
                        "refund-outcomes-chunk-0",
                        1,
                        chunk.path("chunkFingerprint").textValue()));
        descriptor.put("firstGlobalOrdinal", 1);
        descriptor.put("lastGlobalOrdinal", 2);
        descriptor.put("memberCount", 2);
        manifest.put("totalEligiblePopulation", 10);
        manifest.put("totalSelectedPopulation", 2);
        manifest.put("attestedAt", ATTESTED_AT.toString());
        manifest.set("manifestSeal", unsignedSeal());
        manifest.put(
                "manifestFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        manifest,
                        AuthoritativeOutcomeSelectedPopulationVerifier
                                .MAXIMUM_MANIFEST_BYTES));
        seal(
                manifest,
                "manifestSeal",
                AuthoritativeOutcomeSelectedPopulationVerifier
                        .populationAttestation(manifest),
                ATTESTED_AT.plusSeconds(1));

        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_BUNDLE_V1);
        value.set("manifest", manifest);
        value.putArray("chunks").add(chunk);
        value.put("predecessorFingerprint", "");
        return value;
    }

    private ObjectNode disposition() throws Exception {
        JsonNode second = bundle.path("chunks")
                .get(0).path("members").get(1);
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_DISPOSITION_V1);
        value.put("dispositionId", "deleted-refund-member");
        value.put("revision", 1);
        value.put("dispositionFingerprint", "");
        value.set("scope", scope());
        value.set("populationRef", populationRef());
        value.put("unitId", "refund-unit");
        value.put("stratumId", "approved-refunds");
        value.put("sampleOrdinal", 2);
        value.put(
                "inclusionFingerprint",
                second.path("inclusionFingerprint").textValue());
        value.put(
                "subjectFingerprint",
                second.path("subjectFingerprint").textValue());
        value.put(
                "attributionKeyFingerprint",
                second.path("attributionKeyFingerprint").textValue());
        value.put("disposition", "LEGALLY_DELETED");
        value.put("reason", "REGULATORY_ERASURE");
        value.set(
                "retentionPolicyRef",
                ref(
                        "OUTCOME_DATA_RETENTION_POLICY",
                        "refund-retention",
                        'a'));
        value.set(
                "deletionApprovalRef",
                ref(
                        "OUTCOME_MEMBER_DELETION_APPROVAL",
                        "refund-deletion-approval",
                        'b'));
        value.set(
                "deletionAuthoritySetRef",
                ref(
                        "OUTCOME_DELETION_AUTHORITY_SET",
                        "refund-deletion-authorities",
                        'c'));
        value.put(
                "effectiveAt",
                ATTESTED_AT.minusSeconds(60).toString());
        value.put("attestedAt", ATTESTED_AT.toString());
        value.set("dispositionSeal", unsignedSeal());
        value.put(
                "dispositionFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        value,
                        AuthoritativeOutcomeSelectedPopulationVerifier
                                .MAXIMUM_DISPOSITION_BYTES));
        seal(
                value,
                "dispositionSeal",
                AuthoritativeOutcomeSelectedPopulationVerifier
                        .dispositionAttestation(value),
                ATTESTED_AT.plusSeconds(1));
        return value;
    }

    private ObjectNode assessment() throws Exception {
        ArrayNode observations =
                JsonNodeFactory.instance.arrayNode();
        ObjectNode observation =
                observations.addObject();
        observation.put("globalOrdinal", 1);
        observation.set(
                "reference",
                ref(
                        "AUTHORITATIVE_OUTCOME_OBSERVATION",
                        "refund-observation-1",
                        'd'));
        ArrayNode dispositions =
                JsonNodeFactory.instance.arrayNode();
        ObjectNode deleted = dispositions.addObject();
        deleted.put("globalOrdinal", 2);
        deleted.set(
                "reference",
                dispositionRef());

        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_COMPLETENESS_ASSESSMENT_V1);
        value.put("assessmentId", "refund-completeness");
        value.put("revision", 1);
        value.put("assessmentFingerprint", "");
        value.set("scope", scope());
        value.set("populationRef", populationRef());
        value.put("assessedAt", ASSESSED_AT.toString());
        value.put(
                "observationSetFingerprint",
                AuthoritativeOutcomeSelectedPopulationVerifier
                        .sourceSetFingerprint(
                                "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_CURRENT_HEAD_SET_V1",
                                populationRef(),
                                observations));
        value.put(
                "dispositionSetFingerprint",
                AuthoritativeOutcomeSelectedPopulationVerifier
                        .sourceSetFingerprint(
                                "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_LEGAL_DISPOSITION_SET_V1",
                                populationRef(),
                                dispositions));
        ObjectNode stratum =
                value.putArray("strata")
                        .addObject();
        stratum.put("unitId", "refund-unit");
        stratum.put("stratumId", "approved-refunds");
        stratum.set(
                "counts",
                counts(2, 1, 0, 0, 0, 0, 1, 0));
        value.set(
                "totals",
                counts(2, 1, 0, 0, 0, 0, 1, 0));
        value.put("submissionComplete", true);
        value.put("terminalComplete", true);
        value.set("assessmentSeal", unsignedSeal());
        value.put(
                "assessmentFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        value,
                        AuthoritativeOutcomeSelectedPopulationVerifier
                                .MAXIMUM_ASSESSMENT_BYTES));
        seal(
                value,
                "assessmentSeal",
                AuthoritativeOutcomeSelectedPopulationVerifier
                        .assessmentAttestation(value),
                ASSESSED_AT.plusSeconds(1));
        return value;
    }

    private ObjectNode sourcePage() {
        ObjectNode page =
                JsonNodeFactory.instance.objectNode();
        page.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_ASSESSMENT_SOURCE_PAGE_V1);
        page.put("pageFingerprint", "");
        page.set("scope", scope());
        page.set(
                "assessmentRef",
                ref(
                        "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_COMPLETENESS",
                        "refund-completeness",
                        1,
                        assessment.path(
                                "assessmentFingerprint").textValue()));
        page.set("populationRef", populationRef());
        page.put("afterGlobalOrdinal", 0);
        page.put("nextGlobalOrdinal", 2);
        page.put("complete", true);
        ArrayNode entries = page.putArray("entries");
        ObjectNode observation = entries.addObject();
        observation.put("globalOrdinal", 1);
        observation.put("sourceKind", "OBSERVATION");
        observation.set(
                "sourceRef",
                ref(
                        "AUTHORITATIVE_OUTCOME_OBSERVATION",
                        "refund-observation-1",
                        'd'));
        ObjectNode deleted = entries.addObject();
        deleted.put("globalOrdinal", 2);
        deleted.put(
                "sourceKind", "LEGAL_DISPOSITION");
        deleted.set("sourceRef", dispositionRef());
        addressSourcePage(page);
        return page;
    }

    private void addressSourcePage(ObjectNode page) {
        page.put("pageFingerprint", "");
        page.put(
                "pageFingerprint",
                EvidenceVerificationSupport.sha256Bounded(
                        page,
                        AuthoritativeOutcomeSelectedPopulationVerifier
                                .MAXIMUM_SOURCE_PAGE_BYTES));
    }

    private void seal(
            ObjectNode value,
            String sealField,
            JsonNode attestation,
            Instant signedAt) throws Exception {
        String material =
                EvidenceVerificationSupport.sha256Bounded(
                        attestation,
                        AuthoritativeOutcomeSelectedPopulationVerifier
                                .MAXIMUM_ATTESTATION_BYTES);
        Signature signer =
                Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(
                material.getBytes(StandardCharsets.UTF_8));
        ObjectNode seal = value.withObject("/" + sealField);
        seal.put("materialFingerprint", material);
        seal.put("algorithm", "Ed25519");
        seal.put("keyId", key.keyId());
        seal.put("signedAt", signedAt.toString());
        seal.put(
                "signature",
                Base64.getEncoder().encodeToString(
                        signer.sign()));
    }

    private EvidenceVerificationKey resolve(String keyId) {
        return key.keyId().equals(keyId) ? key : null;
    }

    private ObjectNode populationRef() {
        JsonNode manifest = bundle.path("manifest");
        return ref(
                "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_MANIFEST",
                "refund-outcomes",
                1,
                manifest.path("manifestFingerprint").textValue());
    }

    private ObjectNode dispositionRef() {
        return ref(
                "AUTHORITATIVE_OUTCOME_SELECTED_POPULATION_DISPOSITION",
                "deleted-refund-member",
                1,
                disposition.path(
                        "dispositionFingerprint").textValue());
    }

    private static ObjectNode member(
            long globalOrdinal,
            long sampleOrdinal,
            char inclusion,
            char subject,
            char attribution) {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put("globalOrdinal", globalOrdinal);
        value.put("unitId", "refund-unit");
        value.put("stratumId", "approved-refunds");
        value.put("sampleOrdinal", sampleOrdinal);
        value.put(
                "inclusionFingerprint",
                fingerprint(inclusion));
        value.put(
                "subjectFingerprint",
                fingerprint(subject));
        value.put(
                "attributionKeyFingerprint",
                fingerprint(attribution));
        return value;
    }

    private static ObjectNode counts(
            long expected,
            long matched,
            long mismatched,
            long pending,
            long censored,
            long conflicting,
            long legallyDeleted,
            long missing) {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put("expected", expected);
        value.put("matched", matched);
        value.put("mismatched", mismatched);
        value.put("pending", pending);
        value.put("censored", censored);
        value.put("conflicting", conflicting);
        value.put("legallyDeleted", legallyDeleted);
        value.put("missing", missing);
        return value;
    }

    private static ObjectNode scope() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put("tenantId", "tenant-a");
        value.put("organizationId", "org-a");
        value.put("projectId", "refunds");
        value.put("environmentId", "staging");
        value.put("region", "sg");
        return value;
    }

    private static ObjectNode ref(
            String kind,
            String id,
            char material) {
        return ref(kind, id, 1, fingerprint(material));
    }

    private static ObjectNode ref(
            String kind,
            String id,
            long revision,
            String fingerprint) {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
        value.put("kind", kind);
        value.put("id", id);
        value.put("revision", revision);
        value.put("fingerprint", fingerprint);
        return value;
    }

    private static ObjectNode unsignedSeal() {
        ObjectNode value =
                JsonNodeFactory.instance.objectNode();
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

    private static String fingerprint(char material) {
        char safe = Character.toLowerCase(material);
        if (safe < 'a' || safe > 'f') {
            safe = 'a';
        }
        return "sha256:" + String.valueOf(safe).repeat(64);
    }
}
