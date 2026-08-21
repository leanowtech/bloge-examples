# Capability Studio Formal Evidence Runner v1

> 状态：`LEGACY_IMPLEMENTATION_MIGRATION_BLOCKED`
>
> 迁移说明：固定执行语义继续有效，实现组织方式由[收敛式验收引擎技术设计](../../resource-gateway-capability-studio-convergent-acceptance-engine-technical-design.md)接管。当前只允许实施 `GATE-A TYPED_REPLAY`；在 Gate A/B/C 依次通过机器门禁前，禁止继续开发 full runner、stateful Lease 编排或外部 Evidence 发布。
>
> 适用范围：`RG-CS-FELT-v1` 开发验收和企业部署接入。
>
> 结论边界：本 Runner 最多生成 `DEVELOPMENT_VERIFIED`。它不能代替企业 Candidate Authority、Environment Authority、Evidence Store、KMS/HSM 或 Owner Authority，也不能增加 `formalPassCount=0/27`。

## 1. 目的

Formal Evidence Runner 将已有的 formal-v2 组件编排为一个固定、失败关闭、可离线复验的执行事务。Runner 解决以下问题：

1. 在 Java 启动前固定 Candidate、Input 和 Environment 三类材料。
2. 从已认证的 Authority Bundle 和 Target Admission Bundle 创建不可变输入快照。
3. 在同一候选、同一输入和同一环境上运行 Provider Conformance 与 full-evidence Stage Acceptance。
4. 保存 BEFORE/AFTER 状态观察、Lease receipt、transition witness、transcript 和双层 durable wrapper。
5. 在执行后重新验证候选、输入快照和环境没有发生未声明变化。
6. 将 `FELT-01..14` 的原始证据收敛为 strict Evidence manifest，供独立 Reviewer 只读复算。

Runner 不创建业务正确性结论，不签发外部 Authority 事实，也不把本地文件复制冒充外部 Evidence Store 收据。

## 2. 唯一执行拓扑

执行顺序固定如下。实现不得跳步、交换顺序或在失败后继续创建新证据。

```text
shell preflight
  -> candidate snapshot
  -> Authority input-tree snapshot
  -> Target input-tree snapshot
  -> read-only input-tree verification
  -> Provider Conformance v2
  -> full-evidence Stage Acceptance
       -> existing-only BEFORE
       -> atomic Lease commit or exact recovery
       -> persisted transition witness
       -> exact AFTER
       -> inner commit manifest
       -> outer final commitment
       -> final transcript publication
  -> independent durable-wrapper verification
  -> read-only input-tree postflight
  -> candidate/environment postflight
  -> strict FELT manifest verification
  -> external Evidence Store publication and receipt verification
  -> payload-free terminal result
```

在 durable wrapper 验证之前，不得发布成功结论。在外部 Evidence Store 收据和真实 Owner 签署缺失时，终态只能是 `DEVELOPMENT_VERIFIED` 或未完成状态，不能输出 `FORMALLY_ACCEPTED`、`PRODUCTION_VERIFIED` 或等价文案。

## 3. 固定输入

### 3.1 Candidate

Candidate 输入必须在首次 Java 启动前固定：

| 输入 | 固定方式 | 失败条件 |
|---|---|---|
| shaded Test Kit JAR | 普通文件、非 symlink、raw SHA-256 | 缺失、不可读、摘要漂移 |
| Provider classpath | 有序普通文件列表和逐项 raw SHA-256 | 空项、重复项、顺序或摘要漂移 |
| Stage Result v2 | 普通文件、大小上限和 raw SHA-256 | 超限、非普通文件、摘要漂移 |
| source commit | Candidate Attestation v1 | 缺失、格式非法、与候选不一致 |
| source tree status | 固定为 `CLEAN` | `DIRTY` 或无法证明 |
| artifact identity | Candidate Attestation v1 与 JAR 摘要闭包 | 只提供名称或可变标签 |

Runner 必须把实际执行文件复制到 run-scoped private snapshot。所有子进程只能读取 snapshot，不能读取原始候选路径。

### 3.2 Input

Input 由以下不可混用的坐标组成：

| 输入 | 必需 pin |
|---|---|
| Authority Bundle | semantic fingerprint、tree fingerprint、publication fingerprint、transaction nonce/identity |
| Target Admission Bundle | semantic fingerprint、tree fingerprint、publication fingerprint、transaction nonce/identity |
| Stage Result | raw SHA-256 |
| formal Provider | inner Authority material fingerprint 和 formal outer fingerprint |
| deployment state | immutable store descriptor canonical/raw fingerprint |
| evidence publication | publication fingerprint 和单事务 publication parent |

Authority 与 Target 必须分别生成 `Formal Input Tree v1` snapshot。Provider JVM 只能挂载 snapshot 中的 `bundle-root`，不能继续读取 source root。State root 是唯一允许变化的输入；其变化只能通过 BEFORE/AFTER observation、transition witness 和 Lease receipt 表达。

### 3.3 Environment

