# ADR-007：Capability Studio 资产投影与编译边界

> 状态：Proposed（Stage 0，尚未签署）
>
> 日期：2026-08-17
>
> 决策范围：Resource Gateway 能力设计工作台的 `CapabilityAsset`、`ScenarioDataset`、行为表现、编译、运行隔离和验收证据

## 1. 背景

Resource Gateway 已经具备 GraphDraft、场景草稿、FixtureBundle、MirrorPlan、运行追踪和正确性工作台等技术底座，但这些能力分别以实现模块呈现。能力设计工作台需要让业务作者连续完成「定义业务接口 → 准备场景数据 → 编排业务特征 → 定义工具承诺 → 隔离试跑 → 检查证据」。

如果把已有对象直接改名为业务对象，短期可以减少新类型，长期会把不同生命周期、权限边界和语义混在一起：Graph 是实现视图，不是能力契约；FixtureBundle 是运行输入，不是场景数据目录；MirrorPlan 是隔离执行计划，不是业务绑定计划；CapabilitySnapshot 是跨系统投影，不是 Capability Studio 的编辑聚合。

本 ADR 以《Resource Gateway 能力设计工作台产品与技术演进方案》为权威，约束 Stage 0 以后所有实现和协议设计。

## 2. 决策

### 2.1 新建业务投影，不通过重命名复用

新增 Capability Studio 自己的产品投影：

| 产品对象 | 作用 | 权威边界 | 不承载的内容 |
|---|---|---|---|
| `CapabilityAssetDraft` / `CapabilityAssetSnapshot` | 表达业务接口、业务特征和业务工具的定义、依赖和生命周期 | Capability Studio 产品聚合；Snapshot 是可引用的不可变投影 | 不替代 Graph、运行计划或业务治理注册表 |
| `ScenarioDatasetDraft` / `ScenarioDatasetSnapshot` | 管理场景分母、Case、Owner、Oracle、来源、质量和适用契约 | 场景数据产品聚合 | 不复制 FixtureBundle 的 payload material |
| `DataCaseRevision` | 表达一个业务条件下的输入、预期和适用范围 | Dataset 的不可变 Case 修订 | 不直接成为 Runtime 的执行规则 |
| `DependencyBehaviorProfileRevision` | 表达依赖在该场景中的返回、错误、超时、顺序消费或禁止访问 | Dataset 的依赖行为子资产 | 不允许隐式 fallback 到真实服务 |
| `CompiledBindingPlan` | 记录一次确定性编译后的精确引用、策略和 source map | Capability Studio 编译产物 | 不替代 `MirrorPlan`、`EffectiveExecutionPlan` |

上述对象是业务作者和跨系统协议的稳定投影。它们必须通过适配器编译到现有执行权威，而不是新建第二套 DAG 或测试 Runtime。

### 2.2 保留现有 Authority，不做 Authority 迁移

以下对象继续保持现有语义和权威地位：

- `GraphDraft`：图结构、节点、边和画布编排的编辑权威；特征 DAG 仍由它表达。
- `ScenarioDraftSetV2`：现有场景草稿和受控依赖语义的兼容权威；Dataset 编译器向其下沉。
- `FixtureBundle`：Runtime 可消费的 fixture material 组合；payload 只在受控 material 存储中管理。
- `MirrorPlan`：隔离演练的调用决策、真实调用拒绝和外部依赖策略权威。
- `EffectiveExecutionPlan`：一次运行的最终执行计划权威。
- `CapabilitySnapshot`：现有跨系统能力快照与精确资产引用投影；Capability Studio 可以引用它，但不把它改名成新的产品聚合。

任何新增字段必须说明它是产品投影、编译产物还是运行时权威。没有该说明的字段不得进入跨系统协议。

### 2.3 精确引用、payload 边界与 fingerprint

所有跨对象引用使用带 `kind`、`id`、`revision`、`fingerprint`、`authority` 和 `scope` 的 exact ref。引用对象必须指向不可变修订，禁止只传一个人类可读 ID 让接收方猜测库、版本或租户。

