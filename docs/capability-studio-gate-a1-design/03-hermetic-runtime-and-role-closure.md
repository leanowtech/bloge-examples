# Hermetic Runtime and Role Closure

> 文档编号：A1-DESIGN-03 | 阶段：A1 Hermetic Execution Gate
>
> 权限边界：本文规定 Gate A1 发布级运行时的强制闭包语义；dev runner 报告 NON_RELEASE，不授予发布角色合同。

---

## 0. 规范性引用

本文档规范性引用 [00-normative-conventions.md](00-normative-conventions.md)。Observer 产生的 Observation Receipt 中使用的 FP 构造遵循 00 规范。

---

## 1. Hermetic Observer 职责边界

Hermetic Observer（以下简称 Observer）是 A1 运行时的唯一观察节点，职责严格限定为：

1. **观察**：采集 process-facts、input-tree、sandbox-authority、output-tree、duration、exit-code、stdout/stderr 的 ContentDigest（SHA256(raw bytes)，不含元数据，见 00 §2.2）；
2. **不判定**：Observation Receipt 不包含 decision、pass/fail 结论或 Policy Judgment；
3. **报告**：输出纯事实 `HermeticObservation` artifact；通用 `ObservationReceipt.artifactRef` 引用该 artifact，并由 02 规范的 Decision Reducer 独立判定。

`HermeticObservation` 不包含、也不得复用 `observationReceiptFP`。`rg.gatea.observation-receipt.v1` 只用于 02 定义的通用 Observation Receipt envelope，避免同一 TypedFP domain 承载两套 payload 语义。

**dev PGID 强制 NON_RELEASE**：无独立 cgroup/job boundary、无外部 sandbox authority 的每次执行必须标记 `executionMode: NON_RELEASE`，Observer 不得将其升级为发布级 evidence。

**发布级外部依赖**：A1 发布运行时必须依赖 cgroup v2 hierarchy、OS-level job scheduler（Linux cgroup 或等效）和 sandbox isolation layer（seccomp/Landlock/namespaced mount）。任一不可用时写入 `sandboxAuthority: UNAVAILABLE` 并以 `NON_RELEASE` 标记执行。

---

## 2. Input Snapshot 的封存树语义

### 2.1 路径无关快照（原子步骤）

1. 对每个输入路径调用 `openat(AT_FDCWD, path, O_RDONLY | O_NOFOLLOW)` 获取源 fd；
2. 对源 fd 调用 `fstat()` 获取 inode、size、mtime、mode，**不依赖原路径属性**；
3. 从源 fd 读取全部 bytes，写入 sandbox-owned `create-new` 目标文件（mode 0555）；
4. 生成 canonical path：`/input-root/{treeFP}/{exact-set-index}/{relative-path-from-source-root}`；其中 `treeFP` = SHA-256（source-root 完整树内容哈希，不含路径前缀），`exact-set-index` = 文件在 exact-set 中的序号（从 1 开始）；
5. 生成 `exact-set.json`（每个路径的 canonical path、size、SHA-256）和 `snapshot-manifest.json`（treeFP、exact-set 哈希、seal-timestamp）；
6. 执行 `chattr +i`（immutable）或等效只读绑定挂载；
7. 关闭源 fd；snapshot FD 不暴露给被观察进程。

### 2.2 违反处理

| 违反条件 | 处理 |
|---|---|
| `O_NOFOLLOW` 遇到 symlink | `NON_RELEASE`；`inputSnapshotError: SYMLINK_REJECTED` |
| 无法执行 namespace 隔离 mount | `NON_RELEASE`；`sandboxAuthority: INSUFFICIENT` |
| 无法撤销写能力 | `NON_RELEASE`；`sandboxAuthority: CAPABILITY_INSUFFICIENT` |
| 快照文件数与 exact-set 不匹配 | `NON_RELEASE`；`snapshotManifestVerification: FAILED` |
| 进程仍持有原路径引用 | `NON_RELEASE`；`pathLeakDetected: true` |

