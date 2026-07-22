package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityMirrorVerifierTest {

    private static final String SHA_ZERO = "sha256:" + "0".repeat(64);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void verifiesSealedSnapshotAndCompleteClosureOffline() {
        ObjectNode child = externalSnapshot("resource:customer-profile", "org-a");
        ObjectNode root = composedSnapshot("graph:customer-support", "org-a", child);
        ObjectNode closure = closure(root, List.of(root, child));

        CapabilityMirrorVerifier.VerifiedArtifact childResult =
                CapabilityMirrorVerifier.verifySnapshot(child);
        CapabilityMirrorVerifier.VerifiedClosure closureResult =
                CapabilityMirrorVerifier.verifyClosure(closure);

        assertThat(childResult.capabilityId()).isEqualTo("resource:customer-profile");
        assertThat(childResult.kind()).isEqualTo("EXTERNAL");
        assertThat(closureResult.rootCapabilityId()).isEqualTo("graph:customer-support");
        assertThat(closureResult.snapshotCount()).isEqualTo(2);
        assertThat(closureResult.fingerprint()).isEqualTo(closure.path("fingerprint").asText());
    }

    @Test
    void rejectsSnapshotContentDriftWithoutLeakingTheChangedValue() {
        ObjectNode snapshot = externalSnapshot("resource:customer-profile", "org-a");
        ((ObjectNode) snapshot.path("ownership")).put("owner", "sensitive-owner");

        assertThatThrownBy(() -> CapabilityMirrorVerifier.verifySnapshot(snapshot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RG.MIRROR.CLIENT.SNAPSHOT_FINGERPRINT_MISMATCH")
                .hasMessageNotContaining("sensitive-owner");
    }

    @Test
    void rejectsMissingAndUnreachableDependencySnapshots() {
        ObjectNode child = externalSnapshot("resource:customer-profile", "org-a");
        ObjectNode root = composedSnapshot("graph:customer-support", "org-a", child);

        assertThatThrownBy(() -> CapabilityMirrorVerifier.verifyClosure(
                closure(root, List.of(root))))
                .hasMessage("RG.MIRROR.CLIENT.CLOSURE_DEPENDENCY_MISSING");

        ObjectNode orphan = externalSnapshot("resource:orphan", "org-a");
        assertThatThrownBy(() -> CapabilityMirrorVerifier.verifyClosure(
                closure(root, List.of(root, child, orphan))))
                .hasMessage("RG.MIRROR.CLIENT.CLOSURE_UNREACHABLE_SNAPSHOT");
    }

    @Test
    void rejectsCrossScopeAndConflictingRevisionClosureMembers() {
        ObjectNode child = externalSnapshot("resource:customer-profile", "org-a");
        ObjectNode root = composedSnapshot("graph:customer-support", "org-a", child);
        ObjectNode crossScope = child.deepCopy();
        ((ObjectNode) crossScope.path("scope")).put("organizationId", "org-b");

        assertThatThrownBy(() -> CapabilityMirrorVerifier.verifyClosure(
                closure(root, List.of(root, crossScope))))
                .hasMessage("RG.MIRROR.CLIENT.CLOSURE_SCOPE_MISMATCH");

        ObjectNode conflict = child.deepCopy();
        conflict.put("fingerprint", SHA_ZERO);
        assertThatThrownBy(() -> CapabilityMirrorVerifier.verifyClosure(
                closure(root, List.of(root, child, conflict))))
                .hasMessage("RG.MIRROR.CLIENT.CLOSURE_CONFLICTING_REVISION");
    }

    @Test
    void rejectsDependencyCyclesBeforeMisclassifyingThemAsFingerprintDrift() {
        ObjectNode child = externalSnapshot("resource:customer-profile", "org-a");
        ObjectNode root = composedSnapshot("graph:customer-support", "org-a", child);
        child.put("kind", "COMPOSED");
        child.withArray("dependencies").add(dependency("cycle-to-root", root));

        assertThatThrownBy(() -> CapabilityMirrorVerifier.verifyClosure(
                closure(root, List.of(root, child))))
                .hasMessage("RG.MIRROR.CLIENT.CLOSURE_DEPENDENCY_CYCLE");
    }

    @Test
    void verifiesAThousandLevelDependencyChainWithoutRecursiveGraphTraversal() {
        List<ObjectNode> snapshots = new ArrayList<>();
        ObjectNode child = externalSnapshot("resource:leaf", "org-a");
        snapshots.add(child);
        for (int index = 1; index <= 1024; index++) {
            child = composedSnapshot("graph:level-" + index, "org-a", child);
            snapshots.add(child);
        }
        ObjectNode root = child;
        java.util.Collections.reverse(snapshots);

        CapabilityMirrorVerifier.VerifiedClosure verified =
                CapabilityMirrorVerifier.verifyClosure(closure(root, snapshots));

        assertThat(verified.snapshotCount()).isEqualTo(1025);
    }

    private ObjectNode externalSnapshot(String id, String organizationId) {
        return sealSnapshot(baseSnapshot(id, organizationId, "EXTERNAL"));
    }

    private ObjectNode composedSnapshot(String id, String organizationId, ObjectNode child) {
        ObjectNode snapshot = baseSnapshot(id, organizationId, "COMPOSED");
        snapshot.withArray("dependencies").add(dependency("invoke-child", child));
        return sealSnapshot(snapshot);
    }

    private ObjectNode baseSnapshot(String id, String organizationId, String kind) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("schemaVersion", CapabilityMirrorProtocol.CAPABILITY_SNAPSHOT_V1);
        snapshot.put("capabilityId", id);
        snapshot.put("revision", 1);
        snapshot.put("fingerprint", "");
        snapshot.put("kind", kind);
        snapshot.set("scope", scope(organizationId));
        snapshot.set("source", source(kind));
        snapshot.set("contract", contract());
        snapshot.set("runtime", runtime());
        snapshot.putArray("dependencies");
        snapshot.putObject("ownership")
                .put("owner", "support-platform")
                .put("team", "support-platform")
                .put("escalation", "on-call");
        snapshot.put("lifecycle", "DRAFT");
        snapshot.set("provenance", provenance());
        snapshot.put("createdAt", "2026-07-22T12:00:00Z");
        return snapshot;
    }

    private ObjectNode scope(String organizationId) {
        ObjectNode scope = objectMapper.createObjectNode();
        scope.put("tenantId", "tenant-a");
        scope.put("organizationId", organizationId);
        scope.put("projectId", "tool-studio");
        scope.put("environmentId", "test");
        scope.put("region", "sg");
        return scope;
    }

    private ObjectNode source(String kind) {
        ObjectNode source = objectMapper.createObjectNode();
        source.put("sourceKind", "COMPOSED".equals(kind) ? "GRAPH" : "RESOURCE");
        source.put("sourceRef", "catalog-source");
        source.put("sourceFingerprint", SHA_ZERO);
        return source;
    }

    private ObjectNode contract() {
        ObjectNode contract = objectMapper.createObjectNode();
        contract.put("schemaVersion", CapabilityMirrorProtocol.CAPABILITY_CONTRACT_V1);
        contract.set("inputSchema", schemaEnvelope());
        contract.set("outputSchema", schemaEnvelope());
        contract.putArray("errorModel");
        contract.set("effect", effect());
        contract.put("determinism", "DETERMINISTIC");
        contract.putObject("idempotency")
                .put("mode", "DETERMINISTIC")
                .put("keyPath", "")
                .put("replayReturnsOriginal", true);
        contract.putNull("stateModelRef");
        contract.putObject("compatibility")
                .put("input", "EXACT")
                .put("output", "EXACT")
                .put("errorModel", "EXACT");
        contract.putObject("security")
                .put("classification", "INTERNAL")
                .put("requiresSecrets", false)
                .putArray("allowedRegions");
        ((ObjectNode) contract.path("security")).put("payloadRetentionAllowed", false);
        ObjectNode slo = contract.putObject("slo");
        slo.putNull("timeout");
        slo.putNull("availabilityTarget");
        slo.putNull("latencyP95Ms");
        slo.put("owner", "support-platform");
        return contract;
    }

    private ObjectNode schemaEnvelope() {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("format", "json-schema");
        envelope.put("version", "2020-12");
        envelope.putObject("schema").put("type", "object");
        return envelope;
    }

    private ObjectNode effect() {
        ObjectNode effect = objectMapper.createObjectNode();
        effect.put("schemaVersion", CapabilityMirrorProtocol.EFFECT_CONTRACT_V1);
        effect.put("mode", "READ_ONLY");
        effect.putArray("readSet");
        effect.putArray("writeSet");
        effect.putArray("conditionalEffects");
        effect.putNull("compensationRef");
        effect.put("requiresApproval", false);
        effect.put("riskLevel", "LOW");
        effect.put("derivation", "DECLARED");
        effect.putArray("unresolvedReasons");
        return effect;
    }

    private ObjectNode runtime() {
        ObjectNode runtime = objectMapper.createObjectNode();
        runtime.put("kind", "UNRESOLVED");
        runtime.put("bindingRef", "");
        runtime.put("bindingFingerprint", "");
        runtime.put("ready", false);
        runtime.putArray("limitations").add("test fixture has no runtime binding");
        return runtime;
    }

    private ObjectNode provenance() {
        ObjectNode provenance = objectMapper.createObjectNode();
        provenance.put("schemaVersion", CapabilityMirrorProtocol.ARTIFACT_PROVENANCE_V1);
        provenance.put("sourceType", "OWNER");
        provenance.putArray("sourceRefs");
        provenance.put("tenantId", "tenant-a");
        provenance.put("purpose", "offline-verifier-test");
        provenance.putNull("sampleFrom");
        provenance.putNull("sampleTo");
        provenance.putNull("sampleCount");
        provenance.putNull("confidence");
        provenance.putArray("biasRisks");
        provenance.put("approvedBy", "");
        provenance.putNull("approvedAt");
        provenance.putNull("expiresAt");
        provenance.put("revocationRef", "");
        return provenance;
    }

    private ObjectNode dependency(String nodeId, ObjectNode snapshot) {
        ObjectNode dependency = objectMapper.createObjectNode();
        dependency.put("nodeId", nodeId);
        dependency.set("capabilityRef", reference(snapshot));
        dependency.put("required", true);
        dependency.putArray("conditions");
        return dependency;
    }

    private ObjectNode reference(ObjectNode snapshot) {
        ObjectNode reference = objectMapper.createObjectNode();
        reference.put("kind", "CAPABILITY");
        reference.put("id", snapshot.path("capabilityId").asText());
        reference.put("revision", snapshot.path("revision").asLong());
        reference.put("fingerprint", snapshot.path("fingerprint").asText());
        return reference;
    }

    private ObjectNode sealSnapshot(ObjectNode snapshot) {
        snapshot.put("fingerprint", "");
        snapshot.put("fingerprint", EvidenceVerificationSupport.sha256(snapshot));
        return snapshot;
    }

    private ObjectNode closure(ObjectNode root, List<ObjectNode> snapshots) {
        ObjectNode closure = objectMapper.createObjectNode();
        closure.put("schemaVersion", CapabilityMirrorProtocol.CAPABILITY_CLOSURE_V1);
        closure.set("rootRef", reference(root));
        ArrayNode members = closure.putArray("snapshots");
        snapshots.forEach(members::add);
        closure.put("fingerprint", "");
        closure.put("fingerprint", EvidenceVerificationSupport.sha256(closure));
        return closure;
    }
}
