package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * In-memory Stage 1 authority for the one isolated tutorial branch.
 *
 * <p>The demo branch is deliberately process-local. It is an authoring seam for the frontend
 * vertical slice, not a production persistence implementation. The canonical baseline is always
 * read from the validated Stage 0 pack and is never part of the mutable state.</p>
 */
final class CapabilityStudioTutorialBranchStore {
    static final String BRANCH_ID = "tutorial-compensation-history-timeout";
    static final String DEPENDENCY_ID = "api-compensation-history";
    static final String DEPENDENCY_NAME = "补偿历史查询";
    static final String BEHAVIOR_TIMEOUT = "TIMEOUT";
    static final String DEFAULT_CONDITION = "历史补偿查询超过超时阈值";
    static final long DEFAULT_DURATION_MS = 700L;

    private final CapabilityStudioGoldenDemoPack pack;
    private State current;

    CapabilityStudioTutorialBranchStore(CapabilityStudioGoldenDemoPack pack) {
        this.pack = Objects.requireNonNull(pack, "pack");
        CapabilityStudioGoldenDemoPack.TutorialBranch branch = pack.tutorialBranch();
        if (!BRANCH_ID.equals(branch.id()) || branch.behaviorOverrides().size() != 1) {
            throw new IllegalArgumentException("The Capability Studio tutorial branch is not resolvable");
        }
        CapabilityStudioGoldenDemoPack.BehaviorOverride override = branch.behaviorOverrides().getFirst();
        if (!DEPENDENCY_ID.equals(override.dependencyRef().id())
                || !BEHAVIOR_TIMEOUT.equals(override.behavior())) {
            throw new IllegalArgumentException("The Capability Studio tutorial dependency is not resolvable");
        }
        this.current = new State(
                branch.ref().revision(),
                fingerprint(new Behavior(DEFAULT_CONDITION, BEHAVIOR_TIMEOUT, DEFAULT_DURATION_MS)),
                new Behavior(DEFAULT_CONDITION, BEHAVIOR_TIMEOUT, DEFAULT_DURATION_MS));
    }

    synchronized State current() {
        return current;
    }

    synchronized State save(CapabilityStudioTutorialBranchBehaviorUpdateRequest request) {
        validate(request);
        if (request.expectedRevision() != current.revision()) {
            throw new CapabilityStudioTutorialBranchException(
                    "RG.CAPABILITY_STUDIO.REVISION_CONFLICT",
                    "保存时发现分支已经被其他操作更新。",
                    "本次更改未写入，页面上的版本已经过期。",
                    "重新读取当前分支版本，确认变更后使用新的 expectedRevision 再保存。",
                    "expectedRevision",
                    409);
        }

        Behavior candidate = new Behavior(
                request.condition().trim(), request.behavior(), request.durationMs());
        if (candidate.equals(current.behavior())) {
            return current;
        }

        long nextRevision = Math.incrementExact(current.revision());
        current = new State(nextRevision, fingerprint(candidate), candidate);
        return current;
    }

    synchronized void preflight() {
        State snapshot = current;
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

    private boolean resolvableDependency() {
        return pack.apiCapabilities().stream()
                .anyMatch(capability -> DEPENDENCY_ID.equals(capability.id())
                        && DEPENDENCY_NAME.equals(capability.name()));
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
                "RG.CAPABILITY_STUDIO.REQUEST_INVALID",
                whatHappened,
                impact,
                "修正请求字段后重新提交；不要提交 raw payload 或 mock JSON。",
                field,
                400);
    }

    private String fingerprint(Behavior behavior) {
        ObjectMapper mapper = new ObjectMapper();
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
            byte[] bytes = mapper.writeValueAsBytes(canonical);
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to canonicalize tutorial branch behavior", failure);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    record State(long revision, String fingerprint, Behavior behavior) {
    }

    record Behavior(String condition, String behavior, long durationMs) {
    }
}
