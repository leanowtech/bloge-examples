# Capability Studio Gate A0 类型化回放指南

> 状态：`A0_IMPLEMENTATION_CANDIDATE`
>
> 适用对象：Resource Gateway Test Kit 开发者、Capability Studio 验收流水线维护者和后续 A1 独立验证器实现者。
>
> 结论边界：A0 只证明本地证据闭包可由固定类型的真实验证器重放。A0 不产生 `PASS`、`ACCEPTED` 或 Gate B 权限。

## 1. A0 解决什么问题

旧的 manifest 校验容易把「文件存在、字段看起来完整」误认为「证据已被真实验证」。A0 把两者分开：

1. Manifest Compiler 校验精确字节、Schema、顺序、计数、引用和 fingerprint。
2. Evidence Collector 封存 Evidence Root，并在回放前后检查文件身份、权限、链接、摘要和目录闭包。
3. Typed Replay Registry 只允许三个固定适配器。Manifest 不能指定任意类名或参数。
4. Candidate Deriver 从三个适配器槽位重新计算终态。Manifest 自报的状态不能提升结论。

三个固定适配器如下：

| 槽位 | `role` | `kind` | `verifierRevision` | Subject |
|---|---|---|---:|---|
| 1 | `FORMAL_INPUT_TREE` | `FORMAL_INPUT_TREE_V1` | 1 | 封存的输入树目录 |
| 2 | `DURABLE_EVIDENCE_CLOSURE` | `EXECUTION_LEASE_DURABLE_WRAPPER_V1` | 1 | Durable wrapper 文件 |
| 3 | `STAGE_ACCEPTANCE_RESULT` | `STAGE_ACCEPTANCE_RESULT_V2` | 2 | Stage Acceptance Result 文件 |

Manifest 可以声明 0 至 3 个回放。每个槽位最多声明一次。未声明的槽位为 `NOT_RUN`。

## 2. 前置条件

- Java 25 或更高版本。
- Maven 3.9 或更高版本。
- Node.js 18 或更高版本。只在生成零依赖演示包时需要。
- 支持 `unix:uid,gid,mode,nlink` 的 POSIX 文件系统。当前 A0 不支持 Windows 文件系统。
- Manifest 和 Evidence Root 使用绝对、规范化路径。
- Evidence Root 目录权限为 owner 私有，例如 `0700`。
- Manifest 和 Evidence 文件权限为 owner 私有，例如 `0600`。
- Manifest 使用 canonical JSON：对象键递归排序，无重复键，无 trailing JSON。
- `evidenceInventory` 按 `relativePath` 严格升序排列。
- `typedEvidenceReplays` 按 `id` 严格升序排列。

不要在 manifest、CLI 输出或异常中写入凭证和业务 Payload。Manifest 只保存引用、计数和 fingerprint。

## 3. 准备输入

### 3.1 准备 Evidence Root

Evidence Root 只能包含 manifest inventory 声明的文件和这些文件所需的非空目录。系统拒绝以下内容：

- 未声明文件；
- 符号链接；
- 未被固定 durable scope 接受的硬链接；
- owner 不一致的文件或目录；
- group 或 other 可访问的文件或目录；
- 回放期间发生身份、大小、权限、修改时间或字节变化的文件。

Collector 在每个 adapter 回放前后重新读取 subject 的精确字节并校验 raw fingerprint。
这可以识别保持文件大小、恢复修改时间后的内容篡改。A0 仍是同一 OS 用户下的本地候选验证，
不能抵御攻击者在 adapter 读取期间完成「修改并完全恢复」的同 UID 并发攻击；该威胁由 A1
隔离身份、父进程观察和 create-new 运行材料闭合。不要把 A0 单独部署为生产发布门禁。

Manifest 可以放在 Evidence Root 外。Manifest 位于 Evidence Root 内时，Collector 会从 inventory 闭包中排除 manifest 自身，但仍独立封存 manifest。

### 3.2 生成 manifest

使用以下 Schema 生成 manifest：

`docs/schemas/resource-gateway-capability-studio/capability-studio-formal-evidence-run-manifest-v1.schema.json`

生成顺序固定：

1. 写入 14 个 `FELT-01..FELT-14` obligation 槽位。
2. 扫描 Evidence Root，生成按路径排序的 `evidenceInventory`。
3. 计算 `evidenceCount` 和 `evidenceByteSize`。
4. 对 canonical `evidenceInventory` 计算 `inventoryClosureFingerprint`。
5. 增加 0 至 3 个 closed typed replay。
6. 根据是否存在 replay，设置 `verificationLevel`：
   - 无 replay：`INCOMPLETE`
   - 至少一个 replay：`STRUCTURE_VERIFIED`
7. 将 `manifestFingerprint` 临时设为 `null`。
8. 对完整 canonical document 计算 `manifestFingerprint`。
9. 写出 canonical JSON exact bytes。

`passed` 和 `formalPassCount` 必须为 `0`。14 个 obligation 只允许 `FAIL`、`BLOCKED` 或 `NOT_RUN`。

## 4. 运行 A0

