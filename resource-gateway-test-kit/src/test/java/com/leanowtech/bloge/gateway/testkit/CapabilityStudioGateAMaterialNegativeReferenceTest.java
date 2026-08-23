package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Negative reference tests for the Gate A material-attack collector and its supporting primitives.
 *
 * <p>These tests exercise boundary conditions that the manifest-positive suite cannot cover:
 * bounded-read overflow, TOCTOU stability, JSON strictness, reference escaping, temporal bounds,
 * manifest shape, observation ref ordering, tree fingerprint symlink rejection, and
 * admission trust pin path safety.</p>
 *
 * <p>All tests are deterministic, isolated to a temporary directory, and produce no
 * protocol side-effects or credentials.</p>
 */
class CapabilityStudioGateAMaterialNegativeReferenceTest {

    @TempDir
    Path temporaryDirectory;

    // -------------------------------------------------------------------------
    // Admission Trust Pin reader — bounded read overflow
    // -------------------------------------------------------------------------

    @Test
    void admissionTrustPinReaderRejectsFileThatExceedsBoundedReadLimit() throws IOException {
        // The trust pin is read with a 64 KiB hard limit. A file at exactly the limit
        // is acceptable; one byte above must be rejected before the JSON parser runs.
        Path pin = temporaryDirectory.resolve("trust-pin-64k-plus-one.json");
        byte[] content = new byte[64 * 1024 + 1];
        content[0] = '{';
        content[content.length - 1] = '}';
        Files.write(pin, content);

        assertThatThrownBy(() ->
                CapabilityStudioGateAMaterialAttackReference.readAdmissionVerificationTimeForTesting(pin))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a stable regular file");
    }

    // -------------------------------------------------------------------------
    // Admission Trust Pin reader — stable-file identity (TOCTOU)
    // -------------------------------------------------------------------------