Stage 0 的 payload-free 演示包尚未物化独立的 Dataset、Graph 和 Binding 运行制品。演示包内的子引用使用域隔离的坐标摘要 `sha256("capability-studio-demo-v1|kind|id|revision")`，用于拒绝占位值和检测坐标漂移；只有整包 `packFingerprint` 是演示投影内容的内容地址。坐标摘要不得作为外部运行制品已存在或内容已验真的证据。进入 Stage 1 后，各 Authority 必须返回真实内容 fingerprint，编译器必须解析并验证内容闭包，才能生成可运行 Snapshot 和 `ACCEPTED` Manifest。

协议和普通投影只保存：

- exact ref、revision、fingerprint、source map 和状态；
- payload 的 material ref、脱敏摘要、schema fingerprint 或允许展示的 sample fingerprint；
- owner、来源、质量、权限、保留和撤销元数据。

业务请求、响应、fixture payload 和 mock material 不进入目录列表、普通日志、Manifest 或 `CompiledBindingPlan`。需要查看 payload 时必须经过授权的 material 服务，并按脱敏策略返回。任何导出、缓存、异常和指标都必须遵循同一边界。

### 2.4 Draft 与 Snapshot 分离

- Draft 可编辑、可产生冲突、可被撤回；它可以引用其他 Draft，但不得作为运行和跨系统治理的稳定输入。
- Snapshot 在校验依赖闭包、契约兼容、Owner、Oracle、Scope 和策略后生成，具备 revision 和 fingerprint，可被 Dataset、Binding Plan、Evidence 和外部系统精确引用。
- Snapshot 一经引用不得原地修改。任何修改都生成新的 revision，并触发依赖方 stale 传播和重新编译。
- Tutorial Branch 必须从 Canonical Baseline 派生为独立 revision；教学失败不得写回 Canonical Baseline。

### 2.5 编译路径复用现有 Runtime

编译路径固定为：

```text
CapabilityAssetSnapshot + ScenarioDatasetSnapshot
  -> exact reference resolution
  -> ScenarioDraftSetV2 adapter
  -> existing correctness/scenario compiler
  -> FixtureBundle + TestSuite
  -> ExecutionControlCompiler
  -> MirrorPlan / EffectiveExecutionPlan
  -> BLOGE Runtime + RunTrace / Evidence
```

`CompiledBindingPlan` 仅保存上述产物的 exact refs、策略 fingerprint、编译器版本、source map 和拒绝原因。它不复制这些对象的完整内容，也不实现一套新的调度逻辑。

编译器必须：

1. 对 Contract、Graph、Dataset、Case、Behavior 和 Policy 做闭包解析；
2. 对歧义 selector、缺失调用点、不兼容 revision 和未授权 Scope fail closed；
3. 对 `RETURN`、`ERROR`、`TIMEOUT`、顺序消费和 `MUST_NOT_CALL` 保留语义；
4. 对暂不支持的 `REPLAY`、函数 selector、传输行为或 `REPEAT_LAST` 明确拒绝，不静默降级；
5. 相同输入在相同 compiler/policy 版本下得到相同 semantic fingerprint；
6. 产生可追溯 source map，使 Runtime 规则可以回到 Dataset、Case、Behavior 和界面字段。

### 2.6 生产隔离是装配和协议约束

生产运行面必须物理排除调用方注入 fixture、mock、replay 和 binding override 的能力，不能只依赖 UI 隐藏按钮。

最低不变量：

- `production` 与 `production,test` profile 下，测试注入 route、decoder、repository、marker bean 和测试身份均不存在；
- 普通运行 DTO 中出现 fixture、mock、replay、binding override 字段时，生产入口 fail closed 并产生安全审计事件；
- 隔离运行禁止 `FALLBACK_TO_REAL`；无可用替身时在调度前失败；
- 外部网络访问由 network policy 和可观测计数双重证明，不能用“没有日志”推断零调用；
- Dataset、Material、Evidence 按 Enterprise Scope 和分类策略授权，跨 Scope 读取和绑定失败关闭；
- Payload 与 metadata 分离，日志、错误、指标和 Manifest 默认不泄露 payload。

### 2.7 验收证据属于独立协议

