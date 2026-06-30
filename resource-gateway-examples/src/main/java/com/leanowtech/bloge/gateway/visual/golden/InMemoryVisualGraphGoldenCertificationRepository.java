package com.leanowtech.bloge.gateway.visual.golden;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory golden certification repository for tests and local overrides.
 */
public class InMemoryVisualGraphGoldenCertificationRepository
        implements VisualGraphGoldenCertificationRepository {

    private final Map<String, VisualGraphGoldenCertification> certifications = new ConcurrentHashMap<>();

    @Override
    public Optional<VisualGraphGoldenCertification> find(String publicationId) {
        return Optional.ofNullable(certifications.get(publicationId));
    }

    @Override
    public VisualGraphGoldenCertification save(VisualGraphGoldenCertification certification) {
        certifications.put(certification.publicationId(), certification);
        return certification;
    }
}
