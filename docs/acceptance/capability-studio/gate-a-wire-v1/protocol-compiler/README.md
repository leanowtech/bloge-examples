# Gate A Protocol Compiler

该目录把 A1/A2 的实现合同从多份人类文档收敛为一个机器可读事实源：

- `gate-a-protocol-authority-v1.json`：caller-pinned Authority Source；
- `compile-protocol-authority.py`：严格 JSON/路径/闭集预检与确定性 JSON Pointer 投影器；底层不执行任何 role；
- `compiled/`：角色、启动、向量、状态机、材料、canonicalization、Authority 和交付切片投影。

编译器只生成数据，不生成 Candidate、Provider、Verifier、Harness 或 A2 的判断代码。五个
角色必须按各自合同独立消费投影并通过 deterministic role self-test，避免一个公共 runtime
实现同时污染所有证明角色。self-test receipt 只承诺 Authority、actual role JAR、packaged
profile 和 role contract 能独立推出的事实；动态 Verifier/Harness conformance 结果属于
caller-owned A1.7 execution envelope。

## 使用

生成投影前先执行 strict Draft 2020-12 Schema，再执行内置语义对抗检查：

```bash
uv run --with jsonschema python \
  docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/run-protocol-gate.py
```

CI 只读校验，不允许静默重写：

```bash
uv run --with jsonschema python \
  docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/run-protocol-gate.py --check
```

`--check` 还会在临时复制的仓库形态中运行 CLI/文件系统边界测试；也可以单独运行：

```bash
uv run --with jsonschema python \
  docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/test-protocol-tooling.py
```

### 聚焦测试命令

| 目标 | 命令 |
|---|---|
| Authority 校验 + 确定性投影复算 + 双编译一致性 | `uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/compile-protocol-authority.py --self-test` |
| 60 语义攻击分母逐项拒绝 | `uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/test-compiler-attack-catalog.py` |
| CLI/conformance 28 attacks | `uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/test-protocol-cli-boundaries.py` |
| sealed Bundle 30 attacks | `uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/test-protocol-tooling.py` |
| SliceAcceptanceReceipt 凭证 | `uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/test-slice-acceptance-receipt.py` |
| 完整 gate（上述全部 + Schema 闭包） | `uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/run-protocol-gate.py` |
| CI 静默校验（无输出写入） | `uv run --with jsonschema python docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/run-protocol-gate.py --check` |

### 验证规模

- 60 个带唯一名称和预期关系码的关系不变量变异全部被拒绝：38 个 RELATION 攻击、2 个 TRUST_PROJECTION 关系攻击、19 个 BOUNDARY 攻击（dependency/hermetic/slice/path/JSON），以及 1 个 ACTIVE_WITH_NULL_PINS 状态攻击。运行输出逐项打印 `mutation -> rejection code` 映射，证明不依赖宽泛 Schema 偶然失败。
- 63 个 Gate Schema 与 4 个 Reviewer Schema（合计 67）全集完成 duplicate-member、NaN/Infinity、Draft 2020-12、唯一 `$id`、实际文件系统 closed-set 和 `$ref` Registry 闭包检查。
- 8 个 R01/R02 直接测试（runner 共计 30 tests = 24 integrity + 6 production），CLI/conformance 28 attacks，sealed bundle 30 attacks，SliceAcceptanceReceipt 23 attacks，67 个 schemas（63 Gate + 4 Reviewer）。
- 两个独立临时目录连续编译逐字节一致，并逐份按 `sourceSelectors` 回绑 Authority。
- Python compiler 与历史 MJS 的关系语义保持等价，覆盖 role/coordinate/command/main class/profile/registry/projection/manifest/required entry/fixture-oracle/limits、launch/replay/phase/material/canonicalization/authorityRelations、delivery slice topology/module/path/artifact/handoff/A1 order/input/A2 exclusion、Gate/Reviewer schema filesystem closed set，以及 dependency authority/provided ABI/hermetic/slice receipt/raw path/strict JSON checks。

## Authority 状态机

当前 Authority 为 `DRAFT_UNPINNED`，编译器为 fail-closed：

- `DRAFT_UNPINNED`：允许外部 pins unavailable/null，编译器不强制 repository/POM/tree/plugin fingerprint 存在或非占位。
- `ACTIVE`：必须要求 repository/POM/tree/plugin 全部为真实非占位 fingerprint 且 status 为 `PINNED`，否则编译器 fail-closed。
- `REVOKED`：终态。

