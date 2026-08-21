package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Creates the strict Gate A0 candidate result and its exact adapter-result materials. */
public final class CapabilityStudioGateACandidateReplayResult {
    /** Strict result wire version. */
    public static final String MESSAGE_VERSION =
            "resource-gateway.capability-studio.gate-a.candidate-replay-result.v1";
    /** Domain-separation profile for the canonical result fingerprint. */
    public static final String FINGERPRINT_PROFILE = "RG-CS-GATE-A0-RESULT-v1";

    private static final Pattern RESULT_ID = Pattern.compile("A0-[A-Z0-9][A-Z0-9-]{2,63}");
    private static final Pattern URI = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private CapabilityStudioGateACandidateReplayResult() {
    }

    /**
     * Exact raw-byte reference supplied by the challenge authority.
     *
     * @param uri closed material URI
     * @param rawFingerprint exact raw-byte SHA-256 fingerprint
     */
    public record RawRef(String uri, String rawFingerprint) {
        /** Validates an exact raw-byte reference. */
        public RawRef {
            requireUri(uri);
            requireFingerprint(rawFingerprint);
        }
    }

    /**
     * Exact tree-commitment reference supplied by the challenge authority.
     *
     * @param uri closed material URI
     * @param treeFingerprint exact tree-commitment SHA-256 fingerprint
     */
    public record TreeRef(String uri, String treeFingerprint) {
        /** Validates an exact tree reference. */
        public TreeRef {
            requireUri(uri);
            requireFingerprint(treeFingerprint);
        }
    }

    /**
     * Caller-owned bindings that A0 cannot infer from the evidence manifest.
     *
     * @param resultId unique A0 result identifier
     * @param candidateArtifactRef exact candidate artifact reference
     * @param challengeTrustPinRef exact caller-owned Challenge Pin reference
     * @param formalEvidenceManifestUri destination URI for the exact manifest
     * @param challengeInputRootRef exact challenge input-tree reference
     * @param registryRef exact typed replay registry reference
     * @param evidenceUriPrefix URI prefix for declared evidence files
     * @param adapterResultUriPrefix URI prefix for generated adapter materials
     */
    public record Context(
            String resultId,
            RawRef candidateArtifactRef,
            RawRef challengeTrustPinRef,
            String formalEvidenceManifestUri,
            TreeRef challengeInputRootRef,
            RawRef registryRef,
            String evidenceUriPrefix,
            String adapterResultUriPrefix) {
        /** Validates all challenge-owned bindings before evidence is opened. */
        public Context {
            if (resultId == null || !RESULT_ID.matcher(resultId).matches()
                    || candidateArtifactRef == null || challengeTrustPinRef == null
                    || challengeInputRootRef == null || registryRef == null) {
                throw new IllegalArgumentException("Gate A0 result context is invalid");
            }
            requireUri(formalEvidenceManifestUri);
            requireUri(evidenceUriPrefix);
            requireUri(adapterResultUriPrefix);
        }
    }

    /**
     * One exact replay material that the caller must persist at its declared URI.
     *
     * @param uri closed destination URI
     * @param rawFingerprint exact material raw-byte fingerprint
     * @param exactBytes canonical material bytes
     */
    public record AdapterMaterial(String uri, String rawFingerprint, byte[] exactBytes) {
        /** Defensively copies one exact material. */
        public AdapterMaterial {
            requireUri(uri);
            requireFingerprint(rawFingerprint);
            if (exactBytes == null || exactBytes.length == 0
                    || !CapabilityStudioFormalEvidenceRunManifest.sha256(exactBytes)
                    .equals(rawFingerprint)) {
                throw new IllegalArgumentException("Gate A0 adapter material is invalid");
            }
            exactBytes = exactBytes.clone();
        }

        /**
         * Returns a defensive copy of the exact material bytes.
         *
         * @return copied exact bytes
         */
        @Override
        public byte[] exactBytes() {
            return exactBytes.clone();
        }
    }

    /**
     * Strict result bytes plus every adapter material referenced by those bytes.
     *
     * @param resultBytes canonical candidate-result bytes
     * @param adapterMaterials immutable materials keyed by adapter kind
     */
    public record Bundle(byte[] resultBytes, Map<String, AdapterMaterial> adapterMaterials) {
        /** Defensively closes the returned result bundle. */
        public Bundle {
            if (resultBytes == null || resultBytes.length == 0 || adapterMaterials == null) {
                throw new IllegalArgumentException("Gate A0 result bundle is invalid");
            }
            resultBytes = resultBytes.clone();
            Map<String, AdapterMaterial> copy = new LinkedHashMap<>(adapterMaterials);
            verifyResultBytes(resultBytes);
            verifyMaterialClosure(resultBytes, copy);
            adapterMaterials = Collections.unmodifiableMap(copy);
        }

