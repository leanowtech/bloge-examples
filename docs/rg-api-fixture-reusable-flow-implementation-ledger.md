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

`d4d57c5d6` 后的初始 `ApiConnectionSchemaReadinessTest` 为 **19/19 green**，发生在后续语义变更之前。`eb3c40540` 与 `19a5c8a1f` 的最新语义提交有独立 `javac`/helper 检查，Spec 评审为 **Accepted**，P0/P1/P2 均为 0；但最新 Maven 聚焦复跑因并发的未提交 Connection/JDBC 编译错误受阻。真实 PostgreSQL lane 仍未运行。

### 当前差距评估

本轮只完成 V003 schema/readiness 的 hardening；由于没有 production store 的聚焦最终 Maven 证据，不增加 broader goal 完成度，仍为 **35%**，当前差距为 **65%**。

## 14. 未关闭风险

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