### 4.1 五分钟体验

先生成一个不包含业务 Payload、可以从干净工作树复现的 `INCOMPLETE` 演示包：

```bash
resource-gateway-test-kit/scripts/generate-formal-evidence-run-demo.sh \
  /private/tmp/resource-gateway-a0-demo
```

生成器创建以下内容：

```text
/private/tmp/resource-gateway-a0-demo/
  manifest.json       # 0600，canonical exact bytes
  evidence-root/      # 0700，空 Evidence Root
```

再执行本地类型化回放：

```bash
resource-gateway-test-kit/scripts/verify-formal-evidence-run.sh \
  --manifest /private/tmp/resource-gateway-a0-demo/manifest.json \
  --bundle-root /private/tmp/resource-gateway-a0-demo/evidence-root
```

预期输出包含 `outcome=INCOMPLETE`，进程退出码为 `4`。这是「A0 正常完成但没有
typed replay」的诚实结果，不是错误，也不是发布通过。

### 4.2 验证自有 Evidence Root

从仓库根目录运行：

```bash
resource-gateway-test-kit/scripts/verify-formal-evidence-run.sh \
  --manifest /absolute/private/run/manifest.json \
  --bundle-root /absolute/private/run/evidence
```

脚本先构建 Test Kit 的当前工作树，再调用
`CapabilityStudioFormalEvidenceRunVerifyCli`。脚本不会启动 Resource Gateway 服务。

### 4.3 退出码

| 退出码 | 结果 | 含义 |
|---:|---|---|
| 2 | `INVALID` | 结构、引用、fingerprint、权限、闭包、tuple 或回放结果不匹配 |
| 3 | `UNAVAILABLE` | 必需文件、稳定身份或验证材料不可读取 |
| 4 | `STRUCTURE_VERIFIED` 或 `INCOMPLETE` | A0 正常完成；仍不是 formal pass |

退出码 `4` 是预期的 A0 完成状态，不是通用 Unix 成功码。只有后续 A1 独立验证器闭合挑战材料后，才能产生 A1 的 `VERIFIED/0`。

### 4.4 输出示例

```text
NOT_VERIFIED outcome=STRUCTURE_VERIFIED verificationLevel=STRUCTURE_VERIFIED typedReplayCount=1 passed=0 failed=0 blocked=0 notRun=14 evidenceCount=1 evidenceBytes=4096 reasonCode=RG.CAPABILITY_STUDIO.FORMAL_EVIDENCE_RUN_VERIFY.STRUCTURE_VERIFIED terminalClass=LOCAL_TYPED_REPLAY_ONLY formalConclusion=INCOMPLETE
```

输出不包含文件路径、Evidence 内容或业务 Payload。

## 5. Java 接入

### 5.1 本地诊断投影

```java
Path manifest = Path.of("/absolute/private/run/manifest.json");
Path evidenceRoot = Path.of("/absolute/private/run/evidence");

CapabilityStudioFormalEvidenceRunVerifier.Verification result =
        CapabilityStudioFormalEvidenceRunVerifier.verify(manifest, evidenceRoot);

if (!"STRUCTURE_VERIFIED".equals(result.verificationLevel())
        && !"INCOMPLETE".equals(result.verificationLevel())) {
    throw new IllegalStateException("unexpected A0 projection");
}
```

捕获 `VerificationException` 后，只读取 `failureKind()`。不要从异常文本推断业务原因。

`Verification` 和 CLI 单行输出只用于本地诊断，不是跨进程 wire result，也不能进入 A1
Evidence Store。

### 5.2 生成严格候选结果

跨进程接入使用 `CapabilityStudioGateACandidateReplayResult`。调用方必须提供 Challenge
Authority 持有的 candidate、Trust Pin、Input Root 和 Registry 精确引用：

```java
var context = new CapabilityStudioGateACandidateReplayResult.Context(
        "A0-DEMO-001",
        new CapabilityStudioGateACandidateReplayResult.RawRef(
                "candidate/artifact", candidateRawFingerprint),
        new CapabilityStudioGateACandidateReplayResult.RawRef(
                "challenge/trust-pin", challengePinRawFingerprint),
        "formal-evidence/manifest",
        new CapabilityStudioGateACandidateReplayResult.TreeRef(
                "challenge/input-root", challengeInputTreeFingerprint),
        new CapabilityStudioGateACandidateReplayResult.RawRef(
                "registry/typed-replay", registryRawFingerprint),
        "formal-evidence/files",
        "candidate-result/adapter-materials");

CapabilityStudioGateACandidateReplayResult.Bundle bundle =
        CapabilityStudioGateACandidateReplayResult.create(
                manifest, evidenceRoot, context);
```

`bundle.resultBytes()` 是严格、canonical 的 `GateACandidateReplayResult v1`。每个
`VERIFIED` 或 `INVALID` adapter 的 `resultRef` 都指向 `bundle.adapterMaterials()` 中的
一份 exact-byte material。调用方必须在启动 A1 前：

