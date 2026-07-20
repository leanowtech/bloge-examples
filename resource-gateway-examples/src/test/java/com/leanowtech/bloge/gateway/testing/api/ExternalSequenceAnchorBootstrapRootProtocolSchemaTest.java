package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSequenceAnchorBootstrapRootProtocolSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void strictSchemaMatchesGenesisTransitionAndBundleRecords() throws Exception {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        String publicKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                        .getPublic().getEncoded());
        var key = new ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial(
                "root-a", "root-key-a", publicKey, now.minusSeconds(60),
                now.plusSeconds(3600), true, false);
        var genesis = new ExternalSequenceAnchorBootstrapRootGenesis(
                ExternalSequenceAnchorBootstrapRootGenesis.SCHEMA_VERSION,
                "stability-fleet", "notary-bootstrap-roots", "bootstrap.example",
                1, 0, List.of(key), "sha256:" + "a".repeat(64));
        String genesisFingerprint = genesis.materialFingerprint(objectMapper);
        var material = new ExternalSequenceAnchorBootstrapRootTransition.Material(
                ExternalSequenceAnchorBootstrapRootTransition.Material.SCHEMA_VERSION,
                "notary-bootstrap-roots", 1, genesisFingerprint,
                "stability-fleet", "bootstrap.example", 1, 0, List.of(key),
                "sha256:" + "b".repeat(64), now, now, now.plusSeconds(3600));
        String materialFingerprint = ProtocolFingerprint.of(objectMapper, material);
        var signature = new TestSuiteStabilityServingInventory.AuthoritySignature(
                "root-a", "root-key-a", "Ed25519", now,
                Base64.getEncoder().encodeToString(new byte[64]));
        var transition = new ExternalSequenceAnchorBootstrapRootTransition(
                ExternalSequenceAnchorBootstrapRootTransition.SCHEMA_VERSION,
                material, materialFingerprint, List.of(signature), List.of(signature));
        var bundle = new ExternalSequenceAnchorBootstrapRootBundle(
                ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION,
                genesisFingerprint, List.of(transition), materialFingerprint);
        JsonNode schema = objectMapper.readTree(Files.readString(schemaPath()));

        assertProperties(objectMapper.valueToTree(bundle),
                schema.at("/$defs/bundle/properties"));
        assertProperties(objectMapper.valueToTree(transition),
                schema.at("/$defs/transition/properties"));
        assertProperties(objectMapper.valueToTree(material),
                schema.at("/$defs/material/properties"));
        assertProperties(objectMapper.valueToTree(genesis),
                schema.at("/$defs/genesis/properties"));
        assertProperties(objectMapper.valueToTree(key),
                schema.at("/$defs/rootKeyMaterial/properties"));
        assertThat(schema.at("/$defs/bundle/properties/schemaVersion/const").asText())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION);
        assertThat(schema.at("/$defs/bundle/properties/transitions/maxItems").asInt())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootBundle.MAXIMUM_TRANSITIONS);
        assertThat(schema.at("/$defs/genesis/properties/signatureThreshold/maximum").asInt())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootGenesis
                        .MAXIMUM_SIGNATURE_THRESHOLD);
        assertThat(schema.at(
                "/$defs/transition/properties/authorizingRootSignatures/maxItems").asInt())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootTransition
                        .MAXIMUM_SIGNATURES_PER_ROLE);
        assertThat(List.of("bundle", "transition", "material", "genesis",
                "rootKeyMaterial")).allSatisfy(definition -> assertThat(schema.at(
                        "/$defs/" + definition + "/additionalProperties").asBoolean())
                        .isFalse());
    }

    @Test
    void schemaContainsOnlyPublicCeremonyMaterial() throws Exception {
        String schema = Files.readString(schemaPath());

        for (String forbidden : List.of("privateKey", "credential", "payload", "fixture",
                "nodeOutput", "endpoint", "etag", "requestFingerprint")) {
            assertThat(schema).doesNotContain("\"" + forbidden + "\"");
        }
    }

    @Test
    void deploymentGenesisHasAStandaloneSchemaEntryPoint() throws Exception {
        JsonNode schema = objectMapper.readTree(Files.readString(genesisSchemaPath()));

        assertThat(schema.path("$ref").asText()).isEqualTo(
                "external-sequence-anchor-bootstrap-root-bundle-v1.schema.json#/$defs/genesis");
        assertThat(schema.path("$id").asText())
                .endsWith("external-sequence-anchor-bootstrap-root-genesis-v1.schema.json");
    }

    @Test
    void strictCeremonySchemaMatchesProducerAndOpaqueSignerRecords() throws Exception {
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        String publicKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                        .getPublic().getEncoded());
        var key = new ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial(
                "root-a", "root-key-a", publicKey, now.minusSeconds(60),
                now.plusSeconds(3600), true, false);
        var genesis = new ExternalSequenceAnchorBootstrapRootGenesis(
                ExternalSequenceAnchorBootstrapRootGenesis.SCHEMA_VERSION,
                "stability-fleet", "notary-bootstrap-roots", "bootstrap.example",
                1, 0, List.of(key), "sha256:" + "a".repeat(64));
        String genesisFingerprint = genesis.materialFingerprint(objectMapper);
        var material = new ExternalSequenceAnchorBootstrapRootTransition.Material(
                ExternalSequenceAnchorBootstrapRootTransition.Material.SCHEMA_VERSION,
                "notary-bootstrap-roots", 1, genesisFingerprint,
                "stability-fleet", "bootstrap.example", 1, 0, List.of(key),
                "sha256:" + "b".repeat(64), now, now, now.plusSeconds(3600));
        String materialFingerprint = ProtocolFingerprint.of(objectMapper, material);
        String requestId = "sha256:" + "c".repeat(64);
        String signatureValue = Base64.getEncoder().encodeToString(new byte[64]);
        var descriptor = new ExternalSequenceAnchorBootstrapRootSigningAuthority.Descriptor(
                ExternalSequenceAnchorBootstrapRootSigningAuthority.Descriptor.SCHEMA_VERSION,
                "root-a", "root-key-a", "Ed25519", publicKey);
        var signRequest = new ExternalSequenceAnchorBootstrapRootSigningAuthority
                .SignatureRequest(
                ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureRequest
                        .SCHEMA_VERSION,
                requestId, "ceremony-a",
                ExternalSequenceAnchorBootstrapRootSigningAuthority.Role.AUTHORIZING_ROOT,
                "notary-bootstrap-roots", 1, "root-a", "root-key-a",
                materialFingerprint, now);
        var signResponse = new ExternalSequenceAnchorBootstrapRootSigningAuthority
                .SignatureResponse(
                ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureResponse
                        .SCHEMA_VERSION,
                requestId, "root-a", "root-key-a", "Ed25519",
                materialFingerprint, now, signatureValue);
        var rotationRequest = new ExternalSequenceAnchorBootstrapRootCeremonyProducer
                .RotationRequest(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest
                        .SCHEMA_VERSION,
                "ceremony-a", genesisFingerprint, List.of(key),
                "sha256:" + "b".repeat(64), now, now, now.plusSeconds(3600));
        var preflight = new ExternalSequenceAnchorBootstrapRootCeremonyProducer
                .CeremonyPreflight(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyPreflight
                        .SCHEMA_VERSION,
                "ceremony-a", 1L, materialFingerprint, now.plusSeconds(300),
                List.of(descriptor),
                List.of(descriptor));
        var authoritySignature = new TestSuiteStabilityServingInventory.AuthoritySignature(
                "root-a", "root-key-a", "Ed25519", now, signatureValue);
        var transition = new ExternalSequenceAnchorBootstrapRootTransition(
                ExternalSequenceAnchorBootstrapRootTransition.SCHEMA_VERSION,
                material, materialFingerprint, List.of(authoritySignature),
                List.of(authoritySignature));
        var bundle = new ExternalSequenceAnchorBootstrapRootBundle(
                ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION,
                genesisFingerprint, List.of(transition), materialFingerprint);
        var attempts = List.of(
                new ExternalSequenceAnchorBootstrapRootCeremonyProducer.SigningAttempt(
                        ExternalSequenceAnchorBootstrapRootSigningAuthority.Role
                                .AUTHORIZING_ROOT,
                        "root-a", "root-key-a",
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.AttemptStatus.SIGNED),
                new ExternalSequenceAnchorBootstrapRootCeremonyProducer.SigningAttempt(
                        ExternalSequenceAnchorBootstrapRootSigningAuthority.Role.INCOMING_ROOT,
                        "root-a", "root-key-a",
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.AttemptStatus.SIGNED));
        var outcome = new ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome
                        .SCHEMA_VERSION,
                "ceremony-a", bundle, attempts);
        JsonNode schema = objectMapper.readTree(Files.readString(ceremonySchemaPath()));

        assertProperties(objectMapper.valueToTree(descriptor),
                schema.at("/$defs/signerDescriptor/properties"));
        assertProperties(objectMapper.valueToTree(signRequest),
                schema.at("/$defs/signatureRequest/properties"));
        assertProperties(objectMapper.valueToTree(signResponse),
                schema.at("/$defs/signatureResponse/properties"));
        assertProperties(objectMapper.valueToTree(rotationRequest),
                schema.at("/$defs/rotationRequest/properties"));
        assertProperties(objectMapper.valueToTree(preflight),
                schema.at("/$defs/ceremonyPreflight/properties"));
        assertProperties(objectMapper.valueToTree(attempts.getFirst()),
                schema.at("/$defs/signingAttempt/properties"));
        assertProperties(objectMapper.valueToTree(outcome),
                schema.at("/$defs/ceremonyOutcome/properties"));
        assertThat(List.of("signerDescriptor", "signatureRequest", "signatureResponse",
                "rotationRequest", "ceremonyPreflight", "signingAttempt", "ceremonyOutcome"))
                .allSatisfy(definition -> assertThat(schema.at(
                        "/$defs/" + definition + "/additionalProperties").asBoolean())
                        .isFalse());
        assertThat(schema.at("/$defs/ceremonyOutcome/properties/signingAttempts/maxItems")
                .asInt()).isEqualTo(2 * ExternalSequenceAnchorBootstrapRootTransition
                .MAXIMUM_SIGNATURES_PER_ROLE);
        assertThat(schema.at("/$defs/ceremonyOutcome/properties/signingAttempts/uniqueItems")
                .asBoolean()).isTrue();
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootCeremonyProducer
                .CeremonyOutcome(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome
                        .SCHEMA_VERSION,
                "ceremony-a", bundle,
                List.of(attempts.getFirst(), attempts.getFirst(), attempts.getLast())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfiguredExternalSequenceAnchorBootstrapRootTrustStore
                .ExpectedBinding(
                "stability-fleet", "notary-bootstrap-roots", "bootstrap.example",
                ExternalSequenceAnchorBootstrapRootGenesis.MAXIMUM_SIGNATURE_THRESHOLD + 1,
                10, java.time.Duration.ofDays(30), java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(30), 32))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ceremonySchemaContainsNoPrivateCustodyOrProviderDiagnostics() throws Exception {
        String schema = Files.readString(ceremonySchemaPath());

        for (String forbidden : List.of("privateKey", "credential", "secret", "endpoint",
                "providerName", "errorMessage", "exception")) {
            assertThat(schema).doesNotContain("\"" + forbidden + "\"");
        }
    }

    @Test
    void strictDurableCeremonySchemaMatchesMakerCheckerAndLeaseRecords() throws Exception {
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        String publicKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                        .getPublic().getEncoded());
        var key = new ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial(
                "root-a", "root-key-a", publicKey, now.minusSeconds(60),
                now.plusSeconds(3600), true, false);
        var descriptor = new ExternalSequenceAnchorBootstrapRootSigningAuthority.Descriptor(
                ExternalSequenceAnchorBootstrapRootSigningAuthority.Descriptor.SCHEMA_VERSION,
                "root-a", "root-key-a", "Ed25519", publicKey);
        var request = new ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest
                        .SCHEMA_VERSION,
                "ceremony-durable", "sha256:" + "a".repeat(64), List.of(key),
                "sha256:" + "b".repeat(64), now, now, now.plusSeconds(3600));
        var preflight = new ExternalSequenceAnchorBootstrapRootCeremonyProducer
                .CeremonyPreflight(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyPreflight
                        .SCHEMA_VERSION,
                "ceremony-durable", 1L, "sha256:" + "c".repeat(64),
                now.plusSeconds(300),
                List.of(descriptor), List.of(descriptor));
        var proposal = new ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal
                        .SCHEMA_VERSION,
                request, null, preflight, "maker-a", 300);
        var approval = new ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalCommand(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalCommand
                        .SCHEMA_VERSION,
                request.ceremonyId(), "approval-a", "checker-a", 300);
        var acquisition = new ExternalSequenceAnchorBootstrapRootCeremonyJournal
                .AcquisitionCommand(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.AcquisitionCommand
                        .SCHEMA_VERSION,
                request.ceremonyId(), "worker-a", 30);
        var claim = new ExternalSequenceAnchorBootstrapRootCeremonyJournal.ExecutionClaim(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ExecutionClaim
                        .SCHEMA_VERSION,
                request.ceremonyId(), "worker-a", 1L, now.plusSeconds(30), proposal);
        var heartbeat = new ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatCommand(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatCommand
                        .SCHEMA_VERSION,
                "heartbeat-a", claim, 30);
        var successorClaim = new ExternalSequenceAnchorBootstrapRootCeremonyJournal.ExecutionClaim(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ExecutionClaim
                        .SCHEMA_VERSION,
                request.ceremonyId(), "worker-a", 2L, now.plusSeconds(60), proposal);
        var snapshot = new ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonySnapshot(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonySnapshot
                        .SCHEMA_VERSION,
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.EXECUTING,
                proposal, "sha256:" + "d".repeat(64), now, now.plusSeconds(300),
                approval.approvalRequestId(),
                "sha256:" + "e".repeat(64), approval.checkerId(), now,
                now.plusSeconds(300), claim.workerId(), claim.claimVersion(),
                claim.claimUntil(), "", "", null, 0L,
                1L, null, null, null, "", null, now,
                "sha256:" + "f".repeat(64));
        var renewedSnapshot = new ExternalSequenceAnchorBootstrapRootCeremonyJournal
                .CeremonySnapshot(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonySnapshot
                        .SCHEMA_VERSION,
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.EXECUTING,
                proposal, "sha256:" + "d".repeat(64), now, now.plusSeconds(300),
                approval.approvalRequestId(),
                "sha256:" + "e".repeat(64), approval.checkerId(), now,
                now.plusSeconds(300), successorClaim.workerId(),
                successorClaim.claimVersion(), successorClaim.claimUntil(),
                heartbeat.heartbeatRequestId(), "sha256:" + "1".repeat(64), now,
                1L, 1L, null, null, null, "", null, now,
                "sha256:" + "2".repeat(64));
        var heartbeatResult = new ExternalSequenceAnchorBootstrapRootCeremonyJournal
                .HeartbeatResult(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatDisposition.RENEWED,
                successorClaim, renewedSnapshot);
        var execution = new ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionResult(
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus.BUSY,
                snapshot, null);
        JsonNode schema = objectMapper.readTree(Files.readString(journalSchemaPath()));

        assertProperties(objectMapper.valueToTree(proposal),
                schema.at("/$defs/ceremonyProposal/properties"));
        assertProperties(objectMapper.valueToTree(approval),
                schema.at("/$defs/approvalCommand/properties"));
        assertProperties(objectMapper.valueToTree(acquisition),
                schema.at("/$defs/acquisitionCommand/properties"));
        assertProperties(objectMapper.valueToTree(claim),
                schema.at("/$defs/executionClaim/properties"));
        assertProperties(objectMapper.valueToTree(heartbeat),
                schema.at("/$defs/heartbeatCommand/properties"));
        assertProperties(objectMapper.valueToTree(snapshot),
                schema.at("/$defs/ceremonySnapshot/properties"));
        assertProperties(objectMapper.valueToTree(heartbeatResult),
                schema.at("/$defs/heartbeatResult/properties"));
        assertProperties(objectMapper.valueToTree(execution),
                schema.at("/$defs/executionResult/properties"));
        assertThat(List.of("ceremonyProposal", "approvalCommand", "acquisitionCommand",
                "executionClaim", "heartbeatCommand", "ceremonySnapshot",
                "heartbeatResult", "executionResult"))
                .allSatisfy(definition -> assertThat(schema.at(
                        "/$defs/" + definition + "/additionalProperties").asBoolean())
                        .isFalse());
        assertThat(schema.at(
                "/$defs/approvalCommand/properties/approvalDurationSeconds/minimum").asInt())
                .isEqualTo(1);
        assertThat(schema.at(
                "/$defs/acquisitionCommand/properties/leaseDurationSeconds/maximum").asInt())
                .isEqualTo(300);
        assertThat(schema.at(
                "/$defs/ceremonySnapshot/properties/heartbeatCount/maximum").asLong())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootCeremonyJournal
                        .MAXIMUM_HEARTBEATS_PER_ATTEMPT);
        assertThat(schema.at(
                "/$defs/ceremonySnapshot/properties/schemaVersion/const").asText())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonySnapshot
                        .SCHEMA_VERSION);
        assertThat(schema.path("$id").asText()).endsWith(
                "external-sequence-anchor-bootstrap-root-ceremony-journal-v2.schema.json");
        assertThat(schema.at("/$defs").has("recoveryPolicy")).isFalse();
        assertThat(schema.at("/$defs").has("recoveryAcquisitionCommand")).isFalse();
        assertThat(schema.at("/$defs").has("recoveryAcquisition")).isFalse();
        for (String forbidden : List.of("privateKey", "credential", "secret", "endpoint",
                "providerName", "errorMessage", "exception", "claimToken")) {
            assertThat(Files.readString(journalSchemaPath()))
                    .doesNotContain("\"" + forbidden + "\"");
        }
    }

    @Test
    void legacyDurableCeremonySchemaRemainsAnUnmodifiedV1Contract() throws Exception {
        JsonNode legacy = objectMapper.readTree(Files.readString(legacyJournalSchemaPath()));

        assertThat(legacy.at(
                "/$defs/ceremonySnapshot/properties/schemaVersion/const").asText())
                .isEqualTo("bloge.externalSequenceAnchorBootstrapRootCeremonySnapshot.v1");
        assertThat(legacy.at("/$defs/ceremonySnapshot/properties")
                .has("heartbeatCount")).isFalse();
        assertThat(legacy.at("/$defs").has("heartbeatCommand")).isFalse();
        assertThat(legacy.path("$id").asText()).endsWith(
                "external-sequence-anchor-bootstrap-root-ceremony-journal-v1.schema.json");
    }

    @Test
    void processLocalRecoveryPolicyUsesBoundedOverflowSafeExponentialDelay() {
        var policy = new ExternalSequenceAnchorBootstrapRootCeremonyJournal.RecoveryPolicy(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.RecoveryPolicy.SCHEMA_VERSION,
                2L, 10L, 20L);

        assertThat(policy.retryDelaySeconds(1L)).isEqualTo(2L);
        assertThat(policy.retryDelaySeconds(2L)).isEqualTo(4L);
        assertThat(policy.retryDelaySeconds(3L)).isEqualTo(8L);
        assertThat(policy.retryDelaySeconds(4L)).isEqualTo(10L);
        assertThat(policy.retryDelaySeconds(Long.MAX_VALUE)).isEqualTo(10L);
        assertThatThrownBy(() -> policy.retryDelaySeconds(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootCeremonyJournal
                .RecoveryPolicy(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.RecoveryPolicy.SCHEMA_VERSION,
                10L, 9L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicationOutboxPolicyAndRecordsRemainBoundedOutsideCeremonyWireSchemas()
            throws Exception {
        var policy = new ExternalSequenceAnchorBootstrapRootPublicationOutbox
                .PublicationPolicy(
                ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationPolicy
                        .SCHEMA_VERSION,
                2L, 10L, 20L);
        var request = publicationRequest();
        var claim = new ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationClaim(
                ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationClaim
                        .SCHEMA_VERSION,
                request.publicationId(), request.ceremonyId(), "publisher-a", 1L,
                Instant.now().plusSeconds(30), request);

        assertThat(policy.retryDelaySeconds(1L)).isEqualTo(2L);
        assertThat(policy.retryDelaySeconds(2L)).isEqualTo(4L);
        assertThat(policy.retryDelaySeconds(3L)).isEqualTo(8L);
        assertThat(policy.retryDelaySeconds(4L)).isEqualTo(10L);
        assertThat(policy.retryDelaySeconds(Long.MAX_VALUE)).isEqualTo(10L);
        assertThat(claim.publicationId()).isEqualTo(request.publicationId());
        assertThatThrownBy(() -> policy.retryDelaySeconds(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootPublicationOutbox
                .PublicationPolicy(
                ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationPolicy
                        .SCHEMA_VERSION,
                10L, 9L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootPublicationOutbox
                .PublicationRequest(request.schemaVersion(), "root-pub-" + "f".repeat(64),
                request.scopeId(), request.rootSetId(), request.ceremonyId(),
                request.sequence(), request.expectedPreviousMaterialFingerprint(),
                request.bundle(), request.bundleFingerprint(),
                request.headMaterialFingerprint()))
                .isInstanceOf(IllegalArgumentException.class);

        JsonNode journalSchema = objectMapper.readTree(Files.readString(journalSchemaPath()));
        assertThat(journalSchema.at("/$defs").has("publicationRequest")).isFalse();
        assertThat(journalSchema.at("/$defs").has("publicationSnapshot")).isFalse();
    }

    @Test
    void signedPublisherResponseProtocolRequiresExactSuccessOrMeaningfulConflict()
            throws Exception {
        var request = publicationRequest();
        Instant signedAt = Instant.parse("2026-07-21T00:01:00Z");
        var success = new ExternalSequenceAnchorBootstrapRootPublisher.ResponseMaterial(
                ExternalSequenceAnchorBootstrapRootPublisher.ResponseMaterial.SCHEMA_VERSION,
                ExternalSequenceAnchorBootstrapRootPublisher.ResponseDecision.PUBLISHED,
                "publisher.example", "publisher-a", "key-a",
                ProtocolFingerprint.of(objectMapper, request), request.publicationId(),
                request.scopeId(), request.rootSetId(), request.sequence(),
                request.expectedPreviousMaterialFingerprint(), request.bundleFingerprint(),
                request.headMaterialFingerprint(), request.sequence(),
                request.headMaterialFingerprint(), signedAt.minusSeconds(1), signedAt,
                signedAt.plusSeconds(30));
        String fingerprint = ProtocolFingerprint.of(objectMapper, success);
        var envelope = new ExternalSequenceAnchorBootstrapRootPublisher.SignedResponse(
                ExternalSequenceAnchorBootstrapRootPublisher.SignedResponse.SCHEMA_VERSION,
                success, fingerprint, Base64.getEncoder().encodeToString(new byte[64]));

        assertThat(envelope.fingerprintVerified(objectMapper)).isTrue();
        assertThat(success.toReceipt().publicationId()).isEqualTo(request.publicationId());
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootPublisher
                .ResponseMaterial(success.schemaVersion(),
                ExternalSequenceAnchorBootstrapRootPublisher.ResponseDecision.CONFLICT,
                success.trustDomain(), success.publisherId(), success.keyId(),
                success.requestFingerprint(), success.publicationId(), success.scopeId(),
                success.rootSetId(), success.sequence(),
                success.expectedPreviousMaterialFingerprint(), success.bundleFingerprint(),
                success.headMaterialFingerprint(), success.sequence(),
                success.headMaterialFingerprint(), null, signedAt,
                signedAt.plusSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootPublisher
                .SignedResponse(envelope.schemaVersion(), envelope.material(),
                envelope.materialFingerprint(), Base64.getEncoder()
                .encodeToString(new byte[63])))
                .isInstanceOf(IllegalArgumentException.class);

        JsonNode publicationSchema = objectMapper.readTree(
                Files.readString(publicationSchemaPath()));
        assertProperties(objectMapper.valueToTree(request),
                publicationSchema.at("/$defs/publicationRequest/properties"));
        assertProperties(objectMapper.valueToTree(success),
                publicationSchema.at("/$defs/responseMaterial/properties"));
        assertProperties(objectMapper.valueToTree(envelope),
                publicationSchema.at("/$defs/signedResponse/properties"));
        assertThat(publicationSchema.at(
                "/$defs/publicationRequest/properties/schemaVersion/const").asText())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootPublicationOutbox
                        .PublicationRequest.SCHEMA_VERSION);
        assertThat(publicationSchema.at(
                "/$defs/signedResponse/properties/schemaVersion/const").asText())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootPublisher
                        .SignedResponse.SCHEMA_VERSION);
        assertThat(Files.readString(publicationSchemaPath()))
                .doesNotContain("credential", "privateKey", "endpoint", "providerSecret");

        JsonNode journalSchema = objectMapper.readTree(Files.readString(journalSchemaPath()));
        assertThat(journalSchema.at("/$defs").has("signedPublisherResponse")).isFalse();
    }

    private ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest
            publicationRequest() throws Exception {
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        String publicKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                        .getPublic().getEncoded());
        var key = new ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial(
                "root-a", "root-key-a", publicKey, now.minusSeconds(60),
                now.plusSeconds(3600), true, false);
        String predecessor = "sha256:" + "a".repeat(64);
        var material = new ExternalSequenceAnchorBootstrapRootTransition.Material(
                ExternalSequenceAnchorBootstrapRootTransition.Material.SCHEMA_VERSION,
                "notary-bootstrap-roots", 1L, predecessor, "stability-fleet",
                "bootstrap.example", 1, 0, List.of(key),
                "sha256:" + "b".repeat(64), now, now, now.plusSeconds(3600));
        String materialFingerprint = ProtocolFingerprint.of(objectMapper, material);
        var signature = new TestSuiteStabilityServingInventory.AuthoritySignature(
                "root-a", "root-key-a", "Ed25519", now,
                Base64.getEncoder().encodeToString(new byte[64]));
        var transition = new ExternalSequenceAnchorBootstrapRootTransition(
                ExternalSequenceAnchorBootstrapRootTransition.SCHEMA_VERSION,
                material, materialFingerprint, List.of(signature), List.of(signature));
        var bundle = new ExternalSequenceAnchorBootstrapRootBundle(
                ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION,
                predecessor, List.of(transition), materialFingerprint);
        String bundleFingerprint = ProtocolFingerprint.of(objectMapper, bundle);
        return new ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest(
                ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest
                        .SCHEMA_VERSION,
                "root-pub-" + bundleFingerprint.substring("sha256:".length()),
                "stability-fleet", "notary-bootstrap-roots", "ceremony-a", 1L,
                predecessor, bundle, bundleFingerprint, materialFingerprint);
    }

    private static Path schemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-bundle-v1.schema.json");
    }

    private static Path genesisSchemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-genesis-v1.schema.json");
    }

    private static Path ceremonySchemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-ceremony-v1.schema.json");
    }

    private static Path journalSchemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-ceremony-journal-v2.schema.json");
    }

    private static Path legacyJournalSchemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-ceremony-journal-v1.schema.json");
    }

    private static Path publicationSchemaPath() {
        return Path.of("..", "docs", "schemas", "resource-gateway-testing",
                "external-sequence-anchor-bootstrap-root-publication-v1.schema.json");
    }

    private static void assertProperties(JsonNode value, JsonNode properties) {
        assertThat(fieldNames(value)).containsExactlyInAnyOrderElementsOf(
                fieldNames(properties));
    }

    private static LinkedHashSet<String> fieldNames(JsonNode value) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