        /**
         * Returns a defensive copy of the strict candidate-result bytes.
         *
         * @return copied candidate-result bytes
         */
        @Override
        public byte[] resultBytes() {
            return resultBytes.clone();
        }
    }

    /**
     * Replays a compiled formal evidence run and projects all facts into the frozen v1 result.
     *
     * @param manifestFile absolute canonical manifest path
     * @param evidenceRoot absolute Evidence Root path
     * @param context challenge-owned immutable bindings and material URI roots
     * @return strict result bytes and referenced adapter materials
     * @throws CapabilityStudioFormalEvidenceRunVerifier.VerificationException when the manifest
     *         cannot be compiled or an authority dependency is unavailable
     */
    public static Bundle create(Path manifestFile, Path evidenceRoot, Context context) {
        if (context == null) {
            throw invalid();
        }
        var run = CapabilityStudioFormalEvidenceRunVerifier.verifyDetailed(
                manifestFile, evidenceRoot,
                CapabilityStudioFormalEvidenceRunVerifier.VerificationObserver.NONE);
        var decision = run.decision();
        if (decision.closureOutcome()
                == CapabilityStudioCandidateReplayDeriver.ClosureOutcome.UNAVAILABLE) {
            throw unavailable();
        }
        if (decision.closureOutcome()
                == CapabilityStudioCandidateReplayDeriver.ClosureOutcome.INVALID
                || (decision.terminal()
                == CapabilityStudioCandidateReplayDeriver.Terminal.INVALID
                && decision.invalidReplayCount() == 0)) {
            throw invalid();
        }
        try {
            return project(run, context);
        } catch (CapabilityStudioFormalEvidenceRunVerifier.VerificationException failure) {
            throw failure;
        } catch (IOException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    private static Bundle project(
            CapabilityStudioFormalEvidenceRunVerifier.ReplayRun run,
            Context context) throws IOException {
        var plan = run.plan();
        var decision = run.decision();
        ObjectNode result = JSON.createObjectNode();
        result.put("messageVersion", MESSAGE_VERSION)
                .put("resultId", context.resultId())
                .put("gateRevision", 1);
        result.set("candidateArtifactRef", rawRef(context.candidateArtifactRef()));
        result.set("challengeTrustPinRef", rawRef(context.challengeTrustPinRef()));
        result.set("formalEvidenceManifestRef", rawRef(
                new RawRef(context.formalEvidenceManifestUri(), plan.rawManifestFingerprint())));
        result.set("challengeInputRootRef", treeRef(context.challengeInputRootRef()));
        result.set("registryRef", rawRef(context.registryRef()));

        Map<CapabilityStudioTypedEvidenceReplayRegistry.Slot,
                CapabilityStudioTypedEvidenceReplayRegistry.ReplayRequest> requests =
                new EnumMap<>(CapabilityStudioTypedEvidenceReplayRegistry.Slot.class);
        plan.replayPlan().forEach(request -> requests.put(request.slot(), request));
        Map<String, AdapterMaterial> materials = new LinkedHashMap<>();
        ArrayNode adapterResults = result.putArray("adapterResults");
        List<CapabilityStudioCandidateReplayDeriver.ReplayOutcome> outcomes =
                decision.adapterOutcomes();
        int index = 0;
        for (var slot : CapabilityStudioTypedEvidenceReplayRegistry.Slot.values()) {
            var outcome = outcomes.get(index++);
            ObjectNode adapter = adapterResults.addObject()
                    .put("adapterKind", slot.kind())
                    .put("verifierId", slot.verifierId())
                    .put("verifierRevision", slot.revision())
                    .put("status", outcome.name());
            if (outcome == CapabilityStudioCandidateReplayDeriver.ReplayOutcome.VERIFIED
                    || outcome == CapabilityStudioCandidateReplayDeriver.ReplayOutcome.INVALID) {
                AdapterMaterial material = adapterMaterial(
                        context.adapterResultUriPrefix(), slot, requests.get(slot), outcome);
                materials.put(slot.kind(), material);
                adapter.set("resultRef", rawRef(new RawRef(
                        material.uri(), material.rawFingerprint())));
            } else {
                adapter.putNull("resultRef");
            }
        }

        ArrayNode obligationResults = result.putArray("obligationResults");
        for (var obligation : plan.obligations()) {
            ObjectNode item = obligationResults.addObject()
                    .put("obligationId", obligation.id())
                    .put("status", obligation.status());
            ArrayNode evidenceRefs = item.putArray("evidenceRefs");
            for (String path : obligation.evidencePaths()) {
                var evidence = plan.inventory().get(path);
                if (evidence == null) {
                    throw new IllegalArgumentException("Gate A0 evidence reference is invalid");
                }
                evidenceRefs.add(rawRef(new RawRef(
                        join(context.evidenceUriPrefix(), path), evidence.rawFingerprint())));
            }
        }

        result.put("adapterVerifiedCount", decision.typedReplayCount())
                .put("adapterInvalidCount", decision.invalidReplayCount())
                .put("adapterUnavailableCount", decision.unavailableReplayCount())
                .put("adapterNotRunCount", decision.adapterNotRunCount())
                .put("obligationFailedCount", decision.failed())
                .put("obligationBlockedCount", decision.blocked())
                .put("obligationNotRunCount", decision.notRun())
                .put("formalPassCount", 0)
                .put("formalExpectedCount",
                        CapabilityStudioFormalEvidenceRunManifest.FORMAL_EXPECTED_COUNT)
                .put("terminal", decision.terminal().name())
                .put("reasonCode", decision.reasonCode());
        result.putNull("resultFingerprint");
        result.set("resultFingerprint", documentFingerprint(resultFingerprint(result)));
        byte[] resultBytes = CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(result);
        return new Bundle(resultBytes, materials);
    }

    /** Rechecks canonical encoding, strict Schema, fingerprints, and all derived count fields. */
    static void verifyResultBytes(byte[] exactBytes) {
        try {
            JsonNode result = CapabilityStudioFormalEvidenceRunManifest.parseStrict(exactBytes);
            if (!Arrays.equals(exactBytes,
                    CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(result))) {
                throw invalid();
            }
            validate(result);
            if (!resultFingerprint((ObjectNode) result)
                    .equals(result.path("resultFingerprint").path("value").textValue())) {
                throw invalid();
            }
            verifyCounts(result);
        } catch (CapabilityStudioFormalEvidenceRunVerifier.VerificationException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    private static void verifyCounts(JsonNode result) {
        int verified = 0;
        int invalidCount = 0;
        int unavailableCount = 0;
        int adapterNotRun = 0;
        for (JsonNode adapter : result.path("adapterResults")) {
            switch (adapter.path("status").textValue()) {
                case "VERIFIED" -> verified++;
                case "INVALID" -> invalidCount++;
                case "UNAVAILABLE" -> unavailableCount++;
                case "NOT_RUN" -> adapterNotRun++;
                default -> throw invalid();
            }
        }
        int failed = 0;
        int blocked = 0;
        int obligationNotRun = 0;
        for (JsonNode obligation : result.path("obligationResults")) {
            switch (obligation.path("status").textValue()) {
                case "FAIL" -> failed++;
                case "BLOCKED" -> blocked++;
                case "NOT_RUN" -> obligationNotRun++;
                default -> throw invalid();
            }
        }
        if (result.path("adapterVerifiedCount").intValue() != verified
                || result.path("adapterInvalidCount").intValue() != invalidCount
                || result.path("adapterUnavailableCount").intValue() != unavailableCount
                || result.path("adapterNotRunCount").intValue() != adapterNotRun
                || result.path("obligationFailedCount").intValue() != failed
                || result.path("obligationBlockedCount").intValue() != blocked
                || result.path("obligationNotRunCount").intValue() != obligationNotRun) {
            throw invalid();
        }
        String terminal = result.path("terminal").textValue();
        String expectedReason = switch (terminal) {
            case "STRUCTURE_VERIFIED" -> "A0_STRUCTURE_VERIFIED";
            case "INCOMPLETE" -> "A0_INCOMPLETE";
            case "INVALID" -> "A0_INVALID";
            case "UNAVAILABLE" -> "A0_UNAVAILABLE";
            default -> throw invalid();
        };
        if (("STRUCTURE_VERIFIED".equals(terminal)
                && (verified == 0 || invalidCount != 0 || unavailableCount != 0))
                || ("INCOMPLETE".equals(terminal) && adapterNotRun != 3)
                || ("INVALID".equals(terminal)
                && (invalidCount == 0 || unavailableCount != 0))
                || ("UNAVAILABLE".equals(terminal) && unavailableCount == 0)
                || !expectedReason.equals(result.path("reasonCode").textValue())) {
            throw invalid();
        }
    }

    private static void verifyMaterialClosure(
            byte[] resultBytes, Map<String, AdapterMaterial> materials) {
        try {
            JsonNode result = CapabilityStudioFormalEvidenceRunManifest.parseStrict(resultBytes);
            Set<String> expectedKinds = new HashSet<>();
            Set<String> uris = new HashSet<>();
            for (JsonNode adapter : result.path("adapterResults")) {
                String kind = adapter.path("adapterKind").textValue();
                String status = adapter.path("status").textValue();
                if (!("VERIFIED".equals(status) || "INVALID".equals(status))) {
                    continue;
                }
                AdapterMaterial material = materials.get(kind);
                JsonNode reference = adapter.path("resultRef");
                if (material == null || !expectedKinds.add(kind) || !uris.add(material.uri())
                        || !material.uri().equals(reference.path("uri").textValue())
                        || !material.rawFingerprint().equals(reference.path("rawFingerprint")
                        .path("value").textValue())) {
                    throw invalid();
                }
            }
            if (!materials.keySet().equals(expectedKinds)
                    || materials.values().stream().anyMatch(value -> value == null)) {
                throw invalid();
            }
        } catch (CapabilityStudioFormalEvidenceRunVerifier.VerificationException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw invalid();
        }
    }

    private static AdapterMaterial adapterMaterial(
            String uriPrefix,
            CapabilityStudioTypedEvidenceReplayRegistry.Slot slot,
            CapabilityStudioTypedEvidenceReplayRegistry.ReplayRequest request,
            CapabilityStudioCandidateReplayDeriver.ReplayOutcome outcome) throws IOException {
        ObjectNode value = JSON.createObjectNode()
                .put("adapterKind", slot.kind())
                .put("verifierId", slot.verifierId())
                .put("verifierRevision", slot.revision())
                .put("status", outcome.name());
        if (request == null) {
            value.putNull("replayId");
        } else {
            value.put("replayId", request.id());
        }
        byte[] bytes = CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(value);
        String uri = join(uriPrefix, slot.name().toLowerCase(Locale.ROOT));
        return new AdapterMaterial(uri,
                CapabilityStudioFormalEvidenceRunManifest.sha256(bytes), bytes);
    }

    private static void validate(JsonNode result) {
        try {
            if (!CapabilityStudioSchemaSupport.validate(
                    result, CapabilityStudioSchemaSupport.GATE_A_CANDIDATE_REPLAY_RESULT_RESOURCE)
                    .isEmpty()) {
                throw invalid();
            }
        } catch (CapabilityStudioFormalEvidenceRunVerifier.VerificationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static String resultFingerprint(ObjectNode result) throws IOException {
        ObjectNode copy = result.deepCopy();
        copy.putNull("resultFingerprint");
        byte[] canonical = CapabilityStudioFormalEvidenceRunManifest.canonicalBytes(copy);
        byte[] profile = (FINGERPRINT_PROFILE + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[profile.length + canonical.length];
        System.arraycopy(profile, 0, input, 0, profile.length);
        System.arraycopy(canonical, 0, input, profile.length, canonical.length);
        return CapabilityStudioFormalEvidenceRunManifest.sha256(input);
    }

    private static ObjectNode rawRef(RawRef reference) {
        return JSON.createObjectNode().put("uri", reference.uri())
                .set("rawFingerprint", fingerprint("RAW_BYTES", reference.rawFingerprint()));
    }

    private static ObjectNode treeRef(TreeRef reference) {
        return JSON.createObjectNode().put("uri", reference.uri())
                .set("fingerprint", fingerprint("TREE_COMMITMENT", reference.treeFingerprint()));
    }

    private static ObjectNode documentFingerprint(String value) {
        return fingerprint("CANONICAL_DOCUMENT", value);
    }

    private static ObjectNode fingerprint(String kind, String value) {
        return JSON.createObjectNode().put("kind", kind)
                .put("algorithm", "SHA-256").put("value", value);
    }

    private static String join(String prefix, String suffix) {
        String value = prefix + "/" + suffix;
        requireUri(value);
        return value;
    }

    private static void requireUri(String value) {
        if (value == null || value.length() > 512 || !URI.matcher(value).matches()) {
            throw new IllegalArgumentException("Gate A0 material URI is invalid");
        }
    }

    private static void requireFingerprint(String value) {
        if (value == null || !FINGERPRINT.matcher(value).matches()) {
            throw new IllegalArgumentException("Gate A0 material fingerprint is invalid");
        }
    }

    private static CapabilityStudioFormalEvidenceRunVerifier.VerificationException invalid() {
        return new CapabilityStudioFormalEvidenceRunVerifier.VerificationException(
                CapabilityStudioFormalEvidenceRunVerifier.FailureKind.INVALID);
    }

    private static CapabilityStudioFormalEvidenceRunVerifier.VerificationException unavailable() {
        return new CapabilityStudioFormalEvidenceRunVerifier.VerificationException(
                CapabilityStudioFormalEvidenceRunVerifier.FailureKind.UNAVAILABLE);
    }
}
