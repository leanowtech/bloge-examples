# API Resource / Fixture / Reusable Flow 实施台账

> 目标方案：`rg-api-fixture-reusable-flow-authoring-proposal-v1.md`。
>
> 状态：Implementation in progress。架构决策已批准，按深模块和用户任务逐切片实现。
>
> 完成条件：所有目标切片有实现与对应范围的运行证据，最终差距严格小于 3%。

## 1. 评估方法

本台账按用户可观察能力加权，不按代码行数、提交数或既有内部组件数量计分。旧接口能够执行相似动作，
但仍要求前端理解 Descriptor、Contract、GraphDraft 或治理生命周期时，不视为新方案已完成。

| 目标能力 | 权重 |
| --- | ---: |
| 七类 Wire Schema family 与安全/CAS/Scope 协议 | 10% |
| API Resource 后端权威、复合保存与投影闭包 | 18% |
| API Resource 对象页、Default Fixture 与首次模拟 | 12% |
| Reusable Flow 后端、单一 Mapping、发布版本与 Catalog | 18% |
| Tool / Solution 对象页与 DAG 创作 | 10% |
| FixtureSet、Whole-flow Fixture、`APPLY_CASE` 与共享治理 | 12% |
| 稳定 SimulationModule、Trace、Egress 与四维 Verdict | 8% |
| 兼容迁移、入口切换和旧数据修复 | 5% |
| 文档、全量门禁、真实浏览器与可操作性验收 | 7% |
| **合计** | **100%** |

每轮只在证据与目标范围相同且最新实现已复验时增加完成度。聚焦测试不能证明全量门禁，静态 Schema 不能
证明 Controller、持久化或真实用户任务。

## 2. Iteration 1 — Schema 冻结

日期：2026-08-30。

### 已完成的契约原型

- `74f2bb937`：提交目标方案与可编辑架构图。
- `eaf745010`：提交 Authoring JSON Schema families、golden positive/negative examples 与协议测试。
- `3c1824962`：关闭 `SECRET_REF` URI 和敏感 Header 大小写绕过。
- 补充按 Exact Subject 查询 metadata-only `FixtureSetSummary` 的协议边界；禁止把 Input、Material、Replay
  Payload 或 Credential 放入列表响应。

评审稿在原型之后又冻结了 Publish/Share Wire Contract、Example 到 Case 的回执映射、
Fixture 状态可运行性、共享 Header Policy 和可审计 Egress Evidence。这些差距已在 Iteration 2
转成可执行 Schema；本节保留当时的历史证据，不代表当前状态。

### 最新验证