1. 以 create-new 语义保存 `resultBytes`；
2. 按各 `AdapterMaterial.uri()` 保存 `exactBytes()`；
3. 复算并比对每份 `rawFingerprint()`；
4. 禁止覆盖、原地修改或把 CLI stdout 代替 result bytes；
5. 将完整材料根和父进程 transcript 交给独立 A1 verifier。

只有 Evidence Root 最终闭包为 `VERIFIED` 时才允许创建严格 Candidate Result。闭包自身
`INVALID/UNAVAILABLE` 时，`create(...)` 直接失败，不写半成品 Result；调用方只保存由父进程
观察到的退出码和 transcript。Candidate Result 中的 `INVALID/UNAVAILABLE` 必须分别由至少
一个 adapter 的同名状态支撑，不能用隐藏的文件系统状态解释无法重算的 terminal。

候选结果固定包含 3 个 adapter 槽位、14 个 FELT obligation 槽位、派生计数、
`0/27` formal gap、四态 terminal/reason 判别联合，以及带
`RG-CS-GATE-A0-RESULT-v1` 域分离的 canonical document fingerprint。

## 6. 终态如何计算

Candidate Deriver 对 adapter facts、Evidence Root 最终闭包和 manifest 自报的
`verificationLevel` 只做一次终态推导。固定优先级为：

```text
任一 UNAVAILABLE
  > 任一 INVALID
  > 任一 VERIFIED
  > 三槽全部 NOT_RUN
```

对应终态为：

```text
UNAVAILABLE
  > INVALID
  > STRUCTURE_VERIFIED
  > INCOMPLETE
```

`FELT-01..14` 的计数只作为 formal gap projection。即使三个适配器全部 `VERIFIED`，A0 的 `passed` 仍为 `0`，`formalPassCount` 仍为 `0/27`。

## 7. 常见失败

| 现象 | 检查项 | 处理 |
|---|---|---|
| `INVALID/2` | Manifest 是否为 canonical exact bytes | 重新生成 manifest，不要手工格式化 |
| `INVALID/2` | Inventory 路径、大小和 raw fingerprint 是否匹配 | 重新封存 Evidence Root 后生成 manifest |
| `INVALID/2` | `role/kind/verifierId/revision` 是否为固定 tuple | 使用本指南中的三个固定槽位 |
| `INVALID/2` | 是否重复声明同一 adapter slot | 每个槽位只保留一项 |
| `INVALID/2` | 文件、目录、symlink 或 hard-link 是否超出闭包 | 创建新的私有 Evidence Root |
| `INVALID/2` | 路径 ancestor 是否包含 symlink，例如 macOS 的 `/tmp` | 使用 `toRealPath()` 对应的真实绝对路径，例如 `/private/tmp` |
| `UNAVAILABLE/3` | 文件是否可读，ancestor identity 是否稳定 | 停止修改 Evidence Root，然后重新运行 |
| `STRUCTURE_VERIFIED/4` | 是否误当作正式通过 | 将结果交给 A1 独立验证器，不要开放发布权限 |

发生闭包或身份失败后，不要在原 Session 上重试。创建新的 Evidence Root、manifest 和 A0 进程。

## 8. 验证实现

聚焦测试：

```bash
mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=CapabilityStudioFormalEvidenceRunManifestCompilerTest,CapabilityStudioFormalEvidenceBundleCollectorTest,CapabilityStudioCandidateReplayDeriverTest,CapabilityStudioTypedEvidenceReplayRegistryTest,CapabilityStudioFormalEvidenceRunVerifierTest,CapabilityStudioFormalEvidenceRunVerifyCliTest,CapabilityStudioGateACandidateReplayResultTest,CapabilityStudioFormalEvidenceRunScriptTest \
  test
```

完整验证：

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

聚焦测试覆盖：

- duplicate key、trailing JSON 和 non-canonical bytes；
- obligation、inventory 和 replay 槽位漂移；
- 三个真实 typed verifier adapter；
- wrong kind、wrong revision、replay tamper；
- manifest replacement、symlink、hard-link、权限和 identity drift；
- same-size mutation、mtime restoration 和目录 subject descendant mutation；
- `UNAVAILABLE > INVALID > STRUCTURE_VERIFIED > INCOMPLETE` 归约；
- 三个真实 adapter 共存于同一 Evidence Root 的端到端回放；
- strict candidate result、adapter exact-byte materials、count/fingerprint mutation；
- strict result failure terminal 与可见 adapter fact 的闭包；
- shaded JAR 内 candidate/common Schema 相对 `$ref` 的真实子进程解析；
- 从干净工作树生成并验证 `INCOMPLETE` 演示包；
- CLI 和 launcher 的 payload-free 输出与 `2/3/4` 完整退出码矩阵。

## 9. A0 不能替代什么

A0 不能替代：

- 调用方固定的 Challenge Pin；
- 独立 A1 verifier artifact 和父进程 transcript；
- Harness 的 12 项固定 TCK；
- A2 Admission Checker；
- Reviewer Authority、revocation、policy 和签名；
- 企业部署环境、Evidence Store 和 Owner signoff。

因此，A0 结果只能作为后续独立挑战的 Implementation Candidate 输入。
