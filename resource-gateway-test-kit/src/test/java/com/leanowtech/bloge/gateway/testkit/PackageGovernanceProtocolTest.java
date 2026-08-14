package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackageGovernanceProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-14T17:01:00Z");

    @Test
    void packagesAndIndependentlyVerifiesExactRegistryAndGovernanceFixtures() throws Exception {
        JsonNode bundle = fixture(
                PackageGovernanceProtocol.PACKAGE_REGISTRY_INGEST_FIXTURE_RESOURCE);
        JsonNode projection = fixture(
                PackageGovernanceProtocol.PACKAGE_GOVERNANCE_PROJECTION_FIXTURE_RESOURCE);

        var verifiedBundle = PackageGovernanceProtocol.verifyRegistryIngestBundle(bundle);
        var verifiedProjection = PackageGovernanceProtocol.verifyGovernanceProjection(
                projection, bundle, fixtureTrust(), OBSERVED_AT);

        assertThat(verifiedBundle.packageId()).isEqualTo("cancellation-package");
        assertThat(verifiedBundle.revision()).isEqualTo(7);
        assertThat(verifiedBundle.dependencyCount()).isEqualTo(10);
        assertThat(verifiedProjection.externalGeneration()).isOne();
        assertThat(verifiedProjection.status()).isEqualTo("ACCEPTED");
        assertThat(verifiedProjection.packageId()).isEqualTo(verifiedBundle.packageId());
    }

    @Test
    void rejectsBundleAddressClosureAndUnknownFieldTampering() throws Exception {
        ObjectNode address = object(fixture(
                PackageGovernanceProtocol.PACKAGE_REGISTRY_INGEST_FIXTURE_RESOURCE));
        address.put("exporter", "tampered-exporter");
        assertThatThrownBy(() ->
                PackageGovernanceProtocol.verifyRegistryIngestBundle(address))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT."
                        + "REGISTRY_INGEST_BUNDLE_FINGERPRINT_MISMATCH")
                .hasMessageNotContaining("tampered-exporter");

        ObjectNode closure = object(fixture(
                PackageGovernanceProtocol.PACKAGE_REGISTRY_INGEST_FIXTURE_RESOURCE));
        closure.withArray("dependencyManifest").remove(0);
        sealField(closure, "bundleFingerprint");
        assertThatThrownBy(() ->
                PackageGovernanceProtocol.verifyRegistryIngestBundle(closure))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.REGISTRY_INGEST_BUNDLE_CLOSURE_INVALID");

        ObjectNode unknown = object(fixture(
                PackageGovernanceProtocol.PACKAGE_REGISTRY_INGEST_FIXTURE_RESOURCE));
        unknown.put("publishApproved", true);
        assertThatThrownBy(() ->
                PackageGovernanceProtocol.verifyRegistryIngestBundle(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.REGISTRY_INGEST_BUNDLE_INVALID");
    }

    @Test
    void rejectsProjectionAddressTrustExpiryAndCrossBundleDrift() throws Exception {
        JsonNode bundle = fixture(
                PackageGovernanceProtocol.PACKAGE_REGISTRY_INGEST_FIXTURE_RESOURCE);
        ObjectNode projection = object(fixture(
                PackageGovernanceProtocol.PACKAGE_GOVERNANCE_PROJECTION_FIXTURE_RESOURCE));
        projection.put("issuer", "tampered-issuer");
        assertThatThrownBy(() -> PackageGovernanceProtocol.verifyGovernanceProjection(
                projection, bundle, fixtureTrust(), OBSERVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT."
                        + "GOVERNANCE_PROJECTION_MATERIAL_FINGERPRINT_MISMATCH")
                .hasMessageNotContaining("tampered-issuer");

        JsonNode exact = fixture(
                PackageGovernanceProtocol.PACKAGE_GOVERNANCE_PROJECTION_FIXTURE_RESOURCE);
        assertThatThrownBy(() -> PackageGovernanceProtocol.verifyGovernanceProjection(
                exact, bundle, (fingerprint, algorithm, keyId, signedAt, signature) -> false,
                OBSERVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.GOVERNANCE_PROJECTION_SIGNATURE_REJECTED");
        assertThatThrownBy(() -> PackageGovernanceProtocol.verifyGovernanceProjection(
                exact, bundle, fixtureTrust(), Instant.parse("2026-08-16T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT.GOVERNANCE_PROJECTION_EXPIRED");

        ObjectNode otherBundle = object(bundle);
        otherBundle.put("bundleId", "package-registry-ingest:other");
        sealField(otherBundle, "bundleFingerprint");
        assertThatThrownBy(() -> PackageGovernanceProtocol.verifyGovernanceProjection(
                exact, otherBundle, fixtureTrust(), OBSERVED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.BUSINESS_MIRROR.CLIENT."
                        + "GOVERNANCE_PROJECTION_BUNDLE_BINDING_MISMATCH");
    }

    @Test
    void validatesJoinedViewAndIdempotentReceiptShapes() throws Exception {
        JsonNode projection = fixture(
                PackageGovernanceProtocol.PACKAGE_GOVERNANCE_PROJECTION_FIXTURE_RESOURCE);
        JsonNode bundle = fixture(
                PackageGovernanceProtocol.PACKAGE_REGISTRY_INGEST_FIXTURE_RESOURCE);
        ObjectNode view = JSON.createObjectNode();
        view.put("schemaVersion", PackageGovernanceProtocol.PACKAGE_GOVERNANCE_VIEW_V1);
        view.set("scope", bundle.path("scope").deepCopy());
        view.put("packageId", "cancellation-package");
        view.set("currentPackageSnapshotRef",
                projection.path("packageSnapshotRef").deepCopy());
        view.set("currentEvidenceIndexRef",
                projection.path("evidenceIndexRef").deepCopy());
        view.set("currentRegistryIngestBundleRef",
                projection.path("registryIngestBundleRef").deepCopy());
        view.set("projection", projection.deepCopy());
        view.put("freshness", "CURRENT");
        view.put("reasonCode", "");
        view.put("evaluatedAt", "2026-08-14T17:01:00Z");

        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("schemaVersion", PackageGovernanceProtocol.PACKAGE_GOVERNANCE_RECEIPT_V1);
        receipt.set("projectionRef", artifactRef(
                "DOMAIN_CAPABILITY_PACKAGE_GOVERNANCE_PROJECTION",
                projection.path("projectionId").asText(), projection.path("revision").asLong(),
                projection.path("projectionFingerprint").asText()));
        receipt.put("externalGeneration", 1);
        receipt.put("status", "ACCEPTED");
        receipt.put("acceptedAt", "2026-08-14T17:01:00Z");
        receipt.put("replayed", false);

        assertThatNoException().isThrownBy(() ->
                PackageGovernanceProtocol.requireGovernanceView(view));
        assertThatNoException().isThrownBy(() ->
                PackageGovernanceProtocol.requireGovernanceReceipt(receipt));
    }

    private static PackageGovernanceProtocol.ProjectionTrust fixtureTrust() throws Exception {
        KeyPair keyPair = fixtureKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        return (fingerprint, algorithm, keyId, signedAt, signature) -> {
            if (!"Ed25519".equals(algorithm)
                    || !"fixture-ed25519:package-governance-v1".equals(keyId)
                    || !Instant.parse("2026-08-14T17:00:20Z").equals(signedAt)) {
                return false;
            }
            try {
                return EvidenceVerificationSupport.verifyEd25519(
                        fingerprint, signature, publicKey);
            } catch (Exception failure) {
                return false;
            }
        };
    }

    private static KeyPair fixtureKeyPair() throws Exception {
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed("resource-gateway-package-governance-fixture-v1"
                .getBytes(StandardCharsets.UTF_8));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        generator.initialize(new NamedParameterSpec("Ed25519"), random);
        return generator.generateKeyPair();
    }

    private static ObjectNode artifactRef(
            String kind, String id, long revision, String fingerprint) {
        ObjectNode ref = JSON.createObjectNode();
        ref.put("kind", kind);
        ref.put("id", id);
        ref.put("revision", revision);
        ref.put("fingerprint", fingerprint);
        return ref;
    }

    private static ObjectNode object(JsonNode node) {
        return (ObjectNode) node.deepCopy();
    }

    private static void sealField(ObjectNode value, String field) {
        value.put(field, "");
        value.put(field, BusinessMirrorCanonical.fingerprint(value,
                "RG.BUSINESS_MIRROR.CLIENT.TEST_TOO_LARGE",
                "RG.BUSINESS_MIRROR.CLIENT.TEST_CANONICALIZATION_FAILED"));
    }

    private static JsonNode fixture(String resource) throws Exception {
        try (InputStream input = PackageGovernanceProtocolTest.class
                .getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            return JSON.readTree(input);
        }
    }
}