```text
mvn -f resource-gateway-examples/pom.xml -Dtest=AuthoringProtocolSchemaTest test
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

协调者在 `3c1824962` 后独立重跑，时间为 2026-08-30 07:44 +08。Maven 仍报告既有
`bloge-durable` / `bloge-test` POM warning；它没有使本次测试失败，也不是新方案完成证据。

### 差距评估

| 目标能力 | 已证明 |
| --- | ---: |
| Wire Schema family | 6 / 10（原型已验证，待评审后对齐新契约） |
| 文档与当前聚焦门禁 | 3 / 7 |
| 其余运行与 UI 能力 | 0 / 83 |
| **当前完成度** | **9%** |
| **当前差距** | **91%** |

不能把现有 Descriptor、GraphDraft、Fixture Catalog 或 Simulation Kernel 直接计为新方案完成；它们目前只是
待新深模块复用的 Adapter 候选。

## 3. Iteration 2 — 可执行协议与 API Resource 权威领域

日期：2026-08-30。

### 已完成

- `a12b3ff7b`：把 Publish、Share、具名 Default Fixture Case、统一 Fixture 状态和 Egress Evidence
  Union 对齐到可执行 JSON Schema 与 goldens。
- `91425b4fd`：新增纯 `ApiResourceModule` 与线程安全内存适配器，建立 create/update、精确 CAS、
  canonical fingerprint 和精确 `API_RESOURCE` 引用。
- `a26ed1205`：将 `ApiResourceSpec` 收敛为冻结的扁平权威形状；补齐 `MANAGED_WRITE` 幂等、Receipt、
  Reconciliation 合同，关闭浅复制、ID/path、BODY 数量和 Header 约束缺口。
- `b4bb58613`：为两种 Success 和三种 Effect 增加准确的 `kind` wire discriminator。
- `2d3458e36`：省略可选空字段，并以最小/完整 Command 与 Spec 对冻结 Schema 做 round-trip 验证。

本切片刻意不包含 HTTP、数据库、Projection、Default Fixture、Connection create 或 `AuthoringFacade`。
内存适配器是公共领域 seam 的 contract reference，不是 production persistence 完成证据。

### 最新验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=AuthoringProtocolSchemaTest,ApiResourceModuleTest test
AuthoringProtocolSchemaTest: 9 tests
ApiResourceModuleTest: 8 tests
Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

协调者在 `2d3458e36` 后独立串行复验。Standards review 与批准方案 Spec review 均为 Accepted；
审查中发现的防御复制、Managed Write、Spec 形状、BODY、Header prefix、wire discriminator 和 null omission
问题均已修复并重新验证。

### 当前差距评估

| 目标能力 | 已证明 |
| --- | ---: |
| Wire Schema family | 10 / 10 |
| API Resource 后端权威、复合保存与投影闭包 | 3 / 18（纯领域权威与 CAS；未含 production adapter） |
| 文档与当前聚焦门禁 | 4 / 7 |
| 其余运行与 UI 能力 | 0 / 65 |
| **当前完成度** | **17%** |
| **当前差距** | **83%** |

下一切片先实现 production storage foundation：权威 revision/head、Command Journal、pending Secret lease、
私有 Default Fixture staging、三份持久化 `READY` Projection 和 readiness/migration。现有 Registry 以进程内
cache 为权威且无 CAS，不能直接拼接进 `AuthoringFacade` 冒充原子提交。

## 4. Iteration 3 — Scoped Commit Protocol

日期：2026-08-30。

### 已完成

- `17edddc52`：新增 `ApiResourceCommitStore` 深 seam、Scope/Command/Lease/Projection 值对象、
  Journal 状态机与内存 contract reference。
- `01ccaddfc`：关闭跨实例共享 State 锁、真实并发提交、Busy token 泄漏、Decision 反向依赖、
  Projection 独立指纹、上层 Receipt 所有权和 Compiler Scope 问题。
- `d659d1c86`：以 bounded `CommandFailureCode` 收紧失败 Journal；统一 null/stale lease 错误，移除测试
  fingerprint 自动修正，并恢复可审计格式和 JavaDoc。

状态机已证明同一 command coordinate 的 claim、live busy、过期 takeover、attempt fencing、same-payload
replay、different-payload conflict、不可见 staging、commit 前二次 CAS、fail cleanup 和跨 store 实例竞争。
`Busy` 不返回 fencing token；takeover 保留 command ID、增加 attempt number 并轮换 token。三份 Projection
分别验证 canonical body fingerprint，并共同绑定精确 Resource Subject。

### 最新验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ApiResourceModuleTest,ApiResourceCommitStoreContractTest test
ApiResourceModuleTest: 8 tests
ApiResourceCommitStoreContractTest: 13 tests
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

协调者在 `d659d1c86` 后独立串行复验；Standards 与 Spec 双审查均 Accepted。

### 当前差距评估

| 目标能力 | 已证明 |
| --- | ---: |
| Wire Schema family | 10 / 10 |
| API Resource 后端权威、复合保存与投影闭包 | 6 / 18（领域权威 + commit protocol；未含 JDBC/Facade） |
| 文档与当前聚焦门禁 | 4 / 7 |
| 其余运行与 UI 能力 | 0 / 65 |
| **当前完成度** | **20%** |
| **当前差距** | **80%** |

下一步以同一 contract 实现 JDBC adapter、四张正式 migration 表和 fail-fast readiness。只有 H2 正常门禁与
显式 PostgreSQL lane 都能复现 scoped CAS、Journal replay 和 staged invisibility，才能把 production storage
计为完成；内存 monitor 不是生产原子性证据。

## 5. Iteration 4 — Authoring Schema Migration 与 Readiness

日期：2026-08-30。

### 已完成

- `33450d742`：新增四张 API Resource authoring 持久化表及 fail-closed schema readiness 原型。
- `74537af49`、`33e50b33d`：补齐 attempt fencing、`COMMITTED` revision + `READY` Projection head
  闭包、带时区时间戳、精确 PK/UQ/FK/index 有序列元数据检查，以及错误约束重建和 takeover 负向测试。
- `f662fbd2d`：将所有 fingerprint 数据库约束收紧为 `sha256:` 加 64 位小写十六进制，关闭空格与
  混合字符绕过；failure code 与 Java bounded value contract 保持一致。

Migration 同时在 H2 PostgreSQL mode 与 PostgreSQL 方言边界内使用 `TEXT`、`VARCHAR`、
`TIMESTAMP WITH TIME ZONE` 和可移植 `CHECK`。Readiness 只读检查 schema，不在 Repository 或启动门禁中执行
DDL。当前证据证明 H2 PostgreSQL-mode migration 与约束合同；它不等价于真实 PostgreSQL certification，
也不证明尚未实现的 JDBC store 或生产 runtime wiring。

### 最新验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ApiResourceAuthoringSchemaReadinessTest test
ApiResourceAuthoringSchemaReadinessTest: 18 tests
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

协调者在 `f662fbd2d` 前的最终代码内容上独立串行复验。Spec review 与 Standards review 均为
Accepted；审查发现的同名错误对象、列序、attempt token、旧 STAGED takeover、非法 fingerprint 和 failure
code 反例均已修复并重新验证。

### 当前差距评估

| 目标能力 | 已证明 |
| --- | ---: |
| Wire Schema family | 10 / 10 |
| API Resource 后端权威、复合保存与投影闭包 | 8 / 18（领域权威、commit protocol、正式 migration/readiness；未含 JDBC/Facade） |
| 文档与当前聚焦门禁 | 4 / 7 |
| 其余运行与 UI 能力 | 0 / 65 |
| **当前完成度** | **22%** |
| **当前差距** | **78%** |

下一步实现同一 `ApiResourceCommitStore` contract 的 JDBC adapter，并在正式 migration 上证明重启、跨实例
并发 CAS、Journal replay、staged invisibility 和原子 commit。随后才进入 `AuthoringFacade`、Connection/Secret
与 Default Fixture 复合保存。

## 6. Iteration 5 — JDBC Claim 与 Committed Read

日期：2026-08-30。

### 已完成

- `cf259e560`：新增 `JdbcApiResourceCommitStore` 的 scoped claim 和 committed head/revision read；正式
  migration 上支持首 claim、Busy、Replay、Conflict、过期 takeover、attempt token 轮换和旧 STAGED cleanup。
- `fe1d635a9`：将关系行 identity、权威 Spec fingerprint、三 Projection fingerprint 和 Receipt ETag 绑定到
  反序列化 wire；补真实双连接首插竞争、takeover cleanup、历史 revision、scope 和篡改回归。
- `a0d502025`、`54635bed2`：fail-fast 拒绝 JDBC/transaction DataSource 误配与双空配置；将 Journal scope、
  target、endpoint 重新绑定 Resource relationship，并覆盖 schema/status 与 coordinate tamper。

本切片只接受 JDBC claim 与 committed read。`stage`、`commit`、`fail` 仍明确不可用，production runtime 也没有
启用该 Store，因此不能把它报告为完整 production adapter。

### 最新验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=JdbcApiResourceCommitStoreClaimTest test
JdbcApiResourceCommitStoreClaimTest: 10 tests
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

协调者在 `54635bed2` 后独立串行复验。Standards 与 Spec review 最终均为 Accepted。

### 当前差距评估

| 目标能力 | 已证明 |
| --- | ---: |
| Wire Schema family | 10 / 10 |
| API Resource 后端权威、复合保存与投影闭包 | 10 / 18（JDBC claim/read 已证明；stage/commit/fail/Facade 未完成） |
| 文档与当前聚焦门禁 | 4 / 7 |
| 其余运行与 UI 能力 | 0 / 65 |
| **当前完成度** | **24%** |
| **当前差距** | **76%** |

下一步 J2 在同一 Store 内完成 stage、原子 commit、fenced fail 和 production readiness wiring，并让 JDBC
实现通过与内存参考相同的 contract。真实 PostgreSQL certification 仍是独立门禁，不能由 H2 mode 替代。

## 7. Iteration 6 — JDBC Stage/Commit/Fail 与 Runtime Wiring

日期：2026-08-30。

### 已完成

- `02d3262d6`：新增 V20260830_002 并发 staging migration。revision、projection 和 head 通过
  `command_id` 建立复合键及精确外键；既有 projection/head 数据先回填 command provenance，再收紧为
  `NOT NULL`，避免两个 command 暂存同一逻辑 revision 时互相覆盖。
- `416e70915`：完成 JDBC stage/commit/fail 协议，保留不可见 STAGED revision、精确 attempt/token
  fencing、事务内三投影闭包和 committed read/replay 语义。
- `fbb341d69`：补强 staged closure、receipt/指纹和 CAS 失配的 fail-closed 校验；失败路径清理 staged
  数据并写入 bounded failure journal。
- `122eaa383`：接入 opt-in production runtime configuration。默认关闭；启用时缺少 V001/V002 schema
  或 compiler/readiness 条件会 fail startup，不会静默回退到内存 store。

### 最新验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ApiResourceAuthoringSchemaReadinessTest,ApiResourceAuthoringRuntimeConfigurationTest,JdbcApiResourceCommitStoreMutationTest,JdbcApiResourceCommitStoreClaimTest,ApiResourceCommitStoreContractTest test
Tests run: 66, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

验证覆盖：同一逻辑 revision 的双 command staging、双连接单 winner、DB 时间驱动的 lease、stage/commit/fail
及 restart/history replay、tamper fail-closed，以及默认关闭的 production wiring。该命令仍是 H2
PostgreSQL-mode 与本地 runtime 的聚焦证据，不是 PostgreSQL certification 或全量构建证据。

### 当前差距评估

| 目标能力 | 已证明 |
| --- | ---: |
| Wire Schema family | 10 / 10 |
| API Resource 后端权威、复合保存与投影闭包 | 15 / 18（JDBC stage/commit/fail 与 runtime wiring；未含 Facade/HTTP） |
| 文档与全量门禁 | 5 / 7（聚焦 66/66；真实 PostgreSQL 与 full clean verify 未完成） |
| 其余运行与 UI 能力 | 0 / 65 |
| **当前完成度** | **30%** |
| **当前差距** | **70%** |

下一步进入 J3：实现 `AuthoringFacade` 与 Connection/Secret/Default Fixture 复合保存，再接 API Resource
对象页和首次模拟。必须继续保留 Exact Subject、强 ETag/Idempotency、secret-free Problem Detail 与
投影闭包边界；不能把当前 Store 的聚焦证据写成 HTTP 或 UI 已完成。

## 8. Iteration 7 — J3 编译器投影与边界验收

日期：2026-08-30。

### 已完成

- `67d8530b4`、`3532ce5a1`、`c45436249`、`6dd104292`：J3 的 Spec + Standards 评审结论为
  **Accepted**。编译器已为一个 API Resource 创建 3 个 `READY`、精确 subject 的投影；投影通过
  服务端 `Connection` resolver 解析，并复用共享 Header/API-key policy。URI 解析严格拒绝不合规输入，
  JSONPath 映射到运行时 dot path，并显式接入 `visualadapter` 边界及 wiring。
- `FIXTURE_ONLY` 的真实写入路径保持 fail-closed；`MANAGED_WRITE` 在缺少无损 runtime side-effect
  contract 时仍明确 fail-closed，不能由当前编译证据推导为真实写入已可用。

### 最新验证

协调者独立运行 82 个聚焦测试，全部 `Failures: 0, Errors: 0, Skipped: 0`：
`ApiResourceModule8`、schema readiness 19、runtime config 7、JDBC mutation 18、claim 10、
store contract 13、compiler 6、boundary 1。`c45436249` 的隔离 `clean verify` 共 7739 个测试，
只有 `VisualRuntimeBoundary` 1 个失败且 0 个错误；`6dd104292` 修复后，边界聚焦测试为 1/1 通过。
这里不把 `6dd104292` 之后未重跑的 full verify 写成全量通过。

### 当前差距评估

本轮只关闭 API Resource 后端权威能力中的一个编译器投影点；按本台账现有权重，完成度从 30% 调整为
**31%**，当前差距为 **69%**。这不是 HTTP、对象页、真实 PostgreSQL 或全量门禁完成声明。

下一步仍是 `Connection` 元数据、Secret lease、Default Fixture 与 `AuthoringFacade`；随后才进入 API
Resource 对象页和首次模拟。复合保存必须继续满足 Exact Subject、强 ETag/Idempotency、secret-free
Problem Detail、投影闭包与 fail-closed side-effect 边界。

## 9. Iteration 9 — J3-A Connection authority

日期：2026-08-30。

### 已完成

- `9e2ac490f`、`b3eb989d0`、`5877a6714`：J3-A Connection authority 的 Spec 评审为
  **Accepted**；Standards 评审无 P1，P2 已关闭。
- 本切片只实现纯领域权威与 wire 形状：认证/Secret 的 wire variants、同 Scope 下已授权的 opaque
  reference、CAS 与服务端 fingerprint，以及 secret-free View/Error 边界；同时固化 HTTPS、Header
  和 timeout policy。它不等价于 Connection 的持久化或 API 可用性。

### 最新验证

J3-A 聚焦测试 **11/11 green**。该证据只覆盖上述纯 authority 能力；尚未实现或验收 JDBC、权威 head、
Vault lease/activate、`AuthoringFacade` 或 controller。

### 当前差距评估

本轮只关闭 API Resource 后端权威能力中的 Connection 纯 authority 边界；按本台账现有权重，完成度从
31% 调整为 **32%**，当前差距为 **68%**。这不是 JDBC、HTTP、对象页、真实 PostgreSQL 或全量门禁完成声明，
也不意味着最终差距已小于 3%。

`c45436249` 的隔离 `clean verify` 共 7739 个测试，只有 `VisualRuntimeBoundary` 1 个失败且 0 个错误；
`6dd104292` 修复后保留的 full verify 证据为 **7739/7739 green**，边界聚焦测试为 1/1 通过。

下一步仍是 JDBC/head、Vault lease/activate、`AuthoringFacade` 与 controller 的复合保存和 API 暴露，
随后才进入 API Resource 对象页和首次模拟。

## 10. Iteration 10 — J3-B1a Connection schema/readiness

日期：2026-08-30。

### 已完成

- `c2a25a63c`、`99fa6f806`、`955c1ef18`、`294331ddc`：J3-B1a 的 Spec 与 Standards 评审均为
  **Accepted**。V003 新增五张 scoped Connection 持久化表：identity、revision、head、pending secret
  lease 和 active binding；保存 payload-free view 与 metadata，使用 exact `command_id`、`attempt_no`、
  `attempt_token` provenance，数据库约束 staged/committed 可见性和精确 head 选择。
- schema readiness 逐表验证全部 required columns，并核对关键 primary/unique/foreign key、索引以及
  Resource revision 到 Connection identity 的 `ON DELETE RESTRICT` 绑定。迁移同时为既有 Resource revision
  回填 Connection identity；pending lease 不持有明文或序列化 credential/ref 数据。

### 最新验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ApiConnectionSchemaReadinessTest test -DfailIfNoTests=false
ApiConnectionSchemaReadinessTest: 11 tests
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

上述证据仅来自 H2 `MODE=PostgreSQL`。真实 PostgreSQL migration/certification 和 full `clean verify` 本轮均未运行，
不能将 H2 结果扩写为 PostgreSQL 或全量构建证据。

### 当前差距评估

本轮关闭 Connection 的 migration/schema/readiness 约束层；按本台账既有 broader goal 口径，完成度由 **32%**
调整为 **33%**，当前差距为 **67%**。该数字不代表 JDBC Connection store、外部 Vault、Facade 或 HTTP 已完成，
也不意味着 broader goal gap 接近 3%。

### 未实现边界

- JDBC Connection store、跨实例运行时提交与真实 Connection head 读写尚未实现。
- 外部 Vault 的 lease/activate、清理、恢复和 provider 集成尚未实现。
- `AuthoringFacade`、HTTP controller、ETag/Idempotency transport 以及 UI 尚未接入。
- 真实 PostgreSQL lane 与 full `clean verify` 未运行；当前仅有 H2 PostgreSQL-mode focused evidence。

## 11. Iteration 11 — J3-B1b-1 Connection commit seam

日期：2026-08-30。

### 已完成

- `4589c51de`、`7315c6490`、`112d00f17`、`076fa184b`、`6958bc2b4`：J3-B1b-1 的 Spec 与 Standards
  评审均为 **Accepted**，P0/P1/P2 均为 0。新增纯 `ApiConnectionCommitStore` seam、不可变 staged/stored
  records 和 `InMemoryApiConnectionCommitStore` reference adapter；contract 覆盖显式 child CAS、endpoint
  绑定、attempt fencing、staged invisibility、committed history 与 safe strong ETag。
- 提交路径保持 credential 与业务 payload 不出异常、历史或公开视图；该切片不把 in-memory reference adapter
  写成生产持久化实现。

### 最新验证

`InMemoryApiConnectionCommitStoreTest`（实现 `ApiConnectionCommitStoreContractTest`）共 **19/19 green**，
`Failures: 0, Errors: 0, Skipped: 0`。这是 pure commit seam/reference adapter 的聚焦证据；本轮没有运行
full `clean verify`，也没有运行真实 PostgreSQL lane。

### 当前差距评估

本轮新增 Connection commit seam 与内存 reference adapter 的行为闭包；按本台账同一 broader goal 口径，完成度
由 **33%** 小幅调整为 **34%**，当前差距为 **66%**。该调整不代表 JDBC Connection store、Vault/pending-secret、
Facade、HTTP 或生产 PostgreSQL 能力已完成，更不意味着 broader goal gap 接近 3%。

### 未实现边界

- JDBC Connection store、数据库 head/revision 持久化与跨实例提交尚未实现。
- 外部 Vault 与 pending-secret lease/activate、清理、恢复和 provider 集成尚未实现。
- `AuthoringFacade`、HTTP controller、ETag/Idempotency transport 与 UI 尚未接入。
- 真实 PostgreSQL lane 与 full `clean verify` 未运行；当前仅有内存 contract 和前一切片的 H2 focused evidence。

## 12. Iteration 12 — J3-B1b-2 External Secret Provider pure seam

日期：2026-08-30。

### 已完成

- `73c6592fc`、`59370a17f`、`fcefffdee`、`ff0338842`、`6582730ad`、`f6198b75b`、`c8ae04113`：External Secret Provider 纯 seam 的 Spec 与 Standards 评审均为 **Accepted**，P0/P1/P2 均为 0。
- seam 只接受外部 provider：明文 `VALUE` 由调用方持有且负责销毁；`SECRET_REF` 必须绑定到请求 Scope。最终 template 同时闭合 Scope、provider 和 exact attempt，避免跨作用域、错 provider 或错 command/attempt 的解析。
- `prepare`、`activate`、`abort`、`resolve` 均定义幂等与补偿语义；解析结果和异常路径保持 secret-free，Jackson 序列化、`toString` 和 code-only 错误边界不泄露 secret。当前没有本地 AES/JDBC provider。

### 最新验证

`FakeExternalSecretProviderContractTest` 共 **13/13 green**，`Failures: 0, Errors: 0, Skipped: 0`。这是外部 provider 纯 seam 的聚焦 contract 证据；本轮没有运行 full `clean verify` 或真实 PostgreSQL lane。

### 当前差距评估

本轮只关闭 External Secret Provider 的纯 seam 与行为合同；按本台账同一 broader goal 口径，完成度由 **34%** 保守调整为 **35%**，当前差距为 **65%**。该调整不代表 Secret 持久化、生产 provider、Facade、HTTP、UI、真实 PostgreSQL 或 full `clean verify` 已完成。

### 未实现边界

- `PendingSecretStore`、JDBC Secret store 和生产 Vault/provider 集成尚未实现；当前没有本地 AES/JDBC provider。
- `AuthoringFacade`、HTTP controller、ETag/Idempotency transport 与 UI 尚未接入；full `clean verify` 与真实 PostgreSQL lane 未运行。

## 13. Iteration 13 — V003 hardening 与 readiness 语义收紧

日期：2026-08-30。

### 已完成

- `d4d57c5d6`、`eb3c40540`、`19a5c8a1f`：收紧 V003 的 active binding 与 pending lease 约束，并使 readiness 检查在 H2 与 PostgreSQL 表达之间保持可移植且 fail-closed。
- active binding 只允许通过精确外键指向 `COMMITTED` revision；pending lease 只允许 `PENDING` 或 `ABORT_REQUIRED` 状态；恢复索引固定为确定性的七列顺序。
- readiness 对 H2 的 `IN` 与 PostgreSQL 的 `ANY` 以及安全 cast 语义等价形式予以接受；额外、错误或会改变语义的 cast 仍 fail-closed。

### 最新验证与证据边界

在 detached clean HEAD `4893e29a3`（含 `19a5c8a1f`）上，独立运行
`mvn -f resource-gateway-examples/pom.xml -Dtest=ApiConnectionSchemaReadinessTest test`，时间为
2026-08-30 18:48 +08、耗时 40.712 秒，结果为 **21/21 green**（`Failures: 0, Errors: 0, Skipped: 0`，
`BUILD SUCCESS`）。此前 `d4d57c5d6` 后的初始聚焦结果为 19/19，发生在后续语义变更之前；`eb3c40540` 与
`19a5c8a1f` 另有独立 `javac`/helper 检查，Spec 评审为 **Accepted**，P0/P1/P2 均为 0。共享主工作树
同时存在外部未提交 Connection/JDBC 编译错误，不能将其与上述隔离 HEAD 证据混同。真实 PostgreSQL lane
仍未运行；PG metadata 语义仅由 contract test 覆盖。

### 当前差距评估

本轮只完成 V003 schema/readiness 的 hardening；由于没有 production store 的聚焦最终 Maven 证据，不增加 broader goal 完成度，仍为 **35%**，当前差距为 **65%**。

## 14. Iteration 14 — J3-B1b Connection JDBC authoritative store 窄闭环

日期：2026-08-30。

### 已完成

- 新增 `JdbcApiConnectionCommitStore`，继续遵守窄接口：exact lease journal fencing、invisible staged
  revision、head/history CAS、安全 strong ETag、committed receipt integrity、scope-exact reads，以及
  transaction 内 revision/head/binding/journal 同步提交。
- secret 路径只持久化 opaque handle、slot、source mode、provider id 和 command lease expiry；commit 把
  pending lease 原子转换为 active binding 并删除 pending row。JDBC 专用 `failSecretLease` /
  `cleanupSecretLease` 只处理 exact pending/abort-required row；本实现不声称生产 Vault。
- `ApiConnectionSpec.restore`、metadata fingerprint 重算和 staged value equality 支持跨 package JDBC
  reconstruction 与 same-attempt replay；接口没有重新加入 claim/replay 泄漏。
- 新增 `JdbcApiConnectionCommitStoreTest`：继承 Connection store contract，并补充 pending lease
  invisibility/redaction、原子 activation、abort/cleanup、并发 head CAS、committed history 和 scope
  isolation。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='ApiConnectionCommitStoreContractTest,InMemoryApiConnectionCommitStoreTest,JdbcApiConnectionCommitStoreTest,ApiConnectionSchemaReadinessTest' \
  test -DfailIfNoTests=false

InMemoryApiConnectionCommitStoreTest: 19/19
JdbcApiConnectionCommitStoreTest: 25/25
ApiConnectionSchemaReadinessTest: 21/21
Results: Tests run: 65, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

以上 JDBC 证据来自 H2 `MODE=PostgreSQL` 和注入测试时钟；不能扩写为真实 PostgreSQL certification。
生产 Vault/provider activation、故障恢复演练、Facade、HTTP、full `clean verify` 和真实 PostgreSQL lane
仍未运行或未实现。共享工作树中的 frontend 与 PendingSecretStore 未提交修改不属于本窄切片证据。

### 当前差距评估

本轮完成 Connection JDBC store 的聚焦权威提交、读取与 durable pending-lease 语义；按本台账同一 broader
goal 口径，完成度由 **35%** 保守调整为 **37%**，当前差距为 **63%**。这不代表生产 secret provider、真实
PostgreSQL、Facade、HTTP、UI 或 broader 3% gap 已完成。

### 未实现边界

- 真实 PostgreSQL migration/certification、跨实例 CAS 演练和 full `clean verify` 未运行。
- 生产 Vault/provider lease activate/abort/recovery、PendingSecretStore 集成和故障注入闭环未接入。
- `AuthoringFacade`、HTTP controller、ETag/Idempotency transport 与 UI 尚未接入。

## 15. 未关闭风险

- JSON Schema 测试使用仓库内轻量语义校验器；运行时 DAG 环、Schema 路径兼容、Fixture Target 与
  `APPLY_CASE` 约束仍必须由 Java 模块测试证明。
- 生产部署仍需在 PostgreSQL lane 重放 V001/V002/V003 migration，并证明不可见 staging；不得用异步投影冒充
  成功 Receipt。
- API Resource 页面重载必须通过 Exact Subject Fixture summary 查询恢复，不能保存 material 到 Resource View
  或 Local Storage。
- 真实 PostgreSQL 尚未完成 migration、跨实例 CAS、失败恢复、Secret staging/activation 和三投影同
  generation certification；H2 PostgreSQL mode 不能替代该门禁。
- `AuthoringFacade` 还没有复合保存、幂等重放和注入故障测试；HTTP 的强 ETag、428/412、完整 Scope 与
  secret-free Problem Detail 也仍未实现。
- 共享工作树中的 `GraphNodeFixtureControls.tsx` 修改不属于本目标提交，必须继续隔离。

## 17. Iteration 16 — Final JDBC protocol review closure

日期：2026-08-31。

### 已完成

- JDBC Connection 的 child fence holder 按事务精确注册，并在 `afterCompletion` 只解绑本事务安装的
  holder；同一线程连续两次 child-only 失败后，后续合法 outer coordinator transaction 仍可提交。
- Resource `fail` 对已补偿的 `FAILED` attempt 重新锁定 journal → exact attempt，并校验完整 immutable
  authority（Scope、actor、endpoint、target、idempotency、request fingerprint、outer expected
  mode/revision、lease、attempt token/status）后才删除 exact stage。pending rows 存在时保持零变更；普通
  重复 fail 仍 fenced。takeover 仅删除无 pending/outcome/binding provenance 的 abandoned nested child
  stage，并保留可供恢复的历史 child。
- Connection committed receipt closure 绑定 endpoint：`API_RESOURCE_SAVE` 仅接受 canonical
  `bloge.apiResourceSaveReceipt.v1` 与 exact Resource authority；`API_CONNECTION_SAVE` 走 Connection
  receipt 分支。historical read、committed child/spec 和 outer receipt 对 exact attempt provenance
  要求恰好一行，重复候选 fail closed。
- 观察 seam 的证据边界已更正：observer 发生在候选选择后、任何 claim 写入前，异常只证明 claim 未发生，
  不宣称事务回滚。Facade、HTTP、UI 与真实 PostgreSQL certification 仍未验收。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=JdbcApiConnectionCommitStoreTest,JdbcPendingSecretStoreTest,\
JdbcApiResourceCommitStoreClaimTest,JdbcApiResourceCommitStoreMutationTest,\
PendingSecretStoreSchemaReadinessTest test -DfailIfNoTests=false

JdbcApiConnectionCommitStoreTest: 44/44
JdbcPendingSecretStoreTest: 32/32
JdbcApiResourceCommitStoreClaimTest: 11/11
JdbcApiResourceCommitStoreMutationTest: 23/23
PendingSecretStoreSchemaReadinessTest: 8/8
Tests run: 118, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

上述证据来自 H2 `MODE=PostgreSQL`、独立 JDBC connections 与注入测试时钟；它不替代真实 PostgreSQL
锁/隔离级别、Secret Provider、Facade、HTTP、UI 或 full `clean verify` 证据。生产默认 recovery observer
为 no-op。受保护的 frontend 文件和两份 reusable-flow 计划/评审文档不属于本轮提交。

## 18. Iteration 17 — JDBC Connection committed-read authority closure

日期：2026-08-31。

### 已完成

- JDBC Connection 的 committed head/revision 读取以 `command_id + attempt_no + attempt_token` 精确连接可变
  journal 与 immutable attempt，并要求两者的 endpoint/target 完全一致；`API_CONNECTION_SAVE` 额外要求
  authority target 与 child connection 相同。任何 journal 或 immutable attempt 的 authority 篡改均
  fail closed 为 `INTEGRITY`，干净的已提交读仍可见。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=JdbcApiConnectionCommitStoreTest,JdbcPendingSecretStoreTest,\
JdbcApiResourceCommitStoreClaimTest,JdbcApiResourceCommitStoreMutationTest,\
PendingSecretStoreSchemaReadinessTest test -DfailIfNoTests=false

JdbcApiConnectionCommitStoreTest: 46/46
JdbcPendingSecretStoreTest: 32/32
JdbcApiResourceCommitStoreClaimTest: 11/11
JdbcApiResourceCommitStoreMutationTest: 23/23
PendingSecretStoreSchemaReadinessTest: 8/8
Tests run: 120, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

该聚焦证据来自 H2 `MODE=PostgreSQL`、独立 JDBC connections 和测试时钟；随后 resource-gateway
`clean verify` 报告 `Tests run 7,942; failures 0; errors 0; skipped 33`。它不替代真实 PostgreSQL
锁/隔离级别、Secret Provider、Facade、HTTP 或 UI 验收。受保护的 frontend 文件和两份 reusable-flow
计划/评审文档不属于本轮提交。

## 16. Iteration 15 — Durable child publication and cross-store recovery closure

日期：2026-08-31。

### 已完成

- JDBC Connection 的 STAGED→COMMITTED、head/binding exact revision 操作和 committed historical reads
  均闭合 `command_id + attempt_no + attempt_token`；同一逻辑 revision 的旧 retained STAGED child 与新
  attempt 可并存，读取只暴露 exact committed attempt，补偿仍能删除 exact historical STAGED child。
- `commitChild` 只在 ambient coordinator transaction 中产生 provisional child state，并注册 before-commit
  fence 验证 exact outer journal/attempt authority；child-only transaction 会回滚。Resource `fail` 在
  exact pending-secret rows 存在时保持零变更，补偿 terminalize 后仅清理 exact FAILED Resource stage，
  不复活或改写 journal。
- Resource 与 Pending 当前/恢复路径遵守 journal → immutable attempt → connection identity/revision/head /
  binding → pending 的统一锁序。包级测试 observer 只用于在候选选择与 claim 间建立确定性屏障；注入异常
  发生在任何 claim 写入前，因此只证明候选未被改动，而不是事务回滚。生产默认是 no-op。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=JdbcApiConnectionCommitStoreTest,JdbcPendingSecretStoreTest,\
JdbcApiResourceCommitStoreClaimTest,JdbcApiResourceCommitStoreMutationTest \
  test -DfailIfNoTests=false

Tests run: 104, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

该聚焦证据来自 H2 `MODE=PostgreSQL`、独立 JDBC connections 和测试时钟；它证明不了真实 PostgreSQL
锁/隔离级别、生产 Secret Provider、Facade、HTTP、UI 或 full `clean verify`。这些边界仍保持未验收，且
`GraphNodeFixtureControls.tsx` 与两份 reusable-flow 评审/计划文档的并行修改不属于本切片。

## 19. Iteration 18 — J3-C1 standalone Connection application tracer

日期：2026-08-31。

### 已完成

- `9f367f52d` 抽出只负责 claim 的 `AuthoringCommandClaimStore`，并让 Resource store 复用该 seam；同一
  idempotency 坐标的 request fingerprint 与 outer expected revision 均必须精确一致。
- `3a2f6a908` 增加 JDBC/InMemory 一致的 `findRevisionByStrongEtag`：staged revision 不可见，旧 tag 可在
  head 前进后解析，Scope/Connection 不匹配返回 empty，非法或重复 committed provenance fail closed。强
  validator 复用单一安全子集校验。
- `753037e2b` 增加 `ApiConnectionAuthoringStore` 生命周期完整 seam；其 JDBC adapter 在构造时由一个
  `DataSource` 同时建立 claim 与 Connection delegate，避免 facade 注入两个可能 split-brain 的独立 store；
  InMemory Connection adapter 的 claim journal 与 stage/commit 共用同一状态。
- `c66bbb702` 增加 `ApiConnectionAuthoringFacade` 及最小 request/result/failure/precondition 类型；
  `166fa7199` 收紧生命周期 claim seam、配置化 mapper、失败清理与重试分类。纯
  decision validation 在 ETag lookup、fingerprint 和 claim 之前执行；当前只接受 `Auth.None`，拒绝 credential
  capability 时不访问 claim/store，fingerprint 只包含显式非敏感 authority 字段。Replay 先按 receipt 自身
  strong ETag 精确读取历史 Connection，再校验 view/schema/target/revision closure；Acquired 发生 stage 或
  commit 失败时按 exact lease cleanup。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ApiConnectionCommitStoreContractTest,InMemoryApiConnectionCommitStoreTest,JdbcApiConnectionCommitStoreTest,\
ApiConnectionAuthoringFacadeTest,JdbcApiConnectionAuthoringFacadeTest test -DfailIfNoTests=false

InMemoryApiConnectionCommitStoreTest: 28/28
JdbcApiConnectionCommitStoreTest: 50/50
ApiConnectionAuthoringFacadeTest: 10/10
JdbcApiConnectionAuthoringFacadeTest: 1/1
Tests run: 89, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

其中 `JdbcApiConnectionAuthoringFacadeTest` 使用 V001–V010 的 H2 `MODE=PostgreSQL` schema 和同一
`DataSource` claim/Connection adapter，证明 create 与 same-key exact replay；该证据不等价于真实 PostgreSQL
锁/隔离级别 certification。随后串行 `mvn -f resource-gateway-examples/pom.xml clean verify` 报告
`Tests run 7,960; Failures: 0; Errors: 0; Skipped: 33`，并以 `BUILD SUCCESS` 结束。两项证据均不接受
credential provider、API Resource 默认 Fixture、HTTP/controller transport、Facade/UI 全链路或 UI；后续
transport 仍需单独验证。

## 20. Iteration 19 — C1 application review-fix closure

日期：2026-08-31。

### 已完成

- Facade 不再接收或持有外部 `ObjectMapper`；公开构造只有生命周期完整的
  `ApiConnectionAuthoringStore`。Replay closure 由 InMemory/JDBC store 使用各自的 canonical mapper 完成，
  并精确连接 `command_id + attempt_no + attempt_token`、endpoint、target、schema、body、fingerprint 和
  strong ETag。这样不能用不匹配的 facade mapper 伪造 replay，且历史 committed revision 的 exact read
  仍与当前 head 解耦。
- Claim failure 已收敛为不暴露 Resource-specific exception 的通用类型；`LEASE_FENCED` 映射为
  `LEASE_LOST`，`LEASE_EXPIRED` 映射为带权威 `retryAt` 的 retryable busy，实际 CAS mismatch 保留为
  `CAS_MISMATCH`。Auth.None 之外的 capability 在 fingerprint/claim 前拒绝；fingerprint 只覆盖显式、非敏感
  authority 字段。
- Acquired 之后的 cleanup failure 不再静默吞掉：原始失败仍保持 payload-free，cleanup 异常映射为 typed
  persistence/integrity failure。Strong ETag 使用单一 application-safe subset validator；`W/"etag"` 等
  实际 weak/list/unquoted 形状继续 fail closed。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=InMemoryApiConnectionCommitStoreTest,JdbcApiConnectionCommitStoreTest,\
ApiConnectionAuthoringFacadeTest,JdbcApiConnectionAuthoringFacadeTest,ApiConnectionAuthorityTest \
  test -DfailIfNoTests=false

InMemoryApiConnectionCommitStoreTest: 28/28
JdbcApiConnectionCommitStoreTest: 50/50
ApiConnectionAuthoringFacadeTest: 14/14
JdbcApiConnectionAuthoringFacadeTest: 1/1
ApiConnectionAuthorityTest: 15/15
Tests run: 108, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

mvn -f resource-gateway-examples/pom.xml clean verify
Tests run: 7,965, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

聚焦证据来自 H2 `MODE=PostgreSQL`，其中 JDBC facade 使用同一 `DataSource` 与 V001–V010 schema；full
verify 仍是本地资源网关构建证据，不替代真实 PostgreSQL lock/isolation certification。Facade 目前只覆盖
Auth.None 的 Connection create/update/replay tracer；credential provider、Default Fixture、HTTP/controller、
UI 和生产 side-effect 尚未验收。受保护的 frontend 文件与两份 reusable-flow 计划/评审文档不属于本轮提交。

## 21. Iteration 20 — C1 final P2 closure

日期：2026-08-31。

### 已完成

- Acquired 路径的 `LEASE_EXPIRED` 映射为 `BUSY`，并携带该 acquired lease 的权威 `leaseUntil`；claim
  返回的 `ClaimResult.Busy` 仍直接保留其权威 `retryAt`。Cleanup 不再把错误扁平化：`INTEGRITY`、
  `PERSISTENCE`、`LEASE_FENCED` 和 `LEASE_EXPIRED` 分别映射到对应的 application category，并在过期
  情形保留 acquired lease deadline。
- `ApiConnectionAuthoringStore` 只保留 facade 实际需要的 head、ETag lookup、replay closure 与生命周期
  操作；未使用的 revision lookup 已从该窄 seam/JDBC wrapper 移除。JDBC abandoned nested-stage cleanup
  由 package-private `JdbcAuthoringAttemptCleanup` 统一承载，claim store 只在持有同一 journal transaction
  时协调 delegated cleanup，Resource takeover 复用同一规则。
- InMemory 与 JDBC facade integration 均直接调用真实 store 的 `resolveReplay`，对可构造的错误 schema、
  canonical body、strong ETag 和 target authority fail closed 为 typed `INTEGRITY`；receipt 构造器同时
  拒绝不一致的 body fingerprint。该证据不依赖 mock 或 source-text assertion。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=InMemoryApiConnectionCommitStoreTest,JdbcApiConnectionCommitStoreTest,\
ApiConnectionAuthoringFacadeTest,JdbcApiConnectionAuthoringFacadeTest,ApiConnectionAuthorityTest,\
JdbcApiResourceCommitStoreClaimTest,JdbcApiResourceCommitStoreMutationTest \
  test -DfailIfNoTests=false

ApiConnectionAuthorityTest: 15/15
InMemoryApiConnectionCommitStoreTest: 28/28
JdbcApiConnectionCommitStoreTest: 50/50
ApiConnectionAuthoringFacadeTest: 19/19
JdbcApiConnectionAuthoringFacadeTest: 1/1
JdbcApiResourceCommitStoreClaimTest: 11/11
JdbcApiResourceCommitStoreMutationTest: 23/23
Tests run: 147, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

mvn -f resource-gateway-examples/pom.xml clean verify
Tests run: 7,970, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

聚焦与 full verify 均为本地 H2/资源网关证据；它们不替代真实 PostgreSQL lock/isolation certification、
credential provider、HTTP/controller transport、Default Fixture 或 UI acceptance。受保护的 frontend 文件与
两份 reusable-flow 计划/评审文档不属于本轮提交。

## 22. Iteration 21 — C2 Resource application tracer and exact Connection snapshot

日期：2026-08-31。

### 已完成

- `b10faa94b` 增加 V011 与 Resource revision 的 exact Connection snapshot：`connection_id + revision +
  metadata_fingerprint` 同时进入 Resource revision 和 projection-set fingerprint。V001-V010 的历史 Resource
  没有该事实，V011 不用迁移时当前 Connection head 伪造 provenance，而是对遗留行 fail closed。
- 新增 `ApiResourceAuthoringFacade`、transport-neutral request/result/failure/precondition 和冻结的
  `bloge.apiResourceSaveCommand.v1` wrapper。当前仅接受 `EXISTING`、已提交、`Auth.NONE` Connection 与
  `defaultFixture.kind=NONE`；Connection `CREATE` 和 `FROM_EXAMPLES` 在 fingerprint/claim 前明确返回
  `CAPABILITY_UNAVAILABLE`，不静默忽略。
- create/update 以 opaque strong ETag 解析 exact historical Resource revision；same-key replay 先按 receipt
  strong ETag 读取 committed authority，再闭合 receipt、Resource revision/fingerprint 和 Connection snapshot，
  因此 head 前进后仍可重放。新 stale key 继续由 stage CAS fail closed。
- 新增 Connection-store projection resolver 与 feature-scoped application configuration。编译器只能读取
  payload-free Connection metadata；Facade 还会校验编译 snapshot 与 preflight Connection authority 完全一致。
  缺显式 `ApiConnectionAuthoringStore` 时 opt-in context 启动失败，没有隐藏的 in-memory/provider fallback。
- H2 same-database integration 通过真实 Connection facade 建立 Connection，再完成 Resource create/replay，
  验证 V011 列、无残留 STAGED row，并对 receipt 中 Connection revision 篡改后重算 fingerprint 仍 fail closed。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ApiResourceAuthoringFacadeTest,JdbcApiResourceAuthoringFacadeTest,\
ApiResourceAuthoringApplicationConfigurationTest,ApiResourceCommitStoreContractTest,\
JdbcApiResourceCommitStoreClaimTest,JdbcApiResourceCommitStoreMutationTest,\
DefaultApiResourceProjectionCompilerTest,ApiResourceConnectionSnapshotSchemaReadinessTest,\
ApiResourceAuthoringRuntimeConfigurationTest,ApiResourceAuthoringSchemaReadinessTest,\
VisualRuntimeBoundaryTest test

Tests run: 100, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

mvn -f resource-gateway-examples/pom.xml clean verify
Tests run: 7,989, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

该证据来自本地 H2 `MODE=PostgreSQL` 与 in-memory reference store；它不替代真实 PostgreSQL
lock/isolation certification。HTTP/controller、trusted auth scope、Problem Detail、credential provider、nested
Connection create、Default Fixture Set、Flow/DAG facade 与 UI acceptance 仍未实现/验收。受保护的
`GraphNodeFixtureControls.tsx` 与两份未跟踪 reusable-flow 文档不属于本切片。

## 23. Iteration 22 — C3 API Resource HTTP transport

日期：2026-08-31。

### 已完成

- 新增 feature-scoped `PUT /api/authoring/resources/{resourceId}` 薄适配器；默认关闭，仅在
  `gateway.authoring.api-resource.enabled=true` 时注册。Controller 只负责认证、可信 scope、HTTP 条件、
  严格 body 解码和调用一次 `ApiResourceAuthoringFacade`，不复制 application 编排。
- `IntegrationOperation.AUTHORING_API_RESOURCE_WRITE` 要求 purpose `API_RESOURCE_AUTHORING`。tenant、project、
  environment 与 actor 仅来自 `IntegrationRequestAuthenticator` 的 verified context；自报 scope 漂移在
  facade/store 前拒绝。
- create 只接受 `If-None-Match: *`，update 只接受一个 opaque strong `If-Match`；缺少条件返回 428，weak、
  list、wildcard update 与同时携带两类条件均 fail closed。`Idempotency-Key` 限制为 1–160 字符的稳定安全子集。
- 成功响应返回 canonical receipt body，并携带 `ETag`、`Idempotency-Replayed`、`Cache-Control: no-store` 与
  `Pragma: no-cache`。请求采用 application `ObjectMapper` 的 strict copy，root/nested unknown field、malformed
  JSON 和非 `application/json` 都在 facade 前拒绝。
- 新增统一 `problem-detail-v1` transport。认证、请求边界与 application failure 均返回同一字段集合；schema
  显式覆盖 415、500、503，避免 auth/业务/媒体类型错误出现多套 JSON。错误 body 不包含 credential、
  persistence message 或业务 payload。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ApiResourceAuthoringFacadeTest,JdbcApiResourceAuthoringFacadeTest,\
ApiResourceAuthoringApplicationConfigurationTest,ApiResourceCommitStoreContractTest,\
JdbcApiResourceCommitStoreClaimTest,JdbcApiResourceCommitStoreMutationTest,\
DefaultApiResourceProjectionCompilerTest,ApiResourceConnectionSnapshotSchemaReadinessTest,\
ApiResourceAuthoringRuntimeConfigurationTest,ApiResourceAuthoringSchemaReadinessTest,\
VisualRuntimeBoundaryTest,ApiResourceAuthoringControllerTest,\
ApiResourceAuthoringTransportConfigurationTest,AuthoringProtocolSchemaTest test

Tests run: 141, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

mvn -f resource-gateway-examples/pom.xml clean verify
Tests run: 8,021, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

其中 Controller 28、transport configuration 2、protocol schema 11，C2 application/persistence 100。
当前 HTTP 入口仍只支持 `EXISTING`、已提交、`Auth.NONE` Connection 与 `defaultFixture.kind=NONE`；
Connection `CREATE`、credential provider、Default Fixture Set、Flow/DAG application facade、真实 PostgreSQL
certification 和 UI acceptance 均未实现/验收。受保护的 `GraphNodeFixtureControls.tsx` 与两份未跟踪
reusable-flow 文档不属于本切片。

## 24. Iteration 23 — C4 Connection HTTP transport and production wiring

日期：2026-08-31。

### 已完成

- 新增 feature-scoped `PUT /api/authoring/connections/{connectionId}` 与
  `GET /api/authoring/connections/{connectionId}`。两个入口只把 trusted integration identity 投影为
  `AuthoringScope`/actor；create/update 的 `If-None-Match`、strong `If-Match`、`Idempotency-Key`、ETag、replay
  marker 与 no-store 协议和 Resource authoring 一致。
- `IntegrationOperation.AUTHORING_API_CONNECTION_READ/WRITE` 统一要求 `API_RESOURCE_AUTHORING` purpose。
  无凭证、错误 purpose、自报 scope drift、weak/list ETag、双 precondition、非法 key、unknown field、malformed
  JSON 与缺失 content type 均在 facade/store 前拒绝。
- Connection 与 Resource transport 共用一个 Authoring Problem Detail mapper 和 trusted correlation attribute；
  malformed Connection 请求仍返回 Connection code，不退化为 Resource code。Credential capability 424 的
  response 不回显一次性 secret。
- 新增 opt-in Connection application/runtime configuration：一个 application `ObjectMapper` 创建共享
  `ApiConnectionDecisions`，一个 `DataSource` 创建 lifecycle-complete `JdbcApiConnectionAuthoringStore`。缺 store、
  schema 或非正 lease duration 时启动失败，没有 in-memory fallback。
- 新增 V010 production readiness：以只读方式验证 immutable attempt PK/journal FK、Connection revision exact
  attempt PK/FK、head exact provenance、`SUPERSEDED` status closure、recovery/head indexes 与非空 attempt 坐标。
  V009、缺 schema、改变 status closure 或 provenance index 均不能启用 runtime。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='ApiConnectionAuthoringFacadeTest,JdbcApiConnectionAuthoringFacadeTest,\
ApiConnectionAuthoringControllerTest,ApiConnectionAuthoringTransportConfigurationTest,\
ApiConnectionAuthoringConfigurationTest,ApiConnectionAuthorityTest,ApiConnectionSchemaReadinessTest,\
ApiResourceAuthoringControllerTest,ApiResourceAuthoringTransportConfigurationTest,\
ApiResourceAuthoringApplicationConfigurationTest,ApiResourceAuthoringRuntimeConfigurationTest,\
AuthoringProtocolSchemaTest,VisualRuntimeBoundaryTest' test

Tests run: 145, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

```text
mvn -f resource-gateway-examples/pom.xml clean verify
Tests run: 8,051, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

