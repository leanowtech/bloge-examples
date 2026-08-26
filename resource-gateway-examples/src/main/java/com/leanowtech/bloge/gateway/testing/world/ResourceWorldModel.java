package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, revisioned and content-addressed resource world with versioned state declarations. */
public final class ResourceWorldModel {
    private final String worldModelId;
    private final String tenantId;
    private final long revision;
    private final List<WorldSlice> slices;
    private final WorldStateSpec stateSpec;
    private final Map<String, String> stateWriterCoordinates;
    private final String fingerprint;

    public ResourceWorldModel(String worldModelId, String tenantId, long revision, List<WorldSlice> slices) {
        if (worldModelId == null || worldModelId.isBlank() || tenantId == null || tenantId.isBlank()
                || revision <= 0 || slices == null || slices.isEmpty()) {
            throw new WorldModelException(WorldModelException.Code.INVALID_MODEL);
        }
        this.worldModelId = worldModelId.trim();
        this.tenantId = tenantId.trim();
        this.revision = revision;
        List<WorldSlice> ordered = new ArrayList<>(slices);
        if (ordered.stream().anyMatch(java.util.Objects::isNull)) {
            throw new WorldModelException(WorldModelException.Code.INVALID_SLICE);
        }
        ordered.sort(Comparator.comparing(WorldSlice::coordinate));
        Set<String> coordinates = new LinkedHashSet<>();
        for (WorldSlice slice : ordered) {
            if (slice == null) {
                throw new WorldModelException(WorldModelException.Code.INVALID_SLICE);
            }
            if (!this.tenantId.equals(slice.tenantId())) {
                throw new WorldModelException(WorldModelException.Code.TENANT_DRIFT);
            }
            if (!coordinates.add(slice.coordinate())) {
                throw new WorldModelException(WorldModelException.Code.DUPLICATE_SLICE);
            }
        }
        this.slices = List.copyOf(ordered);
        Map<String, StateKeySpec> merged = new LinkedHashMap<>();
        Map<String, Integer> writers = new LinkedHashMap<>();
        Map<String, Boolean> readers = new LinkedHashMap<>();
        Map<String, String> writerCoordinates = new LinkedHashMap<>();
        for (WorldSlice slice : this.slices) {
            for (StateKeySpec declaration : slice.worldStateSpec().declarations()) {
                StateKeySpec previous = merged.putIfAbsent(declaration.key(), declaration);
                if (previous != null && (!Objects.equals(previous.schema(), declaration.schema())
                        || !Objects.equals(previous.defaultValue(), declaration.defaultValue()))) {
                    throw new WorldModelException(WorldModelException.Code.STATE_NOT_SUPPORTED);
                }
                if (declaration.writes()) {
                    writers.merge(declaration.key(), 1, Integer::sum);
                    if (writerCoordinates.putIfAbsent(declaration.key(), slice.coordinate()) != null) {
                        throw new WorldModelException(WorldModelException.Code.STATE_NOT_SUPPORTED);
                    }
                }
                readers.merge(declaration.key(), declaration.access() != StateKeySpec.Access.WRITE,
                        Boolean::logicalOr);
            }
        }
        if (writers.values().stream().anyMatch(value -> value != 1)
                || (merged.keySet().stream().anyMatch(key -> !writers.containsKey(key)))) {
            throw new WorldModelException(WorldModelException.Code.STATE_NOT_SUPPORTED);
        }
        Map<String, StateKeySpec> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, StateKeySpec> entry : merged.entrySet()) {
            StateKeySpec.Access access = readers.get(entry.getKey())
                    ? StateKeySpec.Access.READ_WRITE : StateKeySpec.Access.WRITE;
            normalized.put(entry.getKey(), new StateKeySpec(entry.getValue().key(), access,
                    entry.getValue().schema(), entry.getValue().defaultValue()));
        }
        this.stateSpec = normalized.isEmpty() ? StateSpec.empty()
                : StateSpecV2.of(List.copyOf(normalized.values()));
        this.stateWriterCoordinates = Map.copyOf(writerCoordinates);
        this.fingerprint = VisualBundleFingerprint.fromMaterial(Map.of(
                "worldModelId", this.worldModelId,
                "tenantId", this.tenantId,
                "revision", this.revision,
                "slices", this.slices.stream().map(WorldSlice::fingerprint).toList()));
    }

    public String worldModelId() { return worldModelId; }
    public String tenantId() { return tenantId; }
    public long revision() { return revision; }
    public List<WorldSlice> slices() { return List.copyOf(slices); }
    public String fingerprint() { return fingerprint; }
    public String worldModelFingerprint() { return fingerprint; }
    public WorldStateSpec stateSpec() { return stateSpec; }
    public Map<String, String> stateWriterCoordinates() { return stateWriterCoordinates; }
    public String stateWriterCoordinate(String key) { return stateWriterCoordinates.get(key); }
}
