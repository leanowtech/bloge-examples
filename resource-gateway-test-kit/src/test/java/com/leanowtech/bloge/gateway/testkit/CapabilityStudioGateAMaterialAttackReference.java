package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Test-only, independent Gate A material attack collector.
 *
 * <p>The manifest is metadata only. In particular, this class never accepts the manifest's
 * status, conclusion, reason or exit code as an observation. It materializes the referenced
 * fixtures, applies one mutation, then derives every result from bytes, paths, ZIP entries,
 * process documents, directory trees and Ed25519 signatures.</p>
 */
final class CapabilityStudioGateAMaterialAttackReference {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MATERIAL_MANIFEST =
            "docs/acceptance/capability-studio/gate-a-wire-v1/material-attacks/manifest.json";
    private static final String GUARD_CATALOG =
            "docs/acceptance/capability-studio/gate-a-wire-v1/semantic-guards/guard-catalog-v1.json";
    private static final String MANIFEST_SCHEMA =
            "/schemas/resource-gateway-capability-studio/"
                    + "capability-studio-gate-a-material-attack-manifest-v1.schema.json";
    private static final String SCHEMA_PREFIX =
            "/schemas/resource-gateway-capability-studio/";
    private static final SchemaRegistry SCHEMA_REGISTRY = SchemaRegistry.withDialect(
            Dialects.getDraft202012(), builder -> builder.schemas(
                    CapabilityStudioGateAMaterialAttackReference::schemaTextForUri));

    private static final Map<String, String> DOMAINS = Map.of(
            "a0", "RG-CS-GATE-A0-RESULT-v1",
            "a1", "RG-CS-GATE-A1-REPLAY-RESULT-v1",
            "a2", "RG-CS-GATE-A2-RESULT-v1");