该证据含 H2 `MODE=PostgreSQL` 的 V001-V010 production wiring create/read，以及 Connection/Resource MockMvc
安全协议；它不替代真实 PostgreSQL lock/isolation certification。当前未实现 Connection list/check、production
credential provider、Default Fixture Set、首次模拟、Reusable Flow/DAG、Tool/Solution 对象页或 UI acceptance。

### 当前差距评估

按顶部九项用户可观察能力重新校准：Wire Schema 与 API Resource/Connection authority 已形成可运行的后端和
HTTP tracer，但用户侧对象页、Default Fixture/Simulation、Reusable Flow、Tool/Solution 和 DAG 编排仍是主要
缺口。当前累计完成度保守记为 **44%**，剩余差距 **56%**；不能把 145 个聚焦测试或既有 Visual Authoring
能力折算成新简化工作流已经完成。

## 25. Iteration 24 — C5 scoped Connection discovery

日期：2026-08-31。

### 已完成

- 新增 `GET /api/authoring/connections`，只使用 trusted integration identity 的
  tenant/project/environment 查询当前 Connection heads；结果按 `connectionId` 稳定排序。
- `ApiConnectionAuthoringStore` 与 metadata store 新增 scope-local `listHeads` 深接口。内存/JDBC 两个 adapter
  都排除 staged revision 和其他 scope；JDBC 列表复用单对象读取的 committed attempt、journal receipt、ETag、
  metadata fingerprint 与 canonical view 完整性校验。
