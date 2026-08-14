package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Deterministic, durable command boundary for Package create and save retries. */
public final class DomainCapabilityPackageSaveCoordinator {
    private static final int MAXIMUM_COMMAND_BYTES = 8 * 1_048_576;
    private static final int MAXIMUM_KEY_LENGTH = 160;
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");

    private final DomainCapabilityPackageSaveReceiptRepository receipts;
    private final ObjectMapper mapper;

    public DomainCapabilityPackageSaveCoordinator(
            DomainCapabilityPackageSaveReceiptRepository receipts, ObjectMapper mapper) {
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public Outcome execute(
            String idempotencyKey,
            DomainCapabilityPackageSaveCommand command,
            Supplier<StoredDomainCapabilityPackageDraft> mutation) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(mutation, "mutation");
        String key = requireKey(idempotencyKey);
        String requestFingerprint = ProtocolFingerprint.ofBounded(
                mapper, command, MAXIMUM_COMMAND_BYTES);
        return receipts.withCommandLock(command.scope(), key, () -> {
            DomainCapabilityPackageSaveReceipt previous =
                    receipts.find(command.scope(), key).orElse(null);
            if (previous != null) {
                if (!previous.requestFingerprint().equals(requestFingerprint)) {
                    throw new IdempotencyConflictException();
                }
                return new Outcome(previous, true);
            }
            DomainCapabilityPackageSaveReceipt receipt =
                    DomainCapabilityPackageSaveReceipt.completed(requestFingerprint, mutation.get());
            receipts.save(command.scope(), key, receipt);
            return new Outcome(receipt, false);
        });
    }

    private static String requireKey(String value) {
        String key = value == null ? "" : value.trim();
        if (key.isBlank()) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key is required");
        }
        if (key.length() > MAXIMUM_KEY_LENGTH || !SAFE_KEY.matcher(key).matches()) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key must use 1-160 URL-safe, non-whitespace characters");
        }
        return key;
    }

    public record Outcome(DomainCapabilityPackageSaveReceipt receipt, boolean replayed) {
        public Outcome {
            receipt = Objects.requireNonNull(receipt, "receipt");
        }
    }

    public static final class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException() {
            super("Idempotency-Key was already used for different Package command material");
        }
    }

    public static final class InvalidIdempotencyKeyException extends IllegalArgumentException {
        public InvalidIdempotencyKeyException(String message) {
            super(message);
        }
    }
}