进程启动后，sandbox 内核强制封存树只读绑定（read-only mount）；进程只能看到封存树；禁止原路径继承、禁止源 fd 泄漏；进程无法访问原路径、临时文件或构建产物。

---

## 3. Publication Material Root 的 inode 策略

**create-new owned inode**：每次运行的输出 material 必须从 `openat(..., O_CREAT | O_EXCL | O_WRONLY, 0o444)` 创建；退出前撤销父目录写权限（`fchmodat` 或等效）。

**symlink 拒绝**：所有文件写入前必须 `O_NOFOLLOW` + `lstat()` 预检；检测到 symlink 即 `NON_RELEASE`；`outputMaterialError: SYMLINK_REJECTED`；禁止 `symlink()` 系统调用。

**hardlink 策略**由 capability profile 明确声明（unsupported 值一律 fail-closed，`NON_RELEASE`；`hardlinkPolicy: UNSUPPORTED_PROFILE`）：

| Profile | 行为 |
|---|---|
| `DENY_ALL` | 任何 hardlink 操作均拒绝；`NON_RELEASE` |
| `DENY_EXTERNAL` | 拒绝指向 snapshot 或系统关键路径的 hardlink；允许同一 output tree 内 hardlink（需 manifest 声明） |
| `ALLOW_WITH_MANIFEST` | 每条 hardlink 必须在 manifest 声明 source inode、target path、link count，manifest 哈希绑定 material root |

---

## 4. Executable / Classpath / JAR 的封存树启动

**单一来源原则**：所有可执行文件（JAR、classpath、native binary、shell script）及其运行时依赖（`java.home`、`java.class.path`、`sun.boot.class.path` 等）必须来自同一封存树；禁止从封存树外部加载任何资源；运行时必须验证全部解析至封存树内。

**CodeSource 三层绑定**：① pre-binding：加载前记录 JAR 原始字节流 SHA-256（ContentDigest，00 §2.2）；② post-binding：JAR 关闭前再次校验 SHA-256；③ manifest 绑定：`Manifest.mf` SHA-256 必须与 `executable-manifest.json` 中的 `jarPreDigest`、`jarPostDigest`、`manifestDigest` 完全一致。

**攻击防御**：symlink 重定向 classpath → `O_NOFOLLOW` + `lstat()` 预检；hardlink 替换 JAR → `fstat()` 运行时回验 inode；TOCTOU 窗口 → fd 持有期间不做二次路径解析，manifest 写入原子化（`fsync` + rename）；运行时篡改防御 → ① 自定义 `ClassLoader` 每次 `defineClass` 前重算字节流哈希（辅助层）；② CodeSource 实际加载的 class bytes 必须与 manifest 中 `classBytesDigest` 一致。

---

## 5. Runtime Artifact 治理

**stdout/stderr 治理**：stdout/stderr 原文不进入 Receipt；原始 bytes 的治理（截断上限 64KB）、脱敏（防御性兜底）由 harness 负责；Receipt 仅保存摘要（`stdoutHash`、`stderrHash`、`stdoutLength`、`stderrLength`）和受控诊断字段。

**log 文件防护**：sandbox 内禁止创建 `*.log` 文件，违反则 `NON_RELEASE`；`logFileDetected: true`。

**secret 防护**：运行时禁止输出含敏感前缀（`AWS_SECRET_KEY`、`API_KEY`、`TOKEN`、`PASSWORD` 等）的环境变量内容到 stdout/stderr；正则过滤仅为防御性兜底。

---

## 6. Process Lifecycle 与 Failure 语义

### 6.1 Process Lifecycle

```
start -> running -> [exit | signal | timeout]
```

**成功退出**：exit code == 0，无残留 FD/cgroup → `RELEASE`。

**部分失败**：全部后代 reap，记录最严重 exit code，`partialFailure: true` → `NON_RELEASE`。

### 6.2 Observer Failure 的 producerOwner 规范

当 Observer 自身发生异常（崩溃、OOM、内部错误）时：

