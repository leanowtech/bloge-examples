package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Loads and validates the payload-free Capability Studio golden pack. */
public final class CapabilityStudioGoldenDemoPackLoader {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final String RESOURCE = "capability-studio/golden-demo-pack-v1.json";
    private static final String REF_FINGERPRINT_NAMESPACE = "capability-studio-demo-v1";

    /** Loads the packaged canonical pack with strict unknown-field rejection. */
    public CapabilityStudioGoldenDemoPack load(ObjectMapper mapper) {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing Capability Studio demo resource: " + RESOURCE);
            }
            return load(input, mapper);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read Capability Studio demo resource", failure);
        }
    }

    /** Loads one pack from an input stream; useful for fail-fast and corruption tests. */
    public CapabilityStudioGoldenDemoPack load(InputStream input, ObjectMapper mapper) {
        try {
            ObjectMapper strict = mapper.copy()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
            ObjectNode tree = (ObjectNode) strict.readTree(input);
            String declaredFingerprint = text(tree, "packFingerprint");
            tree.remove("packFingerprint");
            ObjectNode packTree = treeWithFingerprint(tree.deepCopy(), declaredFingerprint);
            CapabilityStudioGoldenDemoPack pack = strict.treeToValue(packTree, CapabilityStudioGoldenDemoPack.class);
            validate(pack, declaredFingerprint, strict, tree);
            return pack;
        } catch (IOException | ClassCastException failure) {
            throw new IllegalArgumentException("Invalid Capability Studio golden demo pack", failure);
        }
    }

    private static ObjectNode treeWithFingerprint(ObjectNode tree, String fingerprint) {
        tree.put("packFingerprint", fingerprint);
        return tree;
    }

    private static void validate(
            CapabilityStudioGoldenDemoPack pack,
            String declaredFingerprint,
            ObjectMapper mapper,
            ObjectNode sourceWithoutFingerprint) {
        require(pack.schemaVersion() == 1, "schemaVersion must be 1");
        require(pack.revision() > 0, "pack revision must be positive");
        requireNonBlank(pack.packId(), "pack id");
        requireNonBlank(pack.displayName(), "pack display name");
        validateOwner(pack.owner());
        requireNonBlank(pack.readiness(), "pack readiness");
        require(FINGERPRINT.matcher(declaredFingerprint).matches(), "invalid pack fingerprint");
        require(pack.packFingerprint().equals(declaredFingerprint), "pack fingerprint changed while loading");
        ObjectNode canonical = sourceWithoutFingerprint.deepCopy();
        String actual = fingerprint(mapper, canonical);
        require(actual.equals(declaredFingerprint),
                "pack fingerprint does not match canonical content: declared="
                        + declaredFingerprint + ", actual=" + actual);

        require(pack.apiCapabilities().size() == 4, "canonical pack must contain exactly 4 API capabilities");
        require(pack.featureCapabilities().size() == 1, "canonical pack must contain exactly 1 Feature capability");
        require(pack.toolCapabilities().size() == 1, "canonical pack must contain exactly 1 Tool capability");
        require(pack.scenarios().size() == 9, "canonical pack must contain exactly 9 scenarios");
        require(pack.canonicalBaseline().immutable(), "canonical baseline must be immutable");
        require(pack.canonicalBaseline().scenarioRefs().size() == 9, "baseline must pin all 9 scenarios");

        Map<String, CapabilityStudioGoldenDemoPack.ExactRef> refs = new HashMap<>();
        Set<String> ids = new HashSet<>();
        List<CapabilityStudioGoldenDemoPack.Capability> capabilities = new ArrayList<>();
        capabilities.addAll(pack.apiCapabilities());
        capabilities.addAll(pack.featureCapabilities());
        capabilities.addAll(pack.toolCapabilities());
        for (CapabilityStudioGoldenDemoPack.Capability capability : capabilities) {
            require(ids.add(capability.id()), "duplicate capability id: " + capability.id());
            validateCapability(capability);
            addRef(refs, capability.ref());
            addRef(refs, capability.contractRef());
            for (CapabilityStudioGoldenDemoPack.ExactRef dependency : capability.dependencyRefs()) {
                validateRef(dependency);
            }
        }
        for (CapabilityStudioGoldenDemoPack.ExactRef supporting : pack.supportingRefs()) {
            addRef(refs, supporting);
        }
        for (CapabilityStudioGoldenDemoPack.TestScenario scenario : pack.scenarios()) {
            require(ids.add(scenario.id()), "duplicate scenario id: " + scenario.id());
            validateScenario(scenario);
            addRef(refs, scenario.ref());
            requireRef(refs, scenario.contractRef());
            requireRef(refs, scenario.sourceRef());
            requireRef(refs, scenario.oracleRef());
            for (CapabilityStudioGoldenDemoPack.ExactRef contract : scenario.applicableContractRefs()) {
                requireRef(refs, contract);
            }
            for (CapabilityStudioGoldenDemoPack.DependencyBehavior dependency : scenario.dependencyBehaviors()) {
                requireRef(refs, dependency.dependencyRef());
            }
        }
        validateBaseline(pack.canonicalBaseline(), refs, pack.scenarios(), capabilities);
        validateTutorialBranch(pack.tutorialBranch(), pack.canonicalBaseline(), refs, pack.scenarios());
    }

    private static void validateCapability(CapabilityStudioGoldenDemoPack.Capability capability) {
        requireNonBlank(capability.id(), "capability id");
        requireNonBlank(capability.name(), "capability name");
        requireNonBlank(capability.description(), "capability description");
        validateRef(capability.ref());
        validateOwner(capability.owner());
        validateRef(capability.contractRef());
        requireNonBlank(capability.sideEffect(), "capability side effect");
        requireNonBlank(capability.sla(), "capability SLA");
        requireNonBlank(capability.readiness(), "capability readiness");
        require(capability.contract() != null, "capability contract summary");
        capability.contract().inputs().forEach(input -> {
            requireNonBlank(input.name(), "contract input name");
            requireNonBlank(input.label(), "contract input label");
            requireNonBlank(input.type(), "contract input type");
            requireNonBlank(input.description(), "contract input description");
        });
        capability.contract().successOutputs().forEach(output ->
                requireNonBlank(output, "contract success output"));
        capability.contract().errors().forEach(error -> {
            requireNonBlank(error.code(), "contract error code");
            requireNonBlank(error.meaning(), "contract error meaning");
            requireNonBlank(error.suggestedAction(), "contract error suggested action");
        });
    }

    private static void validateScenario(CapabilityStudioGoldenDemoPack.TestScenario scenario) {
        requireNonBlank(scenario.id(), "scenario id");
        requireNonBlank(scenario.name(), "scenario name");
        validateRef(scenario.ref());
        validateOwner(scenario.owner());
        validateRef(scenario.contractRef());
        validateRef(scenario.sourceRef());
        validateRef(scenario.oracleRef());
        require(scenario.source() != null, "scenario source summary");
        requireNonBlank(scenario.source().displayName(), "scenario source display name");
        requireNonBlank(scenario.source().type(), "scenario source type");
        require(scenario.oracle() != null, "scenario oracle summary");
        requireNonBlank(scenario.oracle().displayName(), "scenario oracle display name");
        requireNonBlank(scenario.oracle().summary(), "scenario oracle summary");
        requireNonBlank(scenario.category(), "scenario category");
        requireNonBlank(scenario.expectedResult(), "scenario expected result");
        requireNonBlank(scenario.lifecycle(), "scenario lifecycle");
        require("ACTIVE".equals(scenario.lifecycle()), "canonical scenarios must be ACTIVE");
        requireNonBlank(scenario.qualityState(), "scenario quality state");
        require(!scenario.applicableContractRefs().isEmpty(), "active scenario needs applicable contracts");
        require(!scenario.dependencyBehaviors().isEmpty(), "active scenario needs dependency behaviors");
        scenario.dependencyBehaviors().forEach(dependency -> {
            validateRef(dependency.dependencyRef());
            requireNonBlank(dependency.behavior(), "dependency behavior");
            requireNonBlank(dependency.summary(), "dependency summary");
        });
    }

    private static void validateBaseline(
            CapabilityStudioGoldenDemoPack.CanonicalBaseline baseline,
            Map<String, CapabilityStudioGoldenDemoPack.ExactRef> refs,
            List<CapabilityStudioGoldenDemoPack.TestScenario> scenarios,
            List<CapabilityStudioGoldenDemoPack.Capability> capabilities) {
        requireNonBlank(baseline.id(), "baseline id");
        validateRef(baseline.ref());
        addRef(refs, baseline.ref());
        require(baseline.assetRefs().size() == capabilities.size(), "baseline must pin all capabilities");
        baseline.assetRefs().forEach(ref -> requireRef(refs, ref));
        baseline.scenarioRefs().forEach(ref -> requireRef(refs, ref));
        require(new HashSet<>(baseline.scenarioRefs()).size() == scenarios.size(),
                "baseline scenario refs must be unique");
    }

    private static void validateTutorialBranch(
            CapabilityStudioGoldenDemoPack.TutorialBranch branch,
            CapabilityStudioGoldenDemoPack.CanonicalBaseline baseline,
            Map<String, CapabilityStudioGoldenDemoPack.ExactRef> refs,
            List<CapabilityStudioGoldenDemoPack.TestScenario> scenarios) {
        requireNonBlank(branch.id(), "tutorial branch id");
        validateRef(branch.ref());
        require(!branch.ref().equals(baseline.ref()), "tutorial branch must have its own ref");
        require(branch.baseBaselineRef().equals(baseline.ref()), "tutorial branch must derive from canonical baseline");
        require(!branch.behaviorOverrides().isEmpty(), "tutorial branch needs an explicit behavior override");
        Set<CapabilityStudioGoldenDemoPack.ExactRef> scenarioRefs = new HashSet<>(baseline.scenarioRefs());
        branch.behaviorOverrides().forEach(override -> {
            require(scenarioRefs.contains(override.scenarioRef()), "tutorial override must target baseline scenario");
            requireRef(refs, override.dependencyRef());
            require("TIMEOUT".equals(override.behavior()), "tutorial compensation branch must be TIMEOUT");
            requireNonBlank(override.summary(), "tutorial override summary");
        });
        require(scenarios.stream().anyMatch(s -> s.id().contains("compensation-history")),
                "canonical pack must include compensation-history scenario");
    }

    private static void addRef(Map<String, CapabilityStudioGoldenDemoPack.ExactRef> refs,
                               CapabilityStudioGoldenDemoPack.ExactRef ref) {
        validateRef(ref);
        String key = key(ref);
        require(refs.putIfAbsent(key, ref) == null, "duplicate exact ref: " + key);
    }

    private static void requireRef(Map<String, CapabilityStudioGoldenDemoPack.ExactRef> refs,
                                   CapabilityStudioGoldenDemoPack.ExactRef ref) {
        validateRef(ref);
        require(refs.containsKey(key(ref)), "unclosed exact ref: " + key(ref));
    }

    private static void validateRef(CapabilityStudioGoldenDemoPack.ExactRef ref) {
        require(ref != null, "exact ref is required");
        requireNonBlank(ref.kind(), "exact ref kind");
        requireNonBlank(ref.id(), "exact ref id");
        require(ref.revision() > 0, "exact ref revision must be positive");
        require(FINGERPRINT.matcher(ref.fingerprint()).matches(), "invalid exact ref fingerprint");
        require(ref.fingerprint().equals(coordinateFingerprint(ref)),
                "exact ref fingerprint does not match canonical Stage 0 coordinate: "
                        + ref.kind() + ":" + ref.id() + "@" + ref.revision());
    }

    private static void validateOwner(CapabilityStudioGoldenDemoPack.Owner owner) {
        require(owner != null, "owner is required");
        requireNonBlank(owner.id(), "owner id");
        requireNonBlank(owner.name(), "owner name");
    }

    private static String key(CapabilityStudioGoldenDemoPack.ExactRef ref) {
        return ref.kind() + ":" + ref.id() + ":" + ref.revision() + ":" + ref.fingerprint();
    }

    private static String coordinateFingerprint(CapabilityStudioGoldenDemoPack.ExactRef ref) {
        String coordinate = REF_FINGERPRINT_NAMESPACE + "|" + ref.kind() + "|" + ref.id() + "|" + ref.revision();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(coordinate.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String text(ObjectNode tree, String field) {
        if (!tree.has(field) || !tree.get(field).isTextual()) {
            throw new IllegalArgumentException("Missing textual field: " + field);
        }
        return tree.get(field).textValue();
    }

    private static String fingerprint(ObjectMapper mapper, ObjectNode canonical) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(canonical);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Unable to fingerprint Capability Studio pack", failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireNonBlank(String value, String field) {
        require(value != null && !value.isBlank(), field + " is required");
    }
}
