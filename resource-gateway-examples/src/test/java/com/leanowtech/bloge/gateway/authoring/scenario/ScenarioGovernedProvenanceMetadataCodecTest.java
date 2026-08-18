package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioGovernedProvenanceMetadataCodecTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void roundTripsTheCompleteClosureDeterministicallyAcrossJsonPersistence() throws Exception {
        ScenarioGovernedCompilationProvenance provenance = provenance();
        Map<String, Object> encoded =
                ScenarioGovernedProvenanceMetadataCodec.encodeExactRefs(provenance);
        Map<String, Object> persisted = mapper.readValue(
                mapper.writeValueAsBytes(encoded), new TypeReference<>() { });

        assertThat(ScenarioGovernedProvenanceMetadataCodec.decodeExactRefs(encoded))
                .containsExactlyElementsOf(provenance.exactRefs());
        assertThat(ScenarioGovernedProvenanceMetadataCodec.decodeExactRefs(persisted))
                .containsExactlyElementsOf(provenance.exactRefs());
        assertThat(mapper.writeValueAsBytes(encoded)).hasSizeLessThan(1_024);
        assertThat(encoded).containsEntry(
                "encoding", ScenarioGovernedProvenanceMetadataCodec.ENCODING);
    }

    @Test
    void rejectsUnknownFieldsOutOfRangeIndexesAndFractionalRevisions() {
        Map<String, Object> encoded = new LinkedHashMap<>(
                ScenarioGovernedProvenanceMetadataCodec.encodeExactRefs(provenance()));
        encoded.put("callerControlled", true);
        assertThatThrownBy(() ->
                ScenarioGovernedProvenanceMetadataCodec.decodeExactRefs(encoded))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> invalidIndex = mutableEncoded();
        mutableRows(invalidIndex).set(0, List.of(9, "dataset-a", 1,
                "a".repeat(64), 0, 0));
        assertThatThrownBy(() ->
                ScenarioGovernedProvenanceMetadataCodec.decodeExactRefs(invalidIndex))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> fractionalRevision = mutableEncoded();
        mutableRows(fractionalRevision).set(0, List.of(0, "dataset-a", 1.5,
                "a".repeat(64), 0, 0));
        assertThatThrownBy(() ->
                ScenarioGovernedProvenanceMetadataCodec.decodeExactRefs(fractionalRevision))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Map<String, Object> mutableEncoded() {
        Map<String, Object> mutable = new LinkedHashMap<>(
                ScenarioGovernedProvenanceMetadataCodec.encodeExactRefs(provenance()));
        mutable.put("refs", new ArrayList<>(mutableRows(mutable)));
        return mutable;
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> mutableRows(Map<String, Object> encoded) {
        return (List<List<Object>>) encoded.get("refs");
    }

    private static ScenarioGovernedCompilationProvenance provenance() {
        ScenarioGovernedCompilationProvenance.Scope scope =
                new ScenarioGovernedCompilationProvenance.Scope(
                        "tenant-a", "org-a", "project-a", "test", "sg");
        return new ScenarioGovernedCompilationProvenance(
                ScenarioGovernedCompilationProvenance.SCHEMA_VERSION,
                "sha256:" + "f".repeat(64),
                List.of(
                        new ScenarioGovernedCompilationProvenance.ExactRef(
                                "DATASET", "dataset-a", 1,
                                "sha256:" + "a".repeat(64), scope, "studio"),
                        new ScenarioGovernedCompilationProvenance.ExactRef(
                                "TOOL", "tool-a", 2,
                                "sha256:" + "b".repeat(64), scope, "studio")));
    }
}