    @Test
    void admissionTrustPinReaderRejectsFileThatGrowsDuringRead() throws Exception {
        Path pin = temporaryDirectory.resolve("trust-pin-grow-during-read.json");
        Files.writeString(pin, """
                {"admissionContext":{"admissionVerificationTime":"2026-08-21T09:32:00Z"}}
                """);

        // Grow the file after the channel opens but before the read completes.
        byte[] result = CapabilityStudioBoundedFileReader.read(pin, 64 * 1024L, () -> {
            try {
                Files.write(pin, "extra".getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.APPEND);
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        });

        assertThat(result).isNull();
    }

    // -------------------------------------------------------------------------
    // Admission Trust Pin reader — directory is not a regular file
    // -------------------------------------------------------------------------

    @Test
    void admissionTrustPinReaderRejectsDirectoryPath() throws IOException {
        Path directory = temporaryDirectory.resolve("trust-pin-directory.json");
        Files.createDirectory(directory);

        assertThatThrownBy(() ->
                CapabilityStudioGateAMaterialAttackReference.readAdmissionVerificationTimeForTesting(directory))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a stable regular file");
    }

    // -------------------------------------------------------------------------
    // Tree fingerprint — 16 MiB per-file hard limit
    // -------------------------------------------------------------------------

    @Test
    void treeFingerprintRejectsFileThatExceeds16MiBPerFileLimit() throws Exception {
        // The tree collector enforces a 16 MiB per-file limit on every entry in the tree.
        // A tree that contains one file of exactly 16 MiB + 1 bytes must be rejected
        // with an IOException, not silently truncated or accepted.
        Path root = temporaryDirectory.resolve("tree-root");
        Files.createDirectory(root);
        Path oversized = root.resolve("oversized.bin");
        byte[] content = new byte[16 * 1024 * 1024 + 1];
        Files.write(oversized, content);

        assertThatThrownBy(() ->
                CapabilityStudioGateAMaterialAttackReference.treeFingerprintForTesting(root, "RG-CS-GATE-A-ADMISSION-EVIDENCE-ROOT-v1"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds bounded read limit");
    }

    // -------------------------------------------------------------------------
    // Tree fingerprint — symbolic-link rejection
    // -------------------------------------------------------------------------

    @Test
    void treeFingerprintRejectsDirectoryTreeThatContainsASymbolicLink() throws Exception {
        // The tree collector must walk only real directories and regular files.
        // A symlink anywhere in the tree (including as the tree root itself) must cause
        // an IOException, not a silent skip or a partial tree.
        Path root = temporaryDirectory.resolve("tree-with-symlink");
        Files.createDirectory(root);
        Path target = temporaryDirectory.resolve("symlink-target.txt");
        Files.writeString(target, "secret", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(root.resolve("link-to-target.txt"), root.relativize(target));
        } catch (UnsupportedOperationException | IOException failure) {
            // Symlinks are not supported on this filesystem — skip this platform-specific test.
            return;
        }

        assertThatThrownBy(() ->
                CapabilityStudioGateAMaterialAttackReference.treeFingerprintForTesting(root, "RG-CS-GATE-A-ADMISSION-EVIDENCE-ROOT-v1"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("symbolic link");
    }

    // -------------------------------------------------------------------------
    // Tree fingerprint — hard-link rejection (duplicate fileKey)
    // -------------------------------------------------------------------------

    @Test
    void treeFingerprintRejectsTreeContainingHardLinkedFiles() throws Exception {
        // A valid tree entry requires linkCount == 1. A second regular file with the same
        // fileKey (hard link) must be detected and rejected as a duplicate canonical path.
        Path root = temporaryDirectory.resolve("tree-with-hardlink");
        Files.createDirectory(root);
        Path first = root.resolve("first.txt");
        Path second = root.resolve("second.txt");
        Files.writeString(first, "shared-content", StandardCharsets.UTF_8);
        try {
            Files.createLink(second, first);
        } catch (UnsupportedOperationException | IOException failure) {
            // Hard links are not supported on this filesystem — skip this platform-specific test.
            return;
        }

        assertThatThrownBy(() ->
                CapabilityStudioGateAMaterialAttackReference.treeFingerprintForTesting(root, "RG-CS-GATE-A-ADMISSION-EVIDENCE-ROOT-v1"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("hard-linked or unidentified file");
    }

    // -------------------------------------------------------------------------
    // Observation ref ordering — canonical sort order
    // -------------------------------------------------------------------------

    @Test
    void observationRefSortedUniqueCheckRejectsUnsortedUris() {
        // observationRefs must be canonical sorted (lexicographic by URI).
        // A list with URIs in descending order must fail the uniqueness check.
        ObjectNode ref1 = createRef("file-a.txt", "sha256:" + "a".repeat(64));
        ObjectNode ref2 = createRef("file-b.txt", "sha256:" + "b".repeat(64));
        ArrayNode unsorted = com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioGateAMaterialAttackReference.createArrayNodeForTesting(List.of(ref2, ref1));

        assertThat(
                CapabilityStudioGateAMaterialAttackReference.sortedUniqueObservationRefsForTesting(unsorted))
                .isFalse();
    }

    @Test
    void observationRefSortedUniqueCheckRejectsDuplicateUris() {
        // observationRefs must be unique — two entries with the same URI must fail.
        ObjectNode ref1 = createRef("file-a.txt", "sha256:" + "a".repeat(64));
        ObjectNode ref2 = createRef("file-a.txt", "sha256:" + "b".repeat(64));
        ArrayNode duplicates = com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioGateAMaterialAttackReference.createArrayNodeForTesting(List.of(ref1, ref2));

        assertThat(
                CapabilityStudioGateAMaterialAttackReference.sortedUniqueObservationRefsForTesting(duplicates))
                .isFalse();
    }

    @Test
    void observationRefSortedUniqueCheckAcceptsSortedUniqueRefs() {
        // A correctly ordered, duplicate-free list must pass.
        ObjectNode ref1 = createRef("file-a.txt", "sha256:" + "a".repeat(64));
        ObjectNode ref2 = createRef("file-b.txt", "sha256:" + "b".repeat(64));
        ArrayNode correct = com.leanowtech.bloge.gateway.testkit
                .CapabilityStudioGateAMaterialAttackReference.createArrayNodeForTesting(List.of(ref1, ref2));

        assertThat(
                CapabilityStudioGateAMaterialAttackReference.sortedUniqueObservationRefsForTesting(correct))
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Manifest shape — caseClass validation
    // -------------------------------------------------------------------------

    @Test
    void manifestShapeRejectsUnknownCaseClass() throws IOException {
        // The manifest requires every case to declare either PRIMARY_GUARD_ATTACK
        // or SUPPLEMENTAL_ATTACK. Any other value is a structural rejection.
        // Use the full 18-guard catalog with 18 primaries and 1 supplemental with unknown class.
        Path repo = temporaryDirectory.resolve("repo");
        Path schemas = repo.resolve("docs/schemas/resource-gateway-capability-studio");
        Files.createDirectories(schemas);
        Path catalog = repo.resolve("docs/acceptance/capability-studio/gate-a-wire-v1/semantic-guards/guard-catalog-v1.json");
        Files.createDirectories(catalog.getParent());
        Files.writeString(catalog, """
{"messageVersion":"resource-gateway.capability-studio.gate-a.semantic-guard-catalog.v1","guards":[{"guardId":"A0_SLOT_COUNT_PROJECTION","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A0_TERMINAL_DERIVATION","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A0_REFERENCE_CLOSURE","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A0_RESULT_FINGERPRINT","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A1_SLOT_COUNT_PROJECTION","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A1_SLOT_OUTCOME_BINDING","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A1_PROCESS_MATERIAL_CLOSURE","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A1_RESULT_FINGERPRINT","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"HARNESS_PROOF_COMPLETENESS","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"PROVIDER_NAMESPACE_COLLISION_REJECTED","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"PIN_LIFECYCLE_BINDING","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"ADMISSION_EVIDENCE_ROOT_CLOSURE","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"CODESOURCE_INDEPENDENCE","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"REVIEW_SIGNATURE_AUTHORITY","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"REVIEW_COUNT_CONSISTENCY_REJECTED","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"ROLLBACK_BINDING","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A2_CONCLUSION_PRECEDENCE","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A2_RESULT_FINGERPRINT","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"}]}""");
        Path manifest = repo.resolve("docs/acceptance/capability-studio/gate-a-wire-v1/material-attacks/manifest.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, """
{"messageVersion":"resource-gateway.capability-studio.gate-a.material-attack-manifest.v1","manifestId":"TEST","collectorRevision":1,"normalizedVectorsExcluded":true,"guardCatalog":"docs/acceptance/capability-studio/gate-a-wire-v1/semantic-guards/guard-catalog-v1.json","cases":[{"caseId":"PRIMARY-01","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A0_SLOT_COUNT_PROJECTION","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-01","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A0_SLOT_COUNT_PROJECTION","exitCode":2}},{"caseId":"PRIMARY-02","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A0_TERMINAL_DERIVATION","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-02","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A0_TERMINAL_DERIVATION","exitCode":2}},{"caseId":"PRIMARY-03","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A0_REFERENCE_CLOSURE","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-03","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A0_REFERENCE_CLOSURE","exitCode":2}},{"caseId":"PRIMARY-04","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A0_RESULT_FINGERPRINT","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-04","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A0_RESULT_FINGERPRINT","exitCode":2}},{"caseId":"PRIMARY-05","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A1_SLOT_COUNT_PROJECTION","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-05","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A1_SLOT_COUNT_PROJECTION","exitCode":2}},{"caseId":"PRIMARY-06","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A1_SLOT_OUTCOME_BINDING","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-06","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A1_SLOT_OUTCOME_BINDING","exitCode":2}},{"caseId":"PRIMARY-07","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A1_PROCESS_MATERIAL_CLOSURE","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-07","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A1_PROCESS_MATERIAL_CLOSURE","exitCode":2}},{"caseId":"PRIMARY-08","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A1_RESULT_FINGERPRINT","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-08","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A1_RESULT_FINGERPRINT","exitCode":2}},{"caseId":"PRIMARY-09","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"HARNESS_PROOF_COMPLETENESS","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-09","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"HARNESS_PROOF_COMPLETENESS","exitCode":2}},{"caseId":"PRIMARY-10","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"PROVIDER_NAMESPACE_COLLISION_REJECTED","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-10","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"PROVIDER_NAMESPACE_COLLISION_REJECTED","exitCode":2}},{"caseId":"PRIMARY-11","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"PIN_LIFECYCLE_BINDING","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-11","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"PIN_LIFECYCLE_BINDING","exitCode":2}},{"caseId":"PRIMARY-12","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"ADMISSION_EVIDENCE_ROOT_CLOSURE","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-12","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"ADMISSION_EVIDENCE_ROOT_CLOSURE","exitCode":2}},{"caseId":"PRIMARY-13","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"CODESOURCE_INDEPENDENCE","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-13","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"CODESOURCE_INDEPENDENCE","exitCode":2}},{"caseId":"PRIMARY-14","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"REVIEW_SIGNATURE_AUTHORITY","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-14","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"REVIEW_SIGNATURE_AUTHORITY","exitCode":2}},{"caseId":"PRIMARY-15","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"REVIEW_COUNT_CONSISTENCY_REJECTED","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-15","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"REVIEW_COUNT_CONSISTENCY_REJECTED","exitCode":2}},{"caseId":"PRIMARY-16","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"ROLLBACK_BINDING","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-16","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"ROLLBACK_BINDING","exitCode":2}},{"caseId":"PRIMARY-17","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A2_CONCLUSION_PRECEDENCE","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-17","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A2_CONCLUSION_PRECEDENCE","exitCode":2}},{"caseId":"PRIMARY-18","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A2_RESULT_FINGERPRINT","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-18","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A2_RESULT_FINGERPRINT","exitCode":2}},{"caseId":"UNKNOWN-SUP","caseClass":"UNKNOWN_CLASS","guardId":"A0_SLOT_COUNT_PROJECTION","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MUNK","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A0_SLOT_COUNT_PROJECTION","exitCode":2}}]}""");

        assertThatThrownBy(() ->
                CapabilityStudioGateAMaterialAttackReference.manifestShapeForTesting(repo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN_CLASS");
    }

    // -------------------------------------------------------------------------
    // Material — reference URI escapes root (runtime, not manifest schema)
    // -------------------------------------------------------------------------

    @Test
    void materialSafePathRejectsAbsoluteReference() throws IOException {
        // The material collector resolves every URI as relative to the root.
        // An absolute path can never be a valid relative material reference and must
        // be rejected before any filesystem access occurs.
        Path root = temporaryDirectory;
        Path manifest = root.resolve("manifest.json");
        Files.writeString(manifest, """
                {"messageVersion":"resource-gateway.capability-studio.gate-a.material-attack-manifest.v1",
                 "manifestId":"TEST","collectorRevision":1,"normalizedVectorsExcluded":true,
                 "guardCatalog":"docs/acceptance/capability-studio/gate-a-wire-v1/semantic-guards/guard-catalog-v1.json",
                 "cases":[]}
                """);

        var error = assertThatThrownBy(() ->
                CapabilityStudioGateAMaterialAttackReference.materialSafePathForTesting(root, "/etc/passwd"));

        error.isInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e.getMessage()).containsAnyOf("unsafe", "escapes"));
    }

    @Test
    void materialSafePathRejectsParentTraversalReference() throws IOException {
        // A reference containing ".." can escape the material root if not normalised.
        Path root = temporaryDirectory;
        Path manifest = root.resolve("manifest.json");
        Files.writeString(manifest, """
                {"messageVersion":"x","manifestId":"x","collectorRevision":1,"normalizedVectorsExcluded":true,
                 "guardCatalog":"x","cases":[]}
                """);

        var error = assertThatThrownBy(() ->
                CapabilityStudioGateAMaterialAttackReference.materialSafePathForTesting(root, "../../etc/passwd"));

        error.isInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e.getMessage()).containsAnyOf("unsafe", "escapes"));
    }

    // -------------------------------------------------------------------------
    // Reviewer temporal bounds — review time outside policy window
    // -------------------------------------------------------------------------

    @Test
    void reviewerTemporalBoundsRejectsReviewReviewedAtBeforePolicyNotBefore() throws IOException {
        // The reviewer's trust policy has a notBefore time. A review whose reviewedAt
        // predates the policy window must fail temporal bounds, not be silently accepted
        // on the basis that the envelope signature is valid.
        Path root = temporaryDirectory.resolve("review-material");
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("review"));

        // Write minimal fixture: policy with notBefore = 2026-08-21T09:00:00Z,
        // envelope/review body with reviewedAt = 2026-08-20T23:00:00Z (before policy window).
        Files.writeString(root.resolve("review/policy.json"),
                """
                {"schemaVersion":"capability-studio.reviewer-trust-policy.v1",
                 "policyId":"policy:test","issuer":"issuer:test","notBefore":"2026-08-21T09:00:00Z",
                 "validUntil":"2026-08-21T17:00:00Z","candidateSubject":{"kind":"RAW_BYTES","algorithm":"SHA-256",
                 "value":"sha256:2222222222222222222222222222222222222222222222222222222222222222"},
                 "allowedAuthorities":["authority:test"],"allowedKeys":[],
                 "reviewerTrustPolicyFingerprint":{"kind":"CANONICAL_DOCUMENT","algorithm":"SHA-256",
                 "value":"sha256:1111111111111111111111111111111111111111111111111111111111111111"}}
                """);
        Files.writeString(root.resolve("review/body.json"),
                """
                {"schemaVersion":"capability-studio.review-body.v1","reviewerArtifactRawFingerprint":
                 {"kind":"RAW_BYTES","algorithm":"SHA-256","value":"sha256:3333333333333333333333333333333333333333333333333333333333333333"},
                 "reviewedAt":"2026-08-20T23:00:00Z","reviewChecks":[],"findings":[],
                 "reviewBodyFingerprint":{"kind":"CANONICAL_DOCUMENT","algorithm":"SHA-256",
                 "value":"sha256:4444444444444444444444444444444444444444444444444444444444444444"}}
                """);
        Files.writeString(root.resolve("review/envelope.json"),
                """
                {"schemaVersion":"capability-studio.reviewer-authority-envelope.v1","gateId":"GATE-A",
                 "gateRevision":1,"keyId":"key:test","issuer":"issuer:test",
                 "authorityId":"authority:test","candidateRawFingerprint":{"kind":"RAW_BYTES","algorithm":"SHA-256",
                 "value":"sha256:2222222222222222222222222222222222222222222222222222222222222222"},
                 "reviewedMaterialRootFingerprint":{"kind":"AGGREGATE_COMMITMENT","algorithm":"SHA-256",
                 "value":"sha256:5555555555555555555555555555555555555555555555555555555555555555"},
                 "admissionProfileRawFingerprint":{"kind":"RAW_BYTES","algorithm":"SHA-256",
                 "value":"sha256:6666666666666666666666666666666666666666666666666666666666666666"},
                 "reviewBodyRawFingerprint":{"kind":"RAW_BYTES","algorithm":"SHA-256",
                 "value":"sha256:4444444444444444444444444444444444444444444444444444444444444444"},
                 "reviewScope":"GATE_A_ACCEPTANCE","reviewedAt":"2026-08-20T23:00:00Z",
                 "validUntil":"2026-08-21T17:00:00Z","openP0":0,"openP1":0,"skippedCount":0,
                 "signatureAlgorithm":"Ed25519",
                 "signature":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                 "envelopeFingerprint":{"kind":"CANONICAL_DOCUMENT","algorithm":"SHA-256",
                 "value":"sha256:7777777777777777777777777777777777777777777777777777777777777777"}}
                """);
        Files.writeString(root.resolve("review/revocation.json"),
                """
                {"schemaVersion":"capability-studio.reviewer-revocation-snapshot.v1",
                 "issuedAt":"2026-08-21T08:00:00Z","validUntil":"2026-08-22T08:00:00Z",
                 "issuer":"issuer:test","revokedKeyIds":[],"revokedAuthorityIds":[],
                 "reviewerRevocationSnapshotFingerprint":{"kind":"CANONICAL_DOCUMENT","algorithm":"SHA-256",
                 "value":"sha256:8888888888888888888888888888888888888888888888888888888888888888"}}
                """);

        var error = assertThatThrownBy(() ->
                CapabilityStudioGateAMaterialAttackReference.reviewerTemporalBoundsForTesting(
                        root, java.time.Instant.parse("2026-08-21T09:32:00Z")));

        // The explicit failure reason is that reviewedAt is before policy.notBefore.
        error.isInstanceOf(IOException.class)
                .hasMessageContaining("reviewedAt is before policy.notBefore");
    }

    // -------------------------------------------------------------------------
    // Safe path — positive: valid relative paths are accepted
    // -------------------------------------------------------------------------

    @Test
    void materialSafePathAcceptsValidRelativePath() throws IOException {
        Path root = temporaryDirectory.resolve("safe-root");
        Files.createDirectories(root);
        Path child = root.resolve("a/b/c.txt");
        Files.createDirectories(child.getParent());
        Files.writeString(child, "content");

        // safePath must not throw for a well-formed relative path.
        CapabilityStudioGateAMaterialAttackReference.materialSafePathForTesting(root, "a/b/c.txt");
    }

    // -------------------------------------------------------------------------
    // checkTemporalBounds — null-parameter guards
    // -------------------------------------------------------------------------

    @Test
    void checkTemporalBoundsRejectsNullAdmissionTime() {
        var result = CapabilityStudioGateAMaterialAttackReference.checkTemporalBounds(
                null,
                CapabilityStudioGateAMaterialAttackReference.createObjectNodeForTesting(),
                CapabilityStudioGateAMaterialAttackReference.createObjectNodeForTesting(),
                CapabilityStudioGateAMaterialAttackReference.createObjectNodeForTesting(),
                CapabilityStudioGateAMaterialAttackReference.createObjectNodeForTesting());

        assertThat(result.matched()).isFalse();
        assertThat(result.failureReason()).isEqualTo("admissionTime is null");
    }

    @Test
    void checkTemporalBoundsRejectsNullBody() {
        var result = CapabilityStudioGateAMaterialAttackReference.checkTemporalBounds(
                java.time.Instant.parse("2026-08-21T12:00:00Z"),
                null,
                CapabilityStudioGateAMaterialAttackReference.createObjectNodeForTesting(),
                CapabilityStudioGateAMaterialAttackReference.createObjectNodeForTesting(),
                CapabilityStudioGateAMaterialAttackReference.createObjectNodeForTesting());

        assertThat(result.matched()).isFalse();
        assertThat(result.failureReason()).isEqualTo("body is null");
    }

    @Test
    void checkTemporalBoundsRejectsNullEnvelope() {
        var result = CapabilityStudioGateAMaterialAttackReference.checkTemporalBounds(
                java.time.Instant.parse("2026-08-21T12:00:00Z"),
                CapabilityStudioGateAMaterialAttackReference.createObjectNodeForTesting(),
                null,
                CapabilityStudioGateAMaterialAttackReference.createObjectNodeForTesting(),
                CapabilityStudioGateAMaterialAttackReference.createObjectNodeForTesting());

        assertThat(result.matched()).isFalse();
        assertThat(result.failureReason()).isEqualTo("envelope is null");
    }

    @Test
    void checkTemporalBoundsRejectsNullPolicy() {
        var result = CapabilityStudioGateAMaterialAttackReference.checkTemporalBounds(
                java.time.Instant.parse("2026-08-21T12:00:00Z"),
                CapabilityStudioGateAMaterialAttackReference.createObjectNodeForTesting(),
                CapabilityStudioGateAMaterialAttackReference.createObjectNodeForTesting(),
                null,
                CapabilityStudioGateAMaterialAttackReference.createObjectNodeForTesting());

        assertThat(result.matched()).isFalse();
        assertThat(result.failureReason()).isEqualTo("policy is null");
    }

    // -------------------------------------------------------------------------
    // Reviewer temporal bounds — positive: all timestamps within window
    // -------------------------------------------------------------------------

    @Test
    void reviewerTemporalBoundsAcceptsValidTemporalConfiguration() throws IOException {
        // All timestamps are consistent: admissionTime sits inside every valid window.
        Path root = temporaryDirectory.resolve("valid-review");
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("review"));

        // admissionTime = 2026-08-21T12:00:00Z — centred inside all windows.
        Files.writeString(root.resolve("review/policy.json"),
                """
                {"schemaVersion":"capability-studio.reviewer-trust-policy.v1",
                 "policyId":"policy:test","issuer":"issuer:test","notBefore":"2026-08-21T08:00:00Z",
                 "validUntil":"2026-08-21T20:00:00Z","candidateSubject":{"kind":"RAW_BYTES","algorithm":"SHA-256",
                 "value":"sha256:2222222222222222222222222222222222222222222222222222222222222222"},
                 "allowedAuthorities":["authority:test"],"allowedKeys":[],
                 "reviewerTrustPolicyFingerprint":{"kind":"CANONICAL_DOCUMENT","algorithm":"SHA-256",
                 "value":"sha256:1111111111111111111111111111111111111111111111111111111111111111"}}
                """);
        Files.writeString(root.resolve("review/body.json"),
                """
                {"schemaVersion":"capability-studio.review-body.v1","reviewerArtifactRawFingerprint":
                 {"kind":"RAW_BYTES","algorithm":"SHA-256","value":"sha256:3333333333333333333333333333333333333333333333333333333333333333"},
                 "reviewedAt":"2026-08-21T10:00:00Z","reviewChecks":[],"findings":[],
                 "reviewBodyFingerprint":{"kind":"CANONICAL_DOCUMENT","algorithm":"SHA-256",
                 "value":"sha256:4444444444444444444444444444444444444444444444444444444444444444"}}
                """);
        Files.writeString(root.resolve("review/envelope.json"),
                """
                {"schemaVersion":"capability-studio.reviewer-authority-envelope.v1","gateId":"GATE-A",
                 "gateRevision":1,"keyId":"key:test","issuer":"issuer:test",
                 "authorityId":"authority:test","candidateRawFingerprint":{"kind":"RAW_BYTES","algorithm":"SHA-256",
                 "value":"sha256:2222222222222222222222222222222222222222222222222222222222222222"},
                 "reviewedMaterialRootFingerprint":{"kind":"AGGREGATE_COMMITMENT","algorithm":"SHA-256",
                 "value":"sha256:5555555555555555555555555555555555555555555555555555555555555555"},
                 "admissionProfileRawFingerprint":{"kind":"RAW_BYTES","algorithm":"SHA-256",
                 "value":"sha256:6666666666666666666666666666666666666666666666666666666666666666"},
                 "reviewBodyRawFingerprint":{"kind":"RAW_BYTES","algorithm":"SHA-256",
                 "value":"sha256:4444444444444444444444444444444444444444444444444444444444444444"},
                 "reviewScope":"GATE_A_ACCEPTANCE","reviewedAt":"2026-08-21T10:00:00Z",
                 "validUntil":"2026-08-21T18:00:00Z","openP0":0,"openP1":0,"skippedCount":0,
                 "signatureAlgorithm":"Ed25519",
                 "signature":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                 "envelopeFingerprint":{"kind":"CANONICAL_DOCUMENT","algorithm":"SHA-256",
                 "value":"sha256:7777777777777777777777777777777777777777777777777777777777777777"}}
                """);
        Files.writeString(root.resolve("review/revocation.json"),
                """
                {"schemaVersion":"capability-studio.reviewer-revocation-snapshot.v1",
                 "issuedAt":"2026-08-21T09:00:00Z","validUntil":"2026-08-21T20:00:00Z",
                 "issuer":"issuer:test","revokedKeyIds":[],"revokedAuthorityIds":[],
                 "reviewerRevocationSnapshotFingerprint":{"kind":"CANONICAL_DOCUMENT","algorithm":"SHA-256",
                 "value":"sha256:8888888888888888888888888888888888888888888888888888888888888888"}}
                """);

        // Must not throw — all bounds are satisfied.  The same reviewerTemporalBoundsForTesting
        // entry point is what the material attack collector uses; no separate algorithm.
        assertThatCode(() ->
                CapabilityStudioGateAMaterialAttackReference.reviewerTemporalBoundsForTesting(
                        root, java.time.Instant.parse("2026-08-21T12:00:00Z")))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Reviewer temporal bounds — boundary: admissionTime at policy.notBefore edge
    // -------------------------------------------------------------------------

    @Test
    void reviewerTemporalBoundsRejectsAdmissionTimeAfterPolicyValidUntil() throws IOException {
        // admissionTime sits strictly after policy.validUntil — must fail.
        Path root = temporaryDirectory.resolve("boundary-review");
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("review"));

        Files.writeString(root.resolve("review/policy.json"),
                """
                {"schemaVersion":"capability-studio.reviewer-trust-policy.v1",
                 "policyId":"policy:test","issuer":"issuer:test","notBefore":"2026-08-21T08:00:00Z",
                 "validUntil":"2026-08-21T16:00:00Z","candidateSubject":{"kind":"RAW_BYTES","algorithm":"SHA-256",
                 "value":"sha256:2222222222222222222222222222222222222222222222222222222222222222"},
                 "allowedAuthorities":["authority:test"],"allowedKeys":[],
                 "reviewerTrustPolicyFingerprint":{"kind":"CANONICAL_DOCUMENT","algorithm":"SHA-256",
                 "value":"sha256:1111111111111111111111111111111111111111111111111111111111111111"}}
                """);
        Files.writeString(root.resolve("review/body.json"),
                """
                {"schemaVersion":"capability-studio.review-body.v1","reviewerArtifactRawFingerprint":
                 {"kind":"RAW_BYTES","algorithm":"SHA-256","value":"sha256:3333333333333333333333333333333333333333333333333333333333333333"},
                 "reviewedAt":"2026-08-20T23:00:00Z","reviewChecks":[],"findings":[],
                 "reviewBodyFingerprint":{"kind":"CANONICAL_DOCUMENT","algorithm":"SHA-256",
                 "value":"sha256:4444444444444444444444444444444444444444444444444444444444444444"}}
                """);
        Files.writeString(root.resolve("review/envelope.json"),
                """
                {"schemaVersion":"capability-studio.reviewer-authority-envelope.v1","gateId":"GATE-A",
                 "gateRevision":1,"keyId":"key:test","issuer":"issuer:test",
                 "authorityId":"authority:test","candidateRawFingerprint":{"kind":"RAW_BYTES","algorithm":"SHA-256",
                 "value":"sha256:2222222222222222222222222222222222222222222222222222222222222222"},
                 "reviewedMaterialRootFingerprint":{"kind":"AGGREGATE_COMMITMENT","algorithm":"SHA-256",
                 "value":"sha256:5555555555555555555555555555555555555555555555555555555555555555"},
                 "admissionProfileRawFingerprint":{"kind":"RAW_BYTES","algorithm":"SHA-256",
                 "value":"sha256:6666666666666666666666666666666666666666666666666666666666666666"},
                 "reviewBodyRawFingerprint":{"kind":"RAW_BYTES","algorithm":"SHA-256",
                 "value":"sha256:4444444444444444444444444444444444444444444444444444444444444444"},
                 "reviewScope":"GATE_A_ACCEPTANCE","reviewedAt":"2026-08-20T23:00:00Z",
                 "validUntil":"2026-08-21T17:00:00Z","openP0":0,"openP1":0,"skippedCount":0,
                 "signatureAlgorithm":"Ed25519",
                 "signature":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                 "envelopeFingerprint":{"kind":"CANONICAL_DOCUMENT","algorithm":"SHA-256",
                 "value":"sha256:7777777777777777777777777777777777777777777777777777777777777777"}}
                """);
        Files.writeString(root.resolve("review/revocation.json"),
                """
                {"schemaVersion":"capability-studio.reviewer-revocation-snapshot.v1",
                 "issuedAt":"2026-08-21T09:00:00Z","validUntil":"2026-08-21T20:00:00Z",
                 "issuer":"issuer:test","revokedKeyIds":[],"revokedAuthorityIds":[],
                 "reviewerRevocationSnapshotFingerprint":{"kind":"CANONICAL_DOCUMENT","algorithm":"SHA-256",
                 "value":"sha256:8888888888888888888888888888888888888888888888888888888888888888"}}
                """);

        var error = assertThatThrownBy(() ->
                CapabilityStudioGateAMaterialAttackReference.reviewerTemporalBoundsForTesting(
                        root, java.time.Instant.parse("2026-08-21T16:00:01Z")));

        error.isInstanceOf(IOException.class)
                .hasMessageContaining("policy.validUntil");
    }

    // -------------------------------------------------------------------------
    // Manifest exact closure — primary count matches guard catalog
    // -------------------------------------------------------------------------

    @Test
    void manifestExactClosureRequiresExactlyOnePrimaryAttackPerGuard() throws IOException {
        // The manifest must contain exactly 18 PRIMARY_GUARD_ATTACK cases in guard-catalog
        // order, followed by supplemental cases. Adding a 19th PRIMARY case violates the
        // caseClass/order constraint (index 18 expects SUPPLEMENTAL_ATTACK).
        Path repo = temporaryDirectory.resolve("repo");
        Path schemas = repo.resolve("docs/schemas/resource-gateway-capability-studio");
        Files.createDirectories(schemas);
        Path catalog = repo.resolve("docs/acceptance/capability-studio/gate-a-wire-v1/semantic-guards/guard-catalog-v1.json");
        Files.createDirectories(catalog.getParent());
        Files.writeString(catalog, """
{"messageVersion":"resource-gateway.capability-studio.gate-a.semantic-guard-catalog.v1","guards":[{"guardId":"A0_SLOT_COUNT_PROJECTION","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A0_TERMINAL_DERIVATION","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A0_REFERENCE_CLOSURE","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A0_RESULT_FINGERPRINT","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A1_SLOT_COUNT_PROJECTION","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A1_SLOT_OUTCOME_BINDING","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A1_PROCESS_MATERIAL_CLOSURE","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A1_RESULT_FINGERPRINT","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"HARNESS_PROOF_COMPLETENESS","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"PROVIDER_NAMESPACE_COLLISION_REJECTED","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"PIN_LIFECYCLE_BINDING","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"ADMISSION_EVIDENCE_ROOT_CLOSURE","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"CODESOURCE_INDEPENDENCE","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"REVIEW_SIGNATURE_AUTHORITY","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"REVIEW_COUNT_CONSISTENCY_REJECTED","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"ROLLBACK_BINDING","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A2_CONCLUSION_PRECEDENCE","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A2_RESULT_FINGERPRINT","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"}]}""");
        Path manifest = repo.resolve("docs/acceptance/capability-studio/gate-a-wire-v1/material-attacks/manifest.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, """
{"messageVersion":"resource-gateway.capability-studio.gate-a.material-attack-manifest.v1","manifestId":"TEST","collectorRevision":1,"normalizedVectorsExcluded":true,"guardCatalog":"docs/acceptance/capability-studio/gate-a-wire-v1/semantic-guards/guard-catalog-v1.json","cases":[{"caseId":"PRIMARY-01","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A0_SLOT_COUNT_PROJECTION","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-01","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A0_SLOT_COUNT_PROJECTION","exitCode":2}},{"caseId":"PRIMARY-02","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A0_TERMINAL_DERIVATION","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-02","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A0_TERMINAL_DERIVATION","exitCode":2}},{"caseId":"PRIMARY-03","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A0_REFERENCE_CLOSURE","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-03","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A0_REFERENCE_CLOSURE","exitCode":2}},{"caseId":"PRIMARY-04","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A0_RESULT_FINGERPRINT","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-04","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A0_RESULT_FINGERPRINT","exitCode":2}},{"caseId":"PRIMARY-05","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A1_SLOT_COUNT_PROJECTION","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-05","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A1_SLOT_COUNT_PROJECTION","exitCode":2}},{"caseId":"PRIMARY-06","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A1_SLOT_OUTCOME_BINDING","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-06","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A1_SLOT_OUTCOME_BINDING","exitCode":2}},{"caseId":"PRIMARY-07","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A1_PROCESS_MATERIAL_CLOSURE","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-07","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A1_PROCESS_MATERIAL_CLOSURE","exitCode":2}},{"caseId":"PRIMARY-08","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A1_RESULT_FINGERPRINT","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-08","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A1_RESULT_FINGERPRINT","exitCode":2}},{"caseId":"PRIMARY-09","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"HARNESS_PROOF_COMPLETENESS","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-09","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"HARNESS_PROOF_COMPLETENESS","exitCode":2}},{"caseId":"PRIMARY-10","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"PROVIDER_NAMESPACE_COLLISION_REJECTED","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-10","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"PROVIDER_NAMESPACE_COLLISION_REJECTED","exitCode":2}},{"caseId":"PRIMARY-11","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"PIN_LIFECYCLE_BINDING","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-11","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"PIN_LIFECYCLE_BINDING","exitCode":2}},{"caseId":"PRIMARY-12","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"ADMISSION_EVIDENCE_ROOT_CLOSURE","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-12","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"ADMISSION_EVIDENCE_ROOT_CLOSURE","exitCode":2}},{"caseId":"PRIMARY-13","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"CODESOURCE_INDEPENDENCE","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-13","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"CODESOURCE_INDEPENDENCE","exitCode":2}},{"caseId":"PRIMARY-14","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"REVIEW_SIGNATURE_AUTHORITY","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-14","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"REVIEW_SIGNATURE_AUTHORITY","exitCode":2}},{"caseId":"PRIMARY-15","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"REVIEW_COUNT_CONSISTENCY_REJECTED","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-15","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"REVIEW_COUNT_CONSISTENCY_REJECTED","exitCode":2}},{"caseId":"PRIMARY-16","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"ROLLBACK_BINDING","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-16","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"ROLLBACK_BINDING","exitCode":2}},{"caseId":"PRIMARY-17","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A2_CONCLUSION_PRECEDENCE","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-17","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A2_CONCLUSION_PRECEDENCE","exitCode":2}},{"caseId":"PRIMARY-18","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A2_RESULT_FINGERPRINT","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-18","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A2_RESULT_FINGERPRINT","exitCode":2}},{"caseId":"DUPE-19","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A0_SLOT_COUNT_PROJECTION","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MDUPE","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A0_SLOT_COUNT_PROJECTION","exitCode":2}}]}""");

        assertThatThrownBy(() ->
                CapabilityStudioGateAMaterialAttackReference.manifestShapeForTesting(repo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("caseClass/order drift");
    }

    // -------------------------------------------------------------------------
    // Manifest exact closure — supplemental caseClass required after primary slot
    // -------------------------------------------------------------------------

    @Test
    void manifestExactClosureRequiresSupplementsBeClassifiedAsSupplemental() throws IOException {
        // After the 18 primary slots, all supplemental cases must be classified as SUPPLEMENTAL_ATTACK.
        // Using an unknown classification for a supplemental case must fail the shape check.
        Path repo = temporaryDirectory.resolve("repo");
        Path schemas = repo.resolve("docs/schemas/resource-gateway-capability-studio");
        Files.createDirectories(schemas);
        Path catalog = repo.resolve("docs/acceptance/capability-studio/gate-a-wire-v1/semantic-guards/guard-catalog-v1.json");
        Files.createDirectories(catalog.getParent());
        Files.writeString(catalog, """
{"messageVersion":"resource-gateway.capability-studio.gate-a.semantic-guard-catalog.v1","guards":[{"guardId":"A0_SLOT_COUNT_PROJECTION","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A0_TERMINAL_DERIVATION","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A0_REFERENCE_CLOSURE","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A0_RESULT_FINGERPRINT","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A1_SLOT_COUNT_PROJECTION","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A1_SLOT_OUTCOME_BINDING","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A1_PROCESS_MATERIAL_CLOSURE","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A1_RESULT_FINGERPRINT","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"HARNESS_PROOF_COMPLETENESS","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"PROVIDER_NAMESPACE_COLLISION_REJECTED","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"PIN_LIFECYCLE_BINDING","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"ADMISSION_EVIDENCE_ROOT_CLOSURE","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"CODESOURCE_INDEPENDENCE","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"REVIEW_SIGNATURE_AUTHORITY","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"REVIEW_COUNT_CONSISTENCY_REJECTED","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"ROLLBACK_BINDING","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A2_CONCLUSION_PRECEDENCE","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"},{"guardId":"A2_RESULT_FINGERPRINT","owner":"A1","phase":"PROJECTION","rule":"t","mismatch":"INVALID","unavailable":"UNAVAILABLE","sourceFactIds":["A0_ADAPTER_FACT"],"admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE"}]}""");
        Path manifest = repo.resolve("docs/acceptance/capability-studio/gate-a-wire-v1/material-attacks/manifest.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, """
{"messageVersion":"resource-gateway.capability-studio.gate-a.material-attack-manifest.v1","manifestId":"TEST","collectorRevision":1,"normalizedVectorsExcluded":true,"guardCatalog":"docs/acceptance/capability-studio/gate-a-wire-v1/semantic-guards/guard-catalog-v1.json","cases":[{"caseId":"PRIMARY-01","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A0_SLOT_COUNT_PROJECTION","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-01","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A0_SLOT_COUNT_PROJECTION","exitCode":2}},{"caseId":"PRIMARY-02","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A0_TERMINAL_DERIVATION","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-02","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A0_TERMINAL_DERIVATION","exitCode":2}},{"caseId":"PRIMARY-03","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A0_REFERENCE_CLOSURE","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-03","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A0_REFERENCE_CLOSURE","exitCode":2}},{"caseId":"PRIMARY-04","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A0_RESULT_FINGERPRINT","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-04","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A0_RESULT_FINGERPRINT","exitCode":2}},{"caseId":"PRIMARY-05","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A1_SLOT_COUNT_PROJECTION","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-05","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A1_SLOT_COUNT_PROJECTION","exitCode":2}},{"caseId":"PRIMARY-06","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A1_SLOT_OUTCOME_BINDING","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-06","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A1_SLOT_OUTCOME_BINDING","exitCode":2}},{"caseId":"PRIMARY-07","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A1_PROCESS_MATERIAL_CLOSURE","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-07","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A1_PROCESS_MATERIAL_CLOSURE","exitCode":2}},{"caseId":"PRIMARY-08","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A1_RESULT_FINGERPRINT","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-08","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A1_RESULT_FINGERPRINT","exitCode":2}},{"caseId":"PRIMARY-09","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"HARNESS_PROOF_COMPLETENESS","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-09","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"HARNESS_PROOF_COMPLETENESS","exitCode":2}},{"caseId":"PRIMARY-10","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"PROVIDER_NAMESPACE_COLLISION_REJECTED","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-10","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"PROVIDER_NAMESPACE_COLLISION_REJECTED","exitCode":2}},{"caseId":"PRIMARY-11","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"PIN_LIFECYCLE_BINDING","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-11","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"PIN_LIFECYCLE_BINDING","exitCode":2}},{"caseId":"PRIMARY-12","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"ADMISSION_EVIDENCE_ROOT_CLOSURE","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-12","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"ADMISSION_EVIDENCE_ROOT_CLOSURE","exitCode":2}},{"caseId":"PRIMARY-13","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"CODESOURCE_INDEPENDENCE","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-13","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"CODESOURCE_INDEPENDENCE","exitCode":2}},{"caseId":"PRIMARY-14","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"REVIEW_SIGNATURE_AUTHORITY","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-14","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"REVIEW_SIGNATURE_AUTHORITY","exitCode":2}},{"caseId":"PRIMARY-15","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"REVIEW_COUNT_CONSISTENCY_REJECTED","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-15","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"REVIEW_COUNT_CONSISTENCY_REJECTED","exitCode":2}},{"caseId":"PRIMARY-16","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"ROLLBACK_BINDING","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-16","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"ROLLBACK_BINDING","exitCode":2}},{"caseId":"PRIMARY-17","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A2_CONCLUSION_PRECEDENCE","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-17","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A2_CONCLUSION_PRECEDENCE","exitCode":2}},{"caseId":"PRIMARY-18","caseClass":"PRIMARY_GUARD_ATTACK","guardId":"A2_RESULT_FINGERPRINT","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MPRIMARY-18","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A2_RESULT_FINGERPRINT","exitCode":2}},{"caseId":"UNKNOWN-SUP","caseClass":"UNKNOWN_CLASS","guardId":"A0_SLOT_COUNT_PROJECTION","sourceMaterial":{"canonicalBaseMaterial":"PRODUCTION_WIRE_FIXTURE","documents":[],"rawMaterials":[],"collectorReads":[]},"mutation":{"mutationId":"MUNK","operation":"JSON_FIELD_REPLACE","target":"x","singleMutation":true},"expected":{"status":"FAIL","admissionTarget":"requirement:GATE-A-P0-01-TYPED-EVIDENCE","conclusion":"FAIL","reason":"A0_SLOT_COUNT_PROJECTION","exitCode":2}}]}""");

        assertThatThrownBy(() ->
                CapabilityStudioGateAMaterialAttackReference.manifestShapeForTesting(repo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN_CLASS");
    }

    // -------------------------------------------------------------------------
    // Payload-free diagnostics — BoundedFileReader never exposes path in null result
    // -------------------------------------------------------------------------

    @Test
    void boundedFileReaderReturnsNullWithoutExposingPathInErrorMessage() throws IOException {
        // The BoundedFileReader deliberately has no error-detail API.
        // All failures produce null. The caller must not reconstruct the path
        // from the null return value.
        Path directory = temporaryDirectory.resolve("secret-dir");
        Files.createDirectory(directory);

        byte[] result = CapabilityStudioBoundedFileReader.read(directory, 1024);

        assertThat(result).isNull();
        // The null is the only signal; no path string appears in any message.
    }

    // -------------------------------------------------------------------------
    // Helper factories
    // -------------------------------------------------------------------------

    private static final com.fasterxml.jackson.databind.ObjectMapper REF_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static ObjectNode createRef(String uri, String fingerprintValue) {
        ObjectNode ref = REF_JSON.createObjectNode();
        ref.put("uri", uri);
        ObjectNode rawFp = ref.putObject("rawFingerprint");
        rawFp.put("kind", "RAW_BYTES");
        rawFp.put("algorithm", "SHA-256");
        rawFp.put("value", fingerprintValue);
        return ref;
    }
}
