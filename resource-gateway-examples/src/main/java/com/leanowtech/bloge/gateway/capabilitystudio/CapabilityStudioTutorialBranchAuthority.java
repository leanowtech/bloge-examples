package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Business authority for the isolated tutorial branch.
 *
 * <p>This boundary owns golden-pack validation, content addressing, optimistic concurrency,
 * idempotent retries, and the no-real-call preflight rule. SQL mechanics remain in the repository.
 * Construction performs the one-time seed and refuses to serve a database whose canonical
 * baseline has drifted.</p>
 */
final class CapabilityStudioTutorialBranchAuthority {
    static final String BRANCH_ID = "tutorial-compensation-history-timeout";
    static final String DEPENDENCY_ID = "api-compensation-history";
    static final String DEPENDENCY_NAME = "补偿历史查询";
    static final String BEHAVIOR_TIMEOUT = "TIMEOUT";
    static final String DEFAULT_CONDITION = "历史补偿查询超过超时阈值";
    static final long DEFAULT_DURATION_MS = 700L;

    private final CapabilityStudioTutorialBranchRepository repository;
    private final CapabilityStudioGoldenDemoPack pack;
    private final ObjectMapper mapper;
    private final TransactionTemplate mutations;

    CapabilityStudioTutorialBranchAuthority(
            CapabilityStudioTutorialBranchRepository repository,
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper mapper,
            TransactionTemplate mutations) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.pack = Objects.requireNonNull(pack, "pack");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        validatePack(pack);
        seedIfAbsent();
        current();
    }

    State current() {
        CapabilityStudioTutorialBranchRepository.StoredBranch stored = repository
                .findHead(BRANCH_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "Capability Studio tutorial branch head is missing: " + BRANCH_ID));
        verifyStored(stored);
        return state(stored);
    }

    State save(CapabilityStudioTutorialBranchBehaviorUpdateRequest request) {
        validate(request);
        Behavior candidate = new Behavior(request.condition().trim(), request.behavior(), request.durationMs());
        String candidateFingerprint = fingerprint(candidate);
        try {
            return mutations.execute(status -> {
                CapabilityStudioTutorialBranchRepository.StoredBranch current = currentStored();
                if (candidate.equals(behavior(current)) && request.expectedRevision() <= current.revision()) {
                    return state(current);
                }
                if (request.expectedRevision() != current.revision()) {
                    throw revisionConflict();
                }
                long nextRevision = Math.incrementExact(current.revision());
                CapabilityStudioTutorialBranchRepository.StoredBranch replacement = stored(candidate, nextRevision);
                repository.insertRevision(replacement);
                if (repository.compareAndSetHead(current, replacement) != 1) {
                    throw new RevisionRace();
                }
                return state(replacement);
            });
        } catch (DataIntegrityViolationException | RevisionRace race) {
            // A concurrent writer may have committed after the optimistic read. Re-read only
            // after the failed transaction has rolled back, then apply stale idempotency.
            State latest = current();
            if (latest.fingerprint().equals(candidateFingerprint)
                    && request.expectedRevision() <= latest.revision()) {
                return latest;
            }
            throw revisionConflict();
        }
    }

    void preflight() {
        State snapshot = current();
        if (!BEHAVIOR_TIMEOUT.equals(snapshot.behavior().behavior())
                || snapshot.behavior().condition().isBlank()
                || snapshot.behavior().durationMs() <= 0
                || !resolvableDependency()) {
            throw new CapabilityStudioTutorialBranchException(
                    "RG.CAPABILITY_STUDIO.TUTORIAL_BRANCH_UNRESOLVED",
                    "隔离演练发现无法解析的依赖表现。",
                    "演练结果不能证明当前分支行为正确，且不会尝试调用真实外部接口。",
                    "修复依赖名称、行为条件或持续时间后重新执行 preflight。",
                    "behavior",
                    409);
        }
    }

    String canonicalBaselineFingerprint() {
        return pack.canonicalBaseline().ref().fingerprint();
    }

    private void seedIfAbsent() {
        CapabilityStudioTutorialBranchRepository.StoredBranch seed = stored(
                new Behavior(DEFAULT_CONDITION, BEHAVIOR_TIMEOUT, DEFAULT_DURATION_MS), 1);
        try {
            mutations.executeWithoutResult(status -> {
                if (repository.findHead(BRANCH_ID).isEmpty()) {
                    repository.insertRevision(seed);
                    repository.insertHead(seed);
                }
            });
        } catch (DataIntegrityViolationException concurrentSeed) {
            // Another application instance seeded the same validated golden pack. Integrity is
            // checked below; the exception is not allowed to silently reseed or overwrite it.
        }
        CapabilityStudioTutorialBranchRepository.StoredBranch head = currentStored();
        if (head.revision() < 1) {
            throw new IllegalStateException("Capability Studio tutorial branch revision is invalid");
        }
    }

    private CapabilityStudioTutorialBranchRepository.StoredBranch currentStored() {
        CapabilityStudioTutorialBranchRepository.StoredBranch stored = repository
                .findHead(BRANCH_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "Capability Studio tutorial branch head is missing: " + BRANCH_ID));
        verifyStored(stored);
        return stored;
    }

    private void verifyStored(CapabilityStudioTutorialBranchRepository.StoredBranch stored) {
        if (!canonicalBaselineFingerprint().equals(stored.canonicalBaselineFingerprint())) {
            throw new IllegalStateException(
                    "Capability Studio tutorial branch canonical baseline drift detected: "
                            + BRANCH_ID);
        }
        if (!BRANCH_ID.equals(stored.branchId())
                || !DEPENDENCY_ID.equals(stored.dependencyId())
                || !BEHAVIOR_TIMEOUT.equals(stored.behavior())
                || !fingerprint(behavior(stored)).equals(stored.fingerprint())) {
            throw new IllegalStateException(
                    "Capability Studio tutorial branch stored content failed integrity validation");
        }
    }

    private State state(CapabilityStudioTutorialBranchRepository.StoredBranch stored) {
        return new State(stored.revision(), stored.fingerprint(), behavior(stored));
    }

    private Behavior behavior(CapabilityStudioTutorialBranchRepository.StoredBranch stored) {
        return new Behavior(stored.condition(), stored.behavior(), stored.durationMs());
    }

    private CapabilityStudioTutorialBranchRepository.StoredBranch stored(
            Behavior behavior, long revision) {
        return new CapabilityStudioTutorialBranchRepository.StoredBranch(
                BRANCH_ID, revision, fingerprint(behavior), canonicalBaselineFingerprint(),
                DEPENDENCY_ID, behavior.condition(), behavior.behavior(), behavior.durationMs());
    }

    private boolean resolvableDependency() {
        return pack.apiCapabilities().stream()
                .anyMatch(capability -> DEPENDENCY_ID.equals(capability.id())
                        && DEPENDENCY_NAME.equals(capability.name()));
    }

    private static void validatePack(CapabilityStudioGoldenDemoPack pack) {
        CapabilityStudioGoldenDemoPack.TutorialBranch branch = pack.tutorialBranch();
        if (!BRANCH_ID.equals(branch.id())
                || branch.ref().revision() != 1
                || branch.behaviorOverrides().size() != 1
                || !branch.baseBaselineRef().equals(pack.canonicalBaseline().ref())) {
            throw new IllegalArgumentException("The Capability Studio tutorial branch is not resolvable");
        }
        CapabilityStudioGoldenDemoPack.BehaviorOverride override = branch.behaviorOverrides().getFirst();
        if (!DEPENDENCY_ID.equals(override.dependencyRef().id())
                || !BEHAVIOR_TIMEOUT.equals(override.behavior())) {
            throw new IllegalArgumentException("The Capability Studio tutorial dependency is not resolvable");
        }
    }

    private static void validate(CapabilityStudioTutorialBranchBehaviorUpdateRequest request) {
        if (request == null) {
            throw invalid("请求体不能为空。", "请提供 condition、behavior、durationMs 和 expectedRevision。", null);
        }
        if (request.expectedRevision() == null || request.expectedRevision() < 1) {
            throw invalid("expectedRevision 缺失或无效。", "无法安全判断本次更新是否基于最新版本。", "expectedRevision");
        }
        if (request.condition() == null || request.condition().trim().isBlank()) {
            throw invalid("condition 不能为空。", "系统无法说明何时触发该依赖表现。", "condition");
        }
        if (request.condition().trim().length() > 200) {
            throw invalid("condition 不能超过 200 个字符。", "条件过长会使分支意图难以阅读和审计。", "condition");
        }
        if (!BEHAVIOR_TIMEOUT.equals(request.behavior())) {
            throw invalid("当前教程分支只支持 TIMEOUT。", "无法把该分支编译成已验证的隔离依赖表现。", "behavior");
        }
        if (request.durationMs() == null || request.durationMs() < 100 || request.durationMs() > 30_000) {
            throw invalid("durationMs 必须在 100 到 30000 之间。", "超出范围的持续时间不能作为稳定的隔离演练输入。", "durationMs");
        }
    }

    private static CapabilityStudioTutorialBranchException invalid(
            String whatHappened, String impact, String field) {
        return new CapabilityStudioTutorialBranchException(
                "RG.CAPABILITY_STUDIO.REQUEST_INVALID", whatHappened, impact,
                "修正请求字段后重新提交；不要提交 raw payload 或 mock JSON。", field, 400);
    }

    private CapabilityStudioTutorialBranchException revisionConflict() {
        return new CapabilityStudioTutorialBranchException(
                "RG.CAPABILITY_STUDIO.REVISION_CONFLICT",
                "保存时发现分支已经被其他操作更新。",
                "本次更改未写入，页面上的版本已经过期。",
                "重新读取当前分支版本，确认变更后使用新的 expectedRevision 再保存。",
                "expectedRevision", 409);
    }

    private String fingerprint(Behavior behavior) {
        ObjectNode canonical = mapper.createObjectNode();
        canonical.put("schemaVersion", 1);
        canonical.put("branchId", BRANCH_ID);
        canonical.put("canonicalBaselineFingerprint", canonicalBaselineFingerprint());
        ObjectNode behaviorNode = canonical.putObject("behavior");
        behaviorNode.put("dependencyId", DEPENDENCY_ID);
        behaviorNode.put("condition", behavior.condition());
        behaviorNode.put("behavior", behavior.behavior());
        behaviorNode.put("durationMs", behavior.durationMs());
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical)));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Unable to canonicalize tutorial branch behavior", failure);
        }
    }

    record State(long revision, String fingerprint, Behavior behavior) {
    }

    record Behavior(String condition, String behavior, long durationMs) {
    }

    private static final class RevisionRace extends RuntimeException {
    }
}
