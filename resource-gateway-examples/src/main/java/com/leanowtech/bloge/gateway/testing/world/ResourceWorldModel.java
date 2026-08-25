package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, revisioned and content-addressed stateless resource world. */
public final class ResourceWorldModel {
    private final String worldModelId;
    private final String tenantId;
    private final long revision;
    private final List<WorldSlice> slices;
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
}
