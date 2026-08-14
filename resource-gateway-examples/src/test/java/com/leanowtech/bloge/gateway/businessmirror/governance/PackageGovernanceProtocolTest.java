package com.leanowtech.bloge.gateway.businessmirror.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.integration.IntegrationEnvelope;
import com.leanowtech.bloge.gateway.integration.ToolStudioResourceGatewayProtocol;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackageGovernanceProtocolTest {
    @Test
    void protocol11KeepsVersion10ConsumersCompatible() {
        IntegrationEnvelope<String> envelope = IntegrationEnvelope.of(
                "TEST", "test.v1", "value");

        assertThat(envelope.protocolVersion()).isEqualTo("1.1.0");
        assertThat(envelope.compatibility().minConsumerVersion()).isEqualTo("1.0.0");
        assertThat(envelope.compatibility().backwardCompatible()).isTrue();
        assertThat(ToolStudioResourceGatewayProtocol.SUPPORTED_CONSUMER_VERSIONS)
                .containsExactly("1.1.0", "1.0.0");
    }

    @Test
    void registryBundleClosesSnapshotReadinessLinksEvidenceAndDependencies() {
        PackageRegistryIngestBundle bundle = PackageGovernanceProtocolFixtures.bundle();

        assertThat(new PackageRegistryIngestBundleIntegrity(
                PackageGovernanceProtocolFixtures.MAPPER).canonicalVerified(bundle)).isTrue();
        assertThat(bundle.dependencyManifest())
                .containsExactlyElementsOf(bundle.packageSnapshot().dependencyManifest());
        assertThat(bundle.evidenceIndex().artifactRef().kind())
                .isEqualTo("PACKAGE_EVIDENCE_INDEX");
    }

    @Test
    void bundleAddressAndCrossPackageClosureFailClosed() {
        PackageRegistryIngestBundle source = PackageGovernanceProtocolFixtures.bundle();
        PackageRegistryIngestBundle tampered = source.withFingerprint(
                PackageGovernanceProtocolFixtures.fingerprint('f'));

        assertThat(new PackageRegistryIngestBundleIntegrity(
                PackageGovernanceProtocolFixtures.MAPPER).canonicalVerified(tampered)).isFalse();
        assertThatThrownBy(() -> new PackageRegistryIngestBundle(
                source.schemaVersion(), source.bundleFingerprint(), source.bundleId(),
                source.revision(), source.scope(), source.packageSnapshot(),
                source.readinessReport(), source.businessAssetLinkClosure(),
                source.evidenceIndex(), source.dependencyManifest(),
                source.packageSnapshot().createdAt(), source.exporter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closure is invalid");
    }

    @Test
    void signedProjectionVerifiesThroughCallerOwnedAnekeTrust() {
        var signer = PackageGovernanceProtocolFixtures.signer();
        var projection = PackageGovernanceProtocolFixtures.projection(signer);
        var integrity = new DomainCapabilityPackageGovernanceProjectionIntegrity(
                PackageGovernanceProtocolFixtures.MAPPER);

        assertThat(integrity.verify(projection,
                PackageGovernanceProtocolFixtures.trust(signer)).verified()).isTrue();
        assertThat(integrity.verify(projection,
                PackageGovernanceProjectionTrust.unavailable()).reasonCode())
                .isEqualTo("TRUST_UNAVAILABLE");
    }

    @Test
    void terminalGovernanceStateRequiresExactGateDecision() {
        PackageRegistryIngestBundle bundle = PackageGovernanceProtocolFixtures.bundle();
        var source = PackageGovernanceProtocolFixtures.projection(
                PackageGovernanceProtocolFixtures.signer());

        assertThatThrownBy(() -> new DomainCapabilityPackageGovernanceProjection(
                source.schemaVersion(), source.projectionFingerprint(), source.projectionId(),
                source.revision(), source.externalGeneration(), source.scope(),
                bundle.packageSnapshot().artifactRef(), bundle.artifactRef(),
                bundle.evidenceIndex().artifactRef(), source.registryRecordRef(),
                DomainCapabilityPackageGovernanceProjection.Status.CERTIFIED, null,
                source.sourceCursorFingerprint(), source.producedAt(), source.validFrom(),
                source.expiresAt(), source.issuer(), source.projectionSeal()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gateDecisionRef");
    }

    @Test
    void fixedFixturesAreProducedByTheServerModelAndSchemasRejectUnknownFields() throws Exception {
        JsonNode bundleFixture = fixture("package-registry-ingest-bundle-v1.fixture.json");
        JsonNode projectionFixture = fixture(
                "domain-capability-package-governance-projection-v1.fixture.json");
        JsonNode bundleSchema = schema("package-registry-ingest-bundle-v1.schema.json");
        JsonNode projectionSchema = schema(
                "domain-capability-package-governance-projection-v1.schema.json");

        assertThat(firstDifference(PackageGovernanceProtocolFixtures.MAPPER
                .valueToTree(PackageGovernanceProtocolFixtures.bundle()), bundleFixture, "$"))
                .isEmpty();
        var signer = PackageGovernanceProtocolFixtures.signer();
        assertThat(firstDifference(PackageGovernanceProtocolFixtures.MAPPER
                .valueToTree(PackageGovernanceProtocolFixtures.projection(signer)),
                projectionFixture, "$"))
                .isEmpty();
        assertThat(bundleSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(projectionSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(bundleFixture.toString().toLowerCase())
                .doesNotContain("requestpayload", "responsepayload", "credential", "password");
    }

    private static JsonNode fixture(String name) throws Exception {
        return PackageGovernanceProtocolFixtures.MAPPER.readTree(Files.readString(
                Path.of("..", "docs", "schemas", "tool-studio-resource-gateway", name)));
    }

    private static JsonNode schema(String name) throws Exception {
        return fixture(name);
    }

    private static String firstDifference(JsonNode expected, JsonNode actual, String path) {
        if (expected.isNumber() && actual.isNumber()) {
            return expected.decimalValue().compareTo(actual.decimalValue()) == 0
                    ? "" : path + " value";
        }
        if (expected.getNodeType() != actual.getNodeType()) {
            return path + " type";
        }
        if (expected.isObject()) {
            java.util.Set<String> names = new java.util.TreeSet<>();
            expected.fieldNames().forEachRemaining(names::add);
            actual.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (!expected.has(name) || !actual.has(name)) {
                    return path + "." + name + " missing";
                }
                String difference = firstDifference(
                        expected.get(name), actual.get(name), path + "." + name);
                if (!difference.isEmpty()) {
                    return difference;
                }
            }
            return "";
        }
        if (expected.isArray()) {
            if (expected.size() != actual.size()) {
                return path + " size";
            }
            for (int index = 0; index < expected.size(); index++) {
                String difference = firstDifference(
                        expected.get(index), actual.get(index), path + "[" + index + "]");
                if (!difference.isEmpty()) {
                    return difference;
                }
            }
            return "";
        }
        return expected.equals(actual) ? "" : path + " value";
    }
}