Environment 输入至少固定：

- Java 可执行文件的真实路径与 raw SHA-256；
- Java runtime identity；
- OS 与 filesystem capability descriptor；
- Provider JVM properties 的允许集合和规范投影；
- environment descriptor raw SHA-256；
- network/egress policy Evidence coordinate；
- Runner 版本和脚本 raw SHA-256。

环境描述只记录 payload-free 坐标。凭证、私钥、业务 Payload、完整环境变量和值不得进入 manifest 或终端输出。

## 4. 文件系统边界

### 4.1 Run root

每次新事务使用一个全新、绝对、规范化的 private run root。Run root 必须满足：

- 父目录不可被非 Owner 写入；
- run root 初始不存在；
- 创建后权限为 `0700`；
- 不接受 symlink、hardlink、未知 sibling 或预存文件；
- 不覆盖、清理或接管无法认证的既有对象；
- 所有正式输出使用 create-new 和 durability barrier。

Exact retry 只能复用同一 transaction identity、同一 publication pin 和同一 run root。新的业务事务必须使用新的 run root 和 publication parent。

### 4.2 Snapshot 与 state 分离

以下目录必须是不同的物理对象，不能互相包含：

- Authority input snapshot；
- Target input snapshot；
- mutable state root；
- execution-lease evidence publication parent；
- FELT evidence bundle root。

Runner 必须在首次写入 journal 或 Lease 前检查目录身份。无法取得稳定 identity 时返回 `BLOCKED`，不能退化为字符串路径比较。

## 5. 执行阶段

### 5.1 Preflight

Shell preflight 在任何 Java 进程启动前完成：

1. 校验参数唯一性、必填 pin、格式和容量边界。
2. 校验 Test Kit、Provider、Stage Result、descriptor 和环境描述文件。
3. 校验所有输出路径尚不存在。
4. 记录候选文件 identity、大小、模式和 raw SHA-256。
5. 创建 private temporary root，并复制候选文件。
6. 重新计算 snapshot 摘要，与 out-of-band pin 比较。

任一步失败时，Runner 不得发现 Provider，不得创建 input-tree publication，不得创建 Lease、journal 或 final commitment。

### 5.2 Formal Input Tree

Runner 对两棵 source tree 分别执行：

1. `declare`：计算 semantic/tree 坐标，不写目标目录。
2. `snapshot`：使用独立 publication pin 和 nonce 创建 durable wrapper。
3. `verify`：对 committed wrapper 做纯只读离线复验。
4. 挂载：仅把 `<snapshot>/bundle-root` 传给 Provider。

Postflight 再次执行只读 `verify`，并重新声明 source tree。Source tree、snapshot 或 pin 发生变化时，本次运行失败。Runner 不得用第二次 snapshot 写入掩盖漂移。

### 5.3 Provider Conformance

Conformance 使用与 full-evidence Stage Acceptance 完全相同的 Test Kit snapshot、Provider classpath snapshot、Stage Result snapshot、Authority input snapshot 和 JVM 环境投影。

Conformance 必须先完成。本阶段不得调用普通 `CapabilityStudioStageAcceptanceCli`，因为普通 CLI 会提交 Lease，却不会建立完整 full-evidence publication。Runner 直接运行 Provider Conformance v2，再运行 `CapabilityStudioExecutionLeaseEvidenceCli`。

### 5.4 Full-evidence Stage Acceptance

Full-evidence CLI 必须使用：

- 固定 Stage Result snapshot；
- 固定 formal outer pin；
- 固定 evidence publication pin；
- Authority input snapshot 的 `bundle-root`；
- Target input snapshot 的 `bundle-root`；
- 已初始化且 descriptor 已独立固定的 state root；
- 全新的 transcript publication path，或同一事务的 exact retry path。

成功事务必须同时存在：

- verified BEFORE observation；
- verified AFTER observation；
- execution lease request；
- `COMMITTED` 或 `RECOVERED` receipt；
- persisted transition witness；
- retained committed transcript source；
- strict inner commit manifest；
- outer `final-commit-v1.json`；
- final transcript。

只出现普通 `ACCEPTED` stdout、单独 transcript 或单独 manifest 都不构成成功。

### 5.5 Independent verification

Runner 必须启动不含 Provider 的独立只读 JVM，执行以下验证：

1. 验证 Authority input snapshot。
2. 验证 Target input snapshot。
3. 验证 execution-lease transcript 语义。
4. 验证完整 durable wrapper。
5. 验证 strict FELT manifest 和精确 inventory。

独立 verifier 不得获得 state 写权限、Provider classpath、签名私钥或 source root 写权限。

## 6. 结果状态

Runner 只使用以下终态：

