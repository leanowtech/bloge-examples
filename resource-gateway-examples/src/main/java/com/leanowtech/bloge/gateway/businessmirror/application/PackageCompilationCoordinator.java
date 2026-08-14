package com.leanowtech.bloge.gateway.businessmirror.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredDomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceiptRepository;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationResult;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompileCommand;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompiler;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Cross-replica idempotency and atomic publication boundary for Package compilation. */
public final class PackageCompilationCoordinator {
    private static final int MAXIMUM_COMMAND_BYTES = 8 * 1_048_576;
    private static final int MAXIMUM_KEY_LENGTH = 160;
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");

    private final PackageCompilationReceiptRepository receipts;
    private final PackageCompilationFactRepository facts;
    private final PackageCompiler compiler;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PackageCompilationCoordinator(
            PackageCompilationReceiptRepository receipts,
            PackageCompilationFactRepository facts,
            PackageCompiler compiler,
            ObjectMapper mapper,
            Clock clock) {
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Compiles and appends all facts once, or returns the exact prior receipt. */
    public Outcome execute(
            String idempotencyKey,
            PackageCompileCommand command,
            Supplier<StoredDomainCapabilityPackageDraft> sourceLoader) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(sourceLoader, "sourceLoader");
        String key = requireKey(idempotencyKey);
        String requestFingerprint = ProtocolFingerprint.ofBounded(
                mapper, command, MAXIMUM_COMMAND_BYTES);
        return receipts.withCommandLock(command.scope(), key, () -> {
            PackageCompilationReceipt previous = receipts.find(command.scope(), key).orElse(null);
            if (previous != null) {
                if (!previous.requestFingerprint().equals(requestFingerprint)) {
                    throw new IdempotencyConflictException();
                }
                return new Outcome(previous, true);
            }
            StoredDomainCapabilityPackageDraft source = Objects.requireNonNull(
                    sourceLoader.get(), "sourceLoader result");
            if (!source.scope().equals(command.scope())
                    || !source.packageId().equals(command.packageId())
                    || source.revision() != command.sourceDraftRevision()
                    || !source.draftFingerprint().equals(command.sourceDraftFingerprint())) {
                throw new IllegalStateException("Package compile source changed after command admission");
            }
            long revision = facts.reserveRevision(command.scope(), command.packageId());
            Instant compiledAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
            PackageCompilationReceipt receipt = PackageCompilationReceipt.completed(
                    requestFingerprint, compiler.compile(source, revision, compiledAt));
            facts.append(command.scope(), receipt);
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

    /** Exact response plus whether it was replayed from the durable receipt journal. */
    public record Outcome(PackageCompilationReceipt receipt, boolean replayed) {
        public Outcome {
            receipt = Objects.requireNonNull(receipt, "receipt");
        }
    }

    /** Raised when one key is reused for different canonical command material. */
    public static final class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException() {
            super("Idempotency-Key was already used for different Package compilation material");
        }
    }

    /** Raised before mutation when a command does not provide a safe idempotency key. */
    public static final class InvalidIdempotencyKeyException extends IllegalArgumentException {
        public InvalidIdempotencyKeyException(String message) {
            super(message);
        }
    }
}