- wire 继续只返回 payload-free `ApiConnectionView`；不返回 secret value、SecretRef、provider locator 或
  pending lease。列表也不把“已保存”错误表达成“网络可达”。
- `ConnectionCheckCommand.NETWORK_ONLY/SAFE_READ` 需要独立的 egress、timeout、审计与 credential resolution
  authority，本切片没有用任意 socket/HTTP 探测绕过该边界。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='ApiConnectionAuthoringFacadeTest,JdbcApiConnectionAuthoringFacadeTest,\
ApiConnectionAuthoringControllerTest,ApiConnectionAuthoringTransportConfigurationTest,\
ApiConnectionAuthoringConfigurationTest,InMemoryApiConnectionCommitStoreTest,\
JdbcApiConnectionCommitStoreTest,ApiConnectionAuthorityTest,ApiConnectionSchemaReadinessTest,\
AuthoringProtocolSchemaTest' test

Tests run: 186, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,055, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

该证据证明内存/JDBC 的 scope、顺序、staged invisibility 和 payload-free HTTP list；它不等于真实 PostgreSQL
lock/isolation certification，也不覆盖网络连通性检查、credential provider、Default Fixture、Simulation、
Reusable Flow/DAG、Tool/Solution 对象页或 UI acceptance。

### 当前差距评估

Connection 的创建、读取和选择列表已经具备最小后端操作链，但用户目标中的 Fixture/Simulation 与可复用
多 API DAG 仍未开始形成新 authoring surface。累计完成度保守调整为 **45%**，剩余差距 **55%**。

## 26. Iteration 25 — C6 governed Connection-check boundary

日期：2026-08-31。

### 已完成

- 冻结 `bloge.connectionCheckCommand.v1`：`NETWORK_ONLY` 与 `SAFE_READ` 使用严格 union；未知字段、credential
  注入和缺失坐标均不进入应用层。
- 冻结 payload-free `bloge.connectionCheckResult.v1`：只包含 exact Connection revision、总状态、code-only
  stages、耗时和 egress decision id/policy fingerprint。它不能承载响应 payload 或 credential。
- 新增 `POST /api/authoring/connections/{connectionId}:check`。适配器复用 trusted integration identity、统一
  Problem Detail 和 no-store 响应；不接受调用者自报 scope/actor。
- 新增 `ApiConnectionCheckGateway` 深接口。`NETWORK_ONLY` 只把 trusted scope/actor、exact committed
  Connection coordinate、validated base URI 和 bounded timeout 交给 provider；provider 负责 destination policy、
  DNS rebinding、TLS 和 durable egress audit。
- 默认 provider 明确 fail-closed 为 424，不会因保存或启用 authoring 隐式触网。`SAFE_READ` 在同 Connection
  READ_ONLY Resource + Simulation authorization/redaction seam 完成前同样返回 424。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='ApiConnectionAuthoringFacadeTest,JdbcApiConnectionAuthoringFacadeTest,\
ApiConnectionAuthoringControllerTest,ApiConnectionAuthoringTransportConfigurationTest,\
ApiConnectionAuthoringConfigurationTest,InMemoryApiConnectionCommitStoreTest,\
JdbcApiConnectionCommitStoreTest,ApiConnectionAuthorityTest,ApiConnectionSchemaReadinessTest,\
AuthoringProtocolSchemaTest,IntegrationRequestAuthenticatorTest' test

Tests run: 206, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,065, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

该证据证明 wire、exact committed revision lookup、trusted authority、provider injection、payload-free evidence
和默认 fail-closed 行为；它不证明任何具体 destination 已获准，也不等于 DNS/TLS/网络探测或 SAFE_READ
已经由 production provider 执行。

### 当前差距评估

Connection 对象的保存、读取、选择与显式 check extension point 已闭合，但默认 egress provider、credential
provider、Default Fixture、首次 Simulation、Reusable Flow/DAG、Tool/Solution 和对象页仍未完成。累计完成度
保守调整为 **46%**，剩余差距 **54%**。

## 27. Iteration 26 — C7 Default Fixture materialization authority

日期：2026-08-31。

### 已完成

- 新增 `FixtureSubjectRef`、`FixtureSetCommand`、`FixtureSetView`、`FixtureSetSummary` 与
  `FixtureSetSaveReceipt` Java wire authority，覆盖冻结的 exact Subject、Case、Target、Behavior、Material、
  fidelity 与状态词汇；JsonNode 和集合均防御性复制。
- 新增 `DefaultFixtureSetMaterializer`。它从一个 exact API Resource revision 的具名 examples 生成
  `PRIVATE_DRAFT` Fixture Set；请求顺序、Case input、Subject `RETURN`、inline output 和 receipt 中的
  exampleName→caseId 映射保持精确。
- 空、重复或未知 `exampleNames` 统一 fail closed；普通派生 ID 使用 `{resourceId}:r{revision}`。这修正了
  方案文字原先使用 `@`、却与冻结公共 identifier schema 冲突的问题；超长 Resource ID 使用 exact Resource
  fingerprint 派生的合法 ID，不放宽整个公共 ID 字符集。
- 生成的 Command、View、metadata-only Summary 与 Save Receipt 均直接经过现有 JSON Schema 校验与
  Jackson round-trip。Summary 不包含 input、material 或 output。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=DefaultFixtureSetMaterializerTest,AuthoringProtocolSchemaTest test

Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,069, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

该证据只证明 Default Fixture 的纯选择、物化和 wire closure；当前尚无 Fixture Set persistence/head、
Resource 复合事务、HTTP 查询/保存或 Simulation 编译/执行，不能据此宣称用户已经能保存或运行 Fixture。

### 当前差距评估

Default Fixture 已从“只有 schema”推进到可测试的后端物化权威，但用户主路径仍缺持久化、复合保存和首次
Simulation，Reusable Flow/DAG、Tool/Solution 与对象页也未闭合。累计完成度保守调整为 **47%**，剩余差距
**53%**。

## 28. Iteration 27 — C7 Default Fixture persistence and compound Resource save

日期：2026-08-31。

### 已完成

- 新增 V012 `rg_api_fixture_set_identities/revisions/heads`，把 Resource examples 物化出的
  `PRIVATE_DRAFT` Fixture Set 作为不可变私有 revision 持久化；staged revision 在 outer Resource receipt
  提交前不可见。
- 新增 in-memory/JDBC `ApiFixtureSetCommitStore`。JDBC stage、Fixture child commit 与 Resource commit 使用
  同一 DataSource/应用事务；outer receipt 提交后才 publish，失败时 Resource 与 Fixture child 一起回滚或
  精确清理。takeover 只保留 replacement attempt 的 staged child。
- `FROM_EXAMPLES` 在 claim 前验证具名 examples；保存时生成 Fixture，随后以 Fixture+Resource 复合事务提交。
  exact replay 重建并核对同一 Fixture authority，不重复创建 revision。
- committed read 同时闭合 Fixture row/head、immutable command attempt、outer Resource journal、精确 committed
  Resource subject revision 与 canonical `bloge.apiResourceSaveReceipt.v1`。authority 缺失、歧义或篡改返回
  `INTEGRITY`，不会伪装成对象不存在。
- `GeneratedDefaultFixture` 构造即重算完整 Fixture command fingerprint，Case input/output 同步篡改也不能绕过；
  Summary 仍只暴露 metadata。
- `ApiFixtureSetSchemaReadiness` 绑定当前 catalog/schema，验证 V012 columns、PK/FK/index 和 exact CHECK clause；
  同名弱约束、错误 schema 或不同 DataSource transaction manager 都在启动/提交前失败关闭。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='AuthoringProtocolSchemaTest,DefaultApiResourceProjectionCompilerTest,\
DefaultFixtureSetMaterializerTest,InMemoryApiFixtureSetCommitStoreTest,\
ApiFixtureSetSchemaReadinessTest,ApiFixtureSetRuntimeConfigurationTest,\
ApiResourceAuthoringFacadeTest,JdbcApiResourceAuthoringFacadeTest,\
ApiResourceAuthoringApplicationConfigurationTest,ApiResourceCommitStoreContractTest,\
ApiConnectionSchemaReadinessTest' test

Tests run: 98, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

其中 C7 schema/materializer/facade/store/configuration 为 **70/70**，共享 Connection readiness 回归为
**28/28**。随后串行执行完整门禁：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8090, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

本切片仍不声明 standalone Fixture HTTP、share、simulation compiler/runtime、Reusable Flow/DAG、
Tool/Solution 或对象页完成。

### 当前差距评估

Default Fixture 已闭合到 Resource 复合保存和可复核私有持久化，但用户还不能在独立对象页管理 Fixture，
也未完成首次 Simulation 与 Reusable Flow/DAG 主路径。累计完成度保守调整为 **49%**，剩余差距 **51%**。

## 29. Iteration 28 — C8 authenticated Fixture discovery and exact read

日期：2026-08-31。

### 已完成

- 新增 `ApiFixtureSetAuthoringFacade`，直接复用 V012 `ApiFixtureSetCommitStore`，读取 current head、精确
  immutable revision，并按一个完整 `FixtureSubjectRef` 返回 metadata-only summaries；不存在与 authority
  损坏使用互斥的 closed failure code。
- 新增 authenticated `GET /api/authoring/fixture-sets/{fixtureSetId}?revision={revision}` 与
  `GET /api/authoring/fixture-sets?subjectKind=...&subjectId=...&subjectRevision=...&subjectFingerprint=...`。
  Scope 只来自 verified integration identity，客户端不能提交 tenant/project/environment。
- 列表响应只包含 Case id/name；Case input、controls、expected 仅在精确私有 revision 读取中返回。两个入口
  都返回 `Cache-Control: no-store`，并复用统一 payload-free Authoring Problem Detail。
- 新增 feature-scoped application configuration；feature enabled 但缺 V012 store 时启动失败关闭。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ApiFixtureSetAuthoringFacadeTest,ApiFixtureSetAuthoringControllerTest,\
ApiFixtureSetApplicationConfigurationTest,ApiResourceAuthoringControllerTest,\
ApiConnectionAuthoringControllerTest test

Tests run: 63, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

随后串行执行完整门禁：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8101, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

本切片尚未实现 Fixture standalone PUT/share、Simulation application/runtime、Reusable Flow/DAG 或对象页。

### 当前差距评估

Default Fixture 已能通过标准 authenticated API 被精确读取和按 Subject 发现，但“选择 Case 后模拟并看到
REAL/MOCKED evidence”的用户闭环尚未开始。累计完成度保守调整为 **51%**，剩余差距 **49%**。

## 30. Iteration 29 — C8 Fixture Case simulation domain tracer

日期：2026-08-31。

### 已完成

- 新增与冻结 schema 一致的 `SimulationRequest` / `SimulationRun` Java wire model，覆盖 FIXTURE_CASE、AD_HOC、
  egress policy、node trust evidence 与四维 verdict；JSON round-trip 直接通过既有 schema validator。
- 新增 `SimulationModule`：按 trusted `AuthoringScope` 精确读取 Fixture revision、Case 与 API Resource revision，
  并校验 Fixture Subject 的 id/revision/fingerprint 全闭合。
- 当前仅编译可证明无网络副作用的 Subject `RETURN` + inline material。Case input 与返回 output 使用共享
  `VisualSchemaValidator` 对 Resource input/output contract 做真实校验；运行 evidence 明确为 MOCKED、INLINE、
  OUTPUT_LEVEL、FIXTURE attempted=false，assertion 与 governance 不被混为一个“Passed”。
- 新增 `SimulationRunStore` 与线程安全 reference implementation：同 scope + Idempotency-Key + request fingerprint
  精确 replay；不同请求冲突、执行中的命令 Busy；runId 按 scope 隔离，output 采用 defensive copy。
- AD_HOC、ALLOW_EXACT external read、内部 node control 与 governed Fixture material 当前均显式 UNSUPPORTED，
  不会退化成真实 egress 或伪造成功。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=SimulationModuleTest,AuthoringProtocolSchemaTest test

Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

本次是 domain/reference-store tracer；尚未增加 Simulation JDBC migration/readiness、认证 POST/GET、真实
external-read authorization，也未进入 Flow/DAG kernel。累计完成度保守调整为 **56%**，剩余差距 **44%**。

## 31. Iteration 30 — C8 durable authenticated Simulation tracer

日期：2026-08-31。

### 已完成

- 新增 V013 `rg_authoring_simulation_runs`，以 scope + runId 为主键、scope + Idempotency-Key 为唯一命令坐标，
  记录 request fingerprint、RUNNING lease、终态 run JSON 与开始/结束时间；状态与完成态由数据库 CHECK 关闭。
- 新增 `JdbcSimulationRunStore`：使用数据库时间判定 lease，支持精确 acquire/replay/conflict/busy/expired
  resume；完成写入和读取都校验 schemaVersion、runId、status 与持久化 authority，损坏或歧义统一 fail closed。
- 新增 `SimulationRunSchemaReadiness`：只读验证 V013 exact columns、PK、idempotency UQ、recovery index、状态
  literal set、fingerprint 和 RUNNING/terminal completion closure；缺失或弱化约束均阻止 feature 启动。
- 新增 feature-scoped runtime/application configuration。启用后缺 V013 store、Fixture/Resource authority或
  application dependency 时启动失败，不通过 `@ConditionalOnBean` 静默丢失 Simulation 能力。
- 新增认证 transport：
  - `POST /api/authoring/simulations` 要求 trusted `API_RESOURCE_AUTHORING` purpose 与 `Idempotency-Key`，返回
    exact `SimulationRun`、`X-Simulation-Run-Id` 和 replay receipt；
  - `GET /api/authoring/simulations/{runId}` 只在 verified tenant/project/environment scope 内返回 completed run；
  - 两条路均 `no-store`，不信任客户端自报 scope，也不返回 Fixture 之外的 protected material。
- 在 `IntegrationOperation` 中增加独立 execute/read operation，使认证、审计与用途仍由既有 integration
  authority 统一决定；业务/transport 错误继续投影为统一的 authoring Problem wire shape。

### 最新验证与证据边界

聚焦门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=SimulationModuleTest,JdbcSimulationRunStoreTest,SimulationRunSchemaReadinessTest,\
SimulationRunRuntimeConfigurationTest,ApiSimulationApplicationConfigurationTest,\
ApiSimulationControllerTest,AuthoringProtocolSchemaTest test

Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

最终串行全量门禁：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8122, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

首轮全量曾因一次 Embedded PostgreSQL 端口/SSL 就绪竞争出现 1 个环境 error；同一认证测试随后独立
1/1 green，最终 clean verify 未复现且全绿。该证据不被计作产品失败，也不被隐藏。

### 当前差距评估

用户现在可以通过后端认证 API 选择一个已持久化的私有 Fixture Case，幂等执行零 egress 模拟，并读取
持久化 `MOCKED`/contract/assertion/governance evidence。这关闭了单 API Resource 的首条 Fixture→Run 后端
主路径。仍未完成的是 `AD_HOC`、真实 external-read authorization、governed material、内部节点 control、
Reusable Flow/DAG 的保存与执行，以及前端对象页。累计完成度保守调整为 **62%**，剩余差距 **38%**；
下一主切片必须进入 Reusable Flow/DAG，而不是继续扩充单资源外围能力。

## 32. Iteration 31 — reusable Flow/DAG compiler authority

日期：2026-08-31。

### 已完成

- 新增与冻结 `bloge.reusableFlowSaveCommand.v1` 一致的 Java wire authority：`TOOL` / `SOLUTION`、input/output
  Contract、节点、布局、exact `API_RESOURCE` / `FLOW_VERSION` 引用，以及 `FLOW_INPUT` / `NODE_OUTPUT` /
  `CONSTANT` 三种 Mapping source。Jackson 判别字段与完整 command 直接通过仓库 JSON Schema validator 和
  round-trip；SchemaEnvelope 与常量 JSON 均做 defensive copy。
- 新增深模块 `ReusableFlowCompiler`。Mapping 是唯一业务边 authority；编译器精确解析每个依赖的 revision 与
  fingerprint，验证 direct `$` / `$.field` 路径、required target、重复 target、constant value、source/target
  schema compatibility 与 graph output，并由 Mapping 派生稳定拓扑序。
- 缺失或漂移 dependency、非法 command/layout、缺失 mapping、schema 不兼容与 cycle 均进入 closed、
  payload-free failure taxonomy，不会降级为任意 GraphDraft 或真实外部调用。
- `ComposableCatalog` 仅暴露 exact scoped dependency read，`CompiledReusableFlow` 成为后续 draft save、
  simulation 与 publish 共用的单一编译结果；本切片没有并行再造 edge、runtime 或 persistence 模型。

### 最新验证与证据边界

聚焦门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ReusableFlowCompilerTest,AuthoringProtocolSchemaTest test

Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

最终串行全量门禁：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8127, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

### 当前差距评估

Reusable Flow 已从“只有 JSON Schema”推进到可执行验证的 Java wire 与 deterministic DAG compile authority，
但用户尚不能保存、读取、发布或模拟一个 Tool/Solution，也没有对象页。因此累计完成度仅保守调整为
**65%**，剩余差距 **35%**。下一切片是 Flow Draft 的 revision/head、CAS/idempotency、authenticated PUT/GET
与 exact Resource catalog adapter；随后才是 whole-flow Fixture simulation、immutable publication 与前端对象页。

## 33. Iteration 32 — reusable Flow Draft reference authority

日期：2026-08-31。

### 已完成

- 新增 `ReusableFlowDraft` / `ReusableFlowSaveReceipt` / `ReusableFlowSaveResult`，服务端生成并闭合
  `flowId + stable draftId + revision + content fingerprint + strong ETag`。Draft 与 Receipt 直接通过冻结 JSON
  Schema round-trip；Draft 同时提供 exact `FLOW_DRAFT` Fixture/Simulation subject。
