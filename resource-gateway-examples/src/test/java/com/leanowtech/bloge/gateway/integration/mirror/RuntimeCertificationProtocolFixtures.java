package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** Server-produced fixed v1 runtime-certification protocol fixtures. */
public final class RuntimeCertificationProtocolFixtures {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final RuntimeCertificationIntegrity INTEGRITY =
            new RuntimeCertificationIntegrity(MAPPER);
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant-demo", "org-support", "project-business-mirror", "staging", "sg");
    private static final MirrorDeploymentIsolationAttestation.DeploymentIdentity DEPLOYMENT =
            new MirrorDeploymentIsolationAttestation.DeploymentIdentity(
                    "mirror-staging-sg", "cluster-sg-01", "resource-gateway-mirror",
                    "resource-gateway", "resource-gateway-mirror", fingerprint('1'));

    private RuntimeCertificationProtocolFixtures() {
    }

    static RuntimeCertificationManifest manifest() {
        return INTEGRITY.addressManifest(new RuntimeCertificationIntegrity.ManifestMaterial(
                "runtime-certification:sg", 3, SCOPE, "ap-southeast-1", DEPLOYMENT,
                RuntimeCertificationManifest.EnvironmentClass.SANDBOX, fingerprint('a'),
                components(), requirements(), NOW.minusSeconds(60),
                NOW.plusSeconds(86_400), "sre:mirror-runtime"));
    }

    static RuntimeCertificationExecutionAuthorization authorization() {
        RuntimeCertificationManifest manifest = manifest();
        return INTEGRITY.sealAuthorization(
                new RuntimeCertificationIntegrity.AuthorizationMaterial(
                        "runtime-authorization:sg:3", 7, manifest.artifactRef(), SCOPE,
                        manifest.environmentClass(), manifest.environmentFingerprint(),
                        DEPLOYMENT, scenarios(), true, true, true, fingerprint('b'), NOW,
                        NOW.plusSeconds(1), NOW.plusSeconds(1_201),
                        "change-authority:runtime-certification",
                        List.of(ref("CHANGE_APPROVAL", "chg-103", 4, 'c'))),
                new FixedSigner("runtime-authorization-fixture-key"));
    }

    static RuntimeCertificationReport report() {
        RuntimeCertificationManifest manifest = manifest();
        RuntimeCertificationExecutionAuthorization authorization = authorization();
        RegionalDataPlaneCertification regionalCertification =
                RegionalDataPlaneProtocolFixtures.certification();
        MirrorDeploymentIsolationAttestationBundle isolationDecision =
                RegionalDataPlaneProtocolFixtures.isolationBundle();
        List<RuntimeCertificationReport.ScenarioResult> results = results(manifest);
        return INTEGRITY.sealReport(new RuntimeCertificationIntegrity.ReportMaterial(
                "runtime-report:sg:3", 5, manifest.artifactRef(),
                authorization.artifactRef(),
                ref("RUNTIME_CERTIFICATION_AUTHORIZATION_CONSUMPTION",
                        "runtime-authorization:sg:3:runtime-report:sg:3", 1, 'e'),
                regionalCertification.artifactRef(), isolationDecision.artifactRef(),
                isolationDecision.attestation().artifactRef(),
                SCOPE, manifest.region(), DEPLOYMENT, manifest.environmentClass(),
                manifest.environmentFingerprint(), adapter(manifest), components(),
                NOW.plusSeconds(2), NOW.plusSeconds(180), results,
                RuntimeCertificationReport.Verdict.CERTIFIED, 0, 0,
                "runtime-certification-authority:sg",
                List.of(ref("RUNTIME_CERTIFICATION_EVIDENCE_INDEX", "index:sg", 5, 'f'))),
                new FixedSigner("runtime-report-fixture-key"));
    }

    static RuntimeCertificationReplayBundle replayBundle() {
        return new RuntimeCertificationReplayBundleIntegrity(MAPPER).address(
                new RuntimeCertificationReplayBundleIntegrity.Material(
                        "runtime-replay-bundle:sg:3", 2, manifest(), authorization(), report(),
                        RegionalDataPlaneProtocolFixtures.contract(),
                        RegionalDataPlaneProtocolFixtures.certification(),
                        RegionalDataPlaneProtocolFixtures.isolationBundle(),
                        NOW.plusSeconds(181), "runtime-certification-exporter:sg"));
    }

