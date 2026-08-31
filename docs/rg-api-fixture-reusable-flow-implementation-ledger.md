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