    private static final String ADMISSION_TREE_DOMAIN =
            "RG-CS-GATE-A-ADMISSION-EVIDENCE-ROOT-v1";
    private static final String CHALLENGE_TREE_DOMAIN =
            "RG-CS-GATE-A-CHALLENGE-INPUT-ROOT-v1";
    private static final String RUN_MATERIAL_TREE_DOMAIN =
            "RG-CS-GATE-A-RUN-MATERIAL-ROOT-v1";
    private static final String PROCESS_TRANSCRIPT_DOMAIN =
            "RG-CS-GATE-A-PROCESS-TRANSCRIPT-v1";
    private static final String A1_ENVELOPE_DOMAIN =
            "RG-CS-GATE-A1-PROOF-ENVELOPE-v1";
    private static final byte[] REVIEW_SEED = hexBytes(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    private static final byte[] ED25519_PUBLIC_DER_PREFIX = hexBytes("302a300506032b6570032100");
    private static final byte[] ED25519_PRIVATE_DER_PREFIX = hexBytes("302e020100300506032b657004220420");
    private static final Set<String> RESIGNED_REVIEW_SEMANTIC_ATTACKS = Set.of(
            "REAL-REVIEW-COUNT-UNDERREPORT",
            "REAL-REVIEW-CHECK-FINDING-MISMATCH",
            "REAL-REVIEW-DUPLICATE-FINDING-ID",
            "REAL-REVIEW-FINDING-ORDER-DRIFT",
            "REAL-REVIEW-COUNT-OPEN-P1-UNDERREPORT",
            "REAL-REVIEW-COUNT-SKIPPED-UNDERREPORT",
            "REAL-REVIEW-CANDIDATE-BINDING-DRIFT",
            "REAL-REVIEW-BODY-ENVELOPE-REVIEWED-AT-DRIFT",
            "REAL-REVIEW-REVOCATION-ISSUED-AFTER-REVIEW");

    private CapabilityStudioGateAMaterialAttackReference() {
    }

    static List<Observed> verifyFrozenMaterialAttacks() throws IOException {
        Path repository = repositoryRoot();
        JsonNode catalogDocument = JSON.readTree(Files.readAllBytes(repository.resolve(GUARD_CATALOG)));
        GuardCatalog catalog = GuardCatalog.read(catalogDocument);
        JsonNode manifest = JSON.readTree(Files.readAllBytes(repository.resolve(MATERIAL_MANIFEST)));
        requireManifestSchemaCompatible(manifest);
        validateManifestShape(manifest, catalog);

        List<Observed> results = new ArrayList<>();
        for (JsonNode caseNode : manifest.path("cases")) {
            results.add(runCase(repository, caseNode, catalog));
        }
        return List.copyOf(results);
    }

    private static Observed runCase(Path repository, JsonNode caseNode, GuardCatalog catalog)
            throws IOException {
        String caseId = text(caseNode, "caseId");
        String targetGuard = text(caseNode, "guardId");
        Path root = Files.createTempDirectory(Path.of("/tmp"), "rggatea-java-");
        try {
            Material material = new Material(repository, root, caseNode, catalog);
            material.copyDocuments();
            prepare(material, targetGuard);
            material.refreshActualReferences();
            if (targetGuard.startsWith("REVIEW_")) {
                refreshReviewerMaterial(material);
                signReview(material, "baseline");
            }
            validateDocuments(material, caseNode, "baseline");
            assertBaseline(material, targetGuard, caseId, catalog);
            mutate(material, caseNode);
            validateDocuments(material, caseNode, "post-mutation");
            if (RESIGNED_REVIEW_SEMANTIC_ATTACKS.contains(caseId)) {
                assertCryptographicSignatureRemainsValid(material, caseId);
            }
            assertUniqueHit(material, targetGuard, caseId, catalog);

            Observed observed = collect(material, targetGuard, catalog);
            Expected expected = Expected.from(caseNode.path("expected"));
            expected.assertMatches(observed, caseId);
            return observed;
        } catch (RuntimeException failure) {
            throw new AssertionError("Material attack " + caseId + " failed: " + failure.getMessage(), failure);
        } finally {
            deleteTree(root);
        }
    }

    private static void validateManifestShape(JsonNode manifest, GuardCatalog catalog) {
        require(text(manifest, "messageVersion").equals(
                "resource-gateway.capability-studio.gate-a.material-attack-manifest.v1"),
                "manifest messageVersion drifted");
        require(manifest.path("normalizedVectorsExcluded").asBoolean(false),
                "manifest must explicitly exclude normalized reducer vectors");
        require(manifest.path("cases").size() >= catalog.guards().size(),
                "material attack manifest has fewer cases than the Guard Catalog");
        Set<String> primary = new LinkedHashSet<>();
        List<String> primaryOrder = new ArrayList<>();
        int caseIndex = 0;
        for (JsonNode caseNode : manifest.path("cases")) {
            String guard = text(caseNode, "guardId");
            GuardSpec spec = catalog.spec(guard);
            require(spec != null, "unknown Guard in manifest: " + guard);
            String caseClass = caseClass(caseNode);
            require(caseIndex < catalog.guards().size()
                            ? caseClass.equals("PRIMARY_GUARD_ATTACK")
                            : caseClass.equals("SUPPLEMENTAL_ATTACK"),
                    "material attack caseClass/order drift for " + text(caseNode, "caseId"));
            if (caseClass.equals("PRIMARY_GUARD_ATTACK")) {
                require(primary.add(guard), "duplicate primary material attack for " + guard);
                primaryOrder.add(guard);
            }
            require(text(caseNode.path("expected"), "admissionTarget").equals(spec.admissionTarget()),
                    "admission target drift for " + text(caseNode, "caseId"));
            require(caseNode.path("mutation").path("singleMutation").asBoolean(false),
                    "non-single mutation for " + text(caseNode, "caseId"));
            caseIndex++;
        }
        require(primaryOrder.equals(catalog.guards().stream().map(GuardSpec::guardId).toList()),
                "primary material attack order must follow the Guard Catalog");
        require(primary.size() == catalog.guards().size(),
                "every Guard Catalog entry needs one PRIMARY_GUARD_ATTACK");
    }

    private static void requireManifestSchemaCompatible(JsonNode manifest) {
        requireSchema(manifest, MANIFEST_SCHEMA, "material-attacks/manifest.json");
    }

    private static String caseClass(JsonNode caseNode) {
        String explicit = text(caseNode, "caseClass");
        require(explicit.equals("PRIMARY_GUARD_ATTACK")
                        || explicit.equals("SUPPLEMENTAL_ATTACK"),
                "unknown material attack caseClass: " + explicit);
        return explicit;
    }

    static List<String> guardCatalogOrder() throws IOException {
        Path repository = repositoryRoot();
        GuardCatalog catalog = GuardCatalog.read(JSON.readTree(
                Files.readAllBytes(repository.resolve(GUARD_CATALOG))));
        return catalog.guards().stream().map(GuardSpec::guardId).toList();
    }

    private static String replayResultTarget(Material material) {
        if (material.docs.containsKey("a1/replay.json")) {
            return "a1/replay.json";
        }
        if (material.docs.containsKey("run-material/results/a1-replay-result.json")) {
            return "run-material/results/a1-replay-result.json";
        }
        throw new IllegalArgumentException("A1 replay result document is absent");
    }

    private static void prepare(Material material, String guard) throws IOException {
        if (guard.startsWith("A0_")) {
            Path candidate = material.writeBytes("artifacts/candidate.jar", bytes("candidate-artifact-v1\n"));
            ObjectNode document = material.document("a0/result.json");
            materializeReferences(material, document);
            document.with("candidateArtifactRef").set("rawFingerprint", rawRef(candidate));
            material.writeDocument(document, "a0/result.json");
            material.rebind("a0/result.json", "resultFingerprint", DOMAINS.get("a0"));
            return;
        }
        if (guard.startsWith("A1_") && !guard.equals("A1_PROCESS_MATERIAL_CLOSURE")) {
            String target = replayResultTarget(material);
            ObjectNode document = material.document(target);
            materializeReferences(material, document);
            material.writeDocument(document, target);
            material.rebind(target, "resultFingerprint", DOMAINS.get("a1"));
            return;
        }
        if (guard.equals("A1_PROCESS_MATERIAL_CLOSURE")) {
            prepareA1Proof(material);
            return;
        }
        if (guard.equals("HARNESS_PROOF_COMPLETENESS")) {
            prepareA1Proof(material);
            return;
        }
        if (guard.equals("PROVIDER_NAMESPACE_COLLISION_REJECTED")) {
            writeProviderJar(material.path("provider/provider.jar"), false);
            return;
        }
        if (guard.equals("PIN_LIFECYCLE_BINDING")) {
            Path candidate = material.writeBytes("artifacts/candidate.jar", bytes("candidate-artifact-v1\n"));
            ObjectNode pin = material.document("pins/challenge.json");
            pin.putObject("expectedImplementationCandidateRawFingerprint").setAll(rawRef(candidate));
            material.writeDocument(pin, "pins/challenge.json");
            return;
        }
        if (guard.equals("ADMISSION_EVIDENCE_ROOT_CLOSURE")) {
            ObjectNode result = material.document("admission/result.json");
            materializeReferences(material, result);
            String rootUri = result.path("admissionEvidenceRootRef").path("uri")
                    .asText("admission-evidence/root.tree");
            Path root = material.path(rootUri);
            Files.createDirectories(root);
            Path report = material.writeBytes(rootUri + "/TEST_REPORT.json",
                    bytes("canonical test report\n"));
            result.with("admissionEvidenceRootRef").put("uri", rootUri);
            result.with("admissionEvidenceRootRef").set(
                    "fingerprint", treeFingerprint(root, ADMISSION_TREE_DOMAIN));
            material.writeDocument(result, "admission/result.json");
            material.rebind("admission/result.json", "resultFingerprint", DOMAINS.get("a2"));
            return;
        }
        if (guard.startsWith("A2_") || guard.equals("ROLLBACK_BINDING")) {
            ObjectNode result = material.document("admission/result.json");
            materializeReferences(material, result);
            material.writeDocument(result, "admission/result.json");
            material.rebind("admission/result.json", "resultFingerprint", DOMAINS.get("a2"));
            if (guard.equals("ROLLBACK_BINDING")) {
                Path instruction = material.writeBytes("rollback/gate-a.md",
                        bytes("rollback candidate-v1\n"));
                result = material.document("admission/result.json");
                result.with("rollback").with("instructionRef").set("rawFingerprint", rawRef(instruction));
                material.writeDocument(result, "admission/result.json");
                material.rebind("admission/result.json", "resultFingerprint", DOMAINS.get("a2"));
            }
            return;
        }
        if (guard.equals("CODESOURCE_INDEPENDENCE")) {
            Path harness = material.writeBytes("artifacts/harness.jar", bytes("same-code-source-bytes\n"));
            Path alias = material.writeBytes("artifacts/harness-alias.jar", Files.readAllBytes(harness));
            ObjectNode transcript = material.document("process/harness.json");
            transcript.with("codeSource").put("artifactPath", harness.toRealPath().toString());
            transcript.with("codeSource").set("rawFingerprint", rawRef(harness));
            transcript.with("codeSource").put("fileSize", Files.size(harness));
            transcript.with("codeSource").put("fileKey", fileKey(harness));
            transcript.set("codeSourceObservation", codeSourceObservation(harness));
            material.writeDocument(transcript, "process/harness.json");
            material.pinnedCodeSource = harness;
            material.aliasCodeSource = alias;
            return;
        }
        if (guard.equals("REVIEW_SIGNATURE_AUTHORITY")
                || guard.equals("REVIEW_COUNT_CONSISTENCY_REJECTED")) {
            prepareReviewerTrust(material);
            signReview(material, "baseline");
            return;
        }
        throw new IllegalArgumentException("no preparer for " + guard);
    }

    private static void prepareA1Proof(Material material) throws IOException {
        ObjectNode envelope = findDocument(material, "replay-proof-envelope");
        require(envelope != null, "A1 proof envelope is absent");
        String envelopeTarget = material.documentTarget(envelope);
        String resultTarget = envelope.path("replayResultRef").path("uri").asText();
        String processTarget = envelope.path("producerProcessTranscriptRef").path("uri").asText();
        ObjectNode result = material.document(resultTarget);
        ObjectNode process = material.document(processTarget);

        materializeReferences(material, result);
        updateCodeSource(material, result, "candidateCodeSource", "artifacts/a1-candidate.jar",
                "a1-candidate-material-v1\n");
        updateCodeSource(material, result, "verifierCodeSource", "artifacts/a1-verifier.jar",
                "a1-verifier-material-v1\n");
        material.writeDocument(result, resultTarget);
        material.rebind(resultTarget, "resultFingerprint", DOMAINS.get("a1"));

        Path stdout = material.writeBytes(process.path("stdoutRef").path("uri").asText(),
                bytes("canonical replay stdout\n"));
        Path stderr = material.writeBytes(process.path("stderrRef").path("uri").asText(), bytes("\n"));
        setRawFingerprint(process.at("/stdoutRef/rawFingerprint"), stdout);
        setRawFingerprint(process.at("/stderrRef/rawFingerprint"), stderr);
        updateCodeSource(material, process, "codeSource", "artifacts/a1-producer.jar",
                "a1-producer-material-v1\n");
        materializeReferences(material, process);
        material.writeDocument(process, processTarget);
        material.rebind(processTarget, "transcriptFingerprint", PROCESS_TRANSCRIPT_DOMAIN);

        Path runMaterial = material.path("run-material");
        Files.createDirectories(runMaterial);
        envelope = material.document(envelopeTarget);
        envelope.with("replayResultRef").set("rawFingerprint", rawRef(material.path(resultTarget)));
        envelope.with("producerProcessTranscriptRef").set(
                "rawFingerprint", rawRef(material.path(processTarget)));
        envelope.with("producerMaterialRootRef").put("uri", "run-material");
        envelope.with("producerMaterialRootRef").set(
                "fingerprint", treeFingerprint(runMaterial, RUN_MATERIAL_TREE_DOMAIN));
        JsonNode producerFingerprint = process.path("codeSource").path("rawFingerprint");
        envelope.set("expectedProducerCodeSourceRawFingerprint", producerFingerprint.deepCopy());
        envelope.set("observedProducerCodeSourceRawFingerprint", producerFingerprint.deepCopy());
        envelope.put("observedProcessState", process.path("processState").asText());
        envelope.put("observedExitCode", process.path("exitCode").asInt());
        envelope.put("observedTerminal", result.path("terminal").asText());
        material.declare("run-material");
        material.writeDocument(envelope, envelopeTarget);
        material.rebind(envelopeTarget, "envelopeFingerprint", A1_ENVELOPE_DOMAIN);
    }

    private static void updateCodeSource(Material material, ObjectNode document, String field,
                                         String target, String content) throws IOException {
        Path artifact = material.writeBytes(target, bytes(content));
        ObjectNode codeSource = document.with(field);
        codeSource.put("artifactPath", artifact.toRealPath().toString());
        codeSource.set("rawFingerprint", rawRef(artifact));
        codeSource.put("fileSize", Files.size(artifact));
        codeSource.put("fileKey", fileKey(artifact));
        if (field.equals("codeSource")) {
            document.set("codeSourceObservation", codeSourceObservation(artifact));
        }
    }

    private static void materializeReferences(Material material, JsonNode value) throws IOException {
        materializeReferences(material, value, null);
    }

    private static void materializeReferences(Material material, JsonNode value, String fieldName)
            throws IOException {
        if (value.isObject()) {
            ObjectNode object = (ObjectNode) value;
            String uri = object.path("uri").asText(null);
            if (uri != null && object.has("rawFingerprint")) {
                Path path = material.path(uri);
                material.declare(uri);
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("raw reference points at non-file: " + uri);
                }
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    material.writeBytes(uri, bytes("material:" + uri + "\n"));
                }
                object.set("rawFingerprint", rawRefStable(path));
            } else if (uri != null && object.path("fingerprint").path("kind").asText()
                    .equals("TREE_COMMITMENT")) {
                Path root = material.path(uri);
                material.declare(uri);
                Files.createDirectories(root);
                Path marker = root.resolve("material-root.txt");
                if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                    Files.write(marker, bytes("material-root:" + uri + "\n"));
                }
                object.set("fingerprint", treeFingerprint(root, treeDomainForField(fieldName, uri)));
            }
            var fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                materializeReferences(material, entry.getValue(), entry.getKey());
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) {
                materializeReferences(material, item, fieldName);
            }
        }
    }

    private static void mutate(Material material, JsonNode caseNode) throws IOException {
        String guard = text(caseNode, "guardId");
        String caseId = text(caseNode, "caseId");
        switch (guard) {
            case "A0_SLOT_COUNT_PROJECTION" -> {
                material.set("a0/result.json", "/adapterVerifiedCount", 2);
                material.rebind("a0/result.json", "resultFingerprint", DOMAINS.get("a0"));
            }
            case "A0_TERMINAL_DERIVATION" -> {
                ObjectNode document = material.document("a0/result.json");
                document.put("terminal", "STRUCTURE_VERIFIED");
                document.put("reasonCode", "A0_STRUCTURE_VERIFIED");
                material.writeDocument(document, "a0/result.json");
                material.rebind("a0/result.json", "resultFingerprint", DOMAINS.get("a0"));
            }
            case "A0_REFERENCE_CLOSURE" -> {
                boolean digestAttack = caseId.equals("REAL-A0-RAW-FINGERPRINT");
                material.set("a0/result.json", digestAttack
                                ? "/candidateArtifactRef/rawFingerprint/value" : "/candidateArtifactRef/uri",
                        digestAttack ? "sha256:" + "a".repeat(64) : "artifacts/missing.jar");
                material.rebind("a0/result.json", "resultFingerprint", DOMAINS.get("a0"));
            }
            case "A0_RESULT_FINGERPRINT" -> material.set(
                    "a0/result.json", "/resultFingerprint/value", "sha256:" + "f".repeat(64));
            case "A1_SLOT_COUNT_PROJECTION" -> {
                material.set("a1/replay.json", "/passedCount", 8);
                material.rebind("a1/replay.json", "resultFingerprint", DOMAINS.get("a1"));
            }
            case "A1_SLOT_OUTCOME_BINDING" -> {
                ObjectNode document = material.document("a1/replay.json");
                document.put("terminal", "INVALID");
                document.put("reasonCode", "A1_REPLAY_INVALID");
                material.writeDocument(document, "a1/replay.json");
                material.rebind("a1/replay.json", "resultFingerprint", DOMAINS.get("a1"));
            }
            case "A1_PROCESS_MATERIAL_CLOSURE" -> {
                if (caseId.equals("REAL-A1-OUTER-TRANSCRIPT-CRASH")) {
                    ObjectNode document = material.document("run-material/transcripts/a1-process.json");
                    document.put("processState", "FAILED");
                    document.put("exitCode", 1);
                    material.writeDocument(document, "run-material/transcripts/a1-process.json");
                } else {
                    ObjectNode envelope = findDocument(material, "replay-proof-envelope");
                    require(envelope != null, "A1 proof envelope is absent for " + caseId);
                    String envelopeTarget = material.documentTarget(envelope);
                    switch (caseId) {
                        case "REAL-A1-PROOF-WRONG-RESULT-REF" -> envelope
                                .with("replayResultRef").put("uri", "run-material/results/missing.json");
                        case "REAL-A1-PROOF-RESULT-DIGEST" -> envelope
                                .with("replayResultRef").with("rawFingerprint")
                                .put("value", "sha256:" + "b".repeat(64));
                        case "REAL-A1-PROOF-TRANSCRIPT-DIGEST" -> envelope
                                .with("producerProcessTranscriptRef").with("rawFingerprint")
                                .put("value", "sha256:" + "c".repeat(64));
                        case "REAL-A1-PROOF-MATERIAL-ROOT" -> envelope
                                .with("producerMaterialRootRef").with("fingerprint")
                                .put("value", "sha256:" + "d".repeat(64));
                        case "REAL-A1-PROOF-TERMINAL-EXIT" -> {
                            envelope.put("observedTerminal", "INVALID");
                            envelope.put("observedExitCode", 2);
                        }
                        default -> throw new IllegalArgumentException(
                                "unknown A1 proof material attack: " + caseId);
                    }
                    material.writeDocument(envelope, envelopeTarget);
                    material.rebind(envelopeTarget, "envelopeFingerprint", A1_ENVELOPE_DOMAIN);
                }
            }
            case "A1_RESULT_FINGERPRINT" -> material.set(
                    replayResultTarget(material), "/resultFingerprint/value", "sha256:" + "e".repeat(64));
            case "HARNESS_PROOF_COMPLETENESS" -> {
                ObjectNode envelope = findDocument(material, "replay-proof-envelope");
                require(envelope != null, "A1 proof envelope is absent");
                Files.delete(material.path(envelope.path("replayResultRef").path("uri").asText()));
            }
            case "PROVIDER_NAMESPACE_COLLISION_REJECTED" -> writeProviderJar(material.path("provider/provider.jar"), true);
            case "PIN_LIFECYCLE_BINDING" -> {
                // The actual byte replacement is performed immediately after this switch.
            }
            case "ADMISSION_EVIDENCE_ROOT_CLOSURE" -> {
                ObjectNode result = material.document("admission/result.json");
                String rootUri = result.path("admissionEvidenceRootRef").path("uri").asText();
                Files.write(material.path(rootUri + "/TEST_REPORT.json"), bytes("tampered test report\n"));
            }
            case "CODESOURCE_INDEPENDENCE" -> material.set(
                    "process/harness.json", "/codeSource/artifactPath",
                    material.aliasCodeSource.toRealPath().toString());
            case "REVIEW_SIGNATURE_AUTHORITY" -> {
                if (caseId.equals("REAL-REVIEW-SIGNATURE-TAMPER")) {
                    material.set("review/envelope.json", "/signature", Base64.getUrlEncoder()
                            .withoutPadding().encodeToString(new byte[64]));
                } else if (caseId.equals("REAL-REVIEW-CHECK-FINDING-MISMATCH")) {
                    signReview(material, "check-mismatch");
                } else if (caseId.equals("REAL-REVIEW-DUPLICATE-FINDING-ID")) {
                    signReview(material, "duplicate-finding-id");
                } else if (caseId.equals("REAL-REVIEW-FINDING-ORDER-DRIFT")) {
                    signReview(material, "finding-order-drift");
                } else if (caseId.equals("REAL-REVIEW-CANDIDATE-BINDING-DRIFT")) {
                    ObjectNode envelope = material.document("review/envelope.json");
                    envelope.with("candidateRawFingerprint").put("value", "sha256:" + "5".repeat(64));
                    material.writeDocument(envelope, "review/envelope.json");
                    signReview(material, "baseline");
                } else if (caseId.equals("REAL-REVIEW-BODY-ENVELOPE-REVIEWED-AT-DRIFT")) {
                    ObjectNode body = material.document("review/body.json");
                    body.put("reviewedAt", "2026-08-21T10:30:00Z");
                    material.writeDocument(body, "review/body.json");
                    signReview(material, "baseline");
                } else if (caseId.equals("REAL-REVIEW-REVOCATION-ISSUED-AFTER-REVIEW")) {
                    ObjectNode revocation = findDocument(material, "revocation-snapshot");
                    require(revocation != null, "Reviewer revocation snapshot is absent");
                    String revocationTarget = material.documentTarget(revocation);
                    revocation.put("issuedAt", "2026-08-21T11:00:00Z");
                    material.writeBytes(revocationTarget, canonicalDocumentBytes(revocation,
                            "reviewerRevocationSnapshotFingerprint",
                            "RG-CS-REVIEWER-REVOCATION-SNAPSHOT-v1"));
                    refreshReviewerMaterial(material);
                    signReview(material, "baseline");
                } else {
                    ObjectNode envelope = material.document("review/envelope.json");
                    switch (caseId) {
                        case "REAL-REVIEW-KEYID-DRIFT" -> envelope.put("keyId", "key:unknown-fixture");
                        case "REAL-REVIEW-ISSUER-DRIFT" -> envelope.put("issuer", "issuer:untrusted-fixture");
                        case "REAL-REVIEW-AUTHORITY-DRIFT" -> envelope.put("authorityId", "authority:untrusted-fixture");
                        case "REAL-REVIEW-REVOCATION-DRIFT" -> envelope
                                .with("revocationSnapshotRawFingerprint")
                                .put("value", "sha256:" + "e".repeat(64));
                        case "REAL-REVIEW-POLICY-FINGERPRINT-DRIFT" -> {
                            ObjectNode policy = material.document("review/policy.json");
                            policy.with("reviewerTrustPolicyFingerprint")
                                    .put("value", "sha256:" + "f".repeat(64));
                            material.writeDocument(policy, "review/policy.json");
                        }
                        default -> throw new IllegalArgumentException("unknown Reviewer attack: " + caseId);
                    }
                    material.writeDocument(envelope, "review/envelope.json");
                    material.rebind("review/envelope.json", "envelopeFingerprint", A1_ENVELOPE_DOMAIN);
                }
            }
            case "REVIEW_COUNT_CONSISTENCY_REJECTED" -> signReview(material, switch (caseId) {
                case "REAL-REVIEW-COUNT-UNDERREPORT" -> "underreport";
                case "REAL-REVIEW-COUNT-OPEN-P1-UNDERREPORT" -> "underreport-open-p1";
                case "REAL-REVIEW-COUNT-SKIPPED-UNDERREPORT" -> "underreport-skipped-count";
                default -> throw new IllegalArgumentException("unknown count attack: " + caseId);
            });
            case "ROLLBACK_BINDING" -> Files.write(
                    material.path("rollback/gate-a.md"), bytes("rollback candidate-v2 unauthorized\n"));
            case "A2_CONCLUSION_PRECEDENCE" -> {
                ObjectNode document = material.document("admission/result.json");
                if (caseId.equals("REAL-A2-REQUIREMENT-SLOT-DRIFT")) {
                    ((ObjectNode) document.path("requirements").get(0)).put("status", "FAIL");
                } else {
                    ObjectNode first = (ObjectNode) document.path("semanticGuardResults").get(0);
                    first.put("status", "FAIL");
                    first.put("reasonCode", "GUARD_MISMATCH");
                }
                material.writeDocument(document, "admission/result.json");
                material.rebind("admission/result.json", "resultFingerprint", DOMAINS.get("a2"));
            }
            case "A2_RESULT_FINGERPRINT" -> material.set(
                    "admission/result.json", "/resultFingerprint/value", "sha256:" + "d".repeat(64));
            default -> throw new IllegalArgumentException("no mutation for " + guard);
        }
        if (guard.equals("PIN_LIFECYCLE_BINDING")) {
            Files.write(material.path("artifacts/candidate.jar"), bytes("candidate-artifact-v1-tampered\n"));
        }
    }

    private static Observed collect(Material material, String guard, GuardCatalog catalog)
            throws IOException {
        if (guard.startsWith("A0_")) {
            ObjectNode document = material.document("a0/result.json");
            List<String> statuses = new ArrayList<>();
            document.path("adapterResults").forEach(item -> statuses.add(text(item, "status")));
            return switch (guard) {
                case "A0_SLOT_COUNT_PROJECTION" -> observed(catalog, guard,
                        a0CountsMatch(document, statuses), "A0_SLOT_COUNT_PROJECTION", 2);
                case "A0_TERMINAL_DERIVATION" -> {
                    String derived = statuses.contains("UNAVAILABLE") ? "UNAVAILABLE"
                            : statuses.contains("INVALID") ? "INVALID"
                            : statuses.contains("VERIFIED") ? "STRUCTURE_VERIFIED" : "INCOMPLETE";
                    String reason = switch (derived) {
                        case "UNAVAILABLE" -> "A0_UNAVAILABLE";
                        case "INVALID" -> "A0_INVALID";
                        case "STRUCTURE_VERIFIED" -> "A0_STRUCTURE_VERIFIED";
                        default -> "A0_INCOMPLETE";
                    };
                    yield observed(catalog, guard, document.path("terminal").asText().equals(derived)
                                    && document.path("reasonCode").asText().equals(reason),
                            "A0_TERMINAL_DERIVATION", 2);
                }
                case "A0_REFERENCE_CLOSURE" -> observed(catalog, guard,
                        a0ReferenceClosure(material, document),
                        "A0_REFERENCE_CLOSURE", 2);
                default -> observed(catalog, guard,
                        fingerprintMatches(document, "resultFingerprint", DOMAINS.get("a0")),
                        "A0_RESULT_FINGERPRINT", 2);
            };
        }
        if (guard.startsWith("A1_") && !guard.equals("A1_PROCESS_MATERIAL_CLOSURE")) {
            ObjectNode document = material.document(replayResultTarget(material));
            A1SlotState state = a1SlotState(document);
            return switch (guard) {
                case "A1_SLOT_COUNT_PROJECTION" -> observed(catalog, guard,
                        state.countsMatch(),
                        "A1_SLOT_COUNT_PROJECTION", 2);
                case "A1_SLOT_OUTCOME_BINDING" -> {
                    String terminal = state.passed() == 9 && state.failed() == 0
                            && state.skipped() == 0 ? "VERIFIED" : "INVALID";
                    String reason = terminal.equals("VERIFIED")
                            ? "A1_REPLAY_VERIFIED" : "A1_REPLAY_INVALID";
                    yield observed(catalog, guard,
                            document.path("terminal").asText().equals(terminal)
                                    && document.path("reasonCode").asText().equals(reason),
                            "A1_SLOT_OUTCOME_BINDING", 2);
                }
                default -> observed(catalog, guard,
                        fingerprintMatches(document, "resultFingerprint", DOMAINS.get("a1")),
                        "A1_RESULT_FINGERPRINT", 2);
            };
        }
        if (guard.equals("A1_PROCESS_MATERIAL_CLOSURE")) {
            ObjectNode process = material.document("run-material/transcripts/a1-process.json");
            A1Closure closure = verifyA1Closure(material, process);
            return observed(catalog, guard, closure.closed(), closure.reason(), closure.exitCode(),
                    closure.failureConclusion());
        }
        if (guard.equals("HARNESS_PROOF_COMPLETENESS")) {
            ObjectNode envelope = findDocument(material, "replay-proof-envelope");
            if (envelope == null) {
                throw new IllegalArgumentException("Replay Proof Envelope is not applicable");
            }
            boolean present = proofResultExists(material, envelope);
            return observed(catalog, guard, present, "A1_REPLAY_PROOF_MISSING", 2);
        }
        if (guard.equals("PROVIDER_NAMESPACE_COLLISION_REJECTED")) {
            boolean collision;
            try (ZipFile zip = new ZipFile(material.path("provider/provider.jar").toFile())) {
                collision = zip.stream().map(ZipEntry::getName)
                        .anyMatch(name -> name.startsWith("com/leanowtech/bloge/gateway/testkit/"));
            } catch (IOException failure) {
                throw failure;
            }
            return observed(catalog, guard, !collision, "PROVIDER_NAMESPACE_COLLISION_REJECTED", 2);
        }
        if (guard.equals("PIN_LIFECYCLE_BINDING")) {
            JsonNode expected = material.document("pins/challenge.json")
                    .path("expectedImplementationCandidateRawFingerprint");
            return observed(catalog, guard, expected.equals(rawRef(material.path("artifacts/candidate.jar"))),
                    "PIN_LIFECYCLE_BINDING", 2);
        }
        if (guard.equals("ADMISSION_EVIDENCE_ROOT_CLOSURE")) {
            ObjectNode result = material.document("admission/result.json");
            JsonNode rootRef = result.path("admissionEvidenceRootRef");
            Path root = material.optionalPath(rootRef.path("uri").asText());
            if (root == null) {
                root = material.path("admission-evidence");
            }
            boolean closed = root != null && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    && rootRef.path("fingerprint").equals(treeFingerprint(root, ADMISSION_TREE_DOMAIN));
            return observed(catalog, guard, closed,
                    "ADMISSION_EVIDENCE_ROOT_CLOSURE", 2);
        }
        if (guard.equals("CODESOURCE_INDEPENDENCE")) {
            ObjectNode document = material.document("process/harness.json");
            Path expected = material.pinnedCodeSource;
            Path observed = Path.of(document.path("codeSource").path("artifactPath").asText());
            boolean closed = sameFileIdentity(expected, observed)
                    && document.path("codeSource").path("rawFingerprint").equals(rawRef(observed));
            return observed(catalog, guard, closed, "CODESOURCE_INDEPENDENCE", 2);
        }
        if (guard.equals("REVIEW_SIGNATURE_AUTHORITY") || guard.equals("REVIEW_COUNT_CONSISTENCY_REJECTED")) {
            ObjectNode body = material.document("review/body.json");
            ObjectNode envelope = material.document("review/envelope.json");
            ObjectNode policy = material.document("review/policy.json");
            ObjectNode revocation = findDocument(material, "revocation-snapshot");
            boolean signatureValid = verifyReviewer(material, body, envelope, policy, revocation);
            if (guard.equals("REVIEW_COUNT_CONSISTENCY_REJECTED")) {
                ProjectionCounts derived = reviewCounts(body);
                boolean inconsistent = signatureValid && !reviewProjectionsAgree(material, derived);
                return observed(catalog, guard, !inconsistent,
                        "REVIEW_COUNT_CONSISTENCY_REJECTED", 2);
            }
            boolean semantics = reviewChecksMatchFindings(body, policy);
            return observed(catalog, guard, signatureValid && semantics,
                    "REVIEW_SIGNATURE_AUTHORITY", 2);
        }
        if (guard.equals("ROLLBACK_BINDING")) {
            ObjectNode result = material.document("admission/result.json");
            return observed(catalog, guard, result.path("rollback").path("instructionRef")
                            .path("rawFingerprint").equals(rawRef(material.path("rollback/gate-a.md"))),
                    "ROLLBACK_BINDING", 2);
        }
        if (guard.equals("A2_CONCLUSION_PRECEDENCE")) {
            ObjectNode result = material.document("admission/result.json");
            return observed(catalog, guard, a2ProjectionMatches(result, catalog),
                    "A2_CONCLUSION_PRECEDENCE", 2);
        }
        if (guard.equals("A2_RESULT_FINGERPRINT")) {
            return observed(catalog, guard, fingerprintMatches(material.document("admission/result.json"),
                    "resultFingerprint", DOMAINS.get("a2")), "A2_RESULT_FINGERPRINT", 2);
        }
        throw new IllegalArgumentException("no collector for " + guard);
    }

    private static void assertBaseline(Material material, String targetGuard, String caseId,
                                       GuardCatalog catalog) throws IOException {
        for (String guard : applicableGuards(targetGuard, catalog)) {
            Observed observed = collect(material, guard, catalog);
            require(observed.status().equals("PASS"), caseId + " baseline " + guard + " was " + observed);
        }
    }

    private static void assertUniqueHit(Material material, String targetGuard, String caseId,
                                        GuardCatalog catalog) throws IOException {
        List<String> hits = new ArrayList<>();
        Set<String> applicable = new HashSet<>(applicableGuards(targetGuard, catalog));
        for (GuardSpec spec : catalog.guards()) {
            String guard = spec.guardId();
            if (!applicable.contains(guard)) {
                continue;
            }
            try {
                if (!collect(material, guard, catalog).status().equals("PASS")) {
                    hits.add(guard);
                }
            } catch (IOException | RuntimeException notApplicable) {
                // A guard whose material is not in this case is deliberately not applicable.
            }
        }
        require(hits.equals(List.of(targetGuard)),
                caseId + " expected one hit " + targetGuard + ", got " + hits);
    }

    private static List<String> applicableGuards(String targetGuard, GuardCatalog catalog) {
        if (targetGuard.startsWith("A0_")) {
            return catalog.guards().stream().map(GuardSpec::guardId)
                    .filter(item -> item.startsWith("A0_")).toList();
        }
        if (targetGuard.startsWith("A1_") && !targetGuard.equals("A1_PROCESS_MATERIAL_CLOSURE")) {
            return catalog.guards().stream().map(GuardSpec::guardId)
                    .filter(item -> item.startsWith("A1_") && !item.equals("A1_PROCESS_MATERIAL_CLOSURE"))
                    .toList();
        }
        return switch (targetGuard) {
            case "A1_PROCESS_MATERIAL_CLOSURE" -> catalog.guards().stream().map(GuardSpec::guardId)
                    .filter(item -> item.equals("A1_PROCESS_MATERIAL_CLOSURE")
                            || item.equals("A1_RESULT_FINGERPRINT"))
                    .toList();
            case "HARNESS_PROOF_COMPLETENESS" -> List.of(targetGuard);
            case "ADMISSION_EVIDENCE_ROOT_CLOSURE", "ROLLBACK_BINDING" -> catalog.guards().stream()
                    .map(GuardSpec::guardId)
                    .filter(item -> item.equals(targetGuard) || item.startsWith("A2_"))
                    .toList();
            case "REVIEW_SIGNATURE_AUTHORITY", "REVIEW_COUNT_CONSISTENCY_REJECTED" -> catalog.guards()
                    .stream().map(GuardSpec::guardId)
                    .filter(item -> item.startsWith("REVIEW_")).toList();
            case "A2_CONCLUSION_PRECEDENCE", "A2_RESULT_FINGERPRINT" -> catalog.guards().stream()
                    .map(GuardSpec::guardId).filter(item -> item.startsWith("A2_")).toList();
            default -> List.of(targetGuard);
        };
    }

    private static void validateDocuments(Material material, JsonNode caseNode, String phase) throws IOException {
        for (JsonNode descriptor : caseNode.path("sourceMaterial").path("documents")) {
            String target = text(descriptor, "target");
            Path path = material.path(target);
            if (Files.isRegularFile(path)) {
                JsonNode document = JSON.readTree(Files.readAllBytes(path));
                requireSchema(document, SCHEMA_PREFIX + Path.of(text(descriptor, "schema")).getFileName(),
                        text(caseNode, "caseId") + " " + phase + " " + target);
            }
        }
    }

    private static void prepareReviewerTrust(Material material) throws IOException {
        ObjectNode policy = material.document("review/policy.json");
        if (policy.has("reviewerTrustPolicyFingerprint")) {
            material.writeBytes("review/policy.json", canonicalDocumentBytes(policy,
                    "reviewerTrustPolicyFingerprint", "RG-CS-REVIEWER-TRUST-POLICY-v1"));
        }
        ObjectNode revocation = findDocument(material, "revocation-snapshot");
        if (revocation != null && revocation.has("reviewerRevocationSnapshotFingerprint")) {
            material.writeBytes(material.documentTarget(revocation), canonicalDocumentBytes(revocation,
                    "reviewerRevocationSnapshotFingerprint",
                    "RG-CS-REVIEWER-REVOCATION-SNAPSHOT-v1"));
            ObjectNode actualPolicy = material.document("review/policy.json");
            ObjectNode revocationRef = rawRef(material.documentPath(revocation));
            actualPolicy.set("revocationSnapshotRawFingerprint", revocationRef);
            material.writeBytes("review/policy.json", canonicalDocumentBytes(actualPolicy,
                    "reviewerTrustPolicyFingerprint", "RG-CS-REVIEWER-TRUST-POLICY-v1"));
            ObjectNode envelope = material.document("review/envelope.json");
            envelope.set("revocationSnapshotRawFingerprint", revocationRef);
            material.writeDocument(envelope, "review/envelope.json");
        }
    }

    private static void refreshReviewerMaterial(Material material) throws IOException {
        ObjectNode revocation = findDocument(material, "revocation-snapshot");
        if (revocation == null) {
            return;
        }
        Path revocationPath = material.documentPath(revocation);
        ObjectNode policy = material.document("review/policy.json");
        policy.set("revocationSnapshotRawFingerprint", rawRef(revocationPath));
        material.writeBytes("review/policy.json", canonicalDocumentBytes(policy,
                "reviewerTrustPolicyFingerprint", "RG-CS-REVIEWER-TRUST-POLICY-v1"));
        ObjectNode envelope = material.document("review/envelope.json");
        envelope.set("revocationSnapshotRawFingerprint", rawRef(revocationPath));
        material.writeDocument(envelope, "review/envelope.json");
    }

    private static void signReview(Material material, String mode) throws IOException {
        ObjectNode body = material.document("review/body.json");
        switch (mode) {
            case "check-mismatch" -> {
                // The check status is changed below after the common fixture preparation.
            }
            case "duplicate-finding-id" -> {
                ObjectNode duplicate = ((ObjectNode) body.path("findings").get(0)).deepCopy();
                duplicate.put("status", "RESOLVED");
                duplicate.put("detail", "A second signed finding deliberately reuses F-001.");
                body.withArray("findings").add(duplicate);
            }
            case "finding-order-drift" -> {
                ObjectNode first = ((ObjectNode) body.path("findings").get(0)).deepCopy();
                ObjectNode second = first.deepCopy();
                first.put("findingId", "F-002");
                first.put("detail", "Second finding deliberately appears first.");
                second.put("findingId", "F-001");
                second.put("detail", "First finding deliberately appears second.");
                ArrayNode findings = body.withArray("findings");
                findings.removeAll();
                findings.add(first).add(second);
            }
            case "underreport-open-p1" ->
                    ((ObjectNode) body.path("findings").get(0)).put("severity", "P1");
            case "underreport-skipped-count" ->
                    ((ObjectNode) body.path("reviewChecks").get(1)).put("status", "SKIPPED");
            default -> {
                // Baseline and underreport both retain the one valid open P0 finding.
            }
        }
        if (mode.equals("check-mismatch")) {
            ((ObjectNode) body.path("reviewChecks").get(0)).put("status", "PASS");
        }
        ProjectionCounts derived = reviewCounts(body);
        body.put("openP0", mode.equals("underreport") ? 0 : derived.openP0());
        body.put("openP1", mode.equals("underreport-open-p1") ? 0 : derived.openP1());
        body.put("skippedCount", mode.equals("underreport-skipped-count") ? 0 : derived.skippedCount());
        material.writeBytes("review/body.json", canonicalDocumentBytes(body, "reviewBodyFingerprint",
                "RG-CS-REVIEW-BODY-v1"));

        ObjectNode envelope = material.document("review/envelope.json");
        envelope.put("openP0", body.path("openP0").asInt());
        envelope.put("openP1", body.path("openP1").asInt());
        envelope.put("skippedCount", body.path("skippedCount").asInt());
        envelope.set("reviewBodyRawFingerprint", rawRef(material.path("review/body.json")));
        try {
            envelope.put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signBytes(envelope)));
        } catch (Exception failure) {
            throw new IllegalStateException("JDK Ed25519 unavailable", failure);
        }
        material.writeBytes("review/envelope.json", canonicalDocumentBytes(envelope, "envelopeFingerprint",
                "RG-CS-REVIEW-ENVELOPE-v1"));
    }

    private static boolean verifyReviewer(Material material, ObjectNode body, ObjectNode envelope,
                                          ObjectNode policy, ObjectNode revocation) {
        try {
            if (!"Ed25519".equals(envelope.path("signatureAlgorithm").asText())
                    || !"Ed25519".equals(policy.path("signatureAlgorithm").asText())
                    || !policy.path("issuer").asText().equals(envelope.path("issuer").asText())
                    || !envelope.path("candidateRawFingerprint").equals(policy.path("candidateSubject"))
                    || !reviewerTemporalBoundsMatch(body, envelope, policy, revocation)
                    || !stream(policy.path("allowedAuthorities"))
                    .anyMatch(value -> value.asText().equals(envelope.path("authorityId").asText()))) {
                return false;
            }
            JsonNode selected = null;
            for (JsonNode key : policy.path("allowedKeys")) {
                if (key.path("keyId").asText().equals(envelope.path("keyId").asText())) {
                    if (selected != null) {
                        return false;
                    }
                    selected = key;
                }
            }
            if (selected == null || !reviewerKeyAndRevocationMatch(material, envelope, policy, revocation)
                    || !fingerprintMatches(body, "reviewBodyFingerprint", "RG-CS-REVIEW-BODY-v1")
                    || !fingerprintMatches(envelope, "envelopeFingerprint", "RG-CS-REVIEW-ENVELOPE-v1")
                    || !envelope.path("reviewBodyRawFingerprint").equals(rawRef(material.path("review/body.json")))) {
                return false;
            }
            return verifyEnvelopeSignature(envelope, selected);
        } catch (Exception failure) {
            return false;
        }
    }

    private static void assertCryptographicSignatureRemainsValid(Material material, String caseId)
            throws IOException {
        ObjectNode envelope = material.document("review/envelope.json");
        ObjectNode policy = material.document("review/policy.json");
        JsonNode selected = null;
        for (JsonNode key : policy.path("allowedKeys")) {
            if (key.path("keyId").asText().equals(envelope.path("keyId").asText())) {
                require(selected == null, caseId + " selected duplicate signing keys");
                selected = key;
            }
        }
        require(selected != null && verifyEnvelopeSignature(envelope, selected),
                caseId + " must retain a cryptographically valid authorized signature");
    }

    private static boolean verifyEnvelopeSignature(ObjectNode envelope, JsonNode selected) {
        try {
            if (!"Ed25519".equals(envelope.path("signatureAlgorithm").asText())) {
                return false;
            }
            byte[] rawPublicKey = Base64.getUrlDecoder().decode(
                    selected.path("publicKeyBase64Url").asText());
            if (rawPublicKey.length != 32) {
                return false;
            }
            PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(concat(ED25519_PUBLIC_DER_PREFIX, rawPublicKey)));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(signingBytes(envelope));
            return verifier.verify(Base64.getUrlDecoder().decode(
                    envelope.path("signature").asText()));
        } catch (Exception failure) {
            return false;
        }
    }

    private static boolean reviewerTemporalBoundsMatch(ObjectNode body, ObjectNode envelope,
                                                       ObjectNode policy, ObjectNode revocation) {
        try {
            if (revocation == null
                    || !body.path("reviewedAt").equals(envelope.path("reviewedAt"))) {
                return false;
            }
            Instant reviewedAt = Instant.parse(envelope.path("reviewedAt").asText());
            Instant envelopeValidUntil = Instant.parse(envelope.path("validUntil").asText());
            return !reviewedAt.isBefore(Instant.parse(policy.path("notBefore").asText()))
                    && !reviewedAt.isAfter(envelopeValidUntil)
                    && !envelopeValidUntil.isAfter(Instant.parse(policy.path("validUntil").asText()))
                    && !envelopeValidUntil.isAfter(Instant.parse(revocation.path("validUntil").asText()))
                    && !reviewedAt.isBefore(Instant.parse(revocation.path("issuedAt").asText()))
                    && !reviewedAt.isAfter(Instant.parse(revocation.path("validUntil").asText()));
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean reviewerKeyAndRevocationMatch(Material material, ObjectNode envelope,
                                                          ObjectNode policy, ObjectNode revocation)
            throws IOException {
        if (!envelope.path("admissionProfileRawFingerprint")
                .equals(policy.path("admissionProfileRawFingerprint"))
                || !envelope.path("reviewedMaterialRootFingerprint")
                .equals(material.document("review/body.json").path("reviewedMaterialRootFingerprint"))
                || revocation == null) {
            return false;
        }
        if (!policy.path("revocationSnapshotRawFingerprint").equals(
                envelope.path("revocationSnapshotRawFingerprint"))) {
            return false;
        }
        Path revocationPath = material.documentPath(revocation);
        if (revocationPath == null
                || !policy.path("revocationSnapshotRawFingerprint").equals(rawRef(revocationPath))
                || !policy.path("issuer").asText().equals(revocation.path("issuer").asText())
                || stream(revocation.path("revokedKeyIds"))
                .anyMatch(value -> value.asText().equals(envelope.path("keyId").asText()))
                || stream(revocation.path("revokedAuthorityIds"))
                .anyMatch(value -> value.asText().equals(envelope.path("authorityId").asText()))) {
            return false;
        }
        return fingerprintMatches(policy, "reviewerTrustPolicyFingerprint",
                "RG-CS-REVIEWER-TRUST-POLICY-v1")
                && fingerprintMatches(revocation, "reviewerRevocationSnapshotFingerprint",
                "RG-CS-REVIEWER-REVOCATION-SNAPSHOT-v1");
    }

    private static byte[] signingBytes(ObjectNode envelope) {
        ObjectNode claims = envelope.deepCopy();
        claims.remove(List.of("signature", "envelopeFingerprint"));
        return sha256(concat(bytes("RG-CS-REVIEW-ENVELOPE-SIGNING-v1\0"), canonicalBytes(claims)));
    }

    private static byte[] signBytes(ObjectNode envelope) throws Exception {
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
                new PKCS8EncodedKeySpec(concat(ED25519_PRIVATE_DER_PREFIX, REVIEW_SEED)));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(signingBytes(envelope));
        return signer.sign();
    }

    private static byte[] canonicalDocumentBytes(ObjectNode document, String selfField, String domain) {
        ObjectNode copy = document.deepCopy();
        copy.putNull(selfField);
        String canonical = canonicalBytes(copy, true);
        copy.set(selfField, typedFingerprint("CANONICAL_DOCUMENT", sha256Text(
                concat(bytes(domain + "\0"), bytes(canonical)))));
        return bytes(canonicalBytes(copy, true));
    }

    private static String canonicalBytes(JsonNode value, boolean strict) {
        try {
            CapabilityStudioCanonicalizationReference.JsonValue parsed =
                    CapabilityStudioCanonicalizationReference.parseUtf8(JSON.writeValueAsBytes(value));
            return CapabilityStudioCanonicalizationReference.canonicalize(parsed);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] canonicalBytes(JsonNode value) {
        return bytes(canonicalBytes(value, true));
    }

    private static ObjectNode treeFingerprint(Path root, String domain) throws IOException {
        ArrayNode entries = JSON.createArrayNode();
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile).sorted(Comparator.comparing(
                    path -> root.relativize(path).toString().replace('\\', '/'))).toList();
        }
        for (Path file : files) {
            ObjectNode entry = JSON.createObjectNode();
            entry.put("relativePath", root.relativize(file).toString().replace('\\', '/'));
            entry.put("kind", "FILE");
            byte[] content = Files.readAllBytes(file);
            entry.put("byteLength", content.length);
            entry.set("rawFingerprint", rawRef(content));
            entries.add(entry);
        }
        return typedFingerprint("TREE_COMMITMENT", sha256Text(
                concat(bytes(domain + "\0"), canonicalBytes(entries))));
    }

    private static ObjectNode codeSourceObservation(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        ObjectNode snapshot = JSON.createObjectNode();
        snapshot.put("resolvedPath", file.toRealPath().toString());
        snapshot.put("fileKey", fileKey(file));
        snapshot.put("owner", owner(file));
        snapshot.put("group", group(file));
        snapshot.put("linkCount", linkCount(file));
        snapshot.put("posixMode", posixMode(file));
        snapshot.put("fileSize", attributes.size());
        snapshot.set("readRawFingerprint", rawRef(file));
        ObjectNode result = JSON.createObjectNode();
        result.set("preRead", snapshot);
        result.set("postRead", snapshot.deepCopy());
        return result;
    }

    private static String owner(Path file) {
        try {
            return "uid:" + Files.readAttributes(file, PosixFileAttributes.class).owner().getName();
        } catch (IOException | UnsupportedOperationException failure) {
            return "owner:unknown";
        }
    }

    private static String group(Path file) {
        try {
            return "gid:" + Files.readAttributes(file, PosixFileAttributes.class).group().getName();
        } catch (IOException | UnsupportedOperationException failure) {
            return "group:unknown";
        }
    }

    private static int linkCount(Path file) {
        try {
            Map<String, Object> attributes = Files.readAttributes(file, "unix:nlink");
            return ((Number) attributes.getOrDefault("nlink", 1)).intValue();
        } catch (IOException | UnsupportedOperationException failure) {
            return 1;
        }
    }

    private static String posixMode(Path file) {
        try {
            Map<String, Object> attributes = Files.readAttributes(file, "unix:mode");
            int mode = ((Number) attributes.get("mode")).intValue() & 0x0fff;
            return String.format("0%03o", mode);
        } catch (IOException | UnsupportedOperationException failure) {
            return "unknown";
        }
    }

    private static boolean a0CountsMatch(ObjectNode document, List<String> statuses) {
        return document.path("adapterVerifiedCount").asInt(-1)
                == statuses.stream().filter("VERIFIED"::equals).count()
                && document.path("adapterInvalidCount").asInt(-1)
                == statuses.stream().filter("INVALID"::equals).count()
                && document.path("adapterUnavailableCount").asInt(-1)
                == statuses.stream().filter("UNAVAILABLE"::equals).count()
                && document.path("adapterNotRunCount").asInt(-1)
                == statuses.stream().filter("NOT_RUN"::equals).count()
                && document.path("obligationFailedCount").asInt(-1)
                == countStatus(document.path("obligationResults"), "FAIL")
                && document.path("obligationBlockedCount").asInt(-1)
                == countStatus(document.path("obligationResults"), "BLOCKED")
                && document.path("obligationNotRunCount").asInt(-1)
                == countStatus(document.path("obligationResults"), "NOT_RUN")
                && document.path("formalExpectedCount").asInt(-1) == 27;
    }

    private static boolean a1CountsMatch(ObjectNode document, List<String> statuses) {
        return document.path("passedCount").asInt(-1)
                == statuses.stream().filter("PASS"::equals).count()
                && document.path("failedCount").asInt(-1)
                == statuses.stream().filter("FAIL"::equals).count()
                && document.path("skippedCount").asInt(-1)
                == statuses.stream().filter("SKIPPED"::equals).count();
    }

    private static A1SlotState a1SlotState(ObjectNode document) {
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        boolean coherent = document.path("testCount").asInt(-1) == 9
                && document.path("testRuns").size() == 9;
        for (JsonNode slot : document.path("testRuns")) {
            boolean mechanismMatches = slot.hasNonNull("expectedMechanism")
                    && slot.path("processExitCode").asInt(-99)
                    == slot.path("expectedExitCode").asInt(-98)
                    && slot.path("observedTerminal").asText()
                    .equals(slot.path("expectedTerminal").asText())
                    && slot.path("closedReasonCode").asText()
                    .equals(slot.path("expectedReasonCode").asText());
            if (slot.path("skipped").asBoolean(false)) {
                skipped++;
            } else if ("PASS".equals(slot.path("status").asText()) && mechanismMatches) {
                passed++;
            } else {
                failed++;
                coherent = false;
            }
        }
        boolean countsMatch = document.path("testCount").asInt(-1) == 9
                && document.path("passedCount").asInt(-1) == passed
                && document.path("failedCount").asInt(-1) == failed
                && document.path("skippedCount").asInt(-1) == skipped;
        coherent = coherent && countsMatch && skipped == 0
                && document.path("scratchBeforeCount").asInt(-1) == 0
                && document.path("scratchAfterCount").asInt(-1) == 0;
        return new A1SlotState(passed, failed, skipped, coherent, countsMatch);
    }

    private static int countStatus(JsonNode values, String status) {
        int count = 0;
        for (JsonNode value : values) {
            if (status.equals(value.path("status").asText())) {
                count++;
            }
        }
        return count;
    }

    private static boolean a0ReferenceClosure(Material material, ObjectNode document)
            throws IOException {
        JsonNode candidate = document.path("candidateArtifactRef");
        if (!verifyRawReference(material, candidate, true)) {
            return false;
        }
        return verifyReferences(material, document, Set.of(candidate));
    }

    private static boolean verifyReferences(Material material, JsonNode value,
                                            Set<JsonNode> required) throws IOException {
        if (value.isObject()) {
            if (value.has("uri") && value.has("rawFingerprint")) {
                String uri = value.path("uri").asText();
                if (required.contains(value) || material.isDeclared(uri)) {
                    if (!verifyRawReference(material, value, true)) {
                        return false;
                    }
                }
            }
            if (value.has("uri") && value.has("fingerprint")
                    && "TREE_COMMITMENT".equals(value.path("fingerprint").path("kind").asText())) {
                String uri = value.path("uri").asText();
                if (material.isDeclared(uri)) {
                    Path root = material.optionalPath(uri);
                    if (root == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                            || !value.path("fingerprint").equals(
                            treeFingerprint(root, treeDomain(value, uri)))) {
                        return false;
                    }
                }
            }
            var fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (!verifyReferences(material, entry.getValue(), required)) {
                    return false;
                }
            }
        } else if (value.isArray()) {
            for (JsonNode item : value) {
                if (!verifyReferences(material, item, required)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean verifyRawReference(Material material, JsonNode reference,
                                              boolean required) throws IOException {
        String uri = reference.path("uri").asText("");
        Path path = material.optionalPath(uri);
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return !required;
        }
        if (!reference.path("rawFingerprint").equals(rawRefStable(path))) {
            return false;
        }
        return identityFieldsMatch(reference, path);
    }

    private static boolean identityFieldsMatch(JsonNode reference, Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (reference.has("fileSize") && reference.path("fileSize").asLong(-1) != attributes.size()) {
            return false;
        }
        return !reference.has("fileKey") || reference.path("fileKey").asText()
                .equals(fileKey(path));
    }

    private static A1Closure verifyA1Closure(Material material, ObjectNode process)
            throws IOException {
        boolean streamsClosed = true;
        for (String stream : List.of("stdoutRef", "stderrRef")) {
            streamsClosed &= verifyRawReference(material, process.path(stream), true);
        }
        if (!streamsClosed) {
            return a1Failure("A1_PROCESS_MATERIAL_CLOSURE");
        }
        if ("FAILED".equals(process.path("processState").asText())) {
            return new A1Closure(false, true, "A1_OUTER_TRANSCRIPT_CRASHED", 3,
                    "UNAVAILABLE");
        }
        if (process.has("transcriptFingerprint")
                && !fingerprintMatches(process, "transcriptFingerprint",
                "RG-CS-GATE-A-PROCESS-TRANSCRIPT-v1")) {
            return a1Failure("A1_PROCESS_TRANSCRIPT_FINGERPRINT");
        }
        if (!verifyReferences(material, process, Set.of())) {
            return a1Failure("A1_PROCESS_MATERIAL_CLOSURE");
        }
        ObjectNode envelope = findDocument(material, "replay-proof-envelope");
        if (envelope == null) {
            return a1Failure("A1_PROCESS_MATERIAL_CLOSURE");
        }
        JsonNode replayResultRef = envelope.path("replayResultRef");
        Path replayResultPath = material.optionalPath(replayResultRef.path("uri").asText());
        if (replayResultPath == null
                || !Files.isRegularFile(replayResultPath, LinkOption.NOFOLLOW_LINKS)) {
            return a1Failure("A1_REPLAY_RESULT_REF_CLOSURE");
        }
        if (!verifyEnvelopeReference(material, envelope, "replayResultRef",
                envelope.path("replayResultMessageVersion").asText())) {
            return a1Failure("A1_REPLAY_RESULT_RAW_FINGERPRINT");
        }
        if (!verifyEnvelopeReference(material, envelope, "producerProcessTranscriptRef",
                envelope.path("producerProcessMessageVersion").asText())) {
            return a1Failure("A1_PROCESS_TRANSCRIPT_RAW_FINGERPRINT");
        }
        if (!fingerprintMatches(envelope, "envelopeFingerprint", A1_ENVELOPE_DOMAIN)) {
            return a1Failure("A1_PROOF_ENVELOPE_FINGERPRINT");
        }
        if (!"CLOSED".equals(envelope.path("closureStatus").asText())) {
            return a1Failure("A1_PROCESS_MATERIAL_CLOSURE");
        }
        Path root = material.optionalPath(envelope.path("producerMaterialRootRef").path("uri").asText());
        if (root == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !envelope.path("producerMaterialRootRef").path("fingerprint").equals(
                treeFingerprint(root, RUN_MATERIAL_TREE_DOMAIN))) {
            return a1Failure("A1_MATERIAL_ROOT_CLOSURE");
        }
        ObjectNode result = referencedDocument(material, envelope.path("replayResultRef"));
        ObjectNode transcript = referencedDocument(material, envelope.path("producerProcessTranscriptRef"));
        if (result == null || transcript == null
                || !envelope.path("observedProcessState").asText()
                .equals(process.path("processState").asText())
                || envelope.path("observedExitCode").asInt(-1) != process.path("exitCode").asInt(-2)) {
            return a1Failure("A1_TERMINAL_EXIT_MAPPING");
        }
        String terminal = result.path("terminal").asText();
        int expectedExit = terminalExit(terminal);
        boolean terminalClosed = expectedExit >= 0
                && envelope.path("observedTerminal").asText().equals(terminal)
                && envelope.path("observedExitCode").asInt(-1) == expectedExit;
        if (!terminalClosed) {
            return a1Failure("A1_TERMINAL_EXIT_MAPPING");
        }
        return new A1Closure(true, false, "A1_PROCESS_MATERIAL_CLOSURE", 2, "FAIL");
    }

    private static A1Closure a1Failure(String reason) {
        return new A1Closure(false, false, reason, 2, "FAIL");
    }

    private static boolean verifyEnvelopeReference(Material material, ObjectNode envelope,
                                                   String field, String expectedMessageVersion)
            throws IOException {
        JsonNode reference = envelope.path(field);
        if (!verifyRawReference(material, reference, true)) {
            return false;
        }
        ObjectNode document = referencedDocument(material, reference);
        return document != null && expectedMessageVersion.equals(document.path("messageVersion").asText());
    }

    private static ObjectNode referencedDocument(Material material, JsonNode reference) {
        Path path = material.optionalPath(reference.path("uri").asText());
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            return (ObjectNode) JSON.readTree(Files.readAllBytes(path));
        } catch (IOException | ClassCastException failure) {
            return null;
        }
    }

    private static int terminalExit(String terminal) {
        return switch (terminal) {
            case "VERIFIED" -> 0;
            case "INVALID" -> 2;
            case "UNAVAILABLE" -> 3;
            default -> -1;
        };
    }

    private static boolean proofResultExists(Material material, ObjectNode envelope)
            throws IOException {
        JsonNode reference = envelope.path("replayResultRef");
        boolean raw = verifyRawReference(material, reference, true);
        ObjectNode document = referencedDocument(material, reference);
        return raw && document != null;
    }

    private static ObjectNode findDocument(Material material, String nameFragment) {
        for (String target : material.docs.keySet()) {
            if (target.contains(nameFragment)) {
                try {
                    return material.document(target);
                } catch (IOException | RuntimeException ignored) {
                    return null;
                }
            }
            try {
                ObjectNode document = material.document(target);
                if (document.path("messageVersion").asText().contains(nameFragment)
                        || document.path("schemaVersion").asText().contains(nameFragment)) {
                    return document;
                }
            } catch (IOException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean sameFileIdentity(Path expected, Path observed) {
        try {
            if (expected == null || observed == null) {
                return false;
            }
            BasicFileAttributes expectedAttributes = Files.readAttributes(expected,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes observedAttributes = Files.readAttributes(observed,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return expectedAttributes.fileKey() != null
                    && expectedAttributes.fileKey().equals(observedAttributes.fileKey())
                    && expected.toRealPath().equals(observed.toRealPath())
                    && rawRefStable(expected).equals(rawRefStable(observed));
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static ObjectNode rawRefStable(Path path) throws IOException {
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        byte[] content = Files.readAllBytes(path);
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (before.fileKey() == null || !before.fileKey().equals(after.fileKey())
                || before.size() != after.size()) {
            throw new IOException("unstable material identity");
        }
        return rawRef(content);
    }

    private record A1SlotState(int passed, int failed, int skipped, boolean coherent,
                               boolean countsMatch) {
    }

    private record A1Closure(boolean closed, boolean outerCrash, String reason, int exitCode,
                             String failureConclusion) {
    }

    private record ProjectionCounts(int openP0, int openP1, int skippedCount) {
    }

    private static ProjectionCounts reviewCounts(ObjectNode body) {
        int openP0 = 0;
        int openP1 = 0;
        for (JsonNode finding : body.path("findings")) {
            if ("OPEN".equals(finding.path("status").asText())
                    && "P0".equals(finding.path("severity").asText())) {
                openP0++;
            }
            if ("OPEN".equals(finding.path("status").asText())
                    && "P1".equals(finding.path("severity").asText())) {
                openP1++;
            }
        }
        return new ProjectionCounts(openP0, openP1, countStatus(body.path("reviewChecks"), "SKIPPED"));
    }

    private static boolean reviewProjectionsAgree(Material material, ProjectionCounts derived)
            throws IOException {
        List<ObjectNode> projections = new ArrayList<>();
        projections.add(material.document("review/body.json"));
        projections.add(material.document("review/envelope.json"));
        for (String target : material.docs.keySet()) {
            ObjectNode document = material.document(target);
            if (document.has("trustedReview") && document.path("trustedReview").isObject()) {
                projections.add((ObjectNode) document.path("trustedReview"));
            }
        }
        for (ObjectNode projection : projections) {
            if (projection.path("openP0").asInt(-1) != derived.openP0()
                    || projection.path("openP1").asInt(-1) != derived.openP1()
                    || projection.path("skippedCount").asInt(-1) != derived.skippedCount()) {
                return false;
            }
        }
        return true;
    }

    private static boolean reviewChecksMatchFindings(ObjectNode body, ObjectNode policy) {
        Set<String> required = new LinkedHashSet<>();
        for (JsonNode value : policy.path("requiredCheckIds")) {
            required.add(value.asText());
        }
        if (required.isEmpty()) {
            return false;
        }
        Map<String, Integer> findingsByCheck = new TreeMap<>();
        Set<String> findingIds = new HashSet<>();
        List<JsonNode> findings = new ArrayList<>();
        body.path("findings").forEach(findings::add);
        for (JsonNode finding : findings) {
            String id = finding.path("findingId").asText();
            String checkId = finding.path("checkId").asText();
            if (!findingIds.add(id) || !required.contains(checkId)
                    || !Set.of("P0", "P1", "P2").contains(finding.path("severity").asText())
                    || !Set.of("OPEN", "RESOLVED").contains(finding.path("status").asText())) {
                return false;
            }
            findingsByCheck.merge(checkId, 1, Integer::sum);
        }
        List<String> orderedIds = findings.stream().map(value -> value.path("findingId").asText()).toList();
        List<String> expectedOrder = findings.stream().sorted(Comparator
                .comparingInt((JsonNode value) -> severityRank(value.path("severity").asText()))
                .thenComparing(value -> value.path("checkId").asText())
                .thenComparing(value -> value.path("findingId").asText()))
                .map(value -> value.path("findingId").asText()).toList();
        if (!orderedIds.equals(expectedOrder)) {
            return false;
        }
        Set<String> seenChecks = new HashSet<>();
        for (JsonNode check : body.path("reviewChecks")) {
            String checkId = check.path("checkId").asText();
            if (!required.contains(checkId) || !seenChecks.add(checkId)) {
                return false;
            }
            int findingsForCheck = findingsByCheck.getOrDefault(checkId, 0);
            String status = check.path("status").asText();
            if (("PASS".equals(status) && findingsForCheck != 0)
                    || ("FINDING".equals(status) && findingsForCheck == 0)
                    || ("SKIPPED".equals(status) && findingsForCheck != 0)
                    || !Set.of("PASS", "FINDING", "SKIPPED").contains(status)) {
                return false;
            }
        }
        return seenChecks.equals(required);
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "P0" -> 0;
            case "P1" -> 1;
            case "P2" -> 2;
            default -> 3;
        };
    }

    private static boolean a2ProjectionMatches(ObjectNode result, GuardCatalog catalog) {
        List<String> requirements = List.of(
                "GATE-A-P0-01-TYPED-EVIDENCE", "GATE-A-P0-02-INDEPENDENT-VERIFIER",
                "GATE-A-P0-03-LEGACY-TERMINAL", "GATE-A-P1-01-MANIFEST-STABILITY",
                "GATE-A-P1-02-HONEST-INCOMPLETE");
        List<String> artifacts = List.of(
                "IMPLEMENTATION_CANDIDATE", "INDEPENDENT_VERIFIER", "GATE_VERIFIER", "TEST_REPORT");
        List<String> tests = List.of(
                "PLACEHOLDER_REJECTED", "WRONG_KIND_REJECTED", "WRONG_VERIFIER_REVISION_REJECTED",
                "VERIFIER_DIGEST_MUTATION_REJECTED", "REGISTRY_MUTATION_REJECTED",
                "VERIFIER_TCK_MISMATCH_REJECTED", "LEGACY_ACCEPTED_COMPLETE_AUTHORITY",
                "LEGACY_ACCEPTED_MISSING_STORE_REJECTED", "LEGACY_ACCEPTED_MISSING_OWNER_REJECTED",
                "LEGACY_ACCEPTED_MISSING_TARGET_REJECTED", "MANIFEST_IDENTITY_DRIFT_REJECTED",
                "HONEST_INCOMPLETE_ACCEPTED");
        if (!orderedProjectionMatches(result.path("requirements"), "requirementId", requirements)
                || !orderedProjectionMatches(result.path("artifacts"), "role", artifacts)
                || !orderedProjectionMatches(result.path("tests"), "testId", tests)
                || !orderedProjectionMatches(result.path("mandatoryGuards"), "guardId",
                List.of("PROVIDER_NAMESPACE_COLLISION_REJECTED", "REVIEW_COUNT_CONSISTENCY_REJECTED"))) {
            return false;
        }
        JsonNode trustedReview = result.path("trustedReview");
        if (!trustedReview.isObject()
                || !"INDEPENDENT_TRUSTED_REVIEW".equals(trustedReview.path("reviewRole").asText())) {
            return false;
        }
        List<String> statuses = new ArrayList<>();
        addStatuses(statuses, result.path("requirements"));
        addStatuses(statuses, result.path("artifacts"));
        addStatuses(statuses, result.path("tests"));
        addStatuses(statuses, result.path("mandatoryGuards"));
        statuses.add(trustedReview.path("status").asText());

        JsonNode guards = result.path("semanticGuardResults");
        if (!guards.isArray() || guards.size() != catalog.guards().size()) {
            return false;
        }
        for (int index = 0; index < catalog.guards().size(); index++) {
            JsonNode guard = guards.get(index);
            GuardSpec expected = catalog.guards().get(index);
            if (!expected.guardId().equals(guard.path("guardId").asText())
                    || !expected.admissionTarget().equals(guard.path("admissionTarget").asText())
                    || !expected.sourceFactIds().equals(texts(guard.path("sourceFactIds")))
                    || !sortedUniqueObservationRefs(guard.path("observationRefs"))) {
                return false;
            }
            statuses.add(guard.path("status").asText());
        }
        String derived = reduceStatus(statuses);
        String declared = result.path("conclusion").asText();
        String reason = switch (derived) {
            case "PASS" -> "GATE_A_ADMITTED";
            case "OPEN" -> "GATE_A_REQUIRED_MATERIAL_MISSING";
            case "FAIL" -> "GATE_A_VERIFICATION_FAILED";
            default -> "GATE_A_VERIFICATION_UNAVAILABLE";
        };
        return derived.equals(declared)
                && reason.equals(result.path("conclusionReasonCode").asText())
                && (("PASS".equals(derived) && "GATE-B".equals(result.path("nextAllowedGate").asText()))
                || (!"PASS".equals(derived) && result.path("nextAllowedGate").isNull()));
    }

    private static boolean orderedProjectionMatches(JsonNode values, String identity,
                                                    List<String> expected) {
        if (!values.isArray() || values.size() != expected.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!expected.get(index).equals(values.get(index).path(identity).asText())) {
                return false;
            }
            String status = values.get(index).path("status").asText();
            if (!Set.of("PASS", "FAIL", "MISSING", "UNAVAILABLE").contains(status)) {
                return false;
            }
        }
        return true;
    }

    private static void addStatuses(List<String> statuses, JsonNode values) {
        for (JsonNode value : values) {
            statuses.add(value.path("status").asText());
            if (value.path("skipped").asBoolean(false)) {
                statuses.add("MISSING");
            }
        }
    }

    private static String reduceStatus(List<String> statuses) {
        if (statuses.stream().anyMatch("UNAVAILABLE"::equals)) {
            return "UNAVAILABLE";
        }
        if (statuses.stream().anyMatch("FAIL"::equals)) {
            return "FAIL";
        }
        if (statuses.stream().anyMatch("MISSING"::equals)) {
            return "OPEN";
        }
        return "PASS";
    }

    private static List<String> texts(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private static boolean sortedUniqueObservationRefs(JsonNode values) {
        if (!values.isArray()) {
            return false;
        }
        String previous = null;
        Set<String> unique = new HashSet<>();
        for (JsonNode value : values) {
            String current = value.path("uri").asText();
            if (current.isEmpty() || (previous != null && previous.compareTo(current) >= 0)
                    || !unique.add(current)) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    private static String treeDomain(JsonNode reference, String uri) {
        if (reference.isObject()) {
            if (reference.has("producerMaterialRootRef")) {
                return "RG-CS-GATE-A-RUN-MATERIAL-ROOT-v1";
            }
        }
        if (uri.contains("challenge")) {
            return "RG-CS-GATE-A-CHALLENGE-INPUT-ROOT-v1";
        }
        if (uri.contains("admission")) {
            return ADMISSION_TREE_DOMAIN;
        }
        return "RG-CS-GATE-A-RUN-MATERIAL-ROOT-v1";
    }

    private static boolean fingerprintMatches(ObjectNode document, String field, String domain) {
        JsonNode expected = document.path(field);
        ObjectNode actual = typedFingerprint("CANONICAL_DOCUMENT", sha256Text(
                concat(bytes(domain + "\0"), canonicalBytes(withNull(document, field)))));
        return expected.equals(actual);
    }

    private static ObjectNode withNull(ObjectNode source, String field) {
        ObjectNode copy = source.deepCopy();
        copy.putNull(field);
        return copy;
    }

    private static ObjectNode rawRef(Path path) throws IOException {
        return rawRef(Files.readAllBytes(path));
    }

    private static ObjectNode rawRef(byte[] content) {
        return typedFingerprint("RAW_BYTES", sha256Text(content));
    }

    private static ObjectNode typedFingerprint(String kind, String value) {
        return JSON.createObjectNode().put("kind", kind).put("algorithm", "SHA-256").put("value", value);
    }

    private static String selfField(ObjectNode document) {
        for (String field : List.of("resultFingerprint", "transcriptFingerprint", "envelopeFingerprint",
                "reviewBodyFingerprint", "reviewerTrustPolicyFingerprint",
                "reviewerRevocationSnapshotFingerprint", "identityFingerprint")) {
            if (document.has(field)) {
                return field;
            }
        }
        return null;
    }

    private static String documentDomain(ObjectNode document, String field) {
        String version = document.path("messageVersion").asText()
                + document.path("schemaVersion").asText();
        return switch (field) {
            case "resultFingerprint" -> version.contains("candidate-replay-result")
                    ? DOMAINS.get("a0")
                    : version.contains("replay-verification-result") ? DOMAINS.get("a1")
                    : version.contains("admission-verification-result") ? DOMAINS.get("a2") : null;
            case "transcriptFingerprint" -> "RG-CS-GATE-A-PROCESS-TRANSCRIPT-v1";
            case "reviewBodyFingerprint" -> "RG-CS-REVIEW-BODY-v1";
            case "reviewerTrustPolicyFingerprint" -> "RG-CS-REVIEWER-TRUST-POLICY-v1";
            case "reviewerRevocationSnapshotFingerprint" ->
                    "RG-CS-REVIEWER-REVOCATION-SNAPSHOT-v1";
            case "envelopeFingerprint" -> version.contains("replay-proof-envelope")
                    ? "RG-CS-GATE-A1-PROOF-ENVELOPE-v1"
                    : version.contains("admission-proof-envelope")
                    ? "RG-CS-GATE-A2-PROOF-ENVELOPE-v1"
                    : version.contains("reviewer-authority-envelope")
                    ? "RG-CS-REVIEW-ENVELOPE-v1" : null;
            case "identityFingerprint" -> "RG-CS-GATE-A-BUILD-IDENTITY-v1";
            default -> null;
        };
    }

    private static String treeDomainForField(String fieldName, String uri) {
        if ("producerMaterialRootRef".equals(fieldName) || uri.contains("run-material")) {
            return "RG-CS-GATE-A-RUN-MATERIAL-ROOT-v1";
        }
        if ("challengeInputRootRef".equals(fieldName) || uri.contains("challenge")) {
            return "RG-CS-GATE-A-CHALLENGE-INPUT-ROOT-v1";
        }
        return ADMISSION_TREE_DOMAIN;
    }

    private static void writeProviderJar(Path path, boolean collision) throws IOException {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            zipEntry(output,
                    "META-INF/services/com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityProvider",
                    bytes("com.leanowtech.bloge.gatetckprovider.GateATckProvider\n"));
            zipEntry(output, "com/leanowtech/bloge/gatetckprovider/GateATckProvider.class",
                    bytes("gate-a-provider-class-v1\n"));
            if (collision) {
                zipEntry(output, "com/leanowtech/bloge/gateway/testkit/ShadowProvider.class",
                        bytes("forbidden-provider-class-v1\n"));
            }
        }
    }

    private static void zipEntry(ZipOutputStream output, String name, byte[] value) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        output.putNextEntry(entry);
        output.write(value);
        output.closeEntry();
    }

    private static void setRawFingerprint(JsonNode parent, Path file) throws IOException {
        ((ObjectNode) parent).setAll(rawRef(file));
    }

    private static String fileKey(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        return String.valueOf(attributes.fileKey()).replaceAll("[^A-Za-z0-9._:-]", "_");
    }

    private static void requireSchema(JsonNode value, String resource, String context) {
        try {
            String fileName = Path.of(resource).getFileName().toString();
            Path schemaPath = repositoryRoot().resolve("docs/schemas/resource-gateway-capability-studio")
                    .resolve(fileName);
            JsonNode schemaDocument = JSON.readTree(Files.readAllBytes(schemaPath));
            String schemaId = schemaDocument.path("$id").asText(
                    "https://leanowtech.com/schemas/resource-gateway-capability-studio/" + fileName);
            Schema schema = SCHEMA_REGISTRY.getSchema(
                    SchemaLocation.of(schemaId), schemaDocument.toString(), InputFormat.JSON);
            List<Error> errors = schema.validate(value.toString(), InputFormat.JSON);
            require(errors.isEmpty(), context + " schema errors: " + errors);
        } catch (IOException failure) {
            throw new IllegalStateException(context + " schema could not be loaded", failure);
        }
    }

    private static String schemaTextForUri(String uri) {
        try {
            String fileName = Path.of(java.net.URI.create(uri).getPath()).getFileName().toString();
            Path path = repositoryRoot().resolve("docs/schemas/resource-gateway-capability-studio")
                    .resolve(fileName);
            return Files.readString(path);
        } catch (Exception failure) {
            throw new IllegalStateException("local schema ref unavailable: " + uri, failure);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(MATERIAL_MANIFEST))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found from " + System.getProperty("user.dir"));
    }

    private static String text(JsonNode node, String field) {
        require(node.hasNonNull(field), "missing field " + field);
        return node.path(field).asText();
    }

    private static java.util.stream.Stream<JsonNode> stream(JsonNode node) {
        return node.isArray() ? java.util.stream.StreamSupport.stream(node.spliterator(), false)
                : java.util.stream.Stream.empty();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] canonicalBytes(ObjectNode value) {
        return canonicalBytes((JsonNode) value);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String sha256Text(byte[] value) {
        return "sha256:" + hex(sha256(value));
    }

    private static String hex(byte[] value) {
        StringBuilder output = new StringBuilder(value.length * 2);
        for (byte item : value) {
            output.append(Character.forDigit((item >>> 4) & 0xf, 16));
            output.append(Character.forDigit(item & 0xf, 16));
        }
        return output.toString();
    }

    private static byte[] hexBytes(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = new byte[left.length + right.length];
        System.arraycopy(left, 0, result, 0, left.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    private static void deleteTree(Path root) {
        try {
            if (root == null || !Files.exists(root)) {
                return;
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            throw new AssertionError("failed to delete material root " + root, failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private record GuardSpec(String guardId, String admissionTarget, String mismatch,
                             String unavailable, List<String> sourceFactIds) {
    }

    private record GuardCatalog(List<GuardSpec> guards, Map<String, GuardSpec> byId) {
        private static GuardCatalog read(JsonNode document) {
            require(document.isObject(), "Guard Catalog must be an object");
            List<GuardSpec> guards = new ArrayList<>();
            Map<String, GuardSpec> byId = new LinkedHashMap<>();
            for (JsonNode value : document.path("guards")) {
                GuardSpec guard = new GuardSpec(
                        text(value, "guardId"), text(value, "admissionTarget"),
                        text(value, "mismatch"), text(value, "unavailable"),
                        texts(value.path("sourceFactIds")));
                require(byId.put(guard.guardId(), guard) == null,
                        "duplicate Guard Catalog entry: " + guard.guardId());
                guards.add(guard);
            }
            require(guards.size() == 18, "expected 18 Guard Catalog entries");
            return new GuardCatalog(List.copyOf(guards), Map.copyOf(byId));
        }

        private GuardSpec spec(String guardId) {
            return byId.get(guardId);
        }
    }

    record Observed(String guardId, String status, String admissionTarget, String conclusion,
                    String reason, int exitCode) {
    }

    private static Observed observed(GuardCatalog catalog, String guard, boolean passed,
                                     String reason, int exit) {
        return observed(catalog, guard, passed, reason, exit, "FAIL");
    }

    private static Observed observed(GuardCatalog catalog, String guard, boolean passed,
                                     String reason, int exit, String failureConclusion) {
        GuardSpec spec = catalog.spec(guard);
        require(spec != null, "Guard is absent from Catalog: " + guard);
        return new Observed(guard, passed ? "PASS" : failureConclusion, spec.admissionTarget(),
                passed ? "PASS" : failureConclusion, passed ? "GUARD_PASSED" : reason, passed ? 0 : exit);
    }

    private record Expected(String status, String admissionTarget, String conclusion, String reason, int exitCode) {
        static Expected from(JsonNode node) {
            return new Expected(text(node, "status"), text(node, "admissionTarget"),
                    text(node, "conclusion"), text(node, "reason"), node.path("exitCode").asInt(-1));
        }

        void assertMatches(Observed actual, String caseId) {
            require(status.equals(actual.status()), caseId + " status expected " + status + " got " + actual.status());
            require(admissionTarget.equals(actual.admissionTarget()), caseId + " admission target drift");
            require(conclusion.equals(actual.conclusion()), caseId + " conclusion expected " + conclusion
                    + " got " + actual.conclusion());
            require(reason.equals(actual.reason()), caseId + " reason expected " + reason + " got " + actual.reason());
            require(exitCode == actual.exitCode(), caseId + " exit expected " + exitCode + " got " + actual.exitCode());
        }
    }

    private static final class Material {
        private final Path repository;
        private final Path root;
        private final JsonNode caseNode;
        private final GuardCatalog catalog;
        private final Map<String, Path> docs = new LinkedHashMap<>();
        private final Set<String> declaredTargets = new LinkedHashSet<>();
        private Path pinnedCodeSource;
        private Path aliasCodeSource;

        private Material(Path repository, Path root, JsonNode caseNode, GuardCatalog catalog) {
            this.repository = repository;
            this.root = root;
            this.caseNode = caseNode;
            this.catalog = catalog;
        }

        private void copyDocuments() throws IOException {
            for (JsonNode descriptor : caseNode.path("sourceMaterial").path("documents")) {
                String target = text(descriptor, "target");
                Path destination = path(target);
                Files.createDirectories(destination.getParent());
                Files.copy(repository.resolve(sourceFixture(descriptor)), destination);
                docs.put(target, destination);
                declaredTargets.add(target);
            }
            for (JsonNode descriptor : caseNode.path("sourceMaterial").path("rawMaterials")) {
                String target = text(descriptor, "target");
                declaredTargets.add(target);
                String fixture = sourceFixture(descriptor, null);
                if (fixture != null) {
                    Path source = repository.resolve(fixture);
                    Path destination = path(target);
                    Files.createDirectories(destination.getParent());
                    if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                        copyTree(source, destination);
                    } else {
                        Files.copy(source, destination);
                    }
                } else if ("DIRECTORY_TREE".equals(descriptor.path("kind").asText())) {
                    Files.createDirectories(path(target));
                }
            }
        }

        private String sourceFixture(JsonNode descriptor) {
            return sourceFixture(descriptor, "");
        }

        private String sourceFixture(JsonNode descriptor, String fallback) {
            for (String field : List.of("baseFixture", "fixture", "sourceFixture", "source")) {
                String value = descriptor.path(field).asText(null);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return fallback;
        }

        private void copyTree(Path source, Path destination) throws IOException {
            try (var stream = Files.walk(source)) {
                for (Path path : stream.toList()) {
                    Path relative = source.relativize(path);
                    Path target = destination.resolve(relative.toString());
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(target);
                    } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(target.getParent());
                        Files.copy(path, target);
                    }
                }
            }
        }

        private void refreshActualReferences() throws IOException {
            for (String target : new ArrayList<>(docs.keySet())) {
                ObjectNode document = document(target);
                refreshReferences(document, null);
                String selfField = selfField(document);
                String domain = selfField == null ? null : documentDomain(document, selfField);
                if (selfField != null && domain != null) {
                    document.set(selfField, typedFingerprint("CANONICAL_DOCUMENT", sha256Text(
                            concat(bytes(domain + "\0"), canonicalBytes(withNull(document, selfField))))));
                }
                writeDocument(document, target);
            }
        }

        private boolean refreshReferences(JsonNode value, String fieldName) throws IOException {
            boolean changed = false;
            if (value.isObject()) {
                ObjectNode object = (ObjectNode) value;
                String uri = object.path("uri").asText(null);
                Path resolved = uri == null ? null : optionalPath(uri);
                if (resolved != null && Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
                        && object.has("rawFingerprint")) {
                    ObjectNode actual = rawRefStable(resolved);
                    if (!actual.equals(object.path("rawFingerprint"))) {
                        object.set("rawFingerprint", actual);
                        changed = true;
                    }
                }
                if (resolved != null && Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)
                        && object.path("fingerprint").path("kind").asText().equals("TREE_COMMITMENT")) {
                    ObjectNode actual = treeFingerprint(resolved, treeDomainForField(fieldName, uri));
                    if (!actual.equals(object.path("fingerprint"))) {
                        object.set("fingerprint", actual);
                        changed = true;
                    }
                }
                var fields = object.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    changed |= refreshReferences(entry.getValue(), entry.getKey());
                }
            } else if (value.isArray()) {
                for (JsonNode item : value) {
                    changed |= refreshReferences(item, fieldName);
                }
            }
            return changed;
        }

        private ObjectNode document(String target) throws IOException {
            return (ObjectNode) JSON.readTree(Files.readAllBytes(path(target)));
        }

        private void writeDocument(ObjectNode document, String target) throws IOException {
            writeBytes(target, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(document));
        }

        private Path writeBytes(String target, byte[] content) throws IOException {
            Path destination = path(target);
            Files.createDirectories(destination.getParent());
            Files.write(destination, content);
            return destination;
        }

        private void rebind(String target, String field, String domain) throws IOException {
            ObjectNode document = document(target);
            document.set(field, typedFingerprint("CANONICAL_DOCUMENT", sha256Text(
                    concat(bytes(domain + "\0"), canonicalBytes(withNull(document, field))))));
            writeDocument(document, target);
        }

        private void set(String target, String pointer, Object value) throws IOException {
            ObjectNode document = document(target);
            setPointer(document, pointer, JSON.valueToTree(value));
            writeDocument(document, target);
        }

        private boolean safeReferenceExists(String uri) {
            try {
                return Files.isRegularFile(safePath(uri), LinkOption.NOFOLLOW_LINKS);
            } catch (RuntimeException failure) {
                return false;
            }
        }

        private boolean isDeclared(String uri) {
            return declaredTargets.contains(uri);
        }

        private void declare(String uri) {
            declaredTargets.add(uri);
        }

        private Path optionalPath(String relative) {
            try {
                return safePath(relative);
            } catch (RuntimeException failure) {
                return null;
            }
        }

        private Path documentPath(JsonNode document) {
            String target = documentTarget(document);
            return target == null ? null : path(target);
        }

        private String documentTarget(JsonNode document) {
            for (String target : docs.keySet()) {
                try {
                    if (document.equals(this.document(target))) {
                        return target;
                    }
                } catch (IOException ignored) {
                    return null;
                }
            }
            return null;
        }

        private Path safePath(String relative) {
            require(relative != null && !relative.isEmpty() && !relative.startsWith("/")
                            && !relative.contains("\\"),
                    "unsafe material reference: " + relative);
            for (String segment : relative.split("/", -1)) {
                require(!segment.isEmpty() && !segment.equals(".") && !segment.equals(".."),
                        "unsafe material reference: " + relative);
            }
            Path resolved = root.resolve(relative).normalize();
            require(resolved.startsWith(root), "reference escapes material root: " + relative);
            return resolved;
        }

        private Path path(String relative) {
            return safePath(relative);
        }
    }

    private static void setPointer(ObjectNode document, String pointer, JsonNode value) {
        String[] tokens = pointer.substring(1).split("/");
        JsonNode current = document;
        for (int index = 0; index < tokens.length - 1; index++) {
            current = current.path(tokens[index].replace("~1", "/").replace("~0", "~"));
        }
        ((ObjectNode) current).set(tokens[tokens.length - 1].replace("~1", "/").replace("~0", "~"), value);
    }
}
