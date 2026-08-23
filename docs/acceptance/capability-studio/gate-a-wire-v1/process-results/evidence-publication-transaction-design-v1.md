# Evidence Publication Transaction Design v1

**文件**：`docs/acceptance/capability-studio/gate-a-wire-v1/process-results/evidence-publication-transaction-design-v1.md`
**版本**：v1 — 设计冻结（Design Freeze）
**状态**：待 P0/P1 独立评审
**Obligation Denominator**：RG-CS-EPT-v1 = 27（见 §I）
**与 Stage formalExpectedCount=27、FELT-14、S0-26 无共享分母；RG-CS-EPT-v1 不增加 formalPassCount**

---

## A. 问题陈述

### A.1 `CapabilityStudioExecutionLeaseEvidenceCli.java` 职责纠缠

文件：3189 行（`resource-gateway-test-kit/src/main/java/com/leanowtech/bloge/gateway/testkit/CapabilityStudioExecutionLeaseEvidenceCli.java`）。

| 行号 | 符号 | 纠缠职责 |
|---|---|---|
| 129 | `runWithProviderDiscovery(String[])` | CLI 解析 + provider discovery + invocation + exception + output（5 职责混合）|
| 355 | `execute(EvidenceFlow)` | stage dispatch + lock management + output emission + exception classification |
| 387 | `executeLocked(EvidenceFlow)` | lease + stage + error mapping |
| 424 | `emitSuccess(PrintStream, EvidenceFlow)` | CLI rendering 混入 core |
| 438 | `enum Recovery` | 6 种恢复策略嵌入 CLI 顶级类 |
| 485 | `class EvidenceFlow` | 20+ 字段既是数据容器又是控制流状态 |
| 793 | `class TranscriptPublication`（private static） | 实现 `EvidenceTransactionJournal`（`CapabilityStudioStageAcceptanceAuthorityProvider.java:1601`）但该接口不含 bounded child/SIGKILL/Store receipt；TranscriptPublication 自行实现全部这些职责 |

### A.2 FormalInputTreeSnapshotter 可复用边界