Stage 0 使用独立的 `CapabilityStudioGoldenPathAcceptanceManifest v1` 记录 GP-01 至 GP-10、9 个 Scenario、浏览器、可访问性、协议、安全、真实调用计数、限制和签署状态。它不复用 Business Mirror Pilot 的 gate ID，因为两者的验收对象不同：前者验证能力设计工作台的产品与工程闭环，后者验证客户业务镜像包。

Manifest 只消费精确引用和证据指针，不把运行 payload 复制进验收制品。`ACCEPTED` 需要跨字段验证器确认闭包、全量结果、零真实调用、签署和时间关系；JSON Schema 只负责结构约束。

## 3. 被拒绝方案

### 3.1 通过重命名复用现有对象

拒绝原因：会把编辑聚合、运行 material、隔离策略和治理快照混为一体；无法表达 Dataset 的来源、Owner、Oracle、质量和分母，也会迫使外部系统依赖内部对象字段。

### 3.2 新建第二套 Graph 或测试 Runtime

拒绝原因：会产生双重执行语义、双重 trace 和长期漂移；业务化模型的价值在于降低入口门槛，不在于复制执行内核。

### 3.3 直接把 FixtureBundle 作为场景数据中心

拒绝原因：FixtureBundle 以 Runtime 消费为中心，不能独立管理业务场景、预期、覆盖、数据来源和生命周期；payload 也容易被错误暴露到普通投影。

### 3.4 让生产接口接受 mock override，再靠权限控制

拒绝原因：单点权限配置、路由复用或身份误配就可能改变生产业务行为；工业级隔离要求装配、DTO、网络和审计多层 fail closed。

### 3.5 以一个总分或“运行成功”替代验收

拒绝原因：无法知道是契约缺失、数据缺口、错误 Oracle、节点失败还是安全边界未证明；也无法支持 ANEKE 的治理门禁消费。

## 4. 迁移约束

1. 第一阶段只新增产品投影和编译适配器，不修改现有 Authority 的字段语义。
2. 现有 GraphDraft、ScenarioDraftSetV2、FixtureBundle、MirrorPlan、EffectiveExecutionPlan 和 CapabilitySnapshot 的协议版本保持兼容。
3. 新 Schema 必须 `additionalProperties: false`，未知字段拒绝；新增字段只能通过新 schema version 或明确可选扩展进入。
4. 旧 Author、Correctness Studio 和 Business Mirror 作为上下文视图保留，Capability Studio 成为统一入口后通过 exact ref 和 deep link 互相跳转。
5. 新 Dataset 的每个 Active Case 必须有契约 ref、Owner、Oracle、来源和适用 Scope；缺一项只能处于 Draft/Blocked。
6. 任何 `FALLBACK_TO_REAL`、未解析依赖、跨 Scope 引用和不支持的行为在编译阶段拒绝，不得由 UI 或 Runtime 静默修正。
7. Golden Demo Pack 先于大规模页面重构进入仓库；Stage 0 Acceptance Baseline 未签署时，整体状态保持 `NO_GO`。
8. 迁移期间所有适配器必须保留 source map 和原始 exact refs，允许问题从业务投影回到既有运行对象。

## 5. 后果

正面后果：业务对象更稳定，现有执行内核得到复用，跨系统可以消费精确协议，生产隔离可以独立审计，数据质量和正确性证据有清晰归属。

代价：需要维护投影与 Authority 之间的编译适配器，需要处理 stale、冲突和 Scope 闭包，也不能在 Stage 0 通过静态样例伪造完整能力。Schema、Manifest、编译器和隔离测试必须同步演进。

## 6. 验收与后续审阅

本 ADR 不表示实现已完成。Stage 0 的当前结论由以下制品记录：

- [Capability Studio Acceptance Baseline v1](../acceptance/capability-studio/capability-studio-acceptance-baseline-v1.json)
- [Golden Path Acceptance Manifest v1 NO_GO fixture](../acceptance/capability-studio/capability-studio-golden-path-acceptance-manifest-v1.no-go.fixture.json)
- [Screen State Inventory v1](../acceptance/capability-studio/screen-state-inventory-v1.md)
- [Requirement-to-evidence traceability matrix v1](../acceptance/capability-studio/requirement-to-evidence-traceability-matrix-v1.md)

只有完成各门禁的证据收集并由相应角色签署后，才可以把 ADR 状态从 `Proposed` 更新为 `Accepted`。