| 字段 | 值 |
|------|-----|
| `producerOwner` | caller-owned ObserverAuthority（来自 catalog §1 visibility） |
| `visibility` | 由 evidence catalog 中对应 EvidenceCatalogEntry 的 visibilityPolicyRef 决定 |

**producerOwner 确定规则**：
1. Observer 从 catalog 加载自身 ObserverAuthority 条目
2. 该条目 `producerOwner` 即为 caller-owned PrincipalId
3. failure Observation 的 producerOwner 必须等于该值

**visibility 确定规则**：
1. 从 catalog 加载对应 EvidenceCatalogEntry
2. 按 visibilityPolicyRef 指向的策略文档，结合 callerTenantId + role projection 裁剪
3. 默认 DENY，不得泄露 exists 侧信道

### 6.3 Failure Receipt 结构

```
ObserverFailureReceipt {
  observerFailureFP:      WireDigest<TypedFP>  // TypedFP('rg.gatea.observer-failure.v1', failurePayload)，不含 FP 自身
  observerId:             PrincipalId
  producerOwner:          PrincipalId     // caller-owned ObserverAuthority
  failureReason:          FailureReason
  observedAt:             Instant
  status:                 "OBSERVER_FAILURE"
  visibility:             Visibility
}
```

---

## 7. Capability Probe 与错误码

启动角色进程前，Observer 执行 capability probe：`mountNamespace`、`cgroupV2`、`userNamespace`、`seccomp`、`landlock`、`immutableAttr` 均返回 `supported | unsupported`；probe 结果及 `probeTimestamp`、`probeKernelVersion` 写入 `HermeticObservation` artifact，通用 Observation Receipt 仅通过 `artifactRef` 引用该事实构件；禁止将 probe 结果注入被测进程 environment。任一 (`mountNamespace` 或 `cgroupV2`) 为 `unsupported`，运行时必须 `NON_RELEASE`。

**错误码表**（全部 NON_RELEASE，observer internal failure 标记 UNRELIABLE）：

| 错误码 | 含义 | 错误码 | 含义 |
|---|---|---|---|
| `A1_Hermetic_001` | snapshot symlink rejected | `A1_Hermetic_011` | codeSource post-digest mismatch |
| `A1_Hermetic_002` | namespace isolation unavailable | `A1_Hermetic_012` | codeSource inode mismatch |
| `A1_Hermetic_003` | capability insufficient | `A1_Hermetic_013` | TOCTOU detected |
| `A1_Hermetic_004` | hardlink policy unsupported profile | `A1_Hermetic_014` | spi materialization non-unique |
| `A1_Hermetic_005` | hardlink denied by profile | `A1_Hermetic_015` | spi materialization missing |
| `A1_Hermetic_006` | output symlink rejected | `A1_Hermetic_016` | log file detected |
| `A1_Hermetic_007` | escape detected | `A1_Hermetic_017` | secret in stdout/stderr |
| `A1_Hermetic_008` | residue detected | `A1_Hermetic_018` | snapshot manifest verification failed |
| `A1_Hermetic_009` | quiescence unprovable | `A1_Hermetic_019` | path leak detected |
| `A1_Hermetic_010` | detached child escaped | `A1_Hermetic_020` | observer internal failure |

---

## 8. 正向验收与攻击验收矩阵

**正向验收**：正常执行（输入/输出在封存树内，JAR 来自同一封存树）→ `RELEASE`；子进程正常退出，无残留 FD/cgroup → `RELEASE`；Provider SPI 恰好一个实例 → `spiMaterializationNonUnique: false`（符合发布条件）；timeout 触发并 reap 全部后代 → `terminationReason: TIMEOUT`，`NON_RELEASE`；output manifest treeFP 与 snapshot treeFP 不一致 → `NON_RELEASE`。

