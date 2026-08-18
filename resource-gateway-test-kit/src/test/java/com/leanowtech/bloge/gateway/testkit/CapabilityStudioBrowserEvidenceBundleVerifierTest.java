package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioBrowserEvidenceBundleVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BINDING_FINGERPRINT = "sha256:" + "a".repeat(64);
    private static final String START = "2026-08-18T00:00:00Z";
    private static final String END = "2026-08-18T01:00:00Z";
    private static final CapabilityStudioBrowserEvidenceBundleVerifier VERIFIER =
            new CapabilityStudioBrowserEvidenceBundleVerifier();

    @TempDir
    Path temporaryDirectory;

    @Test
    void verifiesComplete60Plus378EvidenceAndBuildsStrictManifest() throws Exception {
        Fixture fixture = completeFixture(temporaryDirectory.resolve("complete"));

        var result = verify(fixture);

        assertThat(result.verified()).isTrue();
        assertThat(CapabilityStudioBrowserEvidenceBundleVerifier.EXPECTED_NORMAL_EVIDENCE_COUNT)
                .isEqualTo(60);
        assertThat(CapabilityStudioBrowserEvidenceBundleVerifier.EXPECTED_ANOMALY_EVIDENCE_COUNT)
                .isEqualTo(378);
        assertThat(CapabilityStudioBrowserEvidenceBundleVerifier.EXPECTED_TOTAL_EVIDENCE_COUNT)
                .isEqualTo(438);
        assertThat(result.expectedEntryCount()).isEqualTo(438);
        assertThat(result.persistedEntryCount()).isEqualTo(438);
        assertThat(result.manifest().path("entries")).hasSize(438);
        assertThat(result.manifest().path("normal").path("resultId").asText())
                .isEqualTo("BMR-fixture-1");
        assertThat(result.manifest().path("anomaly").path("resultId").asText())
                .isEqualTo("BAMR-fixture-1");
        assertThat(CapabilityStudioSchemaSupport.validate(
                result.manifest(), CapabilityStudioSchemaSupport.BROWSER_EVIDENCE_BUNDLE_MANIFEST_RESOURCE))
                .isEmpty();
        assertThat(result.manifest().path("manifestFingerprint").asText())
                .isEqualTo(EvidenceVerificationSupport.sha256Bounded(
                        withoutManifestFingerprint(result.manifest()), 4 * 1024 * 1024));
    }

    @Test
    void producesByteStableManifestForIdenticalInputBytes() throws Exception {
        Fixture first = completeFixture(temporaryDirectory.resolve("first"));
        Fixture second = completeFixture(temporaryDirectory.resolve("second"));

        byte[] firstManifest = JSON.writeValueAsBytes(verify(first).manifest());
        byte[] secondManifest = JSON.writeValueAsBytes(verify(second).manifest());

        assertThat(first.normal()).isEqualTo(second.normal());
        assertThat(first.anomaly()).isEqualTo(second.anomaly());
        assertThat(firstManifest).isEqualTo(secondManifest);
    }

    @Test
    void rejectsMissingEmptyTamperedAndExtraEvidenceFiles() throws Exception {
        Fixture missing = completeFixture(temporaryDirectory.resolve("missing"));
        Files.delete(normalPath(missing.root(), 0));
        assertCode(missing, "EVIDENCE_MISSING");

        Fixture empty = completeFixture(temporaryDirectory.resolve("empty"));
        Files.write(emptyPath(empty.root(), 0), new byte[0]);
        assertCode(empty, "EVIDENCE_EMPTY");

        Fixture tampered = completeFixture(temporaryDirectory.resolve("tampered"));
        Files.writeString(normalPath(tampered.root(), 0), "tampered", StandardCharsets.UTF_8);
        assertCode(tampered, "EVIDENCE_FINGERPRINT_MISMATCH");

        Fixture extra = completeFixture(temporaryDirectory.resolve("extra"));
        Files.writeString(extra.root().resolve("browser-matrix-evidence/stale"),
                "stale", StandardCharsets.UTF_8);
        assertCode(extra, "EVIDENCE_EXTRA");
    }

    @Test
    void rejectsDuplicateWrongPrefixTraversalEncodedTraversalAndBackslashReferences() throws Exception {
        Fixture duplicate = completeFixture(temporaryDirectory.resolve("duplicate"));
        ObjectNode duplicateNormal = duplicate.normalDocument();
        String firstRef = duplicateNormal.at("/cells/0/evidenceRefs/0/evidenceId").asText();
        ((ObjectNode) duplicateNormal.at("/cells/1/evidenceRefs/0")).put("evidenceId", firstRef);
        refreshNormalClosure(duplicateNormal);
        ObjectNode duplicateAnomaly = rebindAnomaly(duplicateNormal, duplicate.anomalyDocument());
        assertCode(new Fixture(bytes(duplicateNormal), bytes(duplicateAnomaly), duplicate.root(),
                duplicateNormal, duplicateAnomaly), "EVIDENCE_DUPLICATE");

        Fixture wrongPrefix = completeFixture(temporaryDirectory.resolve("wrong-prefix"));
        ObjectNode wrong = wrongPrefix.normalDocument();
        ((ObjectNode) wrong.at("/cells/0/evidenceRefs/0"))
                .put("evidenceId", "artifact:wrong-evidence/item");
        refreshNormalClosure(wrong);
        ObjectNode wrongAnomaly = rebindAnomaly(wrong, wrongPrefix.anomalyDocument());
        assertCode(new Fixture(bytes(wrong), bytes(wrongAnomaly), wrongPrefix.root(),
                wrong, wrongAnomaly), "EVIDENCE_PREFIX_INVALID");

        Fixture traversal = completeFixture(temporaryDirectory.resolve("traversal"));
        ObjectNode traversed = traversal.normalDocument();
        ((ObjectNode) traversed.at("/cells/0/evidenceRefs/0"))
                .put("evidenceId", "artifact:browser-matrix-evidence/../outside");
        refreshNormalClosure(traversed);
        ObjectNode traversedAnomaly = rebindAnomaly(traversed, traversal.anomalyDocument());
        assertCode(new Fixture(bytes(traversed), bytes(traversedAnomaly), traversal.root(),
                traversed, traversedAnomaly), "EVIDENCE_PATH_INVALID");

        Fixture encoded = completeFixture(temporaryDirectory.resolve("encoded"));
        ObjectNode encodedNormal = encoded.normalDocument();
        ((ObjectNode) encodedNormal.at("/cells/0/evidenceRefs/0"))
                .put("evidenceId", "artifact:browser-matrix-evidence/%2e%2e/outside");
        refreshNormalClosure(encodedNormal);
        assertThat(verify(new Fixture(bytes(encodedNormal), encoded.anomaly(), encoded.root(),
                encodedNormal, encoded.anomalyDocument())).verified()).isFalse();

        Fixture backslash = completeFixture(temporaryDirectory.resolve("backslash"));
        ObjectNode backslashNormal = backslash.normalDocument();
        ((ObjectNode) backslashNormal.at("/cells/0/evidenceRefs/0"))
                .put("evidenceId", "artifact:browser-matrix-evidence/..\\outside");
        refreshNormalClosure(backslashNormal);
        assertThat(verify(new Fixture(bytes(backslashNormal), backslash.anomaly(), backslash.root(),
                backslashNormal, backslash.anomalyDocument())).verified()).isFalse();
    }

    @Test
    void rejectsSymlinkAndNonRegularEvidence() throws Exception {
        Fixture symlink = completeFixture(temporaryDirectory.resolve("symlink"));
        Path outside = symlink.root().resolve("outside");
        Files.writeString(outside, "outside", StandardCharsets.UTF_8);
        Path expected = normalPath(symlink.root(), 0);
        Files.delete(expected);
        Files.createSymbolicLink(expected, outside);
        assertCode(symlink, "EVIDENCE_SYMLINK");

        Fixture nonRegular = completeFixture(temporaryDirectory.resolve("non-regular"));
        Path nonRegularPath = normalPath(nonRegular.root(), 0);
        Files.delete(nonRegularPath);
        Files.createDirectory(nonRegularPath);
        assertCode(nonRegular, "EVIDENCE_NON_REGULAR");
    }

    @Test
    void rejectsArtifactRootAndAncestorSymlinks() throws Exception {
        Fixture rootTarget = completeFixture(temporaryDirectory.resolve("root-target"));
        Path rootLink = temporaryDirectory.resolve("root-link");
        Files.createSymbolicLink(rootLink, rootTarget.root());
        assertCode(new Fixture(rootTarget.normal(), rootTarget.anomaly(), rootLink,
                rootTarget.normalDocument(), rootTarget.anomalyDocument()), "ARTIFACT_ROOT_SYMLINK");

        Path realParent = temporaryDirectory.resolve("real-parent");
        Fixture ancestorTarget = completeFixture(realParent.resolve("bundle"));
        Path ancestorLink = temporaryDirectory.resolve("ancestor-link");
        Files.createSymbolicLink(ancestorLink, realParent);
        assertCode(new Fixture(ancestorTarget.normal(), ancestorTarget.anomaly(),
                ancestorLink.resolve("bundle"), ancestorTarget.normalDocument(),
                ancestorTarget.anomalyDocument()), "ARTIFACT_ROOT_SYMLINK");
    }

    @Test
    void rejectsCompleteResultsWithWrongEvidenceDenominator() throws Exception {
        Fixture anomalyMismatch = completeFixture(temporaryDirectory.resolve("anomaly-denominator"));
        ObjectNode anomaly = anomalyMismatch.anomalyDocument();
        ((ObjectNode) anomaly.withArray("obligations").get(0))
                .withArray("evidenceRefs").remove(0);
        refreshAnomalyClosure(anomaly);

        assertCode(new Fixture(anomalyMismatch.normal(), bytes(anomaly), anomalyMismatch.root(),
                anomalyMismatch.normalDocument(), anomaly), "EVIDENCE_DENOMINATOR_MISMATCH");

        Fixture normalMismatch = completeFixture(temporaryDirectory.resolve("normal-denominator"));
        ObjectNode normal = normalMismatch.normalDocument();
        ((ObjectNode) normal.withArray("cells").get(0)).withArray("evidenceRefs")
                .addObject()
                .put("evidenceId", "artifact:browser-matrix-evidence/zz-extra")
                .put("fingerprint", "sha256:" + "b".repeat(64));
        refreshNormalSummary(normal);
        refreshNormalClosure(normal);
        ObjectNode reboundAnomaly = rebindAnomaly(normal, normalMismatch.anomalyDocument());
        assertCode(new Fixture(bytes(normal), bytes(reboundAnomaly), normalMismatch.root(),
                normal, reboundAnomaly), "EVIDENCE_DENOMINATOR_MISMATCH");
    }

    @Test
    void rejectsArbitraryMissingCrossObligationAndNonPngEvidenceRoles() throws Exception {
        Fixture arbitrary = completeFixture(temporaryDirectory.resolve("arbitrary-roles"));
        ObjectNode arbitraryAnomaly = arbitrary.anomalyDocument();
        ArrayNode arbitraryRefs = ((ObjectNode) arbitraryAnomaly.withArray("obligations").get(0))
                .withArray("evidenceRefs");
        String arbitraryPrefix = anomalyRefPrefix(arbitraryRefs.get(0).path("exactRef").asText());
        arbitraryRefs.removeAll();
        arbitraryRefs.addObject().put("exactRef", arbitraryPrefix + "-alpha.png")
                .put("fingerprint", "sha256:" + "b".repeat(64));
        arbitraryRefs.addObject().put("exactRef", arbitraryPrefix + "-beta.png")
                .put("fingerprint", "sha256:" + "c".repeat(64));
        arbitraryRefs.addObject().put("exactRef", arbitraryPrefix + "-gamma.json")
                .put("fingerprint", "sha256:" + "d".repeat(64));
        refreshAnomalyClosure(arbitraryAnomaly);
        assertCode(new Fixture(arbitrary.normal(), bytes(arbitraryAnomaly), arbitrary.root(),
                arbitrary.normalDocument(), arbitraryAnomaly), "EVIDENCE_ROLE_MISMATCH");

        Fixture missing = completeFixture(temporaryDirectory.resolve("missing-role"));
        ObjectNode missingAnomaly = missing.anomalyDocument();
        ArrayNode missingRefs = ((ObjectNode) missingAnomaly.withArray("obligations").get(0))
                .withArray("evidenceRefs");
        String missingPrefix = anomalyRefPrefix(missingRefs.get(0).path("exactRef").asText());
        ((ObjectNode) missingRefs.get(1)).put("exactRef", missingPrefix + "-other.png");
        refreshAnomalyClosure(missingAnomaly);
        assertCode(new Fixture(missing.normal(), bytes(missingAnomaly), missing.root(),
                missing.normalDocument(), missingAnomaly), "EVIDENCE_ROLE_MISMATCH");

        Fixture crossed = completeFixture(temporaryDirectory.resolve("crossed-roles"));
        ObjectNode crossedAnomaly = crossed.anomalyDocument();
        ArrayNode firstRefs = ((ObjectNode) crossedAnomaly.withArray("obligations").get(0))
                .withArray("evidenceRefs");
        ArrayNode secondRefs = ((ObjectNode) crossedAnomaly.withArray("obligations").get(1))
                .withArray("evidenceRefs");
        ArrayNode firstCopy = firstRefs.deepCopy();
        ArrayNode secondCopy = secondRefs.deepCopy();
        firstRefs.removeAll().addAll(secondCopy);
        secondRefs.removeAll().addAll(firstCopy);
        refreshAnomalyClosure(crossedAnomaly);
        assertCode(new Fixture(crossed.normal(), bytes(crossedAnomaly), crossed.root(),
                crossed.normalDocument(), crossedAnomaly), "EVIDENCE_ROLE_MISMATCH");

        Fixture nonPng = completeFixture(temporaryDirectory.resolve("normal-non-png"));
        ObjectNode nonPngNormal = nonPng.normalDocument();
        ObjectNode normalRef = (ObjectNode) nonPngNormal.at("/cells/0/evidenceRefs/0");
        normalRef.put("evidenceId",
                normalRef.path("evidenceId").asText().replace(".png", ".json"));
        refreshNormalClosure(nonPngNormal);
        ObjectNode nonPngAnomaly = rebindAnomaly(nonPngNormal, nonPng.anomalyDocument());
        assertCode(new Fixture(bytes(nonPngNormal), bytes(nonPngAnomaly), nonPng.root(),
                nonPngNormal, nonPngAnomaly), "EVIDENCE_ROLE_MISMATCH");
    }

    @Test
    void returnsStableInvalidResultsForNullInputsAndRuntimePathFailure() throws Exception {
        Fixture fixture = completeFixture(temporaryDirectory.resolve("null-inputs"));

        assertThat(VERIFIER.verify(null, fixture.anomaly(), fixture.root()).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_EVIDENCE_BUNDLE_NORMAL_RESULT_INVALID");
        assertThat(VERIFIER.verify(fixture.normal(), null, fixture.root()).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_EVIDENCE_BUNDLE_ANOMALY_RESULT_INVALID");
        assertThat(VERIFIER.verify(fixture.normal(), fixture.anomaly(), null).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_EVIDENCE_BUNDLE_ARTIFACT_ROOT_INVALID");

        Path runtimeFailure = (Path) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Path.class},
                (proxy, method, arguments) -> {
                    throw new IllegalStateException("synthetic path provider failure");
                });
        assertThat(VERIFIER.verify(fixture.normal(), fixture.anomaly(), runtimeFailure).errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_EVIDENCE_BUNDLE_ARTIFACT_ROOT_INVALID");
    }

    @Test
    void rejectsInventoryBeyondHardCapWithoutCollectingTheTree() throws Exception {
        Fixture fixture = completeFixture(temporaryDirectory.resolve("inventory-cap"));
        assertThat(CapabilityStudioBrowserEvidenceBundleVerifier.MAXIMUM_INVENTORY_ENTRIES)
                .isEqualTo(4096);
        Path evidenceDirectory = fixture.root().resolve("browser-matrix-evidence");
        for (int index = 0;
                index <= CapabilityStudioBrowserEvidenceBundleVerifier.MAXIMUM_INVENTORY_ENTRIES;
                index++) {
            Files.createDirectory(evidenceDirectory.resolve("unused-" + index));
        }

        assertCode(fixture, "EVIDENCE_INVENTORY_LIMIT_EXCEEDED");
    }

    @Test
    void rejectsNonCompleteInvalidJsonAndBaseDrift() throws Exception {
        Fixture notComplete = completeFixture(temporaryDirectory.resolve("not-complete"));
        ObjectNode normal = notComplete.normalDocument();
        ObjectNode cell = (ObjectNode) normal.withArray("cells").get(0);
        cell.put("status", "NOT_RUN").putNull("actualInnerViewport");
        cell.with("keyboardPath").put("completed", false).put("stepCount", 0);
        cell.withArray("evidenceRefs").removeAll();
        normal.put("resultStatus", "INCOMPLETE");
        normal.putArray("diagnostics").addObject().put("code", "MATRIX_NOT_COMPLETE");
        refreshNormalSummary(normal);
        refreshNormalClosure(normal);
        ObjectNode notCompleteAnomaly = rebindAnomaly(normal, notComplete.anomalyDocument());
        byte[] notCompleteBytes = bytes(normal);
        assertCode(new Fixture(notCompleteBytes, bytes(notCompleteAnomaly), notComplete.root(),
                normal, notCompleteAnomaly), "NORMAL_RESULT_NOT_COMPLETE");

        Fixture drift = completeFixture(temporaryDirectory.resolve("drift"));
        ObjectNode anomaly = drift.anomalyDocument();
        anomaly.with("candidate").put("artifactFingerprint", "sha256:" + "b".repeat(64));
        refreshAnomalyClosure(anomaly);
        assertCode(new Fixture(drift.normal(), bytes(anomaly), drift.root(),
                drift.normalDocument(), anomaly), "BASE_BINDING_INVALID");

        CapabilityStudioBrowserEvidenceBundleVerifier.VerificationResult invalidJson =
                VERIFIER.verify("{invalid".getBytes(StandardCharsets.UTF_8),
                        drift.anomaly(), drift.root());
        assertThat(invalidJson.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_EVIDENCE_BUNDLE_NORMAL_RESULT_INVALID");
    }

    private Fixture completeFixture(Path root) throws Exception {
        Files.createDirectories(root.resolve("browser-matrix-evidence"));
        Files.createDirectories(root.resolve("browser-anomaly-evidence"));
        CapabilityStudioBrowserMatrixResultBuilder normalBuilder = new CapabilityStudioBrowserMatrixResultBuilder(
                "BMR-fixture-1", 1, "s0-ac-01.v1", candidate(), baseline(), environment(), window());
        for (var key : CapabilityStudioBrowserMatrixResultBuilder.expectedCells()) {
            String exactRef = "artifact:browser-matrix-evidence/" + normalFileName(key);
            byte[] content = ("normal:" + key.cellId()).getBytes(StandardCharsets.UTF_8);
            Files.write(root.resolve(exactRef.substring("artifact:".length())), content);
            normalBuilder.pass(key, key.viewport(), List.of(
                    new CapabilityStudioBrowserMatrixResultBuilder.EvidenceRef(
                            exactRef, fingerprint(content))));
        }
        ObjectNode normalDocument = normalBuilder.build();
        byte[] normal = bytes(normalDocument);

        CapabilityStudioBrowserAnomalyMatrixResultBuilder anomalyBuilder =
                new CapabilityStudioBrowserAnomalyMatrixResultBuilder(
                        "BAMR-fixture-1", 1, "s0-ac-01.v1", anomalyCandidate(), anomalyBaseline(),
                        anomalyEnvironment(), anomalyWindow(),
                        new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BaseMatrixRef(
                                "results/browser-matrix/BMR-fixture-1",
                                normalDocument.path("evidenceClosureFingerprint").asText(),
                                CapabilityStudioBrowserAnomalyMatrixResultBuilder.BaseMatrixStatus.COMPLETE));
        for (var key : CapabilityStudioBrowserAnomalyMatrixResultBuilder.expectedObligations()) {
            var evidence = new java.util.ArrayList<
                    CapabilityStudioBrowserAnomalyMatrixResultBuilder.EvidenceRef>();
            String prefix = anomalyFilePrefix(key);
            for (String suffix : List.of("-error.png", "-recovered.png", "-trigger.json")) {
                String exactRef = "artifact:browser-anomaly-evidence/" + prefix + suffix;
                byte[] content = ("anomaly:" + key.obligationId() + ":" + suffix)
                        .getBytes(StandardCharsets.UTF_8);
                Path path = root.resolve(exactRef.substring("artifact:".length()));
                Files.write(path, content);
                evidence.add(new CapabilityStudioBrowserAnomalyMatrixResultBuilder.EvidenceRef(
                        exactRef, fingerprint(content)));
            }
            anomalyBuilder.pass(key, passingBrowser(key), evidence);
        }
        ObjectNode anomalyDocument = anomalyBuilder.build();
        return new Fixture(normal, bytes(anomalyDocument), root, normalDocument, anomalyDocument);
    }

    private static CapabilityStudioBrowserEvidenceBundleVerifier.VerificationResult verify(
            Fixture fixture) {
        return VERIFIER.verify(fixture.normal(), fixture.anomaly(), fixture.root());
    }

    private static void assertCode(Fixture fixture, String suffix) {
        var result = verify(fixture);
        assertThat(result.verified()).withFailMessage("bundle result=%s", result).isFalse();
        assertThat(result.errorCode())
                .isEqualTo("RG.CAPABILITY_STUDIO.BROWSER_EVIDENCE_BUNDLE_" + suffix);
    }

    private static Path normalPath(Path root, int index) {
        var key = CapabilityStudioBrowserMatrixResultBuilder.expectedCells().get(index);
        return root.resolve("browser-matrix-evidence").resolve(normalFileName(key));
    }

    private static Path emptyPath(Path root, int index) {
        return normalPath(root, index);
    }

    private static String normalFileName(CapabilityStudioBrowserMatrixResultBuilder.CellKey key) {
        return key.goldenPathId().toLowerCase(Locale.ROOT) + "-" + key.locale() + "-"
                + key.viewport().width() + "x" + key.viewport().height() + ".png";
    }

    private static String anomalyFilePrefix(
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.ObligationKey key) {
        return key.obligationId().substring("BAM-".length()).toLowerCase(Locale.ROOT);
    }

    private static String anomalyRefPrefix(String errorRef) {
        return errorRef.substring(0, errorRef.length() - "-error.png".length());
    }

    private static ObjectNode withoutManifestFingerprint(JsonNode manifest) {
        ObjectNode copy = manifest.deepCopy();
        copy.remove("manifestFingerprint");
        return copy;
    }

    private static void refreshNormalClosure(ObjectNode result) {
        result.remove("evidenceClosureFingerprint");
        result.put("evidenceClosureFingerprint", EvidenceVerificationSupport.sha256Bounded(
                result, CapabilityStudioBrowserMatrixResultVerifier.MAXIMUM_RESULT_BYTES));
    }

    private static void refreshNormalSummary(ObjectNode result) {
        int pass = 0;
        int incomplete = 0;
        int failed = 0;
        int skipped = 0;
        int p0 = 0;
        int p1 = 0;
        int evidence = 0;
        for (JsonNode cell : result.withArray("cells")) {
            String status = cell.path("status").asText();
            if ("PASS".equals(status)) {
                pass++;
            } else if ("FAIL".equals(status)) {
                failed++;
            } else {
                incomplete++;
                if ("SKIPPED".equals(status)) {
                    skipped++;
                }
            }
            p0 += cell.path("p0Count").asInt();
            p1 += cell.path("p1Count").asInt();
            evidence += cell.path("evidenceRefs").size();
        }
        result.with("summary")
                .put("expectedCellCount", 60)
                .put("actualCellCount", result.withArray("cells").size())
                .put("passCellCount", pass)
                .put("incompleteCellCount", incomplete)
                .put("failedCellCount", failed)
                .put("skippedCount", skipped)
                .put("p0Count", p0)
                .put("p1Count", p1)
                .put("evidenceRefCount", evidence);
    }

    private static ObjectNode rebindAnomaly(ObjectNode normal, ObjectNode anomaly) {
        anomaly.with("baseMatrixRef")
                .put("fingerprint", normal.path("evidenceClosureFingerprint").asText());
        refreshAnomalyClosure(anomaly);
        return anomaly;
    }

    private static void refreshAnomalyClosure(ObjectNode result) {
        result.remove("evidenceClosureFingerprint");
        result.put("evidenceClosureFingerprint", EvidenceVerificationSupport.sha256Bounded(
                result, CapabilityStudioBrowserAnomalyMatrixResultVerifier.MAXIMUM_RESULT_BYTES));
    }

    private static byte[] bytes(JsonNode value) throws Exception {
        return JSON.writeValueAsBytes(value);
    }

    private static String fingerprint(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static CapabilityStudioBrowserMatrixResultBuilder.Candidate candidate() {
        return new CapabilityStudioBrowserMatrixResultBuilder.Candidate(
                "build/candidate-1", "candidate-revision-1", BINDING_FINGERPRINT, "abcdef1", "CLEAN");
    }

    private static CapabilityStudioBrowserMatrixResultBuilder.BaselineRef baseline() {
        return new CapabilityStudioBrowserMatrixResultBuilder.BaselineRef(
                "baseline/s0-ac-01", 1, BINDING_FINGERPRINT);
    }

    private static CapabilityStudioBrowserMatrixResultBuilder.Environment environment() {
        return new CapabilityStudioBrowserMatrixResultBuilder.Environment(
                BINDING_FINGERPRINT, "chrome/stable", "chromium", "128.0", "128.0", "4.10.2");
    }

    private static CapabilityStudioBrowserMatrixResultBuilder.ExecutionWindow window() {
        return new CapabilityStudioBrowserMatrixResultBuilder.ExecutionWindow(START, END);
    }

    private static CapabilityStudioBrowserAnomalyMatrixResultBuilder.Candidate anomalyCandidate() {
        return new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Candidate(
                "build/candidate-1", "candidate-revision-1", BINDING_FINGERPRINT, "abcdef1",
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.SourceTreeStatus.CLEAN);
    }

    private static CapabilityStudioBrowserAnomalyMatrixResultBuilder.BaselineRef anomalyBaseline() {
        return new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BaselineRef(
                "baseline/s0-ac-01", 1, BINDING_FINGERPRINT);
    }

    private static CapabilityStudioBrowserAnomalyMatrixResultBuilder.Environment anomalyEnvironment() {
        return new CapabilityStudioBrowserAnomalyMatrixResultBuilder.Environment(
                BINDING_FINGERPRINT, "chrome/stable", "chromium", "128.0", "128.0", "4.10.2");
    }

    private static CapabilityStudioBrowserAnomalyMatrixResultBuilder.ExecutionWindow anomalyWindow() {
        return new CapabilityStudioBrowserAnomalyMatrixResultBuilder.ExecutionWindow(START, END);
    }

    private static CapabilityStudioBrowserAnomalyMatrixResultBuilder.BrowserObservations passingBrowser(
            CapabilityStudioBrowserAnomalyMatrixResultBuilder.ObligationKey key) {
        return new CapabilityStudioBrowserAnomalyMatrixResultBuilder.BrowserObservations(
                key.viewport(), false,
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.Axe.clear(), 0, 0,
                CapabilityStudioBrowserAnomalyMatrixResultBuilder.KeyboardPath.complete(10),
                true, true, true, true, true, true, true,
                true, true, true, true, 0, 0);
    }

    private record Fixture(
            byte[] normal,
            byte[] anomaly,
            Path root,
            ObjectNode normalDocument,
            ObjectNode anomalyDocument) {
    }
}
