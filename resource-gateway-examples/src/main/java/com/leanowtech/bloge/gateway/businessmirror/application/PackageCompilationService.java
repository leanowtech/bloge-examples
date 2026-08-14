package com.leanowtech.bloge.gateway.businessmirror.application;

import com.leanowtech.bloge.gateway.businessmirror.authoring.DomainCapabilityPackageDraftRepository;
import com.leanowtech.bloge.gateway.businessmirror.authoring.StoredDomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompileCommand;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageDependencyDriftException;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Authenticated command/query boundary for durable Package compilation. */
public class PackageCompilationService {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    private final DomainCapabilityPackageDraftRepository drafts;
    private final PackageCompilationFactRepository facts;
    private final PackageCompilationCoordinator coordinator;

    public PackageCompilationService(
            DomainCapabilityPackageDraftRepository drafts,
            PackageCompilationFactRepository facts,
            PackageCompilationCoordinator coordinator) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /** Compiles one exact persisted authoring revision and atomically appends its immutable facts. */
    @Transactional
    public PackageCompilationCoordinator.Outcome compile(
            String packageId,
            long sourceDraftRevision,
            String idempotencyKey,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        String id = requireId(packageId, identity);
        if (sourceDraftRevision < 1) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PACKAGE_REVISION_INVALID",
                    "A positive Package source revision is required.", Map.of());
        }
        CapabilitySnapshot.Scope exactScope = scope(identity);
        StoredDomainCapabilityPackageDraft source = drafts.findRevision(
                        exactScope, id, sourceDraftRevision)
                .orElseThrow(() -> notFound(identity, id, sourceDraftRevision));
        PackageCompileCommand command = new PackageCompileCommand("", exactScope, id,
                sourceDraftRevision, source.draftFingerprint(), identity.actorId());
        try {
            return coordinator.execute(idempotencyKey, command, () -> drafts.findRevision(
                            exactScope, id, sourceDraftRevision)
                    .orElseThrow(() -> notFound(identity, id, sourceDraftRevision)));
        } catch (PackageCompilationCoordinator.InvalidIdempotencyKeyException failure) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.COMPILATION_IDEMPOTENCY_KEY_INVALID",
                    failure.getMessage(), Map.of());
        } catch (PackageCompilationCoordinator.IdempotencyConflictException failure) {
            throw new IntegrationProblemException(IntegrationProblem.conflict(
                    "RG.BUSINESS_MIRROR.COMPILATION_IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key is already bound to different Package compilation material.",
                    identity.correlationId(), Map.of()));
        } catch (PackageDependencyDriftException failure) {
            throw new IntegrationProblemException(IntegrationProblem.retryableConflict(
                    "RG.PACKAGE.DEPENDENCY_DRIFT",
                    "Package dependencies changed during compilation; retry from a fresh authority view.",
                    identity.correlationId(), Map.of("sourceDraftRevision", sourceDraftRevision)));
        }
    }

    /** Reads one exact immutable compilation fact set from the authenticated Scope. */
    public PackageCompilationReceipt find(
            String packageId,
            long compilationRevision,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        String id = requireId(packageId, identity);
        if (compilationRevision < 1) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.COMPILATION_REVISION_INVALID",
                    "A positive compilation revision is required.", Map.of());
        }
        return facts.find(scope(identity), id, compilationRevision)
                .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                        "RG.BUSINESS_MIRROR.COMPILATION_NOT_FOUND",
                        "Package compilation was not found in the authorized enterprise scope.",
                        identity.correlationId(),
                        Map.of("packageId", id, "compilationRevision", compilationRevision))));
    }

    private static IntegrationProblemException notFound(
            IntegrationRequestContext identity, String packageId, long revision) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                "RG.BUSINESS_MIRROR.PACKAGE_NOT_FOUND",
                "Package draft revision was not found in the authorized enterprise scope.",
                identity.correlationId(), Map.of("packageId", packageId, "revision", revision)));
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity, String code, String title, Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), details));
    }

    private static void requireIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
    }

    private static CapabilitySnapshot.Scope scope(IntegrationRequestContext identity) {
        return new CapabilitySnapshot.Scope(identity.tenantId(), identity.organizationId(),
                identity.projectId(), identity.environmentId(), identity.region());
    }

    private static String requireId(String value, IntegrationRequestContext identity) {
        String exact = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw badRequest(identity, "RG.BUSINESS_MIRROR.PACKAGE_ID_INVALID",
                    "Package id is invalid.", Map.of());
        }
        return exact;
    }
}