**可复用**：SHA256 transaction identity 结构（[FormalInputTreeSnapshotter.java:593-607](resource-gateway-test-kit/src/main/java/com/leanowtech/bloge/gateway/testkit/CapabilityStudioFormalInputTreeSnapshotter.java#L593-L607)）、LeaseBudget、LocalAtomicOperations、MAXIMUM_AUTHORITY_ENTRY_COUNT。

**不可复用**：`TRANSACTION_MESSAGE_VERSION = "resource-gateway.capability-studio.formal-input-tree-transaction.v1"`（domain 冲突）；`BUNDLE_ROOT_DIRECTORY` wrapper（只读 snapshot）；CommitStatus 二态（EPT 需要 6 态 lifecycle）；`snapshot()` 无 bounded child JVM 管理；无 external Store receipt。

### A.3 S0 JS Transaction 可复用边界

**可复用**：三集互补模型、`header.activePhase` 双字段、owner.json、`mkdirSync(EEXIST)` writer lock、exact rollback 三态、`resultFingerprint` deterministic SHA256。

**不可复用**：Node.js `child_process.spawn` 无法管理 JVM SIGKILL；无 bounded child transcript 捕获；无 external Store；无 fenced epoch；transactionId 无显式 domain constant。

---

## B. 决策

**选择**：新建深模块 `CapabilityStudioEvidencePublicationTransaction`（以下简称 EPT）。

**正式外部 API（仅 2 个方法）**：

```java
public interface CapabilityStudioEvidencePublicationTransaction {
    /** 幂等发布/恢复。重复调用自动按 on-disk state 恢复，无需调用方指定路径。*/
    Verdict execute(Request request);

    /** 只读复验。不持有锁，不写入任何文件。 */
    VerifyResult verify(Path committedRoot, ExpectedPins pins);
}
```

**包内 lifecycle states（不公开为 CLI 参数）**：

```
PREPARED          — active/ 创建，owner + header durable
LEASE_COMMITTED    — lock + epoch acquired；bounded child 执行
LOCAL_COMMITTED    — active→committed/ 原子 rename durable；B0 installed
EXTERNAL_PENDING  — B0 durable；external Store B1 request in-flight
COMPLETE          — B0 + B1 + R1 all durable
ABORTED           — durable abort closure 存在；需要 explicit recovery
```

**所有 CLI exit code 映射**：

| Exit | Outcome | ClosedCategory |
|---|---|---|
| 0 | COMMITTED 或 RECOVERED | — |
| 2 | CLOSED | INVALID |
| 3 | CLOSED | CONFLICT |
| 4 | CLOSED | UNAVAILABLE |
| 5 | CLOSED | BLOCKED |
| 6 | CLOSED | ABORTED / INTERNAL |

**禁止事项**：
- 不在 public Request 中暴露 JVM command、classpath、storeAdapter URL、recover flag、checkMode
- 不在 `verify()` 中写入任何文件
- 不在 recovery 路径调用 `prepareStore()` / `initializeOrValidate()`
- 不复用 `CapabilityStudioExecutionLeaseEvidenceCli` 的嵌套类作为生产代码
- 不使用 live source 修改语义（见 §D.6）

---

### B.4 ExactKeyLockRegistry + .ept-locks 跨进程 File Lock

主类是 `ExactKeyLockRegistry`，含 private nested class `LockEntry`。

**JVM 内 registry**：
- `ConcurrentHashMap<String, LockEntry>`
- `LockEntry = { refcount: int, lock: ReentrantLock }`
- map key = `committedRoot.normalize() + "|" + stableHex`
- `acquire(key, budgetNanos)`：
  - refcount == 0 → 创建 `LockEntry` → `lock.tryLock(remainingBudget, NANOSECONDS)`
  - refcount > 0 → `lock.tryLock(remainingBudget, NANOSECONDS)` 阻塞等待
  - tryLock 返回 false（timeout）→ 立即 `CLOSED(UNAVAILABLE)`
- `release(handle)`：
  - `lock.unlock()`
  - ref--
  - ref == 0 → `map.remove(key, entry)`（compare-and-remove）
- 同 key 串行；不同 key 并行

**跨进程 file lock**（`committedRoot/.ept-locks/<stableRequestId>.lock`）：
- 在 budget 循环内调用 `FileChannel.open(...).tryLock()`
- `tryLock()` 返回 `null` → 在 budget 内等待后重试；budget 耗尽 → `CLOSED(UNAVAILABLE)`
- `tryLock()` 抛 `OverlappingFileLockException` → 在 budget 内等待后重试；budget 耗尽 → `CLOSED(UNAVAILABLE)`
- `tryLock()` 抛 `IOException` → `CLOSED(UNAVAILABLE)`

**lock 持有期**：从 acquire 成功起，覆盖整个 execute() 调用，直到 Verdict 返回前才 release。

---

## C. Frozen Identity

### C.1 唯一 transaction domain

```
EPT_DOMAIN = "resource-gateway.capability-studio.evidence-publication-transaction.v1"
```

**ENGINE-DESIGN §494 必须更新为**：`transactionId = SHA256(EPT_DOMAIN || callerStableSemanticInputs || publicationNonce)`

**与相邻 domain 严格隔离**：

| Domain | 值 | 用于 |
|---|---|---|
| FormalInputTree | `"resource-gateway.capability-studio.formal-input-tree-transaction.v1"` | FormalInputTreeSnapshotter |
| ExecutionLease | `"resource-gateway.capability-studio.execution-lease-evidence-transaction.v1"` | CapabilityStudioExecutionLeaseEvidenceCli（legacy）|
| EPT（本文） | `"resource-gateway.capability-studio.evidence-publication-transaction.v1"` | EPT module |

---

## D. EPT 内部模块结构

### D.1 模块边界

```
resource-gateway-test-kit/
  └── ept/                          # EPT 包（深模块）
        ├── EvidencePublicationTransaction.java    # 顶级接口
        ├── LifecycleState.java                     # PREPARED/LEASE_COMMITTED/LOCAL_COMMITTED/EXTERNAL_PENDING/COMPLETE/ABORTED
        ├── Request.java                            # J.1 字段表
        ├── ExpectedPins.java                       # J.2 字段表
        ├── Verdict.java                            # J.3 字段表（仅 COMMITTED|RECOVERED|CLOSED）
        ├── ClosedCategory.java                     # INVALID|CONFLICT|BLOCKED|UNAVAILABLE|ABORTED|INTERNAL
        ├── FencingAuthority.java                  # §E.2 token 是 authority-authenticated opaque receipt
        ├── StorePublisher.java                     # injected primitive
        ├── BoundedChildExecutor.java               # injected primitive（SIGKILL 管理）
        ├── FileSystem primitives                   # active/committed/closed/ scratch dirs
        ├── CrashPointSimulator.java               # §F.2 可注入 crash
        └── RecoveryProcessor.java                  # §F.1 crash recovery 逻辑
```

### D.2 FencingAuthority token 语义

FencingAuthority 颁发的 token 是 **authority-authenticated opaque receipt**。

| 属性 | 要求 |
|---|---|
| Token 结构 | authority 签发的 opaque bytes（不接受 caller 自填）|
| Authority fingerprint | token 中必须携带签发 authority 的 fingerprint |
| Epoch | token 中必须携带签发时的 epoch number |
| Verification | ExpectedPins/verification 必须验证 authority fingerprint + epoch，不能只信 owner.json |

### D.3 ExpectedPins 字段表

| 字段 | Required | Type | Source | Identity | Validation |
|---|---|---|---|---|---|
| `stableRequestId` | 是 | String（SHA256 hex）| Caller 提供 | 事务唯一标识（不含 nonce）| 64 hex chars |
| `b0RawFingerprint` | 是 | String（SHA256 hex）| Caller 提供 | B0 raw 摘要 | 64 hex chars |
| `b0CanonicalFingerprint` | 是 | String（SHA256 hex）| Caller 提供 | B0 canonical 摘要 | 64 hex chars |
| `b0ClosureFingerprint` | 是 | String（SHA256 hex）| Caller 提供 | B0 closure 摘要 | 64 hex chars |
| `b1ReceiptFingerprint` | 是 | String（SHA256 hex）| Caller 提供 | B1 Store immutable receipt 摘要 | 64 hex chars |
| `r1Fingerprint` | 是 | String（SHA256 hex）| Caller 提供 | R1 final outer commitment 摘要 | 64 hex chars |
| `authorityFingerprint` | 是 | String（SHA256 hex）| FencingAuthority | 签发 authority 摘要 | 64 hex chars |
| `authorityEpoch` | 是 | long | FencingAuthority | 签发时的 epoch | > 0 |

**ExpectedPins 必需/可选规则**：
- **完整 verify**（B0+B1+R1）：b0RawFingerprint + b0CanonicalFingerprint + b0ClosureFingerprint + b1ReceiptFingerprint + r1Fingerprint + authorityFingerprint + authorityEpoch 必须
- **B0-only recovery verify**：内部方法，不属于 public ExpectedPins 校验

**DoD 说明**：Slice E0 才生成 strict schemas。当前无伪 JSON schema（全文不在 JSON Schema 中枚举 EPT 字段）。

`boundedChildTimeoutMillis` 由 `LaunchProfile` 构造器注入，不在 public Request 中暴露。

| 字段 | Required | Type | Source | Identity | Validation |
|---|---|---|---|---|---|
| `boundedChildTimeoutMillis` | 是 | long | LaunchProfile constructor | 超时边界 | > 0 |
| `jrePath` | 是 | Path | LaunchProfile constructor | JRE pinned path | 存在、可执行 |
| `mainClass` | 是 | String | LaunchProfile constructor | typed verifier main class | 非空 |
| `candidateFingerprint` | 是 | String（SHA256 hex）| LaunchProfile constructor | candidate artifact 摘要 | 64 hex chars |
| `declarationFingerprint` | 是 | String（SHA256 hex）| Caller 提供，参与 declaration pin | 声明摘要 | 64 hex chars |
| `launchFingerprint` | 是 | String（SHA256 hex）| 模块计算（包含 candidateFingerprint）| 启动摘要 | 64 hex chars |

**fingerprint 参与 pin**：`declarationFingerprint` 参与 declaration pin；`launchFingerprint`（含 candidateFingerprint）参与 launch pin。caller expected mismatch = INVALID。


### D.4 B0 Fingerprint 完整定义（无自引用）

**核心约束**：inner manifest 不得包含 `b0Raw`、`b0Canonical`、`b0Closure` 派生字段。inner manifest 只含 payload-free ordered evidence content tree fingerprint 及身份字段。

#### D.4.1 三层 Fingerprint 定义

```
b0RawFingerprint = SHA256(b0-inner-manifest.json exact raw bytes)

b0CanonicalFingerprint = SHA256(strict canonical form of b0-inner-manifest.json)

b0ClosureFingerprint = SHA256(
    EPT_DOMAIN
  || stableRequestId
  || transactionId
  || b0RawFingerprint
  || b0CanonicalFingerprint
  || evidenceContentTreeFingerprint
  || ownerEpoch
  || fencingTokenFingerprint
)

fencingTokenFingerprint = SHA256(fencingToken.tokenBytes)
```

**注**：
- `evidenceContentTreeFingerprint` 是 bounded child 执行产物的 content-addressed tree 摘要
- `fencingTokenFingerprint` 仅对 token bytes 取 SHA256，不含 token 内嵌的 epoch/fingerprint（已在 ownerEpoch 字段独立传递）
- `b0ClosureFingerprint` 不自引用 inner manifest 的任何派生字段

#### D.4.2 inner manifest 结构约束

`b0-inner-manifest.json`（committed B0）必须只含：

```json
{
  "version": "capability-studio-ept-b0-manifest.v1",
  "stableRequestId": "<64-hex>",
  "transactionId": "<64-hex>",
  "evidenceContentTreeFingerprint": "<64-hex>",
  "ownerEpoch": "<long>",
  "boundedChildDigest": "<64-hex>",
  "boundedChildInputEnvironmentDigest": "<64-hex>"
}
```

**禁止字段**：`b0RawFingerprint`、`b0CanonicalFingerprint`、`b0ClosureFingerprint` 不得出现在 inner manifest 中。

#### D.4.3 R1 绑定

```
R1 = StorePublisher.issue(B0, b0ClosureFingerprint, transaction, owner)
```

R1 绑定 `b0ClosureFingerprint + B1 + transaction + owner`。

#### D.4.4 ExpectedPins 完整验证

**完整 verify（B0+B1+R1）** 必需字段：

| 字段 | 来源 | 重算要求 |
|---|---|---|
| `b0RawFingerprint` | Caller 提供 + 模块重算比对 | 模块重算 SHA256(exact raw bytes) |
| `b0CanonicalFingerprint` | Caller 提供 + 模块重算比对 | 模块重算 SHA256(strict canonical) |
| `b0ClosureFingerprint` | Caller 提供 + 模块重算比对 | 模块重算 SHA256(EPT_DOMAIN\|\|...\|\|fencingTokenFingerprint) |
| `b1ReceiptFingerprint` | Caller 提供 + 模块重算比对 | 模块重算 SHA256(B1 receipt canonical bytes) |
| `r1Fingerprint` | Caller 提供 + 模块重算比对 | 模块重算 SHA256(R1 receipt canonical bytes) |
| `authorityFingerprint` | Caller 提供 + FencingAuthority 重算比对 | SHA256(authority token bytes) |
| `authorityEpoch` | Caller 提供 + FencingAuthority 重算比对 | epoch 值精确匹配 |

**全部 7 个 fingerprint 必须精确相等才算 PASS**。

---

### D.5 Transaction File Protocol Tree

事务文件层次结构与 durable marker 精确映射：

```
<privateParent>/
└── <stableRequestId>/
    ├── active/
    │   ├── header.json              # PREPARED；writer=EPT；CREATE_NEW；force barrier
    │   ├── owner-authority-receipt.json  # PREPARED；writer=FencingAuthority；CREATE_NEW；force barrier
    │   ├── lease-receipt.json       # LEASE_COMMITTED；writer=EPT；CREATE_NEW；force barrier
    │   ├── child-start.json         # LEASE_COMMITTED；writer=EPT；CREATE_NEW；force barrier
    │   ├── child-process-observation.json  # LEASE_COMMITTED；writer=EPT；CREATE_NEW；force barrier
    │   ├── bounded-transcript.json  # LOCAL_COMMITTED；writer=EPT；CREATE_NEW；force barrier
    │   ├── b0-inner-manifest-scratch.json  # LOCAL_COMMITTED；writer=EPT；无 force（scratch）
    │   └── abort-closure.json       # ABORTED；writer=EPT；CREATE_NEW；force barrier
    ├── committed/
    │   └── <stableRequestId>/
    │       ├── b0-inner-manifest.json  # LOCAL_COMMITTED；atomic rename from scratch；force
    │       ├── b1-receipt.json      # EXTERNAL_PENDING；writer=StorePublisher；external
    │       └── r1-receipt.json      # COMPLETE；writer=StorePublisher；external
    └── closed/
        └── <stableRequestId>/
            └── abort-closure.json   # ABORTED recovery marker
```

**文件对象协议表**：

| 文件对象 | Lifecycle State | Writer | CREATE_NEW | Force Barrier | 对应 Verify |
|---|---|---|---|---|---|
| `header.json` | PREPARED | EPT | 是 | 是 | header.activePhase match |
| `owner-authority-receipt.json` | PREPARED | FencingAuthority | 是 | 是 | token.fingerprint + token.epoch |
| `lease-receipt.json` | LEASE_COMMITTED | EPT | 是 | 是 | epoch + token exact match |
| `child-start.json` | LEASE_COMMITTED | EPT | 是 | 是 | intentSequence match；无 PID/时钟 |
| `child-process-observation.json` | LEASE_COMMITTED | EPT | 是 | 是 | PID + processStartIdentity |
| `bounded-transcript.json` | LOCAL_COMMITTED | EPT | 是 | 是 | content match + overflow flag |
| `b0-inner-manifest-scratch.json` | LOCAL_COMMITTED | EPT | 否 | 否 | 无（scratch candidate）|
| `b0-inner-manifest.json` | LOCAL_COMMITTED | EPT | 原子 rename | 是 | b0RawFingerprint + b0CanonicalFingerprint + b0ClosureFingerprint |
| `b1-receipt.json` | EXTERNAL_PENDING | StorePublisher | 外部 | 外部 | b1ReceiptFingerprint |
| `r1-receipt.json` | COMPLETE | StorePublisher | 外部 | 外部 | r1Fingerprint |
| `abort-closure.json` | ABORTED | EPT | 是 | 是 | abort timestamp + reason |

**child-start.json 内容约束**：

```json
{
  "stableRequestId": "<64-hex>",
  "attemptId": "<generation-attempt-uuid>",
  "launchProfileFingerprint": "<64-hex>",
  "intentSequence": "<monotonically-increasing-sequence>"
}
```

- 不含 PID
- 不含 wall clock 时间戳
- 不含进程启动 identity
- 只表达 spawn intent，用于 crash 后确定 ABORTED no rerun

**child-process-observation.json 内容**：

```json
{
  "stableRequestId": "<64-hex>",
  "attemptId": "<generation-attempt-uuid>",
  "pid": "<operating-system-pid>",
  "processStartIdentity": "<os-provided-start-identity>",
  "observedAt": "<monotonic-nanotime>"
}
```

- 含 PID（外部 supervisor 可独立确认进程存在）
- 含 processStartIdentity（防 PID 回收复用误判）
- durable marker 独立于 child 存活状态

**CP-03 与 CP-04 同步设计**：
- CP-03 durable marker = `fsync(child-start.json)` 完成
- CP-04 durable marker = `fsync(child-process-observation.json)` 完成
- F.2 表与 I.3 CP03/CP04 描述严格一致

---

## E. 双集输入分离

### E.1 authorityInputTree 与 targetInputTree 分离

| 集合 | Fingerprint 字段 | 用途 | 来源 |
|---|---|---|---|
| Authority 输入树 | `authorityInputTreeFingerprint` | FencingAuthority、StorePublisher、Authority 注入的 verifier 的输入 | Caller 提供 |
| Target 输入树 | `targetInputTreeFingerprint` | 被测 typed verifier 的输入 | Caller 提供 |

**模块重算**：caller 提供 expected `authorityInputTreeFingerprint` 和 `targetInputTreeFingerprint`；模块内部重算并比对，mismatch = INVALID。

### E.2 stableRequestId 全部输入

```
stableRequestId = SHA256(
    EPT_DOMAIN
  || authorityInputTreeFingerprint
  || targetInputTreeFingerprint
  || planFingerprint
  || targetBindingFingerprint
  || declarationFingerprint
  || candidateFingerprint
)

transactionId = SHA256(
    EPT_DOMAIN
  || stableRequestId
  || publicationNonce            # external deployment-provided nonce
)
```

**caller expected stableRequestId mismatch = INVALID**

---

## F. 并发与进程

### F.1 crash recovery 语义原则

**核心原则**：crash 时不能声称 verdict 已 durable；closure 由恢复进程写。

| 阶段 | durable marker | crash 后的 verdict |
|---|---|---|
| PREPARED | `fsync(header.json)` | 恢复 close 旧 attempt，generation+1 successor，最终完成才 RECOVERED |
| LEASE_COMMITTED | `fsync(lease-receipt.json)` | 恢复 exact lease，允许一次 child |
| — | `fsync(child-start.json)` | spawn 是否发生不确定，ABORTED no rerun；intent 无假 PID |
| — | child PID exists | child 已 spawn 运行中，supervisor 清理整棵 process tree，ABORTED no rerun |
| LOCAL_COMMITTED 前 | `fsync(bounded-transcript.json)` | recover 校验后安装 B0，no rerun |
| — | atomic rename 完成 | source/target 四态恢复并补 force |
| EXTERNAL_PENDING 前 | `fsync(committed/<stableRequestId>/)` | 只进入 EXTERNAL_PENDING 并补 B1/R1 |
| Store in-flight | Store idempotency key accepted | 查询同 key 补 B1/R1 |

### F.2 8 个真实 Child Crash Points（可注入 parent crash boundary）

每个 crash point 的 durable marker 是 `fsync()` 完成点。Parent crash 在 durable marker **之后**注入，验证恢复路径。

| ID | Durable Marker | Trigger | Recovery Oracle | Verdict |
|---|---|---|---|---|
| CP-01 | `fsync(header.json)` | 进程 SIGKILL（PREPARED durable）| recover close old attempt；generation+1 successor；最终完成才 RECOVERED | RECOVERED |
| CP-02 | `fsync(lease-receipt.json)` | 进程 SIGKILL（lease receipt durable，child-intent marker 不存在）| recover exact lease；allow one child | RECOVERED |
| CP-03 | `fsync(child-start.json)` | 进程 SIGKILL（child-start intent durable，spawn 前/附近 crash）| spawn 是否发生不确定；ABORTED；no rerun；intent 无假 PID | ABORTED |
| CP-04 | `fsync(child-process-observation.json)` | 进程 SIGKILL（child-process-observation durable，supervisor 可确认）| supervisor 清理整棵 process tree；ABORTED；no rerun | ABORTED |
| CP-05 | `fsync(bounded-transcript.json)` | 进程 SIGKILL（complete transcript durable，B0 未安装）| recover 校验后安装 B0；no rerun | COMMITTED 或 INVALID |
| CP-06 | atomic rename `active→committed/` 完成 | 进程 SIGKILL（B0 rename 完成，parent force 未完成）| source/target 四态恢复；补 parent force | RECOVERED |
| CP-07 | `fsync(committed/<stableRequestId>/)` | 进程 SIGKILL（B0 parent force 完成，Store 未调用）| 只进入 EXTERNAL_PENDING；补 B1/R1 | RECOVERED |
| CP-08 | Store idempotency key accepted | 进程 SIGKILL（Store 已 accept，响应或本地 B1 前 crash）| 查询同 key；补 B1/R1 | RECOVERED |

**注**：CRASH 在 durable marker **之前**注入的场景不改变 verdict（EPT 在 crash 前未完成 barrier，无状态变化）。

### F.3 Bounded Child JVM（包内 primitive）

**重要**：bounded child 是 EPT 包内 injected primitive，public Request 不暴露 JVM command/classpath。

**Execution 环境**：

```
java executable: from EPT pinned JRE path（not $PATH）
java version pin: SHA256(ProcessTools.exec("java -version 2>&1"))
classpath order fingerprint: SHA256(ordered classpath entries, ASCII)
main class: from typed verifier descriptor（from LaunchProfile）
working dir: transaction-owned temp directory（private UID）

Stream management:
  stdout: 64 KiB cap（65536 bytes total）；overflow → SIGPIPE
  stderr: 64 KiB cap（65536 bytes total）；overflow → SIGPIPE
  stdin: closed
```

**Process tree 可移植限制**：

```
EPT JVM
  └── child JVM
        └── [grandchild processes]

Portable assumption：child JVM 不生成独立 process group/job object
无法证明所有 descendant quiescent → 行为：
  - 直接 child: destroyForcibly() 尝试终止
  - grandchild: 不保证 quiescent
  - 如果无法证明 process tree 已终止 → UNAVAILABLE
  - 如果证明直接 child terminated 但 grandchild 仍运行 → UNAVAILABLE（process tree 不干净）
```

### F.4 Signal 和 Reap

```
SIGTERM to child: 5s grace → SIGKILL
SIGKILL to child: immediate
Parent SIGKILL: orphan reaper 扫描 dead child PIDs → reaper.reap(pid)
destroyForcibly()（Java 9+）= JVM-level SIGKILL

Transcript overflow:
  达到 65537 bytes → 发送 SIGPIPE → 停止写入
  → 记录 overflow=true + partial digest（仅诊断）
  → **partial digest 不能进入 localCommitId**
  → CLOSED(BLOCKED)
```

---
### F.5 B1/R1 发布协议：.ept-locks/ 私有 staging + Files.createLink

**staging 目录**：`committedRoot/.ept-locks/`（0700，EPT 私有 shared path）
**平面文件名**：`stableHex.receiptName.staging.UUID`
- `stableHex` = stableRequestId 的十六进制表示
- `receiptName` = `b1-receipt.json` 或 `r1-receipt.json`
- 无 nonce 子目录

**B1 发布序列**：

```
Step 1  FileChannel.open(staging, CREATE_NEW, WRITE)  # 原子 open
Step 2  loop: write(content) + force(true)           # 直到全部写完
Step 3  Files.createLink(target/b1-receipt.json, staging)
Step 4  force(target/b1-receipt.json 的父目录)
Step 5  delete(staging)
Step 6  force(committedRoot/.ept-locks/)
```

**R1 发布序列**（与 B1 相同结构，`receiptName = r1-receipt.json`）：

```
Step 1  FileChannel.open(staging, CREATE_NEW, WRITE)
Step 2  loop: write(content) + force(true)
Step 3  Files.createLink(target/r1-receipt.json, staging)
Step 4  force(target/r1-receipt.json 的父目录)
Step 5  delete(staging)
Step 6  force(committedRoot/.ept-locks/)
```

**Files.createLink 异常映射（RG-CS-EPT-v1）**：
- `FileAlreadyExistsException` → `CLOSED(CONFLICT)`
- `RuntimeException` → `CLOSED(UNAVAILABLE)`
- `IOException` → `CLOSED(UNAVAILABLE)`

---

### F.6 same-stable / different-nonce = CONFLICT（external call 前检测）

**transactionId 来源**：`committed/<stableRequestId>/b0-inner-manifest.json` 内的 `transactionId` 字段。

**处理流程**：

```
1. acquire(stableRequestId) → lock acquired
2. 读取 committed/<stableRequestId>/b0-inner-manifest.json.transactionId
   - absent → fresh path：允许继续（首次发布）
   - present && equals(incoming transactionId) → same-nonce recovery：COMPLETE/RECOVERED path
   - present && NOT equals(incoming transactionId) → release(lock) → CLOSED(CONFLICT)
3. CLOSED(CONFLICT) 在任何 Store / Authority / external call 之前返回
```

**关键约束（RG-CS-EPT-v1）**：
- transactionId 比较使用精确字节相等（不 normalize）
- 检查在 **文件 lock 持有期间**完成，避免 TOCTOU 窗口
- CLOSED(CONFLICT) 不调用 StorePublisher、Authority 或任何外部服务
- CLOSED(CONFLICT) 仍需 release(lock)，使另一个竞争者有机会继续

---

---



## G. 双维结果分类

### G.1 2D Outcome Model（Public vs Internal）

**Public Outcome（对外暴露）**：

```
COMMITTED    — B0 + B1 + R1 all durable（首次成功）；exit 0
RECOVERED   — 检测到已存在的 COMPLETE；验证通过；exit 0
CLOSED      — 非成功终止；exit 2/3/4/5/6
```

**ClosedCategory（仅用于 CLOSED）**：

```
INVALID      — 协议、schema、identity、pin、symlink/hardlink/mode/ownership/casefold 违规
CONFLICT    — idempotency key 冲突
BLOCKED     — 能力不足、policy 违规、capacity exceeded、child non-zero exit
UNAVAILABLE — resource unavailable、Store outage、lock timeout、interrupt、metadata unavailable
ABORTED     — durable abort closure 存在；recovery required
INTERNAL    — 内部错误
```

**Lifecycle 可含 ABORTED 但非 public outcome**：

- `Lifecycle.ABORTED` 是内部状态，表示 abort closure 已 durable
- 对外返回 `CLOSED(ABORTED)`，exit 6
- **不允许**：对外返回 `ABORTED` 作为独立 outcome

### G.2 逐类必要充分条件

| Public Outcome | ClosedCategory | 必要充分条件 |
|---|---|---|
| COMMITTED | — | B0 raw/canonical/closure fingerprints match + B1 durable + R1 durable |
| RECOVERED | — | 检测到已存在的 `committed/<stableRequestId>/b0+b1+r1`；B0/B1/R1 fingerprints match |
| CLOSED | INVALID | symlink/hardlink/mode/ownership/casefold/java pin/classpath drift/schema invalid/authorityInputTree mismatch/targetInputTree mismatch |
| CLOSED | CONFLICT | stableRequestId 相同 + boundedChildDigest 不同 或 nonce 不同 |
| CLOSED | BLOCKED | child exit non-zero / capacity exceeded / missing required authority / output overflow |
| CLOSED | UNAVAILABLE | lock timeout / Store 503 / Store timeout / parent missing / metadata unavailable / interrupt / process tree not quiescent |
| 条件 | 检查位置 |
|---|---|
| stableRequestId 相同 | acquire lock 成功后立即检查 |
| transactionId 不同（nonce 不同）| 解析 stored transactionId |
| 任一请求在 EXTERNAL_PENDING 或之后状态 | — |

**处理流程**：

```
1. acquire(stableRequestId) → lock acquired
2. 读取 committed/<stableRequestId>/b0-inner-manifest.json.transactionId
   - absent → fresh path：允许继续（首次发布）
   - present && equals(incoming transactionId) → same-nonce recovery：COMPLETE/RECOVERED path
   - present && NOT equals(incoming transactionId) → release(lock) → CLOSED(CONFLICT)
3. CLOSED(CONFLICT) 在任何 Store / Authority / external call 之前返回
```

**关键约束（RG-CS-EPT-v1）**：
- transactionId 比较使用精确字节相等（不 normalize）
- 检查在 **文件 lock 持有期间**完成，避免 TOCTOU 窗口
- CLOSED(CONFLICT) 不调用 StorePublisher、Authority 或任何外部服务
- CLOSED(CONFLICT) 仍需 release(lock)，使另一个竞争者有机会继续

### H.3 方案 C：嵌入 DB — 有条件拒绝（14/25）

DB WAL 提供 durability；但 bounded child crash 与 DB crash 不同步；引入 deployment artifact；external Store receipt 仍在 DB 外。

---

## I. RG-CS-EPT-v1 固定 27 Obligation 矩阵

**Denominator**：RG-CS-EPT-v1 = 27（与 Stage formalExpectedCount=27、FELT-14、S0-26 无共享分母；不增加 formalPassCount）

```
构成：EPT-H (5) + EPT-FS (6) + EPT-CP (8) + EPT-CN (3) + EPT-PR (2) + EPT-ST (2) + EPT-VR (1) = 27
```

**每格结构**：
- `Obligation ID`：唯一
- `Fixed subcases`：若有（denominator within obligation）
- `Precondition`：初始状态
- `Trigger`：操作
- `Oracle`：pass 条件
- `Evidence`：可独立复验的 artifact
- `Closed code`：CLI exit

**跳过任一 fixed subcase 使该 obligation FAIL；EPT 不允许跳过 subcase。**

### I.1 Happy Path（5 格）

**EPT-H01 — Fresh ordinary-JAR commit+verify**

| 字段 | 值 |
|---|---|
| Precondition | ∅ on-disk state |
| Trigger | execute(Request with fresh nonce) |
| Oracle | B0/b1/r1 durable；Verdict.COMMITTED；exit 0 |
| Evidence | `committed/<stableRequestId>/b0-inner-manifest.json` + `b1-receipt.json` + `r1-receipt.json` |
| Closed code | 0 |

**EPT-H02 — COMPLETE exact retry idempotency**

| 字段 | 值 |
|---|---|
| Fixed subcases | (a) no producer duplicate (b) no Store duplicate |
| Precondition | EPT-H01 已 COMPLETE |
| Trigger | execute(Request, same stableRequestId, same boundedChildDigest) |
| Oracle (a) | Producer logs 中同一 stableRequestId + attemptGeneration 无 duplicate |
| Oracle (b) | Store logs 中同一 b0ClosureFingerprint key 无 duplicate |
| Evidence | `committed/<stableRequestId>/` 未被修改；receipt 与 EPT-H01 相同 |
| Closed code | 0 |

**EPT-H03 — EXTERNAL_PENDING recovery completes B1/R1, B0 unchanged**

| 字段 | 值 |
|---|---|
| Precondition | EXTERNAL_PENDING（B0 durable；B1 absent）|
| Trigger | execute(Request, same stableRequestId) |
| Oracle | B1 + R1 durable；B0 fingerprint 精确等于 pre-recovery；no B0 re-install |
| Evidence | `committed/<stableRequestId>/b1-receipt.json` 新写入；`b0-inner-manifest.json` bytes 未变 |
| Closed code | 0 |

**EPT-H04 — Shaded JAR execute+verify equivalent to ordinary**

| 字段 | 值 |
|---|---|
| Fixed subcases | (a) shaded JAR produces same boundedChildDigest (b) shaded JAR B0 artifact identity matches expected pin (c) shaded JAR verify passes (d) ordinary JAR verify passes |
| Precondition | ∅ on-disk |
| Trigger | execute(Request with shaded JAR verifier；same typed evidence pins）|
| Oracle (a) | `bounded-transcript.json` digest equals ordinary JAR run |
| Oracle (b) | shaded B0 artifact identity fingerprint matches ExpectedPins |
| Oracle (c) | shaded verify(Path, ExpectedPins) returns COMMITTED |
| Oracle (d) | ordinary verify(Path, ExpectedPins) returns COMMITTED |
| Oracle (e) | semantic result fingerprint equivalent（CodeSource/JAR pin differs）|
| Oracle (f) | obligation outcome identical |
| Oracle (g) | canonical protocol projection equivalent |
| Evidence | 各 3 个 artifact 文件；各自 verify 结果 |
| Closed code | 0 |

**注**：CodeSource/JAR pin 不同，raw B0/R1 bytes 不要求 identical。

**EPT-H05 — B0/B1/R1 fixed mutation set all rejected**

| 字段 | 值 |
|---|---|
| Fixed subcases | (a) B0 raw modified after install (b) B0 inner manifest modified (c) B1 receipt modified (d) R1 modified |
| Precondition | EPT-H01 COMPLETE |
| Trigger (a) | 外部修改 `committed/<stableRequestId>/b0/evidence-root/` 任意文件 |
| Trigger (b) | 外部修改 `b0-inner-manifest.json` |
| Trigger (c) | 外部修改 `b1-receipt.json` |
| Trigger (d) | 外部修改 `r1-receipt.json` |
| Oracle (a) | B0 raw fingerprint mismatch → CLOSED(INVALID_B0_RAW) |
| Oracle (b) | B0 inner manifest fingerprint mismatch → CLOSED(INVALID_B0_INNER) |
| Oracle (c) | B1 receipt/issuer/idempotency mismatch → CLOSED(INVALID_B1_RECEIPT) |
| Oracle (d) | R1 outer commitment mismatch → CLOSED(INVALID_R1_OUTER_COMMITMENT) |
| Evidence | 修改前后 fingerprint diff + verify 失败原因 |
| Closed code | 2（CLOSED INVALID）|

**注**：每种 artifact mutation 有独立 closedReasonCode。

### I.2 File System（6 格）

**EPT-FS01 — Unknown sibling object preserved byte-for-byte + fail-closed**

| 字段 | 值 |
|---|---|
| Fixed subcases | (a) unknown file in privateParent (b) unknown dir in privateParent (c) unknown object in active/ (d) unknown object in committed/ |
| Precondition (a-d) | privateParent/active/committed 含额外非 EPT 创建的 file/dir/object |
| Trigger (a-d) | execute(Request) 执行各操作 |
| Oracle (a) | unknown file byte+metadata preserved；CLOSED(INVALID_UNKNOWN_OBJECT) |
| Oracle (b) | unknown dir byte+metadata preserved；CLOSED(INVALID_UNKNOWN_OBJECT) |
| Oracle (c) | active/ 内 unknown object preserved；CLOSED(INVALID_UNKNOWN_OBJECT) |
| Oracle (d) | committed/ 内 unknown object preserved；CLOSED(INVALID_UNKNOWN_OBJECT) |
| Evidence | 操作前后 unknown object hash 对比 |
| Closed code | 2（CLOSED INVALID_UNKNOWN_OBJECT）|

**注**：真实同事务锁竞争属于 EPT-CN01，不混入 FS01。

**EPT-FS02 — Symlink at each protected layer rejected**

| 字段 | 值 |
|---|---|
| Fixed subcases | (a) privateParent is symlink (b) evidence-root/ contains symlink (c) committed/ target is symlink (d) child-start.json parent is symlink |
| Trigger | 在各层注入 symlink |
| Oracle | `INVALID_SYMLINK` at each layer |
| Evidence | `lstat()` 检测到的 symlink path |
| Closed code | 2（CLOSED INVALID）|

**EPT-FS03 — Hardlink/nlink alias rejected**

| 字段 | 值 |
|---|---|
| Fixed subcases | (a) owner.json file nlink > 1 (b) header.json file nlink > 1 (c) B0 file nlink > 1 (d) cross-tree same inode alias |
| Trigger | 各层 hardlink 或 alias 创建 |
| Oracle (a-d) | `INVALID_HARDLINK` at each |
| Evidence | `Files.getAttribute(path, "unix:nlink")` 值 + inode 对比 |
| Closed code | 2（CLOSED INVALID_HARDLINK）|

**注**：不检测 hardlink to directory。

**EPT-FS04 — ASCII-policy/NFC/casefold collision rejected on every OS; native macOS fixture additional**

| 字段 | 值 |
|---|---|
| Fixed subcases | (a) NFC normalization collision (b) casefold collision (c) ASCII collision (d) macOS additional fixture |
| Trigger | 在各文件系统层注入冲突 |
| Oracle | `INVALID_PATH_COLLISION` at each |
| Evidence | collision path + normalization result |
| Closed code | 2（CLOSED INVALID）|

**EPT-FS05 — privateParent mode 0700 enforcement**

| 字段 | 值 |
|---|---|
| Fixed subcases | (a) privateParent mode != 0700 (b) evidence-root/ parent mode != 0700 |
| Trigger | 注入错误 mode |
| Oracle | `INVALID_FILE_MODE` |
| Evidence | actual mode bits |
| Closed code | 2（CLOSED INVALID）|

**EPT-FS06 — 512 files / 32 MiB capacity boundary**

| 字段 | 值 |
|---|---|
| Fixed subcases | (a) exactly 512 files accepted (b) exactly 32 MiB accepted (c) 513 files rejected (d) 32 MiB + 1 byte rejected |
| Trigger | evidence bundle at limit and at limit+1 |
| Oracle (a)(b) | ACCEPTED；commit proceeds |
| Oracle (c)(d) | `BLOCKED_CAPACITY` |
| Evidence | file count + byte count in `b0-inner-manifest.json` |
| Closed code | 0（accepted）或 5（CLOSED BLOCKED）|

### I.3 Crash Points（8 格）

**EPT-CP01 — CP-01 PREPARED durable after parent crash**

| 字段 | 值 |
|---|---|
| Precondition | `fsync(header.json)` 完成（PREPARED durable）|
| Trigger | SIGKILL parent |
| Oracle | recover close old attempt；generation+1 successor；最终完成才 RECOVERED |
| Evidence | abort-closure.json + 新 generation attempt + 最终 RECOVERED verdict |
| Closed code | 0（RECOVERED）|

**EPT-CP02 — CP-02 lease receipt durable, child-intent marker not exists**

| 字段 | 值 |
|---|---|
| Precondition | `fsync(lease-receipt.json)` 完成（lease receipt durable，child-intent marker 不存在）|
| Trigger | SIGKILL parent |
| Oracle | recover exact lease；allow one child |
| Evidence | lease-receipt.json epoch + token match pre-crash |
| Closed code | 0（RECOVERED）|

**EPT-CP03 — CP-03 child-start intent durable, spawn before/near crash**

| 字段 | 值 |
|---|---|
| Precondition | `fsync(child-start.json)` 完成（child-start intent durable，spawn 前/附近 crash）|
| Trigger | SIGKILL parent |
| Oracle | spawn 是否发生不确定；ABORTED；no rerun；intent 无假 PID |
| Evidence | child-start.json durable + abort-closure.json；无 bounded-transcript |
| Closed code | 6（CLOSED ABORTED）|

**注**：child-start.json 只含 stableRequestId/attemptId/launchProfileFingerprint/intentSequence，不含 PID/时钟。

**EPT-CP04 — CP-04 child-process-observation durable, supervisor confirms process**

| 字段 | 值 |
|---|---|
| Precondition | `fsync(child-process-observation.json)` 完成（child-process-observation durable，外部 supervisor 可确认进程）|
| Trigger | SIGKILL parent |
| Oracle | child-process-observation durable + supervisor 确认进程存在；supervisor 清理整棵 process tree；ABORTED；no rerun |
| Evidence | child-process-observation.json durable + abort-closure.json + process tree cleanup log |
| Closed code | 6（CLOSED ABORTED）|

**注**：child-process-observation.json 含 PID/processStartIdentity，外部 supervisor 可独立确认。


**EPT-CP05 — CP-05 complete transcript durable, B0 not installed**

| 字段 | 值 |
|---|---|
| Precondition | `fsync(bounded-transcript.json)` 完成（complete transcript durable，B0 未安装）|
| Trigger | SIGKILL parent |
| Oracle | recover 校验后安装 B0；no rerun |
| Evidence | b0-inner-manifest.json installed；bounded-transcript verified |
| Closed code | 0（COMMITTED）或 2（CLOSED INVALID）|

**EPT-CP06 — CP-06 B0 rename complete, parent force not complete**

| 字段 | 值 |
|---|---|
| Precondition | atomic rename `active→committed/` 完成（B0 rename 完成，parent force 未完成）|
| Trigger | SIGKILL parent |
| Oracle | source/target 四态恢复；补 parent force |
| Evidence | source/target 四态日志 + force completion log |
| Closed code | 0（RECOVERED）|

**EPT-CP07 — CP-07 B0 parent force complete, Store not called**

| 字段 | 值 |
|---|---|
| Precondition | `fsync(committed/<stableRequestId>/)` 完成（B0 parent force 完成，Store 未调用）|
| Trigger | SIGKILL parent |
| Oracle | 只进入 EXTERNAL_PENDING；补 B1/R1 |
| Evidence | b0-inner-manifest.json b0RawFingerprint verified；b1/r1 installed |
| Closed code | 0（RECOVERED）|

**EPT-CP08 — CP-08 Store accepted but response or local B1 lost**

| 字段 | 值 |
|---|---|
| Precondition | Store idempotency key 已 accept（响应或本地 B1 前 crash）|
| Trigger | SIGKILL parent |
| Oracle | 查询同 idempotency key；补 B1/R1 |
| Evidence | Store idempotency key match + b1/r1 installed |
| Closed code | 0（RECOVERED）|

### I.4 Concurrency（3 格）

**EPT-CN01 — Two JVM same request: exactly one COMMITTED, one RECOVERED, real FileLock miss proof**

| 字段 | 值 |
|---|---|
| Fixed subcases | (a) JVM1 acquires lock first → JVM2 gets UNAVAILABLE_LOCK (b) JVM1 COMPLETEs before JVM2 → JVM2 gets RECOVERED (c) JVM1 holds lock and crashes → JVM2 acquires after grace |
| Trigger | Two JVM processes execute same Request simultaneously |
| Oracle (a) | JVM2 FileLock.tryLock timeout → CLOSED(UNAVAILABLE) |
| Oracle (b) | JVM2 on retry finds COMPLETE → RECOVERED |
| Oracle (c) | JVM2 waits grace → acquires lock → re-enters at correct lifecycle state |
| Evidence | JVM2 lock acquisition log + JVM1 final state |
| Closed code | 0 或 4 |

**EPT-CN02 — Same-coordinate mutation/nonce/pin/producer-digest fixed conflicts**

| 字段 | 值 |
|---|---|
| Fixed subcases | (a) stableRequestId same + boundedChildDigest different (b) stableRequestId same + publicationNonce different (c) stableRequestId same + declarationFingerprint different |
| Trigger | execute() with each mutation |
| Oracle (a-c) | `CONFLICT` at each |
| Evidence | verdict + conflict reason |
| Closed code | 3（CLOSED CONFLICT）|

**EPT-CN03 — Interrupt while waiting bounded, no mutation**

| 字段 | 值 |
|---|---|
| Precondition | Lock acquisition in progress |
| Trigger | `InterruptedException` during FileLock.tryLock |
| Oracle | `UNAVAILABLE_INTERRUPTED`；no on-disk state changed |
| Evidence | on-disk state unchanged from pre-trigger |
| Closed code | 4（CLOSED UNAVAILABLE）|

### I.5 Process（2 格）

**EPT-PR01 — Normal exit0 only can COMMITTED; timeout → TERM/KILL → all CLOSED+ABORTED without B0**

| 字段 | 值 |
|---|---|
| Fixed subcases | (a) child exits 0 within timeout → COMMITTED (b) child exceeds timeout → SIGTERM → exits within 5s → CLOSED(ABORTED) (c) child exceeds 5s → SIGKILL → CLOSED(ABORTED); no B0 (d) process tree not quiescent → CLOSED(UNAVAILABLE) |
| Trigger | child lifecycle events |
| Oracle (a) | normal commit; exit 0 |
| Oracle (b)(c) | timeout 后 TERM/KILL 一律 CLOSED(ABORTED) 且无 B0 |
| Oracle (d) | 无法证明 process tree quiescent → CLOSED(UNAVAILABLE) |
| Evidence | child exit log + SIGTERM/SIGKILL record + B0 existence check |
| Closed code | 0（COMMITTED）或 6（CLOSED ABORTED）或 4（CLOSED UNAVAILABLE）|

**EPT-PR02 — stdout/stderr exactly 65536 accepted, 65537 closed, no truncated output accepted as evidence**

| 字段 | 值 |
|---|---|
| Fixed subcases | (a) stdout = 65536 exact (b) stderr = 65536 exact (c) stdout = 65537 (d) stderr = 65537 |
| Trigger | bounded child stdout/stderr at each size |
| Oracle (a)(b) | `BLOCKED_CHILD_OUTPUT_OVERFLOW` NOT triggered；transcript complete |
| Oracle (c)(d) | `BLOCKED_CHILD_OUTPUT_OVERFLOW` triggered；CLOSED(BLOCKED)；partial digest not in localCommitId |
| Evidence | bounded-transcript.json overflow flag + digest |
| Closed code | 0（65536）或 5（65537）|

### I.6 Store（2 格）

**EPT-ST01 — Injected StorePublisher/Authority missing or capability incompatible = BLOCKED**

| 字段 | 值 |
|---|---|
| Fixed subcases | (a) StorePublisher missing (b) required Authority missing (c) capability incompatible (d) Store returns 503 (e) Store timeout |
| Oracle (a)(b)(c) | `BLOCKED`；不写任何 adapter URI |
| Oracle (d)(e) | `UNAVAILABLE_STORE` 或 `UNAVAILABLE_STORE_TIMEOUT`；B0 preserved |
| Evidence | block/unavailable reason in verdict；无 adapter URI 写入 |
| Closed code | 5（CLOSED BLOCKED）或 4（CLOSED UNAVAILABLE）|

**EPT-ST02 — Ambiguous Store accept: exact retry yields same canonical receipt; mismatch invalid/conflict; B0 unchanged**

| 字段 | 值 |
|---|---|
| Fixed subcases | (a) exact retry same canonical receipt (b) receipt content same canonical but different raw bytes (c) receipt content mismatch |
| Oracle (a) | `COMMITTED`；B0 unchanged |
| Oracle (b) | `COMMITTED`；canonical(storeReceiptBytes) equal |
| Oracle (c) | `CONFLICT_STORE_RECEIPT` |
| Evidence | canonical(storeReceiptBytes) comparison log |
| Closed code | 0 或 3（CLOSED CONFLICT）|

### I.7 Verify（1 格）

**EPT-VR01 — Verify zero-write on readonly clone using before/after byte+metadata recursive manifest**

| 字段 | 值 |
|---|---|
| Fixed subcases | (a) verify on read-only clone (b) verify on no-write-permission directory |
| Trigger | `verify(Path, ExpectedPins)` |
| Oracle (a)(b) | No file created, modified, or deleted during verify；before/after manifest 由外部 test harness 放在 target 外；verify 目标 0 写 |
| Evidence | before-manifest.json bytes == after-manifest.json bytes |
| Closed code | 0（VERIFY_SUCCESS）或 2（CLOSED INVALID）|

### I.8 汇总验证

```
Total: 5 + 6 + 8 + 3 + 2 + 2 + 1 = 27 ✓
Each obligation has ≥1 fixed subcase
Each subcase has Precondition / Trigger / Oracle / Evidence / Closed code
Skip any subcase → obligation FAIL
```

**27 Obligation IDs（全部 unique）**：

```
EPT-H01, EPT-H02, EPT-H03, EPT-H04, EPT-H05,
EPT-FS01, EPT-FS02, EPT-FS03, EPT-FS04, EPT-FS05, EPT-FS06,
EPT-CP01, EPT-CP02, EPT-CP03, EPT-CP04, EPT-CP05, EPT-CP06, EPT-CP07, EPT-CP08,
EPT-CN01, EPT-CN02, EPT-CN03,
EPT-PR01, EPT-PR02,
EPT-ST01, EPT-ST02,
EPT-VR01
```

---

## J. Public API：Request 和 ExpectedPins

### J.1 `EvidencePublicationRequest` 字段表

| 字段 | Required | Type | Source | Identity | Validation |
|---|---|---|---|---|---|
| `stableRequestId` | 是 | String（SHA256 hex）| Caller 提供；模块重算验证 | 事务唯一标识（不含 nonce）| 64 hex chars；SHA256(EPT_DOMAIN || authorityInputTreeFingerprint || targetInputTreeFingerprint || planFingerprint || targetBindingFingerprint || declarationFingerprint || candidateFingerprint)；caller expected mismatch = INVALID |
| `publicationNonce` | 是 | String（SHA256 hex）| External deployment 提供 | 防重放 nonce | 64 hex chars |
| `authorityInputTreeFingerprint` | 是 | String（SHA256 hex）| Caller 提供 | Authority 输入树摘要 | 64 hex chars；与 targetInputTreeFingerprint 不同 |
| `targetInputTreeFingerprint` | 是 | String（SHA256 hex）| Caller 提供 | Target 输入树摘要 | 64 hex chars |
| `planFingerprint` | 是 | String（SHA256 hex）| Caller 提供 | Plan 摘要 | 64 hex chars |
| `targetBindingFingerprint` | 是 | String（SHA256 hex）| Caller 提供 | Target binding 摘要 | 64 hex chars |
| `declarationFingerprint` | 是 | String（SHA256 hex）| Caller 提供；参与 declaration pin | 声明摘要 | 64 hex chars |
| `candidateFingerprint` | 是 | String（SHA256 hex）| Caller 提供；参与 launch pin | Candidate artifact 摘要 | 64 hex chars |
| `evidenceRoot` | 是 | URI | Caller 提供 | evidence root URI | 有效 URI |
| `privateParent` | 是 | URI | Caller 提供 | private parent URI | 有效 URI |

**不在 Request 中的字段**（由 constructor/injected authorities 提供）：
- JVM command、classpath、storeAdapter URL、recover flag、checkMode
- `boundedChildTimeoutMillis`（来自 frozen LaunchProfile）

### J.2 `ExpectedPins` 字段表

（同 §D.3）

### J.3 `Verdict` 字段表（返回值）

| 字段 | Required | Type | Source | Identity | Validation |
|---|---|---|---|---|---|
| `outcome` | 是 | Enum | EPT 计算 | Public outcome | COMMITTED \| RECOVERED \| CLOSED |
| `closedCategory` | 否 | Enum | EPT 计算 | ClosedCategory | 仅当 outcome=CLOSED 时：INVALID \| CONFLICT \| BLOCKED \| UNAVAILABLE \| ABORTED \| INTERNAL |
| `exitCode` | 是 | int | EPT 计算 | CLI exit code | 0 \| 2 \| 3 \| 4 \| 5 \| 6 |
| `localCommitId` | 是 | String（SHA256 hex）| EPT 计算 | Local commit 摘要 | COMMITTED 或 RECOVERED 时必须存在 |
| `b0RawFingerprint` | 是 | String（SHA256 hex）| EPT 计算 | B0 raw 摘要 | COMMITTED 或 RECOVERED 时必须存在 |
| `b1ReceiptFingerprint` | 是 | String（SHA256 hex）| EPT 计算 | B1 Store immutable receipt 摘要 | COMMITTED 或 RECOVERED 时必须存在 |
| `r1Fingerprint` | 是 | String（SHA256 hex）| EPT 计算 | R1 final outer commitment（B0+B1+transaction/owner）摘要 | COMMITTED 或 RECOVERED 时必须存在 |
| `closedReasonCode` | 否 | String | EPT 计算 | 稳定失败代码 | 仅当 outcome=CLOSED 时；不含 credentials |

**注**：RECOVERED 成功也必须返回完整 fingerprints。failureReason 改为 stable closedReasonCode。**ExpectedPins B0-only recovery 有内部方法，不是 public 弱校验**。

## K. 文档冲突台账

### K.1 Transaction Identity Domain

| 文档 | 状态 |
|---|---|
| **ENGINE-DESIGN §494**：transactionId domain 未指定 | **已同步**：technical design §1.5/§15.1 已引用 EPT_DOMAIN = 'resource-gateway.capability-studio.evidence-publication-transaction.v1'" |
| FormalInputTreeSnapshotter.java:74 | 冻结（legacy）|
| ExecutionLeaseEvidenceCli.java:77 | 冻结（legacy）|
| **本文 §C.1** | 冻结；R1 = final outer commitment，B1 = Store immutable receipt |

### K.2 分母隔离

| 来源 | 分母 | 增加 formalPassCount？ |
|---|---|---|
| S0 fixture-generator-transaction-design-v1.md §11 | 26 | 否 |
| ENGINE-DESIGN §1058 Stage 1 | 27 | **是** |
| ENGINE-DESIGN FELT | 14 | 否 |
| **RG-CS-EPT-v1（本文 §I）** | **27** | **否** |

### K.3 BLOCKED vs UNAVAILABLE 分类（ENGINE-DESIGN §473 同步）

| 场景 | 分类 | 理由 |
|---|---|---|
| Missing required StorePublisher | BLOCKED | 能力配置缺失 |
| Missing required Authority | BLOCKED | 能力配置缺失 |
| Capability incompatible | BLOCKED | 能力配置缺失 |
| Store 503 | UNAVAILABLE | resource unavailable |
| Store timeout | UNAVAILABLE | resource unavailable |
| Receipt content mismatch | CONFLICT | idempotency violation |
| Receipt schema invalid | INVALID | protocol violation |

### K.4 Recovery Grace Period

`RECOVERY_GRACE_PERIOD_SECONDS = 1800`（30 分钟）
Grace period 到期 **不自动删除/过期/回收**任何对象；仅使 stale owner 成为 takeover eligibility 候选。Takeover 必须满足：fencing authority 确认 + lock acquisition + owner identity check（三者缺一不可）。

---
### K.5 EXTERNAL_PENDING 分段恢复（RG-CS-EPT-v1 约束）

**EXTERNAL_PENDING 进入条件**：B0 durable；B1/R1 absent；Store publish in-flight。

**Store recoverable exception** → `CLOSED(UNAVAILABLE)`；recoverable = Store 返回 5xx / timeout / connection failure。

**Store non-recoverable exception** → `CLOSED(INVALID)`；non-recoverable = receipt schema invalid / canonical bytes mismatch / idempotency violation。

**EXTERNAL_PENDING 分段恢复触发**：调用方以 `EXTERNAL_PENDING` 状态重新调用 `execute()`。

**B1 absent 时的恢复序列**：
1. Store query（同 idempotency key）
   - receipt present → local B1 写入 → 进入 R1 absent 处理
   - absent → Store publish（B1）
2. B1 publish 成功后 R1 absent 处理

**B1 present / R1 absent 时的恢复序列**：
- query：B1 已存在，跳过 query 和 publish
- issue：StorePublisher.issue(R1)
- R1 成功后 metadata.lifecycleState → COMPLETE

**R1 absent / B1 present** 不会触发 Store idempotency key 重新 publish，只 issue R1。



## L. Definition of Ready 和 Definition of Done

### L.1 Definition of Ready（全部可由本文关闭）

| ID | 条件 | 状态 |
|---|---|---|
| R-01 | EPT_DOMAIN 在本文冻结；technical design §1.5/§15.1 已引用 | **已关闭**：§C.1 + technical design §1.5 |
| R-02 | Recovery grace = 1800s + takeover semantics | **已关闭**：§K.4 |
| R-03 | EPT 27 vs Stage 27 vs FELT 14 vs S0 26 分母隔离 | **已关闭**：§K.2 |
| R-04 | CP-03（child-start durable → ABORTED, no re-run）| **已关闭**：§I.3 CP-03 |
| R-05 | overflow → BLOCKED；partial digest not in localCommitId | **已关闭**：§F.4 |
| R-06 | ExpectedPins record 字段精确 | **已关闭**：§D.3 |
| R-07 | destroyForcibly() JVM-level SIGKILL 路径 | **已关闭**：§F.4 |
| R-08 | BLOCKED/UNAVAILABLE/INVALID/CONFLICT 与 ENGINE-DESIGN §473 同步 | **已关闭**：§K.3 |
| R-09 | FencingAuthority token 是 authority-authenticated opaque receipt | **已关闭**：§D.2 |
| R-10 | ExpectedPins 含 authority fingerprint/epoch | **已关闭**：§D.3 |
| R-11 | Public Outcome 仅 COMMITTED\|RECOVERED\|CLOSED | **已关闭**：§G.1 |
| R-12 | Lifecycle 可含 ABORTED 但非 public outcome | **已关闭**：§G.1 |
| R-13 | boundedChildTimeoutMillis 不在 public Request | **已关闭**：§J.1 |
| R-14 | candidateFingerprint 新增且参与 launch pin | **已关闭**：§D.4 |
| R-15 | stableRequestId 包含 candidateFingerprint | **已关闭**：§E.2 |
| R-16 | authority/target input tree 分离 | **已关闭**：§E.1 |
| R-17 | caller expected mismatch = INVALID | **已关闭**：§E.1 |
| R-18 | ST01 不写 adapter URI | **已关闭**：§I.6 ST01 |
| R-19 | VR01 verify 目标 0 写 | **已关闭**：§I.7 VR01 |
| R-20 | before/after manifest 放在 target 外 | **已关闭**：§I.7 VR01 |

**剩余外部未决（不阻塞 Slice 开工）**：
- Evidence Store endpoint URL（部署配置）
- FencingAuthority 实现（可 mock）

### L.2 Definition of Done

| ID | 条件 |
|---|---|
| D-01 | 27/27 格全部 PASS（含所有 fixed subcases）|
| D-02 | 27 个 obligation ID 全部有 CI 输出 |
| D-03 | Legacy differential：TranscriptPublication vs EPT behavior diff 输出 |
| D-04 | strict JSON schema parser 验证所有 §J schemas（DoD：Slice E0 才生成 strict schemas）|
| D-05 | ordinary JAR 和 shaded JAR 各有独立 CI 路径 |
| D-06 | P0/P1 independent review 完成（review 0）|
| D-07 | CLI exit code 0/2/3/4/5/6 映射验证 |
| D-08 | verify zero-write：before/after recursive byte+metadata manifest 在只读副本上相等 |
| D-09 | privateParent mode=0700 enforcement |
| D-10 | destroyForcibly() orphan reaper ≤ 10s latency |
| D-11 | create-new protocol：目标已存在 → CLOSED(CONFLICT) |
| D-12 | Stream overflow 65537 → CLOSED(BLOCKED)；partial digest not in localCommitId |
| D-13 | FencingAuthority token 含 authority fingerprint/epoch |
| D-14 | ExpectedPins verification 验证 authority fingerprint/epoch |
### K.5 EXTERNAL_PENDING 分段恢复（RG-CS-EPT-v1 约束）

**EXTERNAL_PENDING 进入条件**：B0 durable；B1/R1 absent；Store publish in-flight。

**EXTERNAL_PENDING 分段恢复触发**：调用方以 `EXTERNAL_PENDING` 状态重新调用 `execute()`。

**B1 absent 时的恢复序列**：
1. Store query（同 idempotency key）
   - receipt present → 跳过 publish，进入 R1 absent 处理
   - absent → Store publish（B1）
2. B1 publish 成功后进入 R1 absent 处理

**B1 present / R1 absent 时的恢复序列**：
- query：B1 已存在，跳过 query 和 publish
- issue：StorePublisher.issue(R1)
- R1 成功后 lifecycleState → COMPLETE

**R1 absent / B1 present** 不会触发 Store idempotency key 重新 publish，只 issue R1。

**Store exception 映射**：
- Store recoverable（5xx / timeout / connection failure）→ `CLOSED(UNAVAILABLE)`
- Store non-recoverable（receipt schema invalid / canonical bytes mismatch / idempotency violation）→ `CLOSED(INVALID)`