`ACTIVE_WITH_NULL_PINS` mutation 在 DRAFT 基线下固定拒绝为 `PROTOCOL_DEPENDENCY_STATUS_NOT_PINNED`。

## R01/R02 开发态闭合声明

`A1.3-R01`（compiler 可独立执行，protocol gate 全绿）与 `A1.3-R02`（Provider path/Identity path/`requiredJarEntries` 唯一一致）**均已实现为 DEVELOPMENT_VERIFIED 状态**。两个门禁证明 compiler 自身确定性，不证明 Verifier JAR 动态 conformance、A1.4 candidate-path TCK、A1.5 formal replay result、A1.6 trust-plane negative TCK，也不参与 A2 admission。

`A1.3-R03`（caller-owned predecessor receipt 绑定当前 Provider raw bytes）**仍为 BLOCKED_FORMAL_GATE**，依赖结构化 Evidence、ledger marker 和正式 SliceAcceptanceReceipt，不得以本地 Markdown 记录替代。

`A1.3-01`（Packaging Plan）**已标记为 DEVELOPMENT_VERIFIED**，由 `test-packaging-plan.py` 的固定分母突变测试固证（当前代码验收值为 89，由 `EXACT_TEST_COUNT` 常量约束；分母调整需同步更新该常量并重新运行全量门禁验证）。`A1.3-02`（Verifier exact closure）、`A1.3-03`（内核解耦）、`A1.3-04`（Java self-test）、`A1.3-05`（fail-closed positive/negative matrix）、`A1.3-06`（Authority profile 全绿）**仍为 PENDING**，被 `A1.3-R03`（BLOCKED_FORMAL_GATE）正式阻塞。`formalPassCount` 仍为 `0/27`，A1.3 的 Verifier JAR 尚未产出。未改变 schema、revision、`GateAProtocolAuthority` 结构和已编译投影内容。

### A1.3-01 Packaging Plan 数据流

```
Authority raw bytes
  -> LinkedProtocolModel         # 严格 JSON + 语义关联验证，绑定 authority_fingerprint；是 Packaging Plan 的唯一类型化中间模型
  -> IndependentVerifierPackagingPlan   # 纯编译输出，不加载任何 JAR class；exactArchiveEntries（28 项）、七份协议投影引用、providerIdentityRecipe
  -> PublicationReceipt          # content-addressed receipt，planFingerprint + authorityFingerprint 双绑定
```

- `LinkedProtocolModel`：Authority 经严格 JSON + 语义关联验证后产生，不重新读取 Authority，是 Packaging Plan 的唯一类型化中间模型。
- `IndependentVerifierPackagingPlan`：纯编译输出，不加载任何 JAR class，包含七份协议投影引用、`providerIdentityRecipe`（类型化 `resourceId` 引用，不含自由路径字符串）。
- `PublicationReceipt`：domain 为 `RG-CS-GATE-A-INDEPENDENT-VERIFIER-PACKAGING-RECEIPT-v1`；`receiptFingerprint` 仅排除自身，其余 6 字段（`schemaVersion`、`authorityFingerprint`、`planRawFingerprint`、`commitment`、`exactInventory`、`publicationRoot`）参与哈希计算；`publicationRoot` 与 receipt 实际路径须逐路径分量一致。

### CLI 用法

```bash
uv run --with jsonschema python \
  docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/compile-protocol-authority.py \
  --output-root <compiled-projections-dir> \
  --publish-packaging-plan-root <publication-root> \
  [--authority <authority-path>]
```

`--output-root` 指定编译投影输出目录（8 个投影文件 + 1 个 compilation manifest）。`--publish-packaging-plan-root` 指定内容寻址出版根目录。receipt 写入 `<publication-root>/<plan_fp_hex[:2]>/<plan_fp_hex[2:4]>/<plan_fp_hex>/publication-receipt-v1.json`；plan 写入同一 `<plan_fp_hex>` 目录的 `independent-verifier-packaging-plan-v1.json`。布局格式为 `aa/bb/<完整64位sha256 hex>/`（前 4 位 hex 分层），实现 content-addressed 寻址。出版根可容纳多个内容寻址发布，同一计划幂等写入，不要求为空目录。

### 回执边界

Receipt 不自引用：`receiptFingerprint` 仅排除自身字段，其余 6 字段参与哈希计算。`publicationRoot` 字段值须与 receipt 实际写入路径一致（逐路径分量字面验证，禁止 resolve 符号链接），lstat + O_NOFOLLOW 检查贯穿所有路径边界。

### 默认编译规模

