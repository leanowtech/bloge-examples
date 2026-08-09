package com.leanowtech.bloge.gateway.visual.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Makes ambiguous Graph draft save retries deterministic across threads, replicas, and restarts.
 */
public class GraphDraftSaveCoordinator {

    private static final int MAX_COMMAND_BYTES = 16 * 1_048_576;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 160;
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");

    private final GraphDraftSaveReceiptRepository receipts;
    private final ObjectMapper mapper;

    public GraphDraftSaveCoordinator(GraphDraftSaveReceiptRepository receipts, ObjectMapper mapper) {
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public static GraphDraftSaveCoordinator lightweight() {
        return new GraphDraftSaveCoordinator(
                new InMemoryGraphDraftSaveReceiptRepository(),
                new ObjectMapper().findAndRegisterModules());
    }

    /** Executes a mutation once or returns the exact durable result of an identical retry. */
    @Transactional
    public GraphDraftSaveOutcome execute(
            String idempotencyKey,
            GraphDraftSaveCommand command,
            Supplier<GraphDraft> mutation) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(mutation, "mutation");
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (key.isBlank()) {
            return new GraphDraftSaveOutcome(mutation.get(), false, "");
        }
        requireIdempotencyKey(key);
        String requestFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, command, MAX_COMMAND_BYTES);
        return receipts.withCommandLock(command.scope(), key, () -> {
            var previous = receipts.find(command.scope(), key);
            if (previous.isPresent()) {
                if (!previous.get().requestFingerprint().equals(requestFingerprint)) {
                    throw new GraphDraftSaveIdempotencyConflictException();
                }
                return new GraphDraftSaveOutcome(previous.get().draft(), true, requestFingerprint);
            }
            GraphDraft stored = mutation.get();
            receipts.save(command.scope(), key,
                    StoredGraphDraftSaveReceipt.completed(requestFingerprint, stored));
            return new GraphDraftSaveOutcome(stored, false, requestFingerprint);
        });
    }

    private static void requireIdempotencyKey(String key) {
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH || !SAFE_KEY.matcher(key).matches()) {
            throw new GraphDraftSaveInvalidIdempotencyKeyException(
                    "Idempotency-Key must use 1-160 URL-safe, non-whitespace characters");
        }
    }

    public record GraphDraftSaveOutcome(
            GraphDraft draft,
            boolean replayed,
            String requestFingerprint) {
    }

    /** Same key with different canonical command material is always a caller conflict. */
    public static final class GraphDraftSaveIdempotencyConflictException extends RuntimeException {
        public GraphDraftSaveIdempotencyConflictException() {
            super("Idempotency-Key was already used for a different Graph draft save command");
        }
    }

    /** Invalid keys are rejected before any draft mutation or receipt write. */
    public static final class GraphDraftSaveInvalidIdempotencyKeyException extends IllegalArgumentException {
        public GraphDraftSaveInvalidIdempotencyKeyException(String message) {
            super(message);
        }
    }
}