- 新增深模块 `ReusableFlowModule`：先 compile，再生成完整 request fingerprint 与排除 layout 的 content
  fingerprint，最后把 revision/head/idempotency/CAS 委托给一个 `ReusableFlowDraftStore`，HTTP adapter 不需要
  重做这些规则。
- 新增线程安全 reference store：create/update、历史 revision、scope/actor/key 隔离、same-key exact replay、
  changed intent conflict 与 stale CAS 均为原子行为。重放发生在 current-head CAS 之前；失败 CAS 不占 key。
- 无效 DAG 在 store/idempotency 之前被拒绝。layout-only 更新产生新 revision/ETag，但保持 content
  fingerprint；完整 command 仍进入 request fingerprint，因而不会把不同布局误当同一幂等请求。

### 最新验证与证据边界

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ReusableFlowModuleTest,ReusableFlowCompilerTest,AuthoringProtocolSchemaTest test

Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

最终串行全量门禁：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8132, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

本切片尚未增加 V014/JDBC/readiness、production exact Resource catalog adapter、认证 PUT/GET、publication、
whole-flow Fixture simulation 或对象页。因此累计完成度仅保守调整为 **67%**，剩余差距 **33%**；下一刀
必须把相同 `ReusableFlowDraftStore` contract 落到 JDBC 并接入 transport，不能把 in-memory green 当生产闭环。

## 34. Iteration 33 — durable reusable Flow Draft authority

日期：2026-09-01。

### 已完成

- 新增 V014 Flow Draft authority：scoped identity、immutable revision、exact head 与 committed idempotency
  command。稳定 `draftId` 由服务端创建；head 通过 revision、draftId、content fingerprint、strong ETag 的
  exact foreign key 绑定不可变 revision。
- 新增 `JdbcReusableFlowDraftStore`。一次本地数据库事务闭合 revision/head/receipt/command；先按
  scope+actor+flowId+idempotency key 做 exact replay，再锁 current head 做 create/update CAS。并发同 key
  create 只有一个 commit，另一方取得同一 committed replay；失败 CAS 不占用 key。
- committed read 对 draft JSON、receipt、flow/draft/revision、content fingerprint 与 ETag 做完整闭包。
  head 已存在但 exact revision 关联损坏时返回 `INTEGRITY`，不会伪装成 not found。
- 新增只读 `ReusableFlowDraftSchemaReadiness` 与默认关闭的 feature-scoped runtime configuration。启用时
  缺 V014、主键、exact head FK 或 command expectation closure 均阻止启动；运行时不创建或修复 schema。

### 最新验证与证据边界

聚焦门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ReusableFlowCompilerTest,ReusableFlowModuleTest,JdbcReusableFlowDraftStoreTest,\
ReusableFlowDraftSchemaReadinessTest,ReusableFlowDraftRuntimeConfigurationTest,\
AuthoringProtocolSchemaTest test

Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

最终串行全量门禁：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8143, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

readiness 与 JDBC persistence 证据来自 H2 PostgreSQL mode；尚未冒充真实 PostgreSQL 认证。

### 当前差距评估

Flow Draft 已具有生产 JDBC revision/head/CAS/idempotency authority，但尚无 exact API Resource catalog、
认证 PUT/GET、immutable Flow Version publication、whole-flow Fixture simulation 或 Tool/Solution 对象页。
累计完成度保守调整为 **69%**，剩余差距 **31%**。下一刀必须交付 production exact Resource catalog 与
authenticated Flow PUT/GET；随后再进入 immutable publication、whole-flow Fixture simulation 与对象页。

## 35. Iteration 34 — authenticated reusable Flow authoring

日期：2026-09-01。

### 已完成

- 为 `ReusableFlowDraftStore` 增加 exact stored authority：draft、receipt、strong ETag 的 head、revision 与
  historical ETag lookup。in-memory 与 JDBC adapter 均保持 revision-exact read；JDBC 继续校验持久化
  draft/receipt/fingerprint/ETag 闭包。
- `ReusableFlowModule` 接受 HTTP-neutral `Create | MatchStrongEtag` precondition。已提交 update 的历史 ETag
  replay 在 current-head CAS 前恢复；同一旧 ETag 配新 idempotency key 则按 current head 失败，不占用 key。
- 新增 production `ApiResourceComposableCatalog`，仅从 `ApiResourceCommitStore` 解析 exact committed
  API Resource id/revision/fingerprint 与 input/output schema。`FLOW_VERSION` 在 publication authority 落地前
  明确 fail-closed。
- 新增默认关闭的 application configuration，以及认证的
  `PUT /api/authoring/flows/{flowId}`、`GET /api/authoring/flows/{flowId}?revision=...`。scope/actor 仅来自
  `IntegrationRequestAuthenticator`；create、update、Idempotency-Key、opaque strong ETag、replay、no-store 与
  problem detail 均由薄 transport 显式闭合。

### 最新验证与证据边界

聚焦门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ReusableFlowCompilerTest,ReusableFlowModuleTest,JdbcReusableFlowDraftStoreTest,\
ReusableFlowDraftSchemaReadinessTest,ReusableFlowDraftRuntimeConfigurationTest,\
ApiResourceComposableCatalogTest,ReusableFlowAuthoringControllerTest,\
ReusableFlowAuthoringApplicationConfigurationTest,AuthoringProtocolSchemaTest test

Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

最终串行全量门禁：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8151, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

JDBC/readiness 证据仍来自 H2 PostgreSQL mode；本轮没有冒充真实 PostgreSQL 认证。HTTP 测试证明 trusted
scope/actor、严格 precondition/idempotency、exact receipt/ETag/replay 与请求前拒绝，但尚不是浏览器对象页验收。

### 当前差距评估

Flow 已闭合 wire、DAG compile、durable Draft authority、exact API Resource catalog 与认证保存/读取主路径。
尚缺 immutable Flow Version publication、`FLOW_VERSION` catalog resolution、whole-flow Fixture Set/Case
simulation，以及面向用户的 Tool/Solution 对象页与真实端到端验收。累计完成度保守调整为 **73%**，剩余
差距 **27%**。下一刀先交付 immutable Flow Version publication 与 catalog 闭包，再进入 whole-flow Fixture
simulation；UI 不与 authority migration 混在同一提交。

## 36. Iteration 35 — immutable reusable Flow publication

日期：2026-09-01。

### 已完成

- 新增 V015 publication authority：稳定 publication identity、append-only immutable version 与 committed
  publish command。版本以 exact Flow Draft id/revision/content fingerprint 作为来源坐标；相同 Flow 的版本号
  单调递增，并发发布通过锁定 Flow identity 分配不同版本。
- 新增 in-memory 与 JDBC `ReusableFlowPublicationStore`。same-key exact replay 返回同一 receipt；changed
  intent conflict；JDBC read/replay 对 source draft、version JSON、fingerprint、receipt、scope、actor 与 command
  provenance 做完整闭包。
- `ReusableFlowModule.publish` 在任何幂等写入前读取并重新编译 exact Draft，拒绝 draft id、revision、content
  fingerprint 或 dependency 漂移。发布版本快照 TOOL/SOLUTION 业务图、schema、mapping 与依赖坐标，不包含
  editor layout；来源 Draft 坐标仍进入 version fingerprint，因此 lineage 不会被 layout-only 新 revision 混淆。
- `ApiResourceComposableCatalog` 已支持 exact committed `FLOW_VERSION`，可把已发布 Flow 作为另一个 Tool 或
  Solution 的依赖节点；不存在、revision/fingerprint 漂移或损坏 authority 均 fail-closed。
- 新增认证 `POST /api/authoring/flows/{flowId}:publish`。scope/actor 仅来自 trusted integration identity，
  请求必须携带 bounded `Idempotency-Key`；响应返回 exact receipt、`Idempotency-Replayed` 与 no-store。
- 新增默认关闭的 publication runtime configuration 与 read-only V015 readiness；缺 table、PK、exact FK、
  command/version closure 或 `PUBLISHED` 状态约束会阻止启动，运行时不创建或修复 schema。

### 最新验证与证据边界

聚焦门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ReusableFlowCompilerTest,ReusableFlowModuleTest,JdbcReusableFlowDraftStoreTest,\
ReusableFlowDraftSchemaReadinessTest,ReusableFlowDraftRuntimeConfigurationTest,\
InMemoryReusableFlowPublicationStoreTest,JdbcReusableFlowPublicationStoreTest,\
ReusableFlowPublicationSchemaReadinessTest,ReusableFlowPublicationRuntimeConfigurationTest,\
ApiResourceComposableCatalogTest,ReusableFlowAuthoringApplicationConfigurationTest,\
ReusableFlowAuthoringControllerTest,AuthoringProtocolSchemaTest test

Tests run: 58, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

最终串行全量门禁：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8166, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

JDBC/readiness 证据来自 H2 PostgreSQL mode；本轮未冒充真实 PostgreSQL certification。发布 HTTP 与 catalog
测试证明 exact authority、trusted scope/actor、幂等重放和版本依赖解析，但还不是用户对象页或 whole-flow
simulation 的真实浏览器验收。

### 当前差距评估

Flow 已闭合 wire、deterministic DAG compile、durable Draft、认证保存/读取、immutable publication 与 exact
`FLOW_VERSION` catalog resolution。尚缺 whole-flow Fixture Set/Case、Flow simulation execution/evidence，
以及面向用户的 Tool/Solution 对象页和真实端到端验收。累计完成度保守调整为 **78%**，剩余差距
**22%**。下一刀进入 whole-flow Fixture simulation；UI 不与 simulation authority migration 混在同一提交。

## 37. Iteration 36 — whole-flow Fixture simulation authority

日期：2026-09-01。

### 已完成

- 新增 `WholeFlowFixtureMaterializer`：只接受 exact `FLOW_VERSION` Subject，以及每个 Case 唯一的
  `SUBJECT + RETURN/INLINE` control。Node control、nested `APPLY_CASE`、REAL、受保护 asset 和
  protocol/transport fidelity 均 fail-closed，避免递归 Fixture 图或静默执行内部依赖。
- materializer 在产生 Fixture authority 前，以 immutable Flow Version 的 input/output contract 校验 Case
  input、Return material 与 optional expected output；subject id/revision/fingerprint 漂移直接拒绝。
- `SimulationModule` 保持既有 API Resource 路径不变，同时可选接入 `ReusableFlowPublicationStore`。运行
  whole-flow Case 时读取 exact published version，输出 subject-level `SIMULATED_ONLY` 证据与空 node list，
  明确证明内部 API Resource 和子 Flow 都没有执行。
- Spring application configuration 仅在 publication authority 存在时启用 Flow 路径；Resource-only
  deployment 保持原有装配，不因为 Flow feature 未启用而启动失败。
- frozen `SimulationRun` wire 新增 FLOW_VERSION + empty nodes round-trip 证据；无 protected material 或
  graph snapshot 被写入运行结果。

### 最新验证与证据边界

聚焦门禁：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=WholeFlowFixtureMaterializerTest,WholeFlowSimulationModuleTest,SimulationModuleTest,\
ApiSimulationApplicationConfigurationTest,AuthoringProtocolSchemaTest test

Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

最终串行全量门禁：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8171, Failures: 0, Errors: 0, Skipped: 33
BUILD SUCCESS
```

该切片证明 pure materialization 与 execution semantics，尚未把 Flow Fixture 独立保存到 JDBC，也没有认证的
Fixture PUT。不能把测试中构造的 `StoredFixtureSet` 当 production persistence 证据。

### 当前差距评估

后端已能定义和执行 exact published Flow 的 subject-level Whole-flow Fixture，且明确不执行内部节点；但 durable
standalone Fixture authority、authenticated write/read、parent Flow `NODE + APPLY_CASE` 编译/运行、对象页和真实
端到端仍未完成。累计完成度保守调整为 **81%**，剩余差距 **19%**。下一刀先交付 standalone Flow Fixture
JDBC/HTTP vertical slice，再实现父 Flow `APPLY_CASE`；UI 继续独立收口。

## 38. Iteration 37 — durable standalone Flow Fixture authoring

日期：2026-09-01。

### 已完成

- 新增 V016 standalone Fixture authority，独立于 V012 Resource child 表：scope 内 identity、append-only revision、
  exact head、strong ETag 与 endpoint-isolated idempotency command 在同一事务关闭；revision 以外键绑定 exact
  immutable Flow Version。
- 新增 `StandaloneFixtureSetStore` 的 InMemory/JDBC 实现，以及 create/update CAS、historical ETag、exact replay、
  changed replay conflict、scope isolation、authority tamper fail-closed 和 payload-free subject listing。
- `WholeFlowFixtureMaterializer` 支持服务端指定正 revision；既有 Resource Default Fixture 仍产生 revision 1，
  Flow Fixture update 则产生单调 revision，不改变 Case/subject/fingerprint closure。
- 新增 `FixtureSetAuthorityReader` 与 fail-closed composite reader。V012 Resource child 和 V016 standalone Flow
  Fixture 共用既有 GET/list/Simulation 读取路径；同 scope/id 若同时出现在两个 authority 中则按 integrity 拒绝，
  不任意选一个。
- `PUT /api/authoring/fixture-sets/{fixtureSetId}` 使用 trusted tenant/project/environment/actor，新增精确 write
  purpose；create 必须 `If-None-Match: *`，update 必须单个 opaque strong `If-Match`，并要求 bounded
  `Idempotency-Key`。成功响应是 exact receipt + ETag + `Idempotency-Replayed` 且 no-store。
- feature-scoped V016 runtime 默认随 reusable-flow feature opt-in，readiness 缺表时 fail startup；未把配置继续
  塞入全局 `GatewayConfiguration`。

### 聚焦验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=WholeFlowFixtureMaterializerTest,ReusableFlowFixtureModuleTest,\
CompositeFixtureSetAuthorityReaderTest,JdbcStandaloneFixtureSetStoreTest,\
StandaloneFixtureSetSchemaReadinessTest,StandaloneFixtureSetRuntimeConfigurationTest,\
ApiFixtureSetAuthoringFacadeTest,ApiFixtureSetAuthoringControllerTest,\
ApiFixtureSetApplicationConfigurationTest,SimulationModuleTest,WholeFlowSimulationModuleTest,\
ApiSimulationApplicationConfigurationTest,AuthoringProtocolSchemaTest test

Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

完整 Resource Gateway 门禁：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,182; Failures: 0; Errors: 0; Skipped: 33
BUILD SUCCESS
```

### 当前差距评估

Standalone whole-flow Fixture 已可经可信 HTTP 写入、强 ETag 更新、JDBC 持久化、复用既有读取并直接进入
subject-level Simulation；这关闭了 Iteration 36 最大的 production gap。尚缺父 Flow 的 exact
`NODE + APPLY_CASE` 编译/运行、Fixture share/promotion、Tool/Solution/Fixture 对象页和真实浏览器/真实
PostgreSQL 端到端。累计完成度保守调整为 **85%**，剩余差距 **15%**；下一刀进入父 Flow 对子 Flow Case
的显式替代语义，再进入对象页，不扩充隐藏控制面。

## 39. Iteration 38 — parent Flow `NODE + APPLY_CASE`

日期：2026-09-01。

### 已完成

- 新增 `ParentFlowApplyCaseCompiler` 作为唯一父 Flow Fixture 编译入口。每个父节点必须恰好有一个显式
  `NODE + APPLY_CASE`；引用坐标固定为 Fixture Set id + revision + Case id。
- 编译器通过 exact catalog 和 Fixture authority 验证：引用 Case 的 Subject 必须等于目标节点的精确
  API Resource / Flow Version，且引用 Case 只能包含一个终止型 `SUBJECT + RETURN/INLINE`。Node control、
  `REAL` 或再次 `APPLY_CASE` 均 fail-closed，因此不存在递归 Fixture 图。
- 父 Case 输入按父 Flow 的 `FLOW_INPUT` / `NODE_OUTPUT` / `CONSTANT` Mapping 计算每个节点输入并逐节点
  校验 contract；引用 Case 保存的 input 不参与父 Flow 执行。父输出继续由 graph output selection 唯一导出。
- Simulation 只消费编译结果：节点全部记录为 `MOCKED + APPLY_CASE`；API 节点继承引用 Case fidelity，
  子 Flow 只允许 output-level；全程不展开子 Flow、不访问网络。执行结论为 `PASSED_WITH_MOCKS`，contract、
  assertion、governance 仍分别记录。
- standalone Flow Fixture 保存会在占用 idempotency coordinate 前调用同一编译器；runtime/configuration
  将 compiler 同时交给保存与 Simulation，避免 HTTP、workspace 与 runtime 各自解释一份合并规则。

### 聚焦验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ParentFlowApplyCaseCompilerTest,WholeFlowFixtureMaterializerTest,\
ReusableFlowFixtureModuleTest,StandaloneFixtureSetRuntimeConfigurationTest,\
ApiFixtureSetApplicationConfigurationTest,ApiFixtureSetAuthoringFacadeTest,\
ApiFixtureSetAuthoringControllerTest,SimulationModuleTest,WholeFlowSimulationModuleTest,\
ApiSimulationApplicationConfigurationTest,JdbcStandaloneFixtureSetStoreTest,\
AuthoringProtocolSchemaTest test

Tests run: 51, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

