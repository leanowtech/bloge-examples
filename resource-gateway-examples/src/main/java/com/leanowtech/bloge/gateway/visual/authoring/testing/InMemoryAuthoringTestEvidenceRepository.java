package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.EvidenceRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic in-memory implementation used by focused service tests.
 */
public final class InMemoryAuthoringTestEvidenceRepository
        implements AuthoringTestEvidenceRepository {

    private final Map<Key, EvidenceRecord> records = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final VisualEvidenceSigner signer;

    public InMemoryAuthoringTestEvidenceRepository(
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
        this.signer = java.util.Objects.requireNonNull(signer, "signer");
    }

    @Override
    public EvidenceRecord create(EvidenceRecord evidence) {
        EvidenceRecord signed =
                AuthoringTestEvidenceIntegrity.attach(objectMapper, signer, evidence);
        Key key = new Key(signed.scope(), signed.runId());
        if (records.putIfAbsent(key, signed) != null) {
            throw new AuthoringTestEvidenceIntegrityException();
        }
        return signed;
    }

    @Override
    public Optional<EvidenceRecord> find(
            AuthoringTestScope scope,
            String runId) {
        EvidenceRecord value = records.get(new Key(scope, normalized(runId)));
        return Optional.ofNullable(value)
                .map(record -> AuthoringTestEvidenceIntegrity.verify(
                        objectMapper, signer, record));
    }

    @Override
    public List<EvidenceRecord> findByDraft(
            AuthoringTestScope scope,
            String draftId) {
        String requiredDraft = normalized(draftId);
        return records.values().stream()
                .filter(record -> record.scope().equals(scope)
                        && record.draftId().equals(requiredDraft))
                .map(record -> AuthoringTestEvidenceIntegrity.verify(
                        objectMapper, signer, record))
                .sorted(Comparator.comparing(EvidenceRecord::executedAt).reversed()
                        .thenComparing(EvidenceRecord::runId))
                .toList();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record Key(AuthoringTestScope scope, String runId) {
    }
}