| 状态 | 含义 | 退出码 |
|---|---|---:|
| `DEVELOPMENT_VERIFIED` | `FELT-01..14` 全部通过，P0/P1 为 0；仍缺正式外部 Stage 证据 | `0` |
| `INCOMPLETE` | 存在 `NOT_RUN`，或外部 Evidence/Owner 尚未提供 | `3` |
| `BLOCKED` | 依赖、权限、锁、I/O、metadata、Provider 或 Evidence Store 不可用 | `3` |
| `REJECTED` | 已执行的治理或 Authority 判断拒绝 | `3` |
| `INVALID` | 协议、pin、结构、identity、闭包或 canonical bytes 非法 | `2` |
| `FAIL` | 已执行 obligation 违反机器 Oracle 或不变量 | `2` |

退出码 `0` 只能表示开发合同通过，不表示 Stage 0 正式通过。正式 Stage 结果继续由现有 `Stage Acceptance Result v2`、外部 Authority 和 27 条 Stage-exit contract 裁决。

## 7. FELT manifest

Strict FELT manifest 固定以下规则：

- `contractId` 必须是 `RG-CS-FELT-v1`；
- obligation 必须按 `FELT-01..14` 精确排序；
- 状态只能是 `PASS`、`FAIL`、`BLOCKED`、`NOT_RUN`；
- 不存在 `SKIPPED`；
- 每个 `PASS` obligation 至少引用一项 Evidence；
- inventory 必须覆盖 bundle root 中除 manifest 自身外的全部普通文件；
- 每项 Evidence 固定 relative path、role、byte size、raw SHA-256 和 canonical fingerprint/absent 标记；
- `DEVELOPMENT_VERIFIED` 要求 `passed=14`、`failed=0`、`blocked=0`、`notRun=0`、`openP0=0`、`openP1=0`；
- `formalPassCount` 固定为 `0`，`formalExpectedCount` 固定为 `27`。

Manifest 结构通过不表示 Evidence 真实、测试语义正确或 Reviewer 独立。Verifier 必须同时读取完整 inventory、复算每项 Evidence，并验证独立复审坐标。外部 Evidence Store 和 Reviewer Authority 仍需独立认证。

## 8. 外部 Evidence Store

本地 Bundle 通过后，部署方才能把 immutable bundle 发布到外部 Evidence Store。发布接口必须返回 payload-free receipt，至少绑定：

- object reference 和 immutable generation；
- bundle manifest raw fingerprint；
- inventory aggregate fingerprint；
- Candidate/Input/Environment aggregate；
- published-at trusted time；
- store/issuer identity；
- receipt fingerprint 和外部签名坐标。

Resource Gateway 只验证 receipt，不保存签发私钥，不选择信任根，不以本地文件存在代替外部持久化。Provider 缺失、Store 不可用或 receipt 无法验证时，Runner 返回 `BLOCKED/INCOMPLETE`，保留本地证据供 exact retry。

## 9. 安全与故障处理

Runner 必须满足：

- stdout 仅输出一行 payload-free 终态；
- stderr 不包含路径、Payload、凭证、actor、签名、公钥内容或异常消息；
- 子进程 stdout/stderr 使用有界文件隔离；
- 收到终止信号后使用有界 TERM/KILL/reap；
- snapshot 或 evidence 成功发布后，不因后续失败删除 durable material；
- 未认证的未知对象保持不变；
- exact retry 恢复同一 receipt/witness/transcript，不创建第二条 Lease；
- post-lease 失败不回滚 Lease，也不输出成功；
- 所有输入和输出都执行 bounded read/count/aggregate 校验。

## 10. 验收映射

| Runner 阶段 | 主要 FELT obligation |
|---|---|
| Java 前 pin 与 snapshot | `FELT-01` |
| closed result 与失败分类 | `FELT-02`、`FELT-11` |
| existing-only state observation | `FELT-03` |
| recovery-first | `FELT-04`、`FELT-07` |
| atomic Lease transaction | `FELT-05`、`FELT-06` |
| durable wrapper | `FELT-08` |
| unknown-object preservation | `FELT-09` |
| child JVM crash matrix | `FELT-10` |
| packaged JAR execution | `FELT-12` |
| concurrency、compatibility 和 adversarial matrix | `FELT-13` |
| strict manifest 与独立复审 | `FELT-14` |

Runner 的一次正常执行不能替代 crash、并发、兼容和对抗矩阵。`FELT-10` 与 `FELT-13` 必须引用同一候选生成的完整测试 Evidence；不能以正常路径 stdout 推断为通过。

## 11. 当前实施边界

截至本文创建时，仓库已经具备 Formal Input Tree、mounted formal Provider、existing-only observation、full-evidence CLI、transcript verifier 和 durable-wrapper verifier。仍需完成：

1. 将这些组件接入唯一 full runner。
2. 增加 Formal Input Tree 的 read-only `verify` CLI 模式。
3. 增加 strict FELT run manifest 及独立 verifier。
4. 增加外部 Evidence Store publisher/receipt SPI 和部署实现边界。
5. 在 packaged runtime 中执行完整 Runner 正负矩阵。
6. 由企业部署方提供真实 Candidate/Environment/Deployment Authority、Evidence Store 和 Owner 签署。

完成前，`formalPassCount` 保持 `0/27`，Stage 0 保持 `NO_GO`。