Authority 当前固定投影 8 个 + Compilation Manifest 1 个，合计 9 个文件。`requiredJarEntries` 共 28 项。顶层 `dependencyAuthority` 固定 8 个依赖，其中 INDEPENDENT_VERIFIER 的 `runtimeDependencyLockIds` 精确引用其中 7 个。

### 门禁命令与结果

```bash
uv run --with jsonschema python \
  docs/acceptance/capability-studio/gate-a-wire-v1/protocol-compiler/run-protocol-gate.py --check
```

该命令执行全部门禁：Authority fingerprint 校验、双编译逐字节一致、Schema 闭包（63 Gate + 4 Reviewer）、Packaging Plan 突变测试（当前代码验收固定分母 89）、sealed bundle 30 attacks、CLI/conformance 28 attacks、SliceAcceptanceReceipt 23 attacks。Packaging Plan 测试输出末端为 `89/89 tests passed`；`run-protocol-gate.py` 最终输出为 `Gate A Protocol Authority ... PASS`（deterministic plan bytes + content-addressed publication receipt + mutation tests）。

## 权限边界

`GateAProtocolAuthority v1` 由 Challenge Pin 以 raw fingerprint 固定。角色 JAR 内的副本只是待核对 packaged copy，不能反向成为 Authority。生产放行还必须由 role-aware JAR gate
验证 actual JAR 的入口、依赖、资源闭包和 canonicalization 黑盒自测；仅通过本目录的
fixture 或 synthetic ZIP 不能标记为 release-ready。

依赖关系也遵循同一边界：顶层 `dependencyAuthority` 固定 8 个 caller-owned Maven
runtime JAR 的 GAV 与 raw SHA-256。role 的 `embeddedDependencyEntries` 只能写
`lockId`，不能携带可自证的 coordinate/fingerprint；Provider 的
`providedAbiDependencies` 单独绑定 Candidate GAV、SPI entry 和 Challenge Pin 字段，
并且必须为空于 embedded dependency 集合。

Candidate 固定使用 `-Pgate-a-candidate` 与 `gate-a-candidate` classifier，同时保留旧的
`cli` classifier/main class 兼容合同。每个交付切片还必须携带唯一 acceptance id、build
property、test id 集合、receipt path/message version、required evidence 与 handoff，
因此 `A1.3`、`A1.4`、`A1.5` 不能再被同一条普通 `clean verify` 冒充。

所有运行期必须绑定顶层 `hermeticExecutionContract`：外部 attested launcher、不同 UID
与 user namespace、最小只读 mount、oracle/workspace 不可见、network none、cgroup/job
级后代回收，以及 selector nonblocking/absolute deadline capture。launcher 或固定
hermetic launcher observation 或外部 toolchain pin 缺失时必须 fail closed；不得用 PATH 中的 `java` 替代。该 observation 由 caller 可信直接父进程产生，不是远程 attestation，也不进入 Candidate/A1 replay wire。

A1.7 先对该阶段已经生产的 Candidate、Provider、Verifier 与 Harness 四个实际制品执行
deterministic role self-test；`GATE_ADMISSION_CHECKER` 的相同门禁在 A2 交付时执行。五角色
结构和包装模型在 A1.0 冻结，但不得用尚未生产的 A2 synthetic JAR 冒充 self-test 或
conformance 结果。

A1.7 由 caller/reference 侧的 `compile-role-self-test-fixtures.py` 从一份稳定 Authority
byte snapshot、固定 role contract、actual role JAR bytes 和 packaged profile 独立编译四角色
fixture/oracle；编译器绝不执行被测 role。`run-a1-release-gate.py` 只执行 deterministic
self-test gate，不接受 `--execute`，并在 protocol check 前后重新绑定同一个 Authority raw
fingerprint。动态 conformance 必须由未来 caller runner 显式接收 `--authority`、
`--challenge-pin`、`--challenge-input-root` 与 `--output-root`，再由
`validate_run_material.py --root` 校验该 caller-owned root；它不能从 Authority 反向推导
Challenge Pin，也不能把 pin hash 循环嵌入 Authority。

Canonicalization 发布门不接受成功 token。外部 gate 只向角色传入不含答案的 challenge，
角色 stdout 必须与 caller-pinned oracle 文件逐字节相同。

当前 Authority 明确把 caller-pinned A1 视为 candidate 的直接父进程观察权威。若威胁模型
要求防御恶意 A1，则必须提升协议 revision，引入 caller/TEE attested child launch；不能在
v1 中把 A1 自己的观察材料误称为可对抗恶意 A1 的证明。