完整 Resource Gateway 门禁：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,187; Failures: 0; Errors: 0; Skipped: 33
BUILD SUCCESS
```

### 当前差距评估

后端现在具备“外部 API Resource → 可复用 Flow DAG → exact Fixture Case → 父 Flow APPLY_CASE Simulation”的
最小完整语义链，且不会把引用 Case 的展示 input 错当父节点 input。仍未实现部分控制下的本地/真实节点执行、
Fixture share/promotion、Tool/Solution/Fixture 对象页以及真实 UI/PostgreSQL acceptance。累计完成度保守调整为
**89%**，剩余差距 **11%**；下一刀进入对象页 API/前端主路径和真实浏览器验收，再单独处理 share/promotion，
不把这些边界隐藏在当前编译器里。

## 40. Iteration 39 — API Resource 对象页与保存即模拟

日期：2026-09-01。

### 已完成

- 新增 `/workbench/` 懒加载对象工作台。首页只显示批准的三个入口：接入 API、创建工具、创建方案；Fixture
  与 Simulation 不再成为新的顶层 Studio。
- API Resource 对象页把普通作者输入压缩为名称、现有 Connection ID、Method/Path、请求样例和响应样例。
  前端只为已批准的 flat object example 推导 first-level Schema 和 Query Mapping；数组、null、非法字段名和
  非对象样例在请求前拒绝，不把 Descriptor、Design Contract、Operator Ref 或 Fixture JSON 暴露给用户。
- “保存并模拟”只发送一个 `bloge.apiResourceSaveCommand.v1`：服务端原子保存 Resource 与
  `FROM_EXAMPLES` 私有 Default Fixture；随后前端只按回执中的 Fixture Set revision + Case ID 发出一个
  deny-all Simulation。页面分别展示 Design、Fixture、Simulation、Versions，四维 verdict 保持独立。
- 新增 `GET /api/authoring/resources/{resourceId}`，使用 verified scope、新的 read purpose、no-store 和服务端
  strong ETag，只返回已提交 `ApiResourceSpec`。对象页重载后按 exact Resource subject 查询 payload-free
  Fixture summary，因此可以再次运行同一个已保存 Case，而不依赖浏览器内存或猜测 Fixture ID。
- 新路由继续保持动态 chunk；bundle gate 现在显式要求并计算 `AuthoringWorkbench-*` startup closure。

### 聚焦验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ApiResourceAuthoringControllerTest,ApiResourceAuthoringFacadeTest test

Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

npm test -- --run src/authoring-workbench/model.test.ts \
  src/authoring-workbench/api.test.ts \
  src/authoring-workbench/AuthoringWorkbench.test.tsx \
  src/App.test.tsx src/ux/routeChunkContract.test.ts

Test Files: 5 passed; Tests: 37 passed

npm run check:i18n
Test Files: 6 passed; Tests: 39 passed

npx tsc --noEmit
npm run build

TypeScript, Vite production build, i18n/UX/host gates, and route chunk budget: PASS

mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,188; Failures: 0; Errors: 0; Skipped: 33
BUILD SUCCESS
```

### 当前差距评估

API Resource 已形成首条用户可见纵向链：标准表单 → 一个复合保存命令 → 私有 Default Fixture → exact
Simulation → 可重载对象页。当前仍要求预先存在 Connection，且只自动推导 flat object example；Tool/Solution
仍进入既有 Author workspace，尚未使用统一对象页，Fixture 也没有独立对象页/share 动作。真实浏览器与真实
PostgreSQL 验收仍未执行。累计完成度保守调整为 **92%**，剩余差距 **8%**；下一刀进入 Tool/Solution 共用
对象页与 DAG 表单，再补 Fixture share/promotion 和真实端到端，不回退到隐藏 JSON 编辑。

## 41. Iteration 40 — Tool/Solution 共用对象页、DAG、Fixture 与模拟

日期：2026-09-01。

### 已完成

- `/workbench/` 的“创建工具”和“创建方案”现在进入同一个 Flow 对象页，不再跳回大而全的旧 Author
  workspace。Tool/Solution 只共享一种 `ReusableFlowCommand`、Fixture 和 Simulation 语义。
- 作者按执行顺序添加已提交 API Resource。页面读取 exact revision/fingerprint；同名同类型字段自动选择最近的
  前序 `NODE_OUTPUT`，否则明确成为 `FLOW_INPUT`。DAG 边仍只由 mapping 派生，UI 不保存第二份拓扑状态。
- 保存 Flow 使用现有 strong ETag + idempotency 协议并返回 exact Flow Draft subject。重载对象页会重新读取每个
  pinned API Resource revision，fingerprint 漂移时 fail closed。
- Fixture task 通过可见 input/output 表单创建一个 whole-flow `SUBJECT + RETURN/INLINE` Case；保存后立即运行
  deny-all Simulation。该证据只表示整条 Flow 被 Fixture 替代，内部 API 节点没有执行。
- Versions task 把 exact Draft 发布为 immutable Flow Version，并在同一对象页显示 publication coordinate。

### 验证

```text
npm test -- --run src/authoring-workbench/model.test.ts \
  src/authoring-workbench/api.test.ts \
  src/authoring-workbench/flowModel.test.ts \
  src/authoring-workbench/flowApi.test.ts \
  src/authoring-workbench/AuthoringWorkbench.test.tsx \
  src/authoring-workbench/FlowObjectPage.test.tsx \
  src/App.test.tsx src/ux/routeChunkContract.test.ts

Test Files: 8 passed; Tests: 44 passed

npm run check:i18n
Test Files: 6 passed; Tests: 39 passed

npx tsc --noEmit
npm run build

TypeScript, Vite production build, i18n 39/39, UX 52/52, host 21/21 and bundle gates: PASS
AuthoringWorkbench startup closure: 186.90 KiB / 10 files (budget 350 KiB)
```

### 当前差距评估

API Resource、Tool 和 Solution 已具备统一入口和同构对象任务，且 Tool/Solution 可以用多个 exact API Resource
组成可重载、可发布、可设置 whole-flow Fixture、可模拟的 DAG。尚未闭合的是 Fixture 独立对象页及 share/
promotion 可见动作，以及当前新工作台的真实浏览器与真实 PostgreSQL acceptance。累计完成度保守调整为
**96%**，剩余差距 **4%**；下一刀先闭合 Fixture 生命周期和独立对象页，再做最终真实端到端证据。

## 42. Iteration 41 — Flow Draft Fixture 权威闭环

日期：2026-09-01。

### 已完成

- `ReusableFlowDraftStore` 新增按 trusted scope、draft id 和 revision 读取 exact committed Draft 的窄接口；
  JDBC 与内存实现都对 0/1/多条结果 fail closed，并在返回前复用完整 Draft authority closure。
- standalone Fixture application 现在同时接受 `FLOW_VERSION` 与 `FLOW_DRAFT` Subject。Draft 必须与已提交
  authority 的 fingerprint 精确一致；不存在返回 NOT_FOUND，漂移返回 INTEGRITY，不按 graph 内容猜 Draft。
- Draft Fixture 只允许 whole-flow `SUBJECT + RETURN/INLINE`。节点级 `APPLY_CASE` 仍只属于 immutable
  Flow Version，避免作者在未发布 Draft 上建立隐含的子节点控制合同。
- opt-in runtime 必须显式装配 `ReusableFlowDraftStore`，缺失时启动失败；HTTP controller 的真实 wire 测试证明
  `FLOW_DRAFT` Subject 可以通过原有 Fixture Set PUT 协议保存，且 verified identity、strong ETag 和 receipt
  coordinate 不变。

### TDD 与验证

首个 `ReusableFlowFixtureModuleTest` 在新增 store seam/constructor 前按预期编译失败；实现后，以下聚焦门禁通过：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ReusableFlowModuleTest,ReusableFlowFixtureModuleTest,WholeFlowFixtureMaterializerTest,\
JdbcReusableFlowDraftStoreTest,StandaloneFixtureSetRuntimeConfigurationTest,\
ApiFixtureSetAuthoringControllerTest test

Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

完整 Resource Gateway 门禁：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,192; Failures: 0; Errors: 0; Skipped: 55
BUILD SUCCESS
```

### 当前差距评估

本轮关闭的是 Tool/Solution 对象页此前被前端 mock 掩盖的生产断点：可见 Flow Draft 现在确实能保存并运行
whole-flow Fixture，而不是只在组件测试中成立。它没有新增 share/promotion 或独立 Fixture 页面，因此累计完成度
维持 **96%**、剩余差距 **4%**。下一刀应形成 Fixture 独立对象页与可见生命周期，再用真实浏览器串起 API
Resource → 多节点 Tool/Solution → Fixture → Simulation；真实 PostgreSQL 证据与外部 Vault/provider 保持明确边界。

## 43. Iteration 42 — 独立 Fixture 对象页与真实浏览器验收

日期：2026-09-01。

### 已完成

- `/workbench/?fixtureSetId=<id>` 现在进入独立 Fixture 对象页。页面读取 trusted scope 下的 exact Fixture
  authority、状态、Subject 和 Case，不从前端缓存猜 revision、fingerprint 或父对象。
- standalone Flow Draft/Flow Version Fixture 返回真实 strong ETag，可见编辑一个 whole-subject
  `SUBJECT + RETURN/INLINE` Case，保存后按 exact revision 运行 deny-all Simulation。保存与模拟仍是两个独立
  动作和回执，页面不把本地输入冒充服务端已提交状态。
- API Resource Default Fixture 保持 parent-governed、只读，并链接回 Resource 对象页；read response 不伪造
  standalone ETag，也不开放受保护 material。
- `/workbench` 与 `/workbench/` 由服务端转发到打包在 `static/workbench` 下的 production Vite 入口，资源路径在
  子目录部署下闭合。
- 真 Chrome 验收通过可见 UI 完成 Flow Draft Fixture 的编辑、保存、模拟，并在动作后只读核对服务端 revision
  和 output；同一方法同时验证 1280 px 主路径与 390 px 无水平溢出。

### 验证

```text
npm test -- --run src/authoring-workbench/fixtureModel.test.ts \
  src/authoring-workbench/flowApi.test.ts \
  src/authoring-workbench/FixtureObjectPage.test.tsx \
  src/authoring-workbench/FlowObjectPage.test.tsx \
  src/authoring-workbench/AuthoringWorkbench.test.tsx

Test Files: 5 passed; Tests: 13 passed

npm run check:i18n
Test Files: 6 passed; Tests: 39 passed

npx tsc --noEmit
npm run build

i18n 39/39; UX 52/52; host 21/21; TypeScript, Vite and bundle gates: PASS
AuthoringWorkbench startup closure: 188.67 KiB / 11 files
AuthorCanvas startup closure: 349.87 KiB / 22 files (budget 350 KiB)

mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ApiFixtureSetAuthoringFacadeTest,ApiFixtureSetAuthoringControllerTest,\
JdbcStandaloneFixtureSetStoreTest,WholeFlowSimulationModuleTest,\
ApiSimulationApplicationConfigurationTest,GatewayExampleControllerTest test

Tests run: 38; Failures: 0; Errors: 0; Skipped: 0

mvn -f resource-gateway-examples/pom.xml -Pfrontend \
  -Dtest=VisualAuthoringBrowserDomTest#fixtureObjectPageVisiblySavesAndSimulatesAnExactFlowDraftFixture test

Tests run: 1; Failures: 0; Errors: 0; Skipped: 0

mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,198; Failures: 0; Errors: 0; Skipped: 34
BUILD SUCCESS
```

### 当前差距评估

用户主路径已经形成统一、可重载、可见的对象工作台：接入 API Resource → 为 Resource 保存 Default
Fixture 并模拟 → 用多个 exact API Resource 编排 Tool/Solution DAG → 保存/发布 Flow → 为 Flow Draft/Version
设置 whole-flow Fixture → 从独立 Fixture 对象页保存并模拟。此前基于这条主路径给出的 **97.5% / 2.5%**
只是局部路径估算；它没有逐项覆盖已批准的 OpenAPI、Fixture share/promotion、legacy migration、部署认证和
可操作性指标，因此自 Iteration 44 起撤回，不再作为当前完成度或停止条件证据。

## 44. Iteration 43 — OpenAPI 内联预览与可见 Resource 投影

日期：2026-09-01。

### 已完成

- 新增纯应用模块 `OpenApiPreviewModule` 和冻结 wire model：inline OpenAPI JSON/YAML 经既有 importer 发现并
  投影为标准 `ApiResourceCommand`；模块不保存 Connection、Resource、Fixture 或原始文档，也不发起网络请求。
- 新增 `POST /api/authoring/resources:preview-openapi`。Controller 使用独立 authoring purpose 和 trusted
  identity boundary，严格反序列化、`no-store`，错误保持 payload-free。`REMOTE` 在 authenticated egress seam
  落地前稳定返回 `424`，不以服务器 URL fetch 冒充支持。
- 默认预览只列出可安全导入的 GET/POST/PUT/DELETE 操作，因此规范中一个 PATCH/blocked operation 不会让可用
  操作一起消失；显式请求未知或不可导入 operation 仍 fail-closed。
- `/workbench/` API Resource 对象页新增可见的 paste → Preview → Use 流程。选择后把 exact path/query/header/body
  binding、扁平 schema、success matcher 和 deterministic example 带入既有 Save and simulate 流程；编辑 Method、
  Path 或 examples 会退出 imported projection，避免 UI 展示与提交 authority 漂移。
- 页面专用文案复用既有全局翻译，避免把局部标签继续推入所有路由共享的 locale startup closure；未提高预算。

### 验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=OpenApiPreviewModuleTest,AuthoringProtocolSchemaTest,\
ApiResourceAuthoringControllerTest,ApiResourceAuthoringApplicationConfigurationTest test

Tests run: 55; Failures: 0; Errors: 0; Skipped: 0

npm test -- --run src/authoring-workbench/model.test.ts \
  src/authoring-workbench/api.test.ts \
  src/authoring-workbench/AuthoringWorkbench.test.tsx

Test Files: 3 passed; Tests: 13 passed

npm run build

i18n 39/39; UX 52/52; host 21/21; TypeScript, Vite and bundle gates: PASS
AuthoringWorkbench startup closure: 189.43 KiB / 11 files
AuthorCanvas startup closure: 349.93 KiB / 22 files (budget 350 KiB)

mvn -f resource-gateway-examples/pom.xml \
  -Dtest=VisualAuthoringBrowserDomTest#apiResourcePageVisiblyImportsAnOpenApiOperationWithoutPersistence test

Tests run: 1; Failures: 0; Errors: 0; Skipped: 0

mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,207; Failures: 0; Errors: 0; Skipped: 35
BUILD SUCCESS
```

### 当前差距评估

本轮关闭了“设计中有 OpenAPI、对象页却只能手填 Method/Path/example”的明显接入断点，但不能据此重新宣称
低于 3%。按已批准需求逐项复核，当前证据差距仍 **大于 3%（约 8–10%）**：

1. `REMOTE` OpenAPI authenticated egress 尚未实现；
2. Fixture share/promotion 尚未进入简化对象工作台；
3. legacy 默认入口与已有资产迁移/兼容验收尚未闭合；
4. 新对象工作台的完整 API → 多节点 Tool/Solution → Fixture lifecycle 浏览器链、production PostgreSQL/Vault
   专项认证与用户任务时长/步骤数指标仍未形成同一份验收证据。

因此继续实施，下一刀优先收敛 Fixture share/promotion 的对象页生命周期与真实浏览器证据；不以局部代码覆盖率
替代整体可操作性和部署证据。

## 45. Iteration 44 — Fixture 受保护分享与待评审修订版

日期：2026-09-01。

### 已完成

- 冻结 `bloge.fixtureShareCommand.v1` 和 payload-free `bloge.fixtureShareReceipt.v1`。命令绑定 exact
  Fixture Set revision/fingerprint/statusRevision/strong ETag，并且只接受分类、1–365 天保留期、
  redaction profile 和非根 JSON Pointer；JSONPath 不再被误接受。
- V017 新增幂等 share command 与 pending review request authority。JDBC 在同一事务中锁定
  `PRIVATE_DRAFT` source，通过受保护 material/catalog adapter 写入 Fixture Asset，把 asset 提交为
  `PROPOSED`，再派生 `SHARING_PENDING` Fixture Set revision。重放不重复写受保护 material。
- 原私有 revision 保持不变，可按 exact coordinate 继续读取/运行；当前 head 变为
  `SHARING_PENDING`，对象页只读且禁止运行、编辑或复用。
- 独立 Fixture 对象页新增可见的 classification/retention/redaction 表单和 Share 动作；
  Controller 复用 trusted author identity，强 ETag、`Idempotency-Key`、`no-store` 和独立写 purpose。
- production correctness runtime 现在在 material/catalog Bean 注册后组装真实 share writer；
  `ApplicationContextRunner` 明确防止浏览器专用 Bean 掩盖生产缺口。

### 验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='InMemoryStandaloneFixtureSetShareTest,JdbcStandaloneFixtureSetStoreTest,\
StandaloneFixtureSetSchemaReadinessTest,StandaloneFixtureSetRuntimeConfigurationTest,\
CorrectnessFixtureSetShareMaterialWriterTest,ReusableFlowFixtureShareModuleTest,\
ApiFixtureSetAuthoringControllerTest,\
AuthoringProtocolSchemaTest#fixtureShareCommandAndPayloadFreeReceiptRoundTripAgainstFrozenSchemas' test

Tests run: 26; Failures: 0; Errors: 0; Skipped: 0

mvn -f resource-gateway-examples/pom.xml \
  -Dtest=CorrectnessAuthoringCommandRuntimeConfigurationTest test

Tests run: 3; Failures: 0; Errors: 0; Skipped: 0

npm test -- --run src/authoring-workbench/flowApi.test.ts \
  src/authoring-workbench/FixtureObjectPage.test.tsx

Test Files: 2 passed; Tests: 8 passed
npx tsc --noEmit: PASS
npm run check:i18n: 39/39

mvn -f resource-gateway-examples/pom.xml -Pfrontend \
  -Dtest=VisualAuthoringBrowserDomTest#\
fixtureObjectPageVisiblySavesSimulatesAndSubmitsAProtectedRevisionForReview test

frontend build: i18n 39/39; UX 52/52; host 21/21; TypeScript/Vite/bundle PASS
AuthorCanvas startup closure: 349.93 KiB / 22 files
Tests run: 1; Failures: 0; Errors: 0; Skipped: 0

mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,218; Failures: 0; Errors: 0; Skipped: 35
BUILD SUCCESS
```

全量门首次运行以 `VisualRuntimeBoundaryTest` 精确暴露内层 share module 对 integration/correctness
实现类型的反向依赖（8,218 run，1 failure）。修复后内层改用 transport-neutral `FixtureShareIdentity`
和 material writer port，外层 adapter 才执行认证上下文与 correctness 错误翻译；19/19 边界/分享聚焦门
通过后，上述最终 `clean verify` 在最终源码上全绿。

### 当前差距评估

本轮闭合了“私有 Fixture 无法通过简化对象页进入受保护评审”的主断点，但尚不能宣称
余下差距低于 3%。重新按已批准需求评估，当前证据差距约 **5–7%**：

1. review request 还没有从独立 reviewer 验证/批准/激活推进到 Fixture Set `TEAM_AVAILABLE`；
2. `REMOTE` OpenAPI authenticated egress 和 legacy 默认入口/既有资产迁移验收仍未闭合；
3. production PostgreSQL/Vault 专项认证、完整 API → Tool/Solution → Fixture lifecycle 单链及用户任务
   时长/步骤数尚未形成同一份验收证据。

因此继续实施；下一刀优先复用现有 Fixture Asset verify/approve/activate 协议，把 pending review
显式推进为 `TEAM_AVAILABLE`，不以 `PROPOSED` asset 或局部浏览器绿灯冒充生命周期完成。

## 46. Iteration 45 — 独立评审与团队可用 Fixture

日期：2026-09-01。

### 已完成

- 冻结 `bloge.fixtureReviewCommand.v1` 和 payload-free `bloge.fixtureReviewReceipt.v1`。命令绑定 exact
  `SHARING_PENDING` revision/fingerprint/statusRevision/strong ETag、三项 reviewer attestation 和 bounded
  comment；creator 与 reviewer 必须不同。
- V018 持久化 review intent、reviewer authority 与 immutable receipt。应用模块锁定 exact pending request，
  经 `FixtureSetReviewMaterialGate` 推进全部受保护 Fixture Assets，只有全部 `ACTIVE` 后才派生
  `TEAM_AVAILABLE` Fixture Set revision；重放返回同一 receipt。
- correctness adapter 复用现有 verify/approve/activate 服务，并支持从 exact verified `PROPOSED`、
  `APPROVED` 或 `ACTIVE` head 恢复。多资产处理中途失败后重试不会再次激活已完成资产。
- Fixture 对象页新增可见 reviewer link、三项 attestation、comment 和 Approve 动作；只有服务端 receipt
  返回后才显示 `TEAM_AVAILABLE` 并重新启用运行。Reviewer 使用独立 authoring purpose 与同源可见身份交接。
- 修复分享 material writer 的 clearance 传播：trusted identity clearance 现在进入真实
  `IntegrationRequestContext.clearance`，不再误写到 delegated actor 字段；RESTRICTED material 由真实门禁验证。

### 验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='ReusableFlowFixtureReviewModuleTest,CorrectnessFixtureSetReviewMaterialGateTest,\
CorrectnessFixtureSetShareMaterialWriterTest,ApiFixtureSetAuthoringControllerTest,\
CorrectnessAuthoringCommandRuntimeConfigurationTest,StandaloneFixtureSetRuntimeConfigurationTest,\
StandaloneFixtureSetSchemaReadinessTest,JdbcStandaloneFixtureSetStoreTest,\
AuthoringProtocolSchemaTest,ApiFixtureSetApplicationConfigurationTest' \
  -DfailIfNoTests=false test

Tests run: 51; Failures: 0; Errors: 0; Skipped: 0

npm test -- --run src/authoring-workbench/flowApi.test.ts \
  src/authoring-workbench/FixtureObjectPage.test.tsx

Test Files: 2 passed; Tests: 10 passed
npx tsc --noEmit: PASS

npm run build

i18n 39/39; UX 52/52; host 21/21; TypeScript, Vite and bundle gates: PASS
AuthorCanvas startup closure: 349.95 KiB / 22 files

mvn -f resource-gateway-examples/pom.xml \
  -Dtest=VisualAuthoringBrowserDomTest#\
fixtureObjectPageVisiblySavesSimulatesAndSubmitsAProtectedRevisionForReview test

Tests run: 1; Failures: 0; Errors: 0; Skipped: 0

mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,225; Failures: 0; Errors: 0; Skipped: 35
BUILD SUCCESS
```

浏览器首次诊断以真实服务端错误 `RG.CORRECTNESS.FIXTURE_MATERIAL_CLEARANCE_FORBIDDEN` 暴露 clearance
构造参数错位；修复后删除全部临时诊断并在最终源码上重跑通过。随后补出的多资产恢复回归证明首个资产已
激活、第二个失败时，同一 review command 可从持久化 head 继续，而不是重复或绕过治理步骤。

### 当前差距评估

本轮闭合了简化对象页从 `PRIVATE_DRAFT` 分享、独立 reviewer 审批到 `TEAM_AVAILABLE` 可运行的核心
Fixture 生命周期。按已批准目标重新评估，当前剩余证据差距约 **3–5%**：

1. `REMOTE` OpenAPI authenticated egress 尚未实现；
2. legacy 默认入口与既有 Fixture/Resource 资产的迁移兼容验收尚未闭合；
3. 外部 Vault 与生产 PostgreSQL 部署认证仍需独立环境证据；
4. API 接入、多节点 Tool/Solution 编排、Fixture 分享/评审虽各自有真实浏览器证据，但尚未合并为一条
   可计时、可统计点击步骤的端到端任务验收。

因此尚不宣称低于 3%；下一刀应优先形成单条 API → 多节点 Flow → Fixture 分享/评审/复用任务证据，
同时记录完成时间和关键交互次数，再决定 legacy migration 与 remote egress 的收尾顺序。

## 47. Iteration 46 — 单链 API、Tool、受保护 Whole-Flow Fixture 验收

日期：2026-09-01。

### 已完成

- Flow 对象页在发布后保存 exact `FLOW_VERSION` coordinate，并通过服务端 latest-version read 在页面重载后
  恢复它；随后 Fixture authoring 不再回退到可变 `FLOW_DRAFT`。只有 version source 与当前 Draft exact
  匹配才允许绑定，重新保存 Flow 会显式清除旧发布 coordinate，避免静默绑定过期版本。
- API 对象页从 authenticated Connection list 加载 payload-free committed views，作者通过可见下拉选择
  `CRM`；浏览器不再输入预知的隐藏 Connection ID。
- `SimulationModule` 新增 transport-neutral `SimulationIdentity` 与 `FixtureAssetSimulationResolver` port。
  只有 `TEAM_AVAILABLE` Fixture、exact ACTIVE protected asset、trusted same-scope identity 和已装配 resolver
  同时成立时才解析 material；缺任一条件均 fail closed。
- correctness adapter 复用既有 governed material resolver，保留 exact asset revision/schema fingerprint、
  enterprise scope、material-read purpose、clearance 和审计边界。Simulation evidence 标记
  `FIXTURE_ASSET` 且 governance verdict 为 `PASSED`。Whole-subject RETURN 运行记录一个 synthetic
  `subject` evidence node（MOCKED、OUTPUT_LEVEL、zero egress），而不会伪造或执行 Flow 内部 API 节点；
  安全输出仍按 immutable Subject contract 验证。
- 分享派生只接受 Inline Return 与相同 whole-output expectation；protected writer 在持久化后从 exact
  material authority 解析安全输出，并把派生 revision 的 expectation 同步为同一脱敏值。因此最终运行的
  contract 与 assertion 均可独立得到 `PASSED`，不会拿私有明文期望与脱敏输出做伪失败比较。
- production Simulation 配置现在可在 API Resource-only 或 Reusable Flow 部署中选择性装配 protected
  resolver，不因 Flow authority 缺失而退回不支持 protected material 的构造路径。
- 新 real-browser 方法在同一 Chrome session 完成：两个 inline OpenAPI GET → 两个 API Resource/Default
  Fixture → 两节点 Tool DAG → publish immutable Flow Version → whole-Flow Fixture → Share `/customerLabel` →
  visible reviewer sign-in → Approve/`TEAM_AVAILABLE` → Run。最终输出必须显示服务端 redaction，而不是明文。
- 浏览器以统一 `trackedClick` 计数真实主操作，精确为 **27 次**；页面任务计时 **9,899 ms**，低于
  90 秒门槛，并保留 1280 px 无水平溢出断言。

### 验证

```text
npm test -- --run src/authoring-workbench/api.test.ts \
  src/authoring-workbench/flowApi.test.ts \
  src/authoring-workbench/AuthoringWorkbench.test.tsx \
  src/authoring-workbench/flowModel.test.ts \
  src/authoring-workbench/FlowObjectPage.test.tsx \
  src/authoring-workbench/FixtureObjectPage.test.tsx

Test Files: 6 passed; Tests: 27 passed
npx tsc --noEmit: PASS

npm run build

i18n 39/39; UX 52/52; host 21/21; TypeScript, Vite and bundle gates: PASS
AuthoringWorkbench startup closure: 192.44 KiB / 12 files
AuthorCanvas startup closure: 349.96 KiB / 22 files

mvn -f resource-gateway-examples/pom.xml \
  -Dtest='SimulationModuleTest,WholeFlowSimulationModuleTest,ApiSimulationControllerTest,\
ApiSimulationApplicationConfigurationTest,GovernedFixtureSimulationResolverTest,\
InMemoryReusableFlowPublicationStoreTest,JdbcReusableFlowPublicationStoreTest,\
ReusableFlowAuthoringControllerTest,ReusableFlowModuleTest,ReusableFlowFixtureModuleTest,\
ReusableFlowFixtureShareModuleTest,ReusableFlowFixtureReviewModuleTest,\
CorrectnessFixtureSetShareMaterialWriterTest,CorrectnessAuthoringCommandRuntimeConfigurationTest' test

Tests run: 55; Failures: 0; Errors: 0; Skipped: 0

mvn -f resource-gateway-examples/pom.xml \
  -Dtest=VisualAuthoringBrowserDomTest#\
simpleWorkbenchCompletesApiDagAndReviewedFixtureTaskWithinBoundedActions test

Tests run: 1; Failures: 0; Errors: 0; Skipped: 0
primaryActions=27; elapsedMs=9899; test elapsed=21.39 s
Visible result: SUCCEEDED; SIMULATED_ONLY; contract/assertions/governance PASSED;
subject MOCKED/FIXTURE_ASSET/OUTPUT_LEVEL/FIXTURE/NO_EGRESS

mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,229; Failures: 0; Errors: 0; Skipped: 36
BUILD SUCCESS; Total time: 11:55 min
```

### 当前差距评估

已批准的本地用户任务主链现有同一份真实浏览器证据，代码/本地验收差距重估为约 **2–3%**。仍未
闭合的项目不应由本地 mock 或 H2 结果替代：

1. `REMOTE` OpenAPI authenticated egress 的 production provider 与目的地策略；
2. 外部 Vault provider 的部署认证；
3. V001–V018 在真实 PostgreSQL 服务上的迁移与并发认证；
4. legacy 默认入口及既有资产迁移的兼容验收。

前三项属于外部环境/部署认证；第四项属于兼容收尾。它们不再阻断“清晰接入两个 API、组成可复用
Tool、为 immutable Flow Version 配置并评审 Whole-Flow Fixture、再模拟运行”的本地可操作主链。

## 48. Iteration 47 — 受治理的 REMOTE OpenAPI 宿主端口

日期：2026-09-01。

### 已完成

- `OpenApiPreviewModule` 同时接受 `INLINE` 与 `REMOTE`，但 REMOTE 不信任 command 中的身份字段；HTTP adapter
  把已认证的 tenant/project/environment、actor 与 purpose 投影为 `OpenApiPreviewIdentity`，模块再向宿主网关
  发起一次读取请求。预览仍不保存 OpenAPI 文档、Connection、Resource 或 Fixture。
- 新增深端口 `RemoteOpenApiDocumentGateway`。端口契约要求宿主实现目的地授权、DNS 结果钉扎与 rebinding
  防护、禁止 redirect、限制连接/读取时间，并且仅可从 exact committed Connection 与 Secret Store authority
  解析认证材料；异常、日志和诊断不得包含 URL、credential、document 或 provider payload。
- 请求在端口前再次校验：只允许无 userinfo/query/fragment 的 absolute HTTPS URI，`connectionId` 是可选的
  bounded identifier；明确传递 10 MiB 和 15 秒上限。端口返回后模块独立验证 OpenAPI JSON/YAML media type、
  UTF-8 和 byte bound，防止错误宿主适配器绕过应用层限制。
- 没有受治理网关 Bean 时使用 fail-closed `unavailable()` 并稳定返回 424；意外网络/provider 失败归一化为
  payload-free 502，原始 URL 和 provider message 不进入 Problem Detail。生产配置只通过可选深端口装配，
  不在仓库里伪造无法证明 DNS pinning 的通用 `HttpClient` fallback。

### 验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=OpenApiPreviewModuleTest,AuthoringProtocolSchemaTest,\
ApiResourceAuthoringControllerTest,ApiResourceAuthoringApplicationConfigurationTest test

Tests run: 63; Failures: 0; Errors: 0; Skipped: 0
```

最终串行项目门：

```text
mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,235; Failures: 0; Errors: 0; Skipped: 36
BUILD SUCCESS; Total time: 11:46 min
```

覆盖内容包括：可信 scope/actor/purpose 传递、带/不带 Connection 的 REMOTE request、默认 424、显式网关
装配、unsafe URL 在 egress 前拒绝、unsupported media type、oversize、invalid UTF-8、unexpected provider error
归一化、Document defensive copy，以及 502/424 响应均不回显 URL。

### 当前差距评估

本轮把 REMOTE 从“模块硬编码拒绝”推进到可部署且 fail-closed 的宿主端口，关闭了仓库内 wire、identity、
预算与安全错误协议缺口；但它没有冒充外部网络/Vault 认证。当前剩余差距仍约 **2–3%**：

1. 部署方仍需提供并认证真实 `RemoteOpenApiDocumentGateway` 与 `ExternalSecretProvider`，包括 DNS pinning、
   destination policy、Secret Store scope 和实际 TLS/timeout 证据；
2. V001–V018 仍需在真实 PostgreSQL 服务做 migration 与并发认证；
3. legacy 默认入口和既有资产迁移仍需兼容验收。

因此继续实施兼容收尾；外部两项只能由目标部署环境提供证据，不能由本地 mock/H2 伪造。

## 49. Iteration 48 — 默认对象入口与显式 Legacy rollback

日期：2026-09-01。

### 已完成

- Web 根路径 `/` 在没有精确 `spine=v1` 时重定向到 `/workbench/`，不再把普通作者先送进
  Capability Studio。对象工作台首页仍只给出“接入 API、创建工具、创建方案”三个任务入口。
- 打包 WebView 没有 `workspaceRoute` 时同样进入对象工作台；显式 `workspaceRoute=capabilities` 继续有效。
- `/capabilities/` 保留原 Capability Studio；`/?spine=v1` 保留只读深链 Launcher；
  `/author/?authorWorkspace=legacy` 保留旧 Author rollback，且原有 draft/node/run deep-link 参数不丢失。
- 真实浏览器的完整 API → DAG → whole-flow Fixture → review/share → simulation 验收现在从 `/` 开始，
  因此默认入口不是只由路由单元测试推断。

### 聚焦验证

```text
npm test -- --run src/App.test.tsx
Tests: 22 passed; Failures: 0

mvn -f resource-gateway-examples/pom.xml -Dtest=GatewayExampleControllerTest test
Tests run: 14; Failures: 0; Errors: 0; Skipped: 0

npm run build
i18n: 39/39; UX: 52/52; host: 21/21; TypeScript/Vite/bundle: PASS
AuthoringWorkbench startup closure: 192.44 KiB / 12 files

mvn -f resource-gateway-examples/pom.xml -Pfrontend \
  -Dtest=VisualAuthoringBrowserDomTest#\
simpleWorkbenchCompletesApiDagAndReviewedFixtureTaskWithinBoundedActions test
Tests run: 1; Failures: 0; Errors: 0; Skipped: 0
primaryActions=27; elapsedMs=10055; BUILD SUCCESS

mvn -f resource-gateway-examples/pom.xml clean verify
Tests run: 8,235; Failures: 0; Errors: 0; Skipped: 36
BUILD SUCCESS; Total time: 11:50 min
```

这一步只切换入口投影，不迁移或删除任何现有 Capability、Graph、Fixture 或 Resource 数据。
下一步仍需完成既有 Descriptor + Contract、GraphDraft/Publication 与 Fixture 引用的显式迁移/修复清单；
不可证明的资产必须标记 `NEEDS_REPAIR` 或保留 Legacy，不得静默补全。

## 50. Iteration 49 — 存量资产迁移与修复清单

日期：2026-09-01。

### 已完成

- 默认对象工作台增加次级 **Existing assets** 入口。入口在清单为空或暂时不可用时仍保持可见，避免恢复路径
  因异步计数或服务错误而消失；首页的三个创建概念保持不变。
- 新的 `bloge.legacyAssetMigrationInventory.v1` 只读协议统一列出：legacy Resource Descriptor 与 Design
  Contract 配对、可信 scope 内的 Graph Draft/Publication、以及 Draft 中 Fixture 引用的独立迁移项。
- 每项只返回 kind、source id/revision、display name、status、Fixture reference count、reason codes 和服务端选择的
  相对应用路径。协议、模块、Controller 和真实浏览器均验证不返回 URL template、default headers、schema、
  Fixture value、governed material id、credential 或外部 URL。
- `READY_TO_REAUTHOR` 只表示可从可见对象页重新创作，不表示已经迁移；缺 Descriptor/Contract 或 inactive
  Contract 标为 `NEEDS_REPAIR`；含非 data edge 或不可证明 Publication 的资产标为 `LEGACY_ONLY`。
- 清单读取复用可信 workload identity 与 `API_RESOURCE_AUTHORING` purpose，并按 verified
  tenant/project/environment 过滤 Draft 与 Publication。它不依赖 durable Resource 写入开关，也不执行数据库
  migration、自动 Connection 选择或 Fixture material 复制。

### 验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=VisualRuntimeBoundaryTest,LegacyAssetMigrationModuleTest,\
LegacyAssetMigrationControllerTest,LegacyAssetMigrationConfigurationTest,\
AuthoringProtocolSchemaTest test

Tests run: 23; Failures: 0; Errors: 0; Skipped: 0

npm test -- --run src/authoring-workbench/api.test.ts \
  src/authoring-workbench/AuthoringWorkbench.test.tsx

Test Files: 2 passed; Tests: 12 passed
npm run check:i18n: 39/39
npx tsc --noEmit: PASS

npm run build

i18n 39/39; UX 52/52; host 21/21; TypeScript, Vite and bundle gates: PASS
AuthoringWorkbench startup closure: 193.33 KiB / 12 files
AuthorCanvas startup closure: 349.96 KiB / 22 files

mvn -f resource-gateway-examples/pom.xml -Pfrontend \
  -Dtest=VisualAuthoringBrowserDomTest#\
simpleWorkbenchShowsPayloadFreeLegacyMigrationInventory test

Tests run: 1; Failures: 0; Errors: 0; Skipped: 0
BUILD SUCCESS

mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,240; Failures: 0; Errors: 0; Skipped: 37
BUILD SUCCESS; Total time: 11:43 min
```

