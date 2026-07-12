package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.util.Set;

/**
 * In-memory visual graph run history repository for tests and local overrides.
 */
public class InMemoryVisualGraphRunRepository implements VisualGraphRunRepository {

    private final Map<String, VisualGraphRunRecord> records = new ConcurrentHashMap<>();
    private final VisualEvidenceSigner evidenceSigner;
    private final VisualRunPayloadRepository payloadRepository;

    public InMemoryVisualGraphRunRepository() {
        this(new InMemoryVisualEvidenceSigner());
    }

    public InMemoryVisualGraphRunRepository(VisualEvidenceSigner evidenceSigner) {
        this(evidenceSigner, null);
    }

    public InMemoryVisualGraphRunRepository(VisualEvidenceSigner evidenceSigner,
                                            VisualRunPayloadRepository payloadRepository) {
        this.evidenceSigner = evidenceSigner == null ? VisualEvidenceSigner.unavailable() : evidenceSigner;
        this.payloadRepository = payloadRepository == null
                ? new InMemoryVisualRunPayloadRepository(new ConfiguredVisualPayloadGovernancePolicy(
                        "in-memory-default", "1", "PUBLIC", Set.of(),
                        Map.of("PUBLIC", Duration.ofDays(30))), this.evidenceSigner)
                : payloadRepository;
    }

    @Override
    public Collection<VisualGraphRunRecord> all() {
        return records.values().stream()
                .sorted(Comparator.comparing(VisualGraphRunRecord::createdAt).reversed()
                        .thenComparing(VisualGraphRunRecord::runId))
                .toList();
    }

    @Override
    public Optional<VisualGraphRunRecord> find(String runId) {
        return Optional.ofNullable(records.get(runId));
    }

    @Override
    public VisualGraphRunRecord create(VisualGraphRunRecord record) {
        String runId = record.runId().isBlank() ? UUID.randomUUID().toString() : record.runId();
        VisualGraphRunRecord identified = record.withIdentity(runId, Instant.now());
        VisualRunPayloadRepository.Capture capture = payloadRepository.capture(identified);
        VisualGraphRunRecord canonical = identified.detachPayload(capture.descriptor());
        VisualGraphRunRecord stored = canonical.withEvidenceSeal(
                evidenceSigner.seal(canonical.evidenceMaterialFingerprint()));
        VisualGraphRunRecord previous = records.putIfAbsent(runId, stored);
        if (previous != null) {
            throw new IllegalArgumentException("Visual graph run already exists: " + runId);
        }
        VisualRunPayloadRepository.Access access = payloadRepository.access(runId, stored.createdAt());
        return access.readable() ? stored.withPayload(access.payload()) : stored;
    }

    @Override
    public VisualEvidenceSigner evidenceSigner() {
        return evidenceSigner;
    }

    @Override
    public VisualRunPayloadRepository payloadRepository() {
        return payloadRepository;
    }
}
