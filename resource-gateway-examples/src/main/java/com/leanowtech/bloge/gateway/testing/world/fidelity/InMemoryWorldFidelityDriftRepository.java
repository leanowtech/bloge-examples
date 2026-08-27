package com.leanowtech.bloge.gateway.testing.world.fidelity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.Set;

/** Thread-safe reference repository; durable adapters can preserve the same CAS contract. */
public final class InMemoryWorldFidelityDriftRepository implements WorldFidelityDriftRepository {
    private final Map<String, DriftAnnotation> current = new HashMap<>();
    private final Map<String, WorldFidelityReport> history = new HashMap<>();
    private final Set<String> consumedReceipts = new HashSet<>();

    @Override
    public synchronized Optional<DriftAnnotation> current(String tenantId, String targetFingerprint) {
        return Optional.ofNullable(current.get(key(tenantId, targetFingerprint)));
    }

    @Override
    public synchronized void append(String tenantId, WorldFidelityReport report) {
        if (tenantId == null || report == null) {
            throw new WorldFidelityException(WorldFidelityException.Code.INVALID_INPUT);
        }
        String key = historyKey(tenantId, report.targetFingerprint(), report.reportFingerprint());
        if (history.putIfAbsent(key, report) != null) {
            throw new WorldFidelityException(WorldFidelityException.Code.DRIFT_CAS_CONFLICT);
        }
    }

    @Override
    public synchronized boolean compareAndSet(String tenantId, String targetFingerprint,
                                               DriftState expected, DriftAnnotation next) {
        if (next == null || !targetFingerprint.equals(next.targetFingerprint())) return false;
        String key = key(tenantId, targetFingerprint);
        DriftAnnotation actual = current.get(key);
        if (actual == null ? expected != null : actual.state() != expected
                || !actual.targetFingerprint().equals(targetFingerprint)) return false;
        current.put(key, next);
        return true;
    }

    @Override
    public synchronized boolean compareAndSetAndConsumeReceipt(String tenantId, String targetFingerprint,
                                                                 DriftState expected, DriftAnnotation next,
                                                                 String receiptFingerprint) {
        if (next == null || !targetFingerprint.equals(next.targetFingerprint())
                || !matches(current.get(key(tenantId, targetFingerprint)), expected)) return false;
        String receiptKey = key(tenantId, receiptFingerprint);
        if (consumedReceipts.contains(receiptKey)) return false;
        consumedReceipts.add(receiptKey);
        current.put(key(tenantId, targetFingerprint), next);
        return true;
    }

    @Override
    public synchronized boolean consumeReceipt(String tenantId, String receiptFingerprint) {
        return consumedReceipts.add(key(tenantId, receiptFingerprint));
    }

    @Override
    public synchronized List<WorldFidelityReport> history(String tenantId, String targetFingerprint) {
        String prefix = key(tenantId, targetFingerprint) + "\u0000";
        return history.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
                .map(Map.Entry::getValue).toList();
    }

    private static String historyKey(String tenantId, String targetFingerprint, String reportFingerprint) {
        return key(tenantId, targetFingerprint) + "\u0000" + reportFingerprint;
    }

    private static boolean matches(DriftAnnotation actual, DriftState expected) {
        return actual == null ? expected == null : actual.state() == expected;
    }

    private static String key(String tenantId, String value) {
        if (tenantId == null || tenantId.isBlank() || value == null || value.isBlank()) {
            throw new WorldFidelityException(WorldFidelityException.Code.INVALID_INPUT);
        }
        return tenantId + "\u0000" + value;
    }
}
