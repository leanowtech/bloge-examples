package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorEvidenceIntegrityServiceTest {
    private static final Instant SIGNED_AT = Instant.parse("2026-07-23T00:00:00Z");
    private static final String RUN_ID = "mirror-run-1";
    private static final String PLAN = fingerprint('1');
    private static final String CLOSURE = fingerprint('2');
    private static final String CONTROL = fingerprint('3');
    private static final String CONTEXT = fingerprint('4');
    private static final String SEMANTIC = fingerprint('5');
    private static final String REQUEST = fingerprint('6');
    private static final String OUTPUT = fingerprint('7');
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant-a", "org-a", "support", "test", "sg");
    private static final MirrorArtifactRef ROOT = new MirrorArtifactRef(
            "CAPABILITY", "graph:support", 7, fingerprint('8'));
    private static final MirrorArtifactRef EXTERNAL = new MirrorArtifactRef(
            "CAPABILITY", "resource:customer.lookup", 4, fingerprint('9'));
    private static final MirrorArtifactRef FIXTURE = new MirrorArtifactRef(
            "FIXTURE_BUNDLE", "support-fixtures", 3, fingerprint('a'));

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
    private final MirrorEvidenceIntegrityService service = new MirrorEvidenceIntegrityService(
            mapper, signer, Clock.fixed(SIGNED_AT, ZoneOffset.UTC));

    @Test
    void sealsAndImmediatelyVerifiesOnePortableTerminalBundle() {
        MirrorEvidenceIntegrityService.SealResult result = service.seal(evidence());

        assertThat(result.verified()).isTrue();
        assertThat(result.failureCode()).isEmpty();
        assertThat(result.bundle().schemaVersion())
                .isEqualTo(MirrorEvidenceBundle.SCHEMA_VERSION);
        assertThat(result.bundle().payloadPolicy())
                .isEqualTo(MirrorEvidenceBundle.PayloadPolicy.HASH_ONLY);
        assertThat(result.attestation().signedAt()).isEqualTo(SIGNED_AT);
        assertThat(result.attestation().independentlyVerifiable()).isTrue();
        assertThat(result.attestation().evidenceFingerprint()).startsWith("sha256:");
        assertThat(result.bundle().bundleFingerprint()).startsWith("sha256:");
        assertThat(service.verify(result.bundle()))
                .isEqualTo(MirrorEvidenceIntegrityService.Verification.VERIFIED);
    }

    @Test
    void serializedBundleContainsOnlyPayloadFingerprints() {
        MirrorEvidenceBundle bundle = service.seal(evidence()).bundle();

        JsonNode value = mapper.valueToTree(bundle);

        assertThat(value.at("/evidence/nodeTraces/0/inputFingerprint").asText())
                .isEqualTo(REQUEST);
        assertThat(value.at("/evidence/nodeTraces/0/outputFingerprint").asText())
                .isEqualTo(OUTPUT);
        assertThat(value.at("/evidence/edgeTraces/0/valueFingerprint").asText())
                .isEqualTo(OUTPUT);
        assertThat(value.at("/evidence/resolutions/0/output").isNull()).isTrue();
        assertThat(value.toString())
                .doesNotContain("customer-payload")
                .doesNotContain("business-request")
                .doesNotContain("business-response");
        assertThat(bundle.toString()).doesNotContain(REQUEST).doesNotContain(OUTPUT);
        assertThat(bundle.evidence().toString()).doesNotContain(REQUEST).doesNotContain(OUTPUT);
    }

    @Test
    void refusesVisibleResolverPayloadsAtTheProtocolBoundary() {
        MirrorResolution visible = MirrorResolutionIntegrity.seal(mapper,
                resolution(MirrorResolution.PayloadVisibility.FULL, true,
                        Map.of("secret", "customer-payload"), ""));

        assertThatThrownBy(() -> copyEvidence(evidence(), null, List.of(visible), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not include resolver payloads");
    }

    @Test
    void detectsAlteredPayloadFingerprintAfterSigning() {
        MirrorEvidenceBundle original = service.seal(evidence()).bundle();
        MirrorRunEvidence alteredEvidence = copyEvidence(original.evidence(),
                node(fingerprint('b')), null, null, null);
        MirrorEvidenceBundle altered = new MirrorEvidenceBundle("",
                original.bundleFingerprint(), original.payloadPolicy(),
                original.attestation(), alteredEvidence);

        assertThat(service.verify(altered))
                .isEqualTo(MirrorEvidenceIntegrityService.Verification.INVALID);
    }

    @Test
    void detectsAlteredNestedResolutionSeal() {
        MirrorEvidenceBundle original = service.seal(evidence()).bundle();
        MirrorResolution sealed = original.evidence().resolutions().getFirst();
        MirrorResolution alteredResolution = sealed.withFingerprints(fingerprint('c'),
                sealed.outputFingerprint());
        MirrorRunEvidence alteredEvidence = copyEvidence(original.evidence(), null,
                List.of(alteredResolution), null, null);
        MirrorEvidenceAttestation alteredAttestation = new MirrorEvidenceAttestation("",
                MirrorEvidenceAttestation.SignatureStatus.VERIFIED,
                original.attestation().runId(), original.attestation().planFingerprint(),
                original.attestation().evidenceFingerprint(), original.attestation().signedAt(),
                original.attestation().keyId(), original.attestation().algorithm(),
                original.attestation().signature(), true);
        MirrorEvidenceBundle altered = new MirrorEvidenceBundle("",
                original.bundleFingerprint(), original.payloadPolicy(),
                alteredAttestation, alteredEvidence);

        assertThat(service.verify(altered))
                .isEqualTo(MirrorEvidenceIntegrityService.Verification.INVALID);
    }

    @Test
    void detectsSignatureTimeAndSignatureTampering() {
        MirrorEvidenceBundle original = service.seal(evidence()).bundle();
        MirrorEvidenceAttestation changedTime = copyAttestation(original.attestation(),
                original.attestation().signedAt().plusSeconds(1),
                original.attestation().signature());
        MirrorEvidenceAttestation changedSignature = copyAttestation(original.attestation(),
                original.attestation().signedAt(), "not-base64");

        assertThat(service.verify(copyBundle(original, changedTime)))
                .isEqualTo(MirrorEvidenceIntegrityService.Verification.INVALID);
        assertThat(service.verify(copyBundle(original, changedSignature)))
                .isEqualTo(MirrorEvidenceIntegrityService.Verification.INVALID);
    }

    @Test
    void refusesToSignBeforeTheRunReachedItsTerminalTime() {
        MirrorEvidenceIntegrityService staleClock = new MirrorEvidenceIntegrityService(
                mapper, signer, Clock.fixed(Instant.parse("2026-07-22T23:59:57Z"),
                ZoneOffset.UTC));

        MirrorEvidenceIntegrityService.SealResult result = staleClock.seal(evidence());

        assertThat(result.verified()).isFalse();
        assertThat(result.failureCode())
                .isEqualTo(MirrorEvidenceIntegrityService.MATERIAL_INVALID);
    }

    @Test
    void failsClosedWhenSigningAuthorityIsUnavailable() {
        MirrorEvidenceIntegrityService unavailable = new MirrorEvidenceIntegrityService(
                mapper, VisualEvidenceSigner.unavailable(),
                Clock.fixed(SIGNED_AT, ZoneOffset.UTC));

        MirrorEvidenceIntegrityService.SealResult result = unavailable.seal(evidence());

        assertThat(result.verified()).isFalse();
        assertThat(result.bundle()).isNull();
        assertThat(result.failureCode())
                .isEqualTo(MirrorEvidenceIntegrityService.SIGNER_UNAVAILABLE);
        assertThat(result.attestation().signatureStatus())
                .isEqualTo(MirrorEvidenceAttestation.SignatureStatus.VERIFICATION_UNAVAILABLE);
    }

    @Test
    void rejectsUnsupportedAlgorithmsAndFalseUnavailableClaims() {
        MirrorEvidenceAttestation verified = service.seal(evidence()).attestation();

        assertThatThrownBy(() -> new MirrorEvidenceAttestation("",
                MirrorEvidenceAttestation.SignatureStatus.VERIFIED, verified.runId(),
                verified.planFingerprint(), verified.evidenceFingerprint(), verified.signedAt(),
                verified.keyId(), "RSA", verified.signature(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ed25519");
        assertThatThrownBy(() -> new MirrorEvidenceAttestation("",
                MirrorEvidenceAttestation.SignatureStatus.VERIFICATION_UNAVAILABLE,
                verified.runId(), verified.planFingerprint(), verified.evidenceFingerprint(),
                Instant.EPOCH, "forged-key", "", "", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without a signature claim");
    }

    @Test
    void normalizesTraceOrderingAndRejectsDuplicateCoordinates() {
        MirrorRunEvidence base = evidence();
        MirrorRunEvidence.NodeTrace second = new MirrorRunEvidence.NodeTrace(
                "format", "customer.format", "SUCCESS", "REAL", REQUEST, OUTPUT, "", 2,
                "/root/format#PRIMARY", "/root", "C-1", 1, 1, List.of());
        MirrorRunEvidence ordered = copyEvidence(base, null, null,
                List.of(second, base.nodeTraces().getFirst()), null);

        assertThat(ordered.nodeTraces()).extracting(MirrorRunEvidence.NodeTrace::nodeId)
                .containsExactly("format", "loadCustomer");
        assertThatThrownBy(() -> copyEvidence(base, null, null,
                List.of(base.nodeTraces().getFirst(), base.nodeTraces().getFirst()), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coordinates must be unique");
    }

    @Test
    void boundsTraceCardinalityBeforeSigning() {
        List<MirrorRunEvidence.NodeTrace> tooMany = Collections.nCopies(
                MirrorRunEvidence.MAXIMUM_TRACE_ITEMS + 1, evidence().nodeTraces().getFirst());

        assertThatThrownBy(() -> copyEvidence(evidence(), null, null, tooMany, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("item limit");
    }

    @Test
    void certificationRequiresDeploymentIsolationProofAndNoLimitations() {
        assertThatThrownBy(() -> copyEvidence(evidence(), null, null, null,
                MirrorRunEvidence.EvidenceClass.CERTIFIABLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proven egress isolation");

        MirrorDeploymentIsolationRunTrust.Binding trustBinding =
                MirrorPersistenceTestFixtures.trustBinding(SCOPE);
        MirrorRunEvidence.IsolationFacts proven = new MirrorRunEvidence.IsolationFacts(
                MirrorRunEvidence.IsolationFacts.EngineMode.INDEPENDENT_TEST_ENGINE,
                List.of(), List.of("InvocationRecorder"), false, false, false,
                false, false, false, true,
                trustBinding.attestationRef(), trustBinding, List.of());
        MirrorRunEvidence.IsolationFacts falselyLimited = new MirrorRunEvidence.IsolationFacts(
                MirrorRunEvidence.IsolationFacts.EngineMode.INDEPENDENT_TEST_ENGINE,
                List.of(), List.of("InvocationRecorder"), false, false, false,
                false, false, false, true,
                trustBinding.attestationRef(), trustBinding,
                List.of("UNPROVEN_CREDENTIAL_SCAN"));
        MirrorRunEvidence base = evidence();
        MirrorRunEvidence certifiable = new MirrorRunEvidence("", base.runId(), base.requestId(),
                base.requestContextFingerprint(), base.planId(), base.planFingerprint(),
                base.capabilityClosureFingerprint(), base.executionControlFingerprint(),
                base.rootCapability(), base.fixtureBundleRef(), base.externalBindings(), base.scope(),
                base.authorizedPurpose(), base.status(), MirrorRunEvidence.EvidenceClass.CERTIFIABLE,
                base.semanticResultFingerprint(), base.startedAt(), base.completedAt(),
                base.nodeTraces(), base.edgeTraces(), base.resolutions(), proven, List.of());

        assertThat(service.seal(certifiable).verified()).isTrue();
        assertThat(certifiable.schemaVersion()).isEqualTo(MirrorRunEvidence.SCHEMA_VERSION);
        assertThat(certifiable.isolation().deploymentTrustBinding())
                .isEqualTo(trustBinding);
        assertThatThrownBy(() -> new MirrorRunEvidence("", base.runId(), base.requestId(),
                base.requestContextFingerprint(), base.planId(), base.planFingerprint(),
                base.capabilityClosureFingerprint(), base.executionControlFingerprint(),
                base.rootCapability(), base.fixtureBundleRef(), base.externalBindings(), base.scope(),
                base.authorizedPurpose(), base.status(), MirrorRunEvidence.EvidenceClass.CERTIFIABLE,
                base.semanticResultFingerprint(), base.startedAt(), base.completedAt(),
                base.nodeTraces(), base.edgeTraces(), base.resolutions(), falselyLimited, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no limitations");
    }

    @Test
    void legacyV1CertificationRemainsReadableAndVerifiableWithoutRunTrustBinding() {
        MirrorRunEvidence base = evidence();
        MirrorRunEvidence.IsolationFacts legacyIsolation =
                new MirrorRunEvidence.IsolationFacts(
                        MirrorRunEvidence.IsolationFacts.EngineMode.INDEPENDENT_TEST_ENGINE,
                        List.of(), List.of("InvocationRecorder"), false, false, false,
                        false, false, false, true,
                        new MirrorArtifactRef("DEPLOYMENT_ISOLATION_ATTESTATION",
                                "legacy-isolation", 1, fingerprint('d')),
                        List.of());
        MirrorRunEvidence legacy = new MirrorRunEvidence(
                MirrorRunEvidence.SCHEMA_VERSION_V1, base.runId(), base.requestId(),
                base.requestContextFingerprint(), base.planId(), base.planFingerprint(),
                base.capabilityClosureFingerprint(), base.executionControlFingerprint(),
                base.rootCapability(), base.fixtureBundleRef(), base.externalBindings(),
                base.scope(), base.authorizedPurpose(), base.status(),
                MirrorRunEvidence.EvidenceClass.CERTIFIABLE,
                base.semanticResultFingerprint(), base.startedAt(), base.completedAt(),
                base.nodeTraces(), base.edgeTraces(), base.resolutions(), legacyIsolation,
                List.of());

        MirrorEvidenceIntegrityService.SealResult sealed = service.seal(legacy);

        assertThat(sealed.verified()).isTrue();
        assertThat(sealed.bundle().schemaVersion())
                .isEqualTo(MirrorEvidenceBundle.SCHEMA_VERSION_V1);
        assertThat(sealed.attestation().schemaVersion())
                .isEqualTo(MirrorEvidenceAttestation.SCHEMA_VERSION_V1);
        assertThat(service.verify(sealed.bundle()))
                .isEqualTo(MirrorEvidenceIntegrityService.Verification.VERIFIED);
    }

    @Test
    void sealsStatefulV3InItsOwnSignatureDomainAndRejectsNestedStateTampering() {
        MirrorEvidenceIntegrityService.SealResult sealed =
                service.seal(statefulEvidence());

        assertThat(sealed.verified()).isTrue();
        assertThat(sealed.bundle().schemaVersion())
                .isEqualTo(
                        MirrorEvidenceBundle.STATEFUL_SCHEMA_VERSION);
        assertThat(sealed.attestation().schemaVersion())
                .isEqualTo(
                        MirrorEvidenceAttestation.STATEFUL_SCHEMA_VERSION);
        assertThat(sealed.evidence().schemaVersion())
                .isEqualTo(
                        MirrorRunEvidence.STATEFUL_SCHEMA_VERSION);
        assertThat(service.verify(sealed.bundle()))
                .isEqualTo(
                        MirrorEvidenceIntegrityService.Verification.VERIFIED);

        MirrorStateRunEvidence alteredState =
                ((MirrorStateRunEvidence) sealed.evidence()
                        .stateEvidence())
                        .withFingerprint(fingerprint('f'));
        MirrorRunEvidence alteredEvidence =
                copyEvidenceWithState(
                        sealed.evidence(), alteredState);
        MirrorEvidenceBundle alteredBundle =
                new MirrorEvidenceBundle(
                        sealed.bundle().schemaVersion(),
                        sealed.bundle().bundleFingerprint(),
                        sealed.bundle().payloadPolicy(),
                        sealed.attestation(), alteredEvidence);

        assertThat(service.verify(alteredBundle))
                .isEqualTo(
                        MirrorEvidenceIntegrityService.Verification.INVALID);
    }

    @Test
    void sealsReadWriteV4InItsOwnSignatureDomainAndRejectsNestedTampering() {
        MirrorEvidenceIntegrityService.SealResult sealed =
                service.seal(readWriteEvidence());

        assertThat(sealed.verified()).isTrue();
        assertThat(sealed.bundle().schemaVersion())
                .isEqualTo(
                        MirrorEvidenceBundle
                                .READ_WRITE_SCHEMA_VERSION);
        assertThat(sealed.attestation().schemaVersion())
                .isEqualTo(
                        MirrorEvidenceAttestation
                                .READ_WRITE_SCHEMA_VERSION);
        assertThat(sealed.evidence().schemaVersion())
                .isEqualTo(
                        MirrorRunEvidence
                                .READ_WRITE_SCHEMA_VERSION);
        assertThat(service.verify(sealed.bundle()))
                .isEqualTo(
                        MirrorEvidenceIntegrityService
                                .Verification.VERIFIED);

        MirrorStateEvidence alteredState =
                sealed.evidence().stateEvidence()
                        .withFingerprint(fingerprint('f'));
        MirrorRunEvidence alteredEvidence =
                copyEvidenceWithState(
                        sealed.evidence(), alteredState);
        MirrorEvidenceBundle alteredBundle =
                new MirrorEvidenceBundle(
                        sealed.bundle().schemaVersion(),
                        sealed.bundle().bundleFingerprint(),
                        sealed.bundle().payloadPolicy(),
                        sealed.attestation(), alteredEvidence);

        assertThat(service.verify(alteredBundle))
                .isEqualTo(
                        MirrorEvidenceIntegrityService
                                .Verification.INVALID);
    }

    @Test
    void recursivelyDetachesCallerOwnedCollectionsBeforeSigning() {
        ArrayList<String> isolationLimitations = new ArrayList<>(
                List.of("DEPLOYMENT_EGRESS_NOT_ATTESTED"));
        MirrorRunEvidence.IsolationFacts isolation = new MirrorRunEvidence.IsolationFacts(
                MirrorRunEvidence.IsolationFacts.EngineMode.INDEPENDENT_TEST_ENGINE,
                List.of(), List.of("InvocationRecorder"), false, false, false,
                false, false, false, false, null, isolationLimitations);
        isolationLimitations.add("MUTATED_AFTER_CONSTRUCTION");
        MirrorRunEvidence base = evidence();
        MirrorRunEvidence value = new MirrorRunEvidence("", base.runId(), base.requestId(),
                base.requestContextFingerprint(), base.planId(), base.planFingerprint(),
                base.capabilityClosureFingerprint(), base.executionControlFingerprint(),
                base.rootCapability(), base.fixtureBundleRef(), base.externalBindings(), base.scope(),
                base.authorizedPurpose(), base.status(), base.evidenceClass(),
                base.semanticResultFingerprint(), base.startedAt(), base.completedAt(),
                base.nodeTraces(), base.edgeTraces(), base.resolutions(), isolation,
                List.of("DEPLOYMENT_EGRESS_NOT_ATTESTED"));

        MirrorEvidenceIntegrityService.SealResult result = service.seal(value);

        assertThat(result.verified()).isTrue();
        assertThat(result.evidence().isolation().limitations())
                .containsExactly("DEPLOYMENT_EGRESS_NOT_ATTESTED");
        assertThat(result.attestation().toString()).doesNotContain(result.attestation().signature());
    }

    private MirrorRunEvidence evidence() {
        Instant started = Instant.parse("2026-07-22T23:59:58Z");
        MirrorRunEvidence.IsolationFacts isolation = new MirrorRunEvidence.IsolationFacts(
                MirrorRunEvidence.IsolationFacts.EngineMode.INDEPENDENT_TEST_ENGINE,
                List.of(), List.of("InvocationRecorder"), false, false, false,
                false, false, false, false, null,
                List.of("DEPLOYMENT_EGRESS_NOT_ATTESTED"));
        return new MirrorRunEvidence("", RUN_ID, "mirror-request-1", CONTEXT,
                "support-plan", PLAN, CLOSURE, CONTROL, ROOT, FIXTURE, List.of(binding()), SCOPE,
                "MIRROR_REHEARSAL", MirrorRunEvidence.Status.PASSED,
                MirrorRunEvidence.EvidenceClass.EXPLORATORY, SEMANTIC, started,
                started.plusSeconds(1), List.of(node(OUTPUT)), List.of(edge()),
                List.of(sealedResolution()), isolation,
                List.of("DEPLOYMENT_EGRESS_NOT_ATTESTED"));
    }

    private MirrorRunEvidence statefulEvidence() {
        MirrorRunEvidence base = evidence();
        MirrorArtifactRef stateRef = new MirrorArtifactRef(
                "SESSION_STATE", "session-1", 1, fingerprint('b'));
        MirrorArtifactRef modelRef = new MirrorArtifactRef(
                "STATE_MODEL", "customer-state", 1, fingerprint('c'));
        MirrorArtifactRef readSpecRef = new MirrorArtifactRef(
                "STATE_READ_SPEC", "query-customer", 1, fingerprint('d'));
        MirrorStateRunEvidence stateEvidence =
                MirrorStateRunEvidenceIntegrity.seal(
                        mapper, new MirrorStateRunEvidence(
                                MirrorStateRunEvidence.SCHEMA_VERSION,
                                "", RUN_ID, PLAN, stateRef, modelRef,
                                0, fingerprint('e'),
                                Instant.parse("2026-07-22T00:00:00Z"),
                                MirrorStateRunEvidence.Mode
                                        .READ_ONLY_SNAPSHOT,
                                List.of(
                                        new MirrorStateRunEvidence
                                                .StatefulBinding(
                                                "/root/loadCustomer#PRIMARY",
                                                "/root", EXTERNAL,
                                                readSpecRef)),
                                List.of(
                                        new MirrorStateRunEvidence
                                                .StateAccess(
                                                "/root/loadCustomer#PRIMARY",
                                                "/root", "C-1", 1, 1,
                                                EXTERNAL, readSpecRef,
                                                REQUEST, fingerprint('0'),
                                                MirrorStateRunEvidence
                                                        .AccessOutcome
                                                        .LIVE_ENTITY,
                                                fingerprint('1'), OUTPUT,
                                                "")),
                                List.of()));
        MirrorResolution stateResolution =
                MirrorResolutionIntegrity.seal(
                        mapper, new MirrorResolution(
                                "", "", RUN_ID, PLAN, EXTERNAL,
                                "/root/loadCustomer#PRIMARY", "/root",
                                "C-1", 1, 1, REQUEST,
                                MirrorResolution.Status.RESOLVED,
                                MirrorPlan.MirrorSource.SESSION_STATE,
                                MirrorResolution.PayloadVisibility.HASH_ONLY,
                                false, null, OUTPUT, null,
                                List.of(stateRef, modelRef, readSpecRef),
                                List.of("state-read-spec:query-customer:1"),
                                new ArtifactProvenance.Confidence(
                                        1, 1, 1, "state-read-v1"),
                                1, List.of("PAYLOAD_HASH_ONLY")));
        return new MirrorRunEvidence(
                MirrorRunEvidence.STATEFUL_SCHEMA_VERSION,
                base.runId(), base.requestId(),
                base.requestContextFingerprint(), base.planId(),
                base.planFingerprint(),
                base.capabilityClosureFingerprint(),
                base.executionControlFingerprint(),
                base.rootCapability(), base.fixtureBundleRef(),
                base.externalBindings(), base.scope(),
                base.authorizedPurpose(), base.status(),
                base.evidenceClass(),
                base.semanticResultFingerprint(), base.startedAt(),
                base.completedAt(), base.nodeTraces(),
                base.edgeTraces(), List.of(stateResolution),
                stateEvidence, base.isolation(), base.limitations());
    }

    private MirrorRunEvidence readWriteEvidence() {
        MirrorRunEvidence base = evidence();
        MirrorStateTransitionRunEvidence source =
                MirrorStateTransitionRunEvidenceIntegrityTest
                        .evidence();
        MirrorStateTransitionRunEvidence.StateTransition
                transition = source.transitions().getFirst();
        MirrorStateTransitionRunEvidence stateEvidence =
                MirrorStateTransitionRunEvidenceIntegrity.seal(
                        mapper,
                        new MirrorStateTransitionRunEvidence(
                                source.schemaVersion(), "",
                                RUN_ID, PLAN,
                                source.sessionStateRef(),
                                source.finalSessionStateRef(),
                                source.stateModelRef(),
                                source.stateRevision(),
                                source.finalStateRevision(),
                                source.worldFingerprint(),
                                source.finalWorldFingerprint(),
                                source.logicalClock(),
                                source.finalLogicalClock(),
                                source.mode(),
                                List.of(
                                        new MirrorStateTransitionRunEvidence
                                                .StatefulBinding(
                                                "/root/loadCustomer#PRIMARY",
                                                "/root", EXTERNAL,
                                                MirrorStateTransitionRunEvidence
                                                        .Interaction.WRITE,
                                                null,
                                                transition
                                                        .writeEffectRef())),
                                List.of(),
                                List.of(
                                        new MirrorStateTransitionRunEvidence
                                                .StateTransition(
                                                "/root/loadCustomer#PRIMARY",
                                                "/root", "C-1",
                                                1, 1, EXTERNAL,
                                                transition
                                                        .writeEffectRef(),
                                                transition
                                                        .initialStateRef(),
                                                transition.finalStateRef(),
                                                transition.revisionBefore(),
                                                transition.revisionAfter(),
                                                transition
                                                        .initialWorldFingerprint(),
                                                transition
                                                        .finalWorldFingerprint(),
                                                transition
                                                        .initialLogicalClock(),
                                                transition
                                                        .finalLogicalClock(),
                                                transition
                                                        .requestFingerprint(),
                                                transition
                                                        .idempotencyKeyFingerprint(),
                                                transition
                                                        .commandFingerprint(),
                                                transition
                                                        .receiptFingerprint(),
                                                transition
                                                        .responseFingerprint(),
                                                transition
                                                        .resultingWorldFingerprint(),
                                                transition.committedAt(),
                                                transition.replayed(),
                                                transition.events())),
                                List.of()));
        return new MirrorRunEvidence(
                MirrorRunEvidence.READ_WRITE_SCHEMA_VERSION,
                base.runId(), base.requestId(),
                base.requestContextFingerprint(),
                base.planId(), base.planFingerprint(),
                base.capabilityClosureFingerprint(),
                base.executionControlFingerprint(),
                base.rootCapability(),
                base.fixtureBundleRef(),
                base.externalBindings(), base.scope(),
                base.authorizedPurpose(), base.status(),
                base.evidenceClass(),
                base.semanticResultFingerprint(),
                base.startedAt(), base.completedAt(),
                base.nodeTraces(), base.edgeTraces(),
                base.resolutions(), stateEvidence,
                base.isolation(), base.limitations());
    }

    private MirrorRunEvidence.NodeTrace node(String outputFingerprint) {
        return new MirrorRunEvidence.NodeTrace("loadCustomer", "customer.lookup", "MOCKED",
                "OUTPUT_LEVEL", REQUEST, outputFingerprint, "", 4,
                "/root/loadCustomer#PRIMARY", "/root", "C-1", 1, 1,
                List.of(new MirrorRunEvidence.AttemptTrace(1, "MOCKED", "OUTPUT_LEVEL",
                        REQUEST, outputFingerprint, "", 4)));
    }

    private MirrorRunEvidence.EdgeTrace edge() {
        return new MirrorRunEvidence.EdgeTrace("loadCustomer->format", "TRANSFERRED", OUTPUT,
                "/root", "C-1", 1, "/root/loadCustomer#PRIMARY",
                "/root/format#PRIMARY");
    }

    private MirrorResolution sealedResolution() {
        return MirrorResolutionIntegrity.seal(mapper,
                resolution(MirrorResolution.PayloadVisibility.HASH_ONLY,
                        false, null, OUTPUT));
    }

    private MirrorResolution resolution(
            MirrorResolution.PayloadVisibility visibility,
            boolean outputIncluded,
            Object output,
            String outputFingerprint) {
        return new MirrorResolution("", "", RUN_ID, PLAN, EXTERNAL,
                "/root/loadCustomer#PRIMARY", "/root", "C-1", 1, 1, REQUEST,
                MirrorResolution.Status.RESOLVED, MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                visibility, outputIncluded, output, outputFingerprint, null,
                List.of(FIXTURE), List.of("customer-response"),
                new ArtifactProvenance.Confidence(1, 1, 1, "owner-rule-v1"), 1,
                visibility == MirrorResolution.PayloadVisibility.FULL
                        ? List.of() : List.of("PAYLOAD_HASH_ONLY"));
    }

    private static MirrorRunEvidence copyEvidence(
            MirrorRunEvidence source,
            MirrorRunEvidence.NodeTrace replacementNode,
            List<MirrorResolution> resolutions,
            List<MirrorRunEvidence.NodeTrace> nodes,
            MirrorRunEvidence.EvidenceClass evidenceClass) {
        List<MirrorRunEvidence.NodeTrace> selectedNodes = nodes != null ? nodes
                : replacementNode != null ? List.of(replacementNode) : source.nodeTraces();
        return new MirrorRunEvidence(source.schemaVersion(), source.runId(), source.requestId(),
                source.requestContextFingerprint(), source.planId(), source.planFingerprint(),
                source.capabilityClosureFingerprint(), source.executionControlFingerprint(),
                source.rootCapability(), source.fixtureBundleRef(), source.externalBindings(),
                source.scope(),
                source.authorizedPurpose(), source.status(),
                evidenceClass == null ? source.evidenceClass() : evidenceClass,
                source.semanticResultFingerprint(), source.startedAt(), source.completedAt(),
                selectedNodes, source.edgeTraces(),
                resolutions == null ? source.resolutions() : resolutions,
                source.isolation(), source.limitations());
    }

    private static MirrorRunEvidence copyEvidenceWithState(
            MirrorRunEvidence source,
            MirrorStateEvidence stateEvidence) {
        return new MirrorRunEvidence(
                source.schemaVersion(), source.runId(),
                source.requestId(),
                source.requestContextFingerprint(), source.planId(),
                source.planFingerprint(),
                source.capabilityClosureFingerprint(),
                source.executionControlFingerprint(),
                source.rootCapability(), source.fixtureBundleRef(),
                source.externalBindings(), source.scope(),
                source.authorizedPurpose(), source.status(),
                source.evidenceClass(),
                source.semanticResultFingerprint(),
                source.startedAt(), source.completedAt(),
                source.nodeTraces(), source.edgeTraces(),
                source.resolutions(), stateEvidence,
                source.isolation(), source.limitations());
    }

    private static MirrorEvidenceAttestation copyAttestation(
            MirrorEvidenceAttestation source, Instant signedAt, String signature) {
        return new MirrorEvidenceAttestation(source.schemaVersion(), source.signatureStatus(),
                source.runId(), source.planFingerprint(), source.evidenceFingerprint(), signedAt,
                source.keyId(), source.algorithm(), signature, true);
    }

    private static MirrorEvidenceBundle copyBundle(
            MirrorEvidenceBundle source, MirrorEvidenceAttestation attestation) {
        return new MirrorEvidenceBundle(source.schemaVersion(), source.bundleFingerprint(),
                source.payloadPolicy(), attestation, source.evidence());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static MirrorRunEvidence.ExternalBinding binding() {
        return new MirrorRunEvidence.ExternalBinding(ROOT, "loadCustomer", EXTERNAL,
                "/root/loadCustomer#PRIMARY", "/root");
    }
}