**攻击验收**：symlink 指向 `/etc/passwd` 并通过 snapshot → `SYMLINK_REJECTED`；hardlink 输出指向 snapshot JAR → manifest 验证失败；子进程 `setsid` 脱离 PGID → `escapeDetected: true`；关闭 pipe 后 detached child 继续写文件 → `detachedChildEscaped: true`；`LD_PRELOAD` 注入 so → sandbox authority 禁止 capability，`NON_RELEASE`；JAR 加载后被 inotify 监控路径替换 → `codeSource post-digest mismatch`；通过 `/proc/self/fd` 枚举 snapshot 外路径 → `pathLeakDetected: true`；多个 ServiceLoader 结果 → `spiMaterializationNonUnique: true`；secret 通过环境变量传入 → runtime 清除后仍检测到则 `NON_RELEASE`。

**Observer Failure 验收**：

| 场景 | 预期结果 |
|------|----------|
| Observer 自身崩溃 | 生成 failure Observation，producerOwner = caller-owned ObserverAuthority |
| Observer OOM | producerOwner 正确填充，visibility 按 catalog visibilityPolicyRef 决定 |
| Observer 内部错误 | failure Reason 记录具体错误码，RETRY 允许 |

**验收维度总览**：

| 维度 | 通过条件 | 失败条件 |
|---|---|---|
| 隔离完整性 | namespace + cgroup + capability 全部 present | 任一 absent → NON_RELEASE |
| 输入封存 | snapshot 从 fd 完成，immutable 只读 | symlink / path leak / manifest mismatch → NON_RELEASE |
| 输出安全 | inode create-new，symlink 拒绝，hardlink profile 声明 | symlink / hardlink / inode 复用 → NON_RELEASE |
| 代码来源 | pre/post/manifest 三层哈希一致，inode 一致 | 任意 mismatch → NON_RELEASE |
| 进程追踪 | 全部后代在 registry，quiescence 可证明 | escape / residue / unprovable → NON_RELEASE |
| SPI 唯一性 | ServiceLoader 恰好返回一个实例 | 零个或多个 → NON_RELEASE |
| 泄露防护 | 无 secret stdout/stderr，无 log 文件 | 检测到任一项 → NON_RELEASE |
| Observer 可靠性 | Observer 自身不崩溃，不遗漏后代 | `observerFailure: true` → UNRELIABLE |
| Observer producerOwner | caller-owned ObserverAuthority 正确填充 | producerOwner 与 catalog 不符 → UNRELIABLE |
| Observer visibility | 按 catalog visibilityPolicyRef 正确裁剪 | 未授权访问 → DENY（不泄露侧信道） |

---

## 9. 规范一致性要求

### 9.1 ObserverAuthority Catalog Entry

catalog 中必须包含 Observer 专用的 EvidenceCatalogEntry（来自 Source Package 的 authority 源单元）：

**Schema identity**: urn:studio:schema:observer-failure:v1（01 §10.2 第 13 个 target schema candidate）；
**Schema ownership**：ObserverFailureReceipt payload/envelope 所有权归属本文档 §9.1/§9.2；
**Scope distinction**：hermetic-observation-v1 用于通用 hermetic run observations，与本 schema 严格区分。
```
EvidenceCatalogEntry {
  evidenceId:          "urn:studio:a1:observer-failure"
  schemaRef:           "urn:studio:schema:observer-failure:v1"
  producerOwner:       PrincipalId        // caller-owned ObserverAuthority
  semanticVerifier:    SemanticVerifierSpec {
    verifierId: "builtin:observer-failure"
    inputs: []
    policy: "READ_CATALOG_ONLY"
    outcomeSource: "OBSERVER_GENERATED"
  }
  artifactRelation:    RELATES_TO
  relatedEvidenceRefs: []
  visibilityPolicyRef:  "urn:studio:visibility:observer-failure:v1"
}
```

### 9.2 ObserverFailure Receipt 字段约束

```
ObserverFailureReceipt {
  observerFailureFP:   WireDigest<TypedFP>  // TypedFP('rg.gatea.observer-failure.v1', failurePayload)，不含 FP 自身
  observerId:           PrincipalId
  producerOwner:        PrincipalId     // 必须等于 catalog 中 ObserverAuthority.producerOwner
  failureReason:        FailureReason
  observedAt:           Instant
  status:               "OBSERVER_FAILURE"
  visibility:           Visibility      // 由 visibilityPolicyRef 决定
}
```
