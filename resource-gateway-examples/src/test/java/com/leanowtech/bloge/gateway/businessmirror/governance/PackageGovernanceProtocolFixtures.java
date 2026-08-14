package com.leanowtech.bloge.gateway.businessmirror.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.evidence.PackageEvidenceFixtures;
import com.leanowtech.bloge.gateway.businessmirror.evidence.PackageEvidenceIndex;
import com.leanowtech.bloge.gateway.businessmirror.evidence.PackageEvidenceProjector;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.visual.runtime.DeterministicVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Optional;

/** Deterministic server-side protocol fixtures for ANEKE Package integration. */
public final class PackageGovernanceProtocolFixtures {
    static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final java.time.Instant PROJECTED_AT =
            PackageEvidenceFixtures.NOW.plusSeconds(10);
    static final java.time.Instant GOVERNED_AT = PROJECTED_AT.plusSeconds(10);

    private PackageGovernanceProtocolFixtures() {
    }

    /** Emits one server-produced fixed fixture to standard output. */
    public static void main(String[] args) throws Exception {
        String kind = args.length == 0 ? "bundle" : args[0];
        Object value = switch (kind) {
            case "bundle" -> bundle();
            case "projection" -> projection(signer());
            default -> throw new IllegalArgumentException("unknown fixture kind: " + kind);
        };
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    static PackageCompilationReceipt receipt() {
        return PackageEvidenceFixtures.receipt(MAPPER);
    }

    static PackageEvidenceIndex evidenceIndex() {
        return PackageEvidenceProjector.project(
                receipt(), Optional.empty(), Optional.empty(), 3, PROJECTED_AT, MAPPER);
    }

    static PackageRegistryIngestBundle bundle() {
        PackageCompilationReceipt receipt = receipt();
        PackageEvidenceIndex index = evidenceIndex();
        PackageRegistryIngestBundle material = new PackageRegistryIngestBundle("", "",
                "package-registry-ingest:cancellation-package", receipt.compilationRevision(),
                receipt.snapshot().scope(), receipt.snapshot(), receipt.readiness(),
                receipt.businessAssetLinkClosure(), index,
                receipt.snapshot().dependencyManifest(), PROJECTED_AT,
                "resource-gateway:business-mirror");
        return new PackageRegistryIngestBundleIntegrity(MAPPER).address(material);
    }

    static VisualEvidenceSigner signer() {
        return new DeterministicVisualEvidenceSigner(
                Clock.fixed(GOVERNED_AT, ZoneOffset.UTC));
    }

    static DomainCapabilityPackageGovernanceProjection projection(
            VisualEvidenceSigner signer) {
        PackageRegistryIngestBundle bundle = bundle();
        PackageEvidenceIndex evidence = bundle.evidenceIndex();
        var material = new DomainCapabilityPackageGovernanceProjectionIntegrity.Material(
                "aneke-governance:cancellation-package:1", 1, 1,
                bundle.scope(), bundle.packageSnapshot().artifactRef(), bundle.artifactRef(),
                evidence.artifactRef(),
                ref("ANEKE_PACKAGE_REGISTRY_RECORD", "registry:cancellation-package", 'a'),
                DomainCapabilityPackageGovernanceProjection.Status.ACCEPTED,
                ref("ANEKE_PACKAGE_GATE_DECISION", "gate:cancellation-package:1", 'b'),
                fingerprint('c'), GOVERNED_AT, GOVERNED_AT,
                GOVERNED_AT.plus(Duration.ofHours(24)), "aneke:tool-studio");
        return new DomainCapabilityPackageGovernanceProjectionIntegrity(MAPPER)
                .seal(material, signer);
    }

    static PackageGovernanceProjectionTrust trust(VisualEvidenceSigner signer) {
        return new PackageGovernanceProjectionTrust() {
            @Override
            public boolean verify(
                    com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal seal,
                    DomainCapabilityPackageGovernanceProjection projection) {
                return signer.verify(seal, seal.materialFingerprint()).valid();
            }

            @Override
            public boolean available() {
                return true;
            }
        };
    }

    static MirrorArtifactRef ref(String kind, String id, char material) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(material));
    }

    static String fingerprint(char material) {
        return "sha256:" + String.valueOf(material).repeat(64);
    }
}