### 当前差距评估

本轮关闭的是“发现、分类、解释与进入正确修复入口”，不是自动转换。Resource 仍需作者可见地选择 Connection
并保存新权威；高级 Graph edge 继续由 Legacy Author 承载；Fixture value 与 governed material 不会从旧资产复制。
尚未实现逐项 re-author command、迁移覆盖率 receipt 或批量 mutation/replay，因此不能把清单计数当迁移完成率。
外部 Vault/provider 与真实 PostgreSQL migration/concurrency 仍是部署环境证据，不能由本地 H2 或浏览器清单代替。

## 51. Iteration 50 — Descriptor + Contract 显式重创闭环

日期：2026-09-01。

### 已完成

- 为 `READY_TO_REAUTHOR` Resource 增加只读预览协议
  `bloge.legacyApiResourceReauthorPreview.v1` 与端点
  `GET /api/authoring/migrations/legacy-assets/resources/{resourceId}:preview`。预览只投影相对 path、可证明的
  request bindings、response success/output path、简化 schema 与生成 example。
- host、default headers、auth、credential、旧 Fixture value、governed material reference 均不进入 wire；打开预览
  不执行写入。非 GET、unsafe path、ambiguous/unsupported mapping 或不可证明 contract 一律 `NEEDS_REPAIR`。
- Existing assets 的 READY 动作进入默认对象工作台并加载预填 API 表单。Connection 有意保持为空，作者必须可见地
  选择一个已提交 Connection，随后复用既有 save-and-simulate：保存新 Resource、创建 Default Fixture、用该 Fixture
  完成模拟。真实浏览器只在动作完成后读取服务端权威，确认新 Resource 使用作者选择的 `crm` Connection。
- 迁移预览在 durable Resource 写入关闭时仍可读取；独立 scoped problem handler 保持认证、no-store、401 challenge
  与 payload-free 404/422 错误，不依赖写路径的条件装配。

### 验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=LegacyAssetMigrationModuleTest,LegacyAssetMigrationControllerTest,\
LegacyAssetMigrationConfigurationTest,AuthoringProtocolSchemaTest test

Tests run: 28; Failures: 0; Errors: 0; Skipped: 0

npm test -- --run src/authoring-workbench/model.test.ts \
  src/authoring-workbench/api.test.ts \
  src/authoring-workbench/AuthoringWorkbench.test.tsx

Test Files: 3 passed; Tests: 19 passed
npm run check:i18n: 39/39
npx tsc --noEmit: PASS

npm run build
i18n 39/39; UX 52/52; host 21/21; TypeScript, Vite and bundle gates: PASS
AuthoringWorkbench startup closure: 193.66 KiB / 12 files
AuthorCanvas startup closure: 349.98 KiB / 22 files

mvn -f resource-gateway-examples/pom.xml \
  -Dtest=VisualAuthoringBrowserDomTest#\
simpleWorkbenchReauthorsLegacyResourceThroughVisibleReview test

Tests run: 1; Failures: 0; Errors: 0; Skipped: 0
BUILD SUCCESS

mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,247; Failures: 0; Errors: 0; Skipped: 38
BUILD SUCCESS; Total time: 11:48 min
```

### 当前差距评估

本轮关闭了 Resource Descriptor + Design Contract 的逐项、显式、安全重创路径，不提供批量自动迁移。Graph Draft /
Publication 仍需逐项投影到 Tool/Flow，Fixture reference 仍需显式重建且不得复制旧 payload 或 protected material。
真实 PostgreSQL migration/concurrency、Remote OpenAPI egress 与外部 Vault/provider 认证继续属于部署环境证据。

## 52. Iteration 51 — Graph Draft / Publication 显式 Flow 重创闭环

日期：2026-09-01。

### 已完成

- 增加只读协议 `bloge.legacyReusableFlowReauthorPreview.v1` 与认证端点
  `GET /api/authoring/migrations/legacy-assets/flows/{sourceKind}/{sourceId}:preview?revision={revision}`，支持精确
  revision 的 Graph Draft 和 frozen Publication。预览不创建、修改或删除任何权威对象。
- 只投影可证明的 API-only data DAG：每个 `resource:<id>` 节点必须解析为同 scope 的 exact committed Resource
  head；保留 graph input/output contract、layout，以及 direct context-path、node-output 和 constant mapping。高级/control
  edge、复杂表达式、union/ambiguous target 或缺失 Resource 一律 fail closed 为 `LEGACY_ONLY` / `NEEDS_REPAIR`。
- `GraphDraft.nodeFixtures` 与 governed reference 只形成有界计数和 `FIXTURE_REAUTHOR_REQUIRED` 诊断；Fixture value、
  protected material、receipt、credential 和 locator 均不进入预览，也不会写入新 Flow。
- Existing assets 的 `REAUTHOR_FLOW` 动作进入可见 Flow object page。作者看到“不自动迁移”、Fixture 引用计数与诊断，
  复核 Resource revision/fingerprint 后再保存；随后复用既有 Flow publish、Fixture create/simulate/share/review/run 路径。
  增删节点会主动丢弃导入投影，回到普通 builder，避免把已失真的 mapping 伪装成原图。
- 单浏览器验收在同一条 1280px 链路中可见地创建两个 API Resource、打开 legacy Graph Draft、保存并发布 Flow、
  创建与模拟 Fixture、完成 share/review/run；服务端只在动作完成后用于权威断言。

### 验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=LegacyAssetMigrationModuleTest,LegacyAssetMigrationControllerTest,\
LegacyAssetMigrationConfigurationTest,AuthoringProtocolSchemaTest test

Tests run: 31; Failures: 0; Errors: 0; Skipped: 0

npm test -- --run src/authoring-workbench/api.test.ts \
  src/authoring-workbench/flowApi.test.ts \
  src/authoring-workbench/FlowObjectPage.test.tsx \
  src/authoring-workbench/AuthoringWorkbench.test.tsx

Test Files: 4 passed; Tests: 26 passed
npm run check:i18n: 39/39
npm run check:ux: 52/52
npm run check:host: 21/21
npx tsc --noEmit: PASS

npm run build
TypeScript, Vite and bundle gates: PASS
AuthoringWorkbench startup closure: 194.11 KiB / 12 files
AuthorCanvas startup closure: 349.95 KiB / 22 files

mvn -f resource-gateway-examples/pom.xml \
  -Dtest=VisualAuthoringBrowserDomTest#\
simpleWorkbenchCompletesApiDagAndReviewedFixtureTaskWithinBoundedActions test

Tests run: 1; Failures: 0; Errors: 0; Skipped: 0
primaryActions: 26; elapsedMs: 9,426
BUILD SUCCESS

mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,250; Failures: 0; Errors: 0; Skipped: 38
BUILD SUCCESS; Total time: 11:51 min
```

### 当前差距评估

本轮关闭 Graph Draft / Publication 到可复用 Flow 的逐项、显式、安全重创路径，不提供批量自动迁移。Fixture
reference 仍需独立的显式重创入口；旧 payload 与 governed material 必须继续留在受保护权威中，不能从 Graph Draft
复制。真实 PostgreSQL migration/concurrency、Remote OpenAPI authenticated egress 与外部 Vault/provider 认证仍属于
部署环境证据。按已批准范围粗估，本地可实现代码/验收差距约 3%–4%；下一轮优先关闭 Fixture reference 重创。

## 53. Iteration 52 — Fixture reference 显式重创闭环

日期：2026-09-01。

### 已完成

- 增加只读协议 `bloge.legacyFixtureReauthorPreview.v1` 与认证端点
  `GET /api/authoring/migrations/legacy-assets/fixtures/{draftId}:preview?revision={revision}`。响应只包含 legacy
  draft coordinate、exact target Flow Draft、建议 Fixture Set id，以及排序后的 node id、material kind、fidelity 和
  `expectedInputPresent`。旧 inline input/output、governed asset id/fingerprint、receipt、credential 和 protected
  material 不进入 wire。
- Fixture inventory 在 exact committed Flow Draft 尚不存在时返回 `NEEDS_REPAIR` + `REAUTHOR_FLOW`。作者保存结构完全
  一致的新 Flow 后，inventory 才返回 `READY_TO_REAUTHOR` + `REAUTHOR_FIXTURE`，并打开该 Flow 的 Fixture tab。预览和
  inventory 都不执行写入。
- Fixture 页面保留 `{}` 输入和输出，要求作者显式录入新 whole-Flow Case。保存继续使用 exact Flow Draft subject，
  并经过既有 simulation authority；系统不从 legacy `GraphDraft.nodeFixtures` 复制任何材料。
- Fixture share module 和 correctness material adapter 均支持 exact committed Flow Draft。分享只使用 draft 的 output
  schema 和 payload-free coordinate 创建受保护材料，随后沿用独立审核、`TEAM_AVAILABLE` 与 governed-run 路径；不会
  将 Fixture 静默切换为 later Flow Version。
- 单浏览器链从两个 API Resource 开始，依次完成 legacy Flow 重创、legacy Fixture 重录、模拟、发布、分享、可见 reviewer
  登录、审核和受治理运行。服务端只在用户动作完成后读取权威，最终 Fixture subject 与 exact committed Flow Draft
  coordinate 相等。

### 验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=LegacyAssetMigrationModuleTest,LegacyAssetMigrationControllerTest,\
LegacyAssetMigrationConfigurationTest,AuthoringProtocolSchemaTest,\
ReusableFlowFixtureShareModuleTest,CorrectnessFixtureSetShareMaterialWriterTest,\
StandaloneFixtureSetRuntimeConfigurationTest test

Tests run: 39; Failures: 0; Errors: 0; Skipped: 0

npm test -- --run src/authoring-workbench/flowApi.test.ts \
  src/authoring-workbench/FlowObjectPage.test.tsx \
  src/authoring-workbench/AuthoringWorkbench.test.tsx

Test Files: 3 passed; Tests: 20 passed
npx tsc --noEmit: PASS

npm run build
i18n 39/39; UX 52/52; host 21/21; TypeScript, Vite and bundle gates: PASS
AuthoringWorkbench startup closure: 194.65 KiB / 12 files
AuthorCanvas startup closure: 349.96 KiB / 22 files

mvn -f resource-gateway-examples/pom.xml \
  -Dtest=VisualAuthoringBrowserDomTest#\
simpleWorkbenchCompletesApiDagAndReviewedFixtureTaskWithinBoundedActions test

Tests run: 1; Failures: 0; Errors: 0; Skipped: 0
primaryActions: 28; elapsedMs: 9,574
BUILD SUCCESS

mvn -f resource-gateway-examples/pom.xml clean verify

Tests run: 8,253; Failures: 0; Errors: 0; Skipped: 38
BUILD SUCCESS; Total time: 11:44 min
```

### 当前差距评估

旧 Resource、API-only Graph Draft / frozen Publication 和 Fixture reference 现在都有逐项、显式、payload-free 的本地
重创路径。按已批准范围复核，剩余本地代码与验收差距约 2%–2.5%，已低于 3% 停止条件。剩余工作不影响该单项链路：
批量 mutation/replay、迁移覆盖率 receipt，以及更高级 legacy control edge 仍未实现。真实 PostgreSQL
migration/concurrency、Remote OpenAPI authenticated egress 与外部 Vault/provider 认证属于部署环境证据，不能由本地
H2、单机 Chrome 或 mock provider 代替。

## 54. Iteration 53 — Fixture 可运行状态闭包与完成审计纠偏

日期：2026-09-01。

### 已完成

- 对已批准 v1/v1.1、冻结 Schema、当前代码和运行证据重新建立完成矩阵。审计撤销上一轮“2%–2.5%”结论：该数字没有
  可复核分母，并遗漏新建 Connection、Flow Version 父级复用/节点 Fixture，以及 Fixture 生命周期运行门禁三个本地
  P1。目标保持进行中。
- `SimulationModule` 在占用 run idempotency coordinate 前检查 exact committed Fixture revision。私有 inline Case 只允许
  `PRIVATE_DRAFT`；受保护 material 只允许 `TEAM_AVAILABLE` 并继续要求 trusted identity 与 governed resolver。
- `ParentFlowApplyCaseCompiler` 当前只支持 inline leaf Case，因此明确只接受 `PRIVATE_DRAFT`。`SHARING_PENDING`、`STALE`
  和 `REVOKED` 在直接 Simulation 与父 Flow `APPLY_CASE` 两条路径均 fail closed。
- JavaDoc 说明状态门禁属于创建新不可变 Run 的授权边界，不把枚举存在误当成运行控制。

### TDD 与验证

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=SimulationModuleTest,ParentFlowApplyCaseCompilerTest,WholeFlowSimulationModuleTest test

Tests run: 13; Failures: 0; Errors: 0; Skipped: 0
BUILD SUCCESS
```

新增负例在修复前分别证明：直接 Simulation 会运行阻断状态，父 Flow 没有独立生命周期门禁；修复后两个公开 seam
均拒绝三种状态。完整门禁将在本轮剩余 P1 闭合后统一运行。

### 当前差距评估

安全门禁已关闭，但标准 API 页仍不能创建 `Auth.NONE` Connection，简单 Flow 页仍不能从统一 Catalog 选择
`FLOW_VERSION` 或可见地创作节点 `APPLY_CASE`。加上 390 px 完整任务链与迁移 coverage receipt 等 P2，当前本地
实现/验收差距保守估计仍约 5%–7%，明显高于 3% 停止条件。下一步按这两条用户主线继续 TDD 实现。

## 55. Iteration 54 — API Resource 内联创建无凭证 Connection

日期：2026-09-01。

### 已完成

- 标准 API 对象页默认显示 `Create`，作者只填写 Connection 名称和 absolute HTTP(S) base URL；也可切换
  `Existing` 复用已提交 Connection。前端 wire 严格使用冻结 Schema 的
  `connection={mode:"CREATE",command:{...auth:{kind:"NONE"}}}`，不再要求隐藏或预置 Connection ID。
- `ApiResourceAuthoringFacade` 在 claim 前复用 `ApiConnectionDecisions` 校验 nested command，只接受
  `Auth.NONE`。获得 command authority 后由 `commandId` 确定性派生 child Connection ID；Connection 与 Resource
  共享 exact attempt，Resource compiler 只解析该 attempt 的 staged payload-free Connection snapshot。
- 最终事务依次关闭 Connection child、可选 Default Fixture child 和 Resource receipt；事务失败时 child stage 与
  Resource stage 均回滚/清理，Connection 只在 canonical Resource receipt 持久化后发布。重放返回同一 Resource
  receipt 与 derived Connection，不产生第二条 head。
- Controller 有独立 nested-create JSON 解码回归；JDBC integration 覆盖成功/replay 与强制 outer commit failure
  后两类 authority 均不可见。JavaDoc 明确 credential-free child、stage-aware resolver 与发布边界。
- 真实浏览器不再 seed/pick `crm`：OpenAPI preview、legacy re-author、两 API → Tool DAG → Default Fixture 三条方法
  均通过；主链确认两个 API 使用两个不同的 `connection-*` authority，并在 28 个主动作内完成。

### TDD 与验证

```text
后端聚焦：Controller + compound facade/JDBC + configuration + projection + Connection stores
Tests run: 141; Failures: 0; Errors: 0; Skipped: 0

前端聚焦：2 files / 12 tests passed
i18n: 6 files / 39 tests passed
UX: 4 files / 52 tests passed
host: 3 files / 21 tests passed
TypeScript: passed
Vite + bundle: passed; AuthorCanvas startup closure 350.00 KiB budget内

真实浏览器：3 tests; Failures: 0; Errors: 0; Skipped: 0
主链：primaryActions=28

全量门禁：Tests run: 8,259; Failures: 0; Errors: 0; Skipped: 38
BUILD SUCCESS; 11:43
```

### 当前差距评估

本轮关闭了标准 API 页的本地 P1。仍有一个主要本地 P1：简单 Flow 页尚未从统一 Catalog 选择 immutable
`FLOW_VERSION` 作为父级节点，也没有可见的节点级 Fixture `APPLY_CASE` 创作/模拟闭环。390 px 完整链、迁移
coverage receipt/bulk replay 与真实 PostgreSQL/Vault 属于 P2 或外部环境证据。当前本地实现/验收差距保守估计
约 **3%–5%**，仍未满足低于 3% 的停止条件；下一步继续关闭 Flow Version 父级复用与节点 Fixture 主线。