    /** Prints fixtures for deterministic source-controlled regeneration. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "expected manifest, authorization, report, or bundle");
        }
        Object value = switch (args[0]) {
            case "manifest" -> manifest();
            case "authorization" -> authorization();
            case "report" -> report();
            case "bundle" -> replayBundle();
            default -> throw new IllegalArgumentException("unknown fixture kind: " + args[0]);
        };
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    private static RuntimeCertificationReport.AdapterDescriptor adapter(
            RuntimeCertificationManifest manifest) {
        return new RuntimeCertificationReport.AdapterDescriptor(
                "", "adapter:chaos-lab", fingerprint('d'), "customer-platform:sandbox",
                manifest.environmentClass(), manifest.environmentFingerprint(), scenarios(),
                true, true, true, true, true);
    }

    private static List<RuntimeCertificationManifest.ComponentCoordinate> components() {
        return Arrays.stream(RuntimeCertificationManifest.ComponentKind.values())
                .map(kind -> new RuntimeCertificationManifest.ComponentCoordinate(
                        kind, "component:" + kind.name().toLowerCase(), switch (kind) {
                    case RESOURCE_GATEWAY -> "1.0.0";
                    case BLOGE_ENGINE -> "0.8.9-RC2";
                    case DATABASE -> "postgresql-16.4";
                    case JVM -> "openjdk-25.0.3";
                }, fingerprint((char) ('1' + kind.ordinal()))))
                .toList();
    }

    private static List<RuntimeCertificationManifest.ScenarioRequirement> requirements() {
        return Arrays.stream(RuntimeCertificationManifest.Scenario.values())
                .map(value -> new RuntimeCertificationManifest.ScenarioRequirement(
                        value, 300, 120, value.mandatoryInvariantCodes()))
                .toList();
    }

    private static List<RuntimeCertificationReport.ScenarioResult> results(
            RuntimeCertificationManifest manifest) {
        return java.util.stream.IntStream.range(0, manifest.scenarios().size())
                .mapToObj(index -> {
                    RuntimeCertificationManifest.ScenarioRequirement requirement =
                            manifest.scenarios().get(index);
                    Instant started = NOW.plusSeconds(5L + index * 10L);
                    List<RuntimeCertificationReport.InvariantObservation> observations =
                            requirement.requiredInvariantCodes().stream()
                                    .map(code -> new RuntimeCertificationReport
                                            .InvariantObservation(code,
                                            RuntimeCertificationReport.InvariantStatus.PASSED,
                                            List.of(ref("RUNTIME_INVARIANT_PROOF",
                                                    requirement.scenario().name().toLowerCase()
                                                            + ":" + code.toLowerCase(),
                                                    1, digit(index + 1)))))
                                    .toList();
                    return new RuntimeCertificationReport.ScenarioResult(
                            requirement.scenario(), "attempt:" + index,
                            RuntimeCertificationReport.ScenarioStatus.PASSED,
                            started, started.plusSeconds(1), started.plusSeconds(2),
                            started.plusSeconds(3), started.plusSeconds(4), true, true,
                            0, 0, fingerprint(digit(index + 2)),
                            fingerprint(digit(index + 3)), observations,
                            List.of(ref("RUNTIME_SCENARIO_PROOF",
                                    requirement.scenario().name().toLowerCase(),
                                    1, digit(index + 4))), "PASSED");
                }).toList();
    }

    private static List<RuntimeCertificationManifest.Scenario> scenarios() {
        return List.of(RuntimeCertificationManifest.Scenario.values());
    }

    private static MirrorArtifactRef ref(
            String kind, String id, long revision, char value) {
        return new MirrorArtifactRef(kind, id, revision, fingerprint(value));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static char digit(int value) {
        return Character.forDigit(Math.floorMod(value, 10), 10);
    }

    private record FixedSigner(String keyId) implements VisualEvidenceSigner {
        @Override
        public VisualRunEvidenceSeal seal(String materialFingerprint) {
            return new VisualRunEvidenceSeal("", materialFingerprint, "Ed25519", keyId, NOW,
                    Base64.getEncoder().encodeToString(new byte[64]));
        }

        @Override
        public Verification verify(
                VisualRunEvidenceSeal seal, String actualMaterialFingerprint) {
            return new Verification(false, "FIXTURE_ONLY", "No private fixture key exists.");
        }

        @Override
        public Optional<VerificationKey> key(String candidateKeyId) {
            return Optional.empty();
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
