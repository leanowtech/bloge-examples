# S2 有状态世界与函数返回值注入技术方案

本文把 [`rg-evolution-design-1.2.1.md`](./rg-evolution-design-1.2.1.md) 的阶段二展开为可直接实施、可独立验收的工程方案。当前状态为 `IN_PROGRESS`：设计边界已经冻结，`S2-A` 版本化状态资产、`S2-B` 有状态片段独立试跑和 `S2-C` 统一运行时状态会话已通过开发验证；`S2-D..E` 尚未实现，阶段二整体尚未达到 `DEVELOPMENT_VERIFIED`。

## 1. 结论先行

阶段二不能被实现成“给 World Delegate 加一个并发 Map，再让函数 fixture 复用算子 selector”。这两种做法都会制造无法证明的行为：

1. 锁只能防数据竞争，不能证明并行分支的业务顺序确定；
2. 算子调用点与表达式函数调用点的身份、生命周期和准入语义不同；
3. 把状态值写进普通 evidence 会扩大敏感数据暴露面；
4. BLOGE 当前只让依赖执行服务的函数经过 `ExpressionFunctionResolver`，无法对纯函数做受控强注入；
5. 运行时发现冲突再补救已经太晚，状态拓扑和函数可替换性必须在编译期冻结。

因此采用以下架构：

- **状态声明是资产协议**：引入版本化 `StateSpecV2`，不把任意 Map 塞回阶段一的 `StateSpec`；
- **状态冲突在编译期消除**：单键单写者，并证明所有读写冲突节点在 DAG 上可达；不可排序就拒绝编译；
- **状态转移是原子纯函数**：World Fragment 接收请求和只读状态快照，返回响应和写集；运行时校验后一次提交；
- **状态会话按 Scenario Run 隔离**：不使用进程全局状态，也不复用业务镜像 Session；
- **函数控制独立编译**：函数规则生成 `CompiledFunctionControlPlan`，由运行期函数 resolver 消费；
- **原函数属性决定证据等级**：外部依赖函数可保持契约级，纯函数强注入必定降为探索级；
- **BLOGE 先补最小内核扩展点**：所有表达式函数都经过 resolver，默认 `DIRECT` 保持旧行为和二进制调用方式。

## 2. 范围

### 2.1 本阶段交付

- 世界状态声明、剧情初始状态覆盖和规范指纹；
- 状态访问拓扑校验、原子状态会话、快照、恢复和负载无关 evidence；
- 有状态 World Fragment 的独立试跑与统一 `WORLD_DELEGATE` 执行；
- 函数调用点的精确身份、规则编译、返回值注入、消费计数和准入；
- 环境事实函数、外部依赖函数、纯函数强注入三类证据语义；
- 生产隔离、失败关闭、确定性重放、跨剧情隔离和全量回归。

### 2.2 明确不做

- 不把 World State 变成业务生产状态或通用 KV 服务；
- 不允许 World Fragment 发网络请求、调用外部算子或递归委托 World；
- 不把原始状态值写入默认运行证据、日志或控制头；
- 不支持状态冲突的“最后写入者获胜”；
- 不在阶段二实现跨进程共享状态或长期状态资产治理；
- 不删除阶段一无状态协议和兼容构造器。

## 3. 领域模型

### 3.1 状态声明

阶段一 `StateSpec.empty()` 继续代表 `bloge.worldStateSpec.v1`。阶段二新增真正独立的版本化类型：

```java
sealed interface WorldStateSpec permits StateSpec, StateSpecV2 {
    String schemaVersion();
    boolean isEmpty();
    Map<String, Object> fingerprintMaterial();
}

record StateSpecV2(
    String schemaVersion,
    List<StateKeySpec> keys
) implements WorldStateSpec {}

record StateKeySpec(
    String key,
    Access access,              // READ / WRITE / READ_WRITE
    Map<String, Object> schema, // JSON Schema
    Object defaultValue
) {}
```

规则：

- key 使用受限 JSON Pointer 风格路径，规范化后唯一；
- 默认值必须满足 schema；
- 同一 World Model 内，一个 key 恰好有一个写者，可有多个读者；
- 多个切片声明同一 key 时，schema 和默认值指纹必须一致；
- 只读声明不能单独创造默认值；
- 状态声明、schema、默认值和访问模式进入切片及 World Model 指纹；
- 最大 key 数、schema 字节数、默认值字节数和嵌套深度固定受限。

### 3.2 剧情初始状态

把 `Scenario.WorldStateInit` 从只有 `EMPTY` 的枚举演进为版本化值对象，同时保留 `WorldStateInit.EMPTY`：

```java
record WorldStateInit(String schemaVersion, Map<String, Object> overrides) {
    static final WorldStateInit EMPTY = ...;
}
```

Scenario 构造时完成以下校验：

- override 只能引用 World Model 已声明的 key；
- 值必须满足对应 schema；
- 规范化后的 override 进入 Scenario 指纹；
- 不允许通过 context 暗中初始化状态。

### 3.3 有状态片段协议

无状态片段继续接收原始渲染请求并直接返回响应。有状态片段使用固定信封：

```json
{
  "request": { "accountId": "A-1", "amount": 20 },
  "state": { "balance": 100 }
}
```

片段必须返回：

```json
{
  "response": { "accepted": true, "balance": 80 },
  "stateWrites": { "balance": 80 }
}
```

`stateWrites` 是写集而不是全量新状态，避免旧快照覆盖其他键。运行时拒绝：

- 未声明 key、只读 key 写入、缺失 `response`；
- 写值不满足 schema；
- 输出超过大小或深度上限；
- Fragment 失败后仍尝试提交；
- 返回状态键的顺序或 Map 实现影响指纹。

## 4. 状态运行时

### 4.1 深模块边界

新增 `WorldStateSession`，对外只暴露四个动作：

```java
WorldStateView read(StateAccessPlan access);
<T> T transition(WorldInvocationCoordinate coordinate,
                   StateAccessPlan access,
                   Function<WorldStateView, StateTransition<T>> evaluator);
WorldStateSnapshot snapshot();
void restore(WorldStateSnapshot snapshot);
```

调用方不能拿到可变 Map，也不能绕过 `transition` 修改状态。Fragment 在锁内拿到冻结快照，完成纯计算；只有响应 schema 和写集校验全部通过后才原子提交。异常、超时、取消和 schema 失败均不提交。

### 4.2 确定性不是锁出来的

编译器为每个选中切片生成 `StateAccessPlan`，并把其映射到图节点。对任意两个访问同一 key、且至少一个写入的节点，必须满足以下任一条件：

1. 节点 A 在 DAG 上可达节点 B；
2. 节点 B 在 DAG 上可达节点 A；
3. 两者属于同一稳定顺序的受控循环，且循环坐标已被内核冻结。

否则以 `WORLD_STATE_ACCESS_ORDER_AMBIGUOUS` 拒绝编译。阶段二首版不接受并行 foreach 对状态 key 的写入，也不把线程抢锁顺序当作业务顺序。

由此得到两个性质：

- 冲突访问已有业务拓扑顺序；
- 无冲突的并行访问彼此可交换，实际调度顺序不影响最终状态。

事务日志按 `graphPath + nodeId + graphOccurrence + occurrence + attempt` 规范排序，仅记录坐标、读写 key、read-set 指纹、write-set 指纹和结果，不记录值。不能记录全量状态的前后指纹，否则两个互不冲突的并行事务仍会因实际提交先后不同而产生不同日志。

### 4.3 隔离和恢复

- 每次 Scenario Run 创建一个 `WorldStateSession`；
- session 身份绑定 `scenario fingerprint + world fingerprint + graph artifact fingerprint + runId`；
- 两次剧情即使引用同一 World revision，也不能共享内存对象或状态头；
- `WorldStateSnapshot` 包含规范状态值、版本、绑定指纹和快照指纹，可序列化并严格恢复；
- 默认 evidence 只保存状态快照指纹、事务数和键名集合；
- 需要保存原始状态快照时，必须走阶段一治理资产库的 payload 分级路径，不得内联到 evidence。

## 5. 函数返回值注入

### 5.1 BLOGE 最小前置改造

当前 `ExpressionCompiler` 仅在 `requiredExecutionServices()` 非空时调用 resolver，导致纯函数无法被受控替换；当前函数上下文也没有完整嵌套图路径。BLOGE 需要补两个通用、非 Resource Gateway 专属的执行坐标能力：

- 每个表达式函数调用都执行 `expressionFunctionResolver.resolve(callSite, registeredFunction)`；
- 默认 `ExpressionFunctionResolver.DIRECT` 原样返回注册函数；
- `GraphContext` 携带不进入业务 Map 的只读 `graphPathScope`，根图固定为 `/root`，嵌套图沿用 `OperatorResolutionScope` 的 path 规则；snapshot 和 node scope 必须保留该坐标；
- `FunctionInvocationContext` 通过方法暴露 `graphPath + nodeId + source line + source column`，不要求函数从业务 context 猜路径；
- DSL 编译时同步形成类型化、不可变的函数调用清单，至少包含所属 nodeId、函数名、源码行列和调用位置类别；清单作为 BLOGE 通用编译产物公开，不编码 Resource Gateway 规则；
- transform 的纯净校验仍检查**原注册函数**，不能靠替身把非纯函数伪装成纯函数；
- resolver 返回 null、名称漂移或能力声明漂移时失败关闭；
- resolver 能读取原函数 `isPure()` 与 `requiredExecutionServices()`，但不能修改这些治理事实。

这项改造不把 Resource Gateway 类型下沉进 BLOGE，也不改变未配置 resolver 的执行结果。函数调用清单必须在解析 AST 时生成并随编译图保留，不能由 Resource Gateway 反向解析 DSL、读取闭包私有字段或依赖运行一次后才发现的动态 trace。根图与嵌套图各自保存局部清单；上层沿与 operator inventory 相同的嵌套图遍历规则组合完整 graphPath，从而保持内核产物通用、调用身份完整。

### 5.2 独立函数控制计划

函数调用点不是 `InvocationInventory.Entry`。新增：

```java
record FunctionInvocationSite(
    String graphPath,
    String nodeId,
    String functionName,
    int line,
    int column
) {}

record CompiledFunctionControlPlan(
    List<ResolvedFunctionControl> controls,
    String fingerprint
) {}
```

函数规则至少支持 `functionName + line + column` 精确调用点；只按函数名选择属于显式宽匹配，若命中多个结构调用点且消费语义不能证明等价，则编译失败。函数规则不进入算子 `SelectorResolver`，也不生成假 NodeSpec。

`CompiledFunctionControlPlan` 必须从 BLOGE 的静态函数调用清单编译，而不是只保存待运行时匹配的规则。编译器逐项对拍函数库声明与运行时注册事实，冻结所有命中调用点、原函数治理事实、行为、消费约束和证据上限；规则命中零调用点、同优先级重叠、宽匹配歧义或调用点身份不完整时失败关闭。运行时 resolver 只能消费该冻结计划，不得重新解释 selector 或读取可变函数库。

如果 BLOGE 暂时不能向 resolver 提供完整 `graphPath`，Resource Gateway 必须在该范围失败关闭，不能用相同 nodeId 猜测嵌套图。完整调用点身份是阶段二出口条件，不接受长期降级。

### 5.3 三类准入

| 原函数类型 | 判断依据 | 可否注入 | 最高证据等级 |
|---|---|---:|---|
| 环境事实型 | 声明 `requiredExecutionServices` | 是，优先使用已有执行服务 fixture | `CERTIFIABLE` |
| 外部查询型 | `isPure=false` 或显式外部依赖声明 | 是 | `CERTIFIABLE` |
| 纯函数 | `isPure=true` 且无外部依赖 | 是，但必须显式 `forcePureOverride` | `EXPLORATORY` |

函数库 schema 必须补充 `pure`、`requiredExecutionServices` 和 `effect`。运行时登记事实与声明不一致时失败关闭。旧函数库没有这些字段时状态为 `UNKNOWN`，只能预览，不能产出契约级函数替身证据。

### 5.4 运行语义

- resolver 按精确调用点选择规则；
- 输入匹配使用规范化参数数组，不读取任意 GraphContext 业务数据；
- `RETURN`、`THROW`、`DELAY`、`TIMEOUT` 和消费次数复用 fixture 的语义，但由函数专用执行器实现；
- 未命中默认调用原函数；对声明为必须替换的规则，未消费使测试失败；
- 函数 trace 记录调用点、原函数指纹、控制模式、消费次数、参数/返回值指纹和证据降级原因，不默认记录参数或返回值；
- 对纯函数强注入时，证据降级必须发生在服务端聚合层，调用方不能覆盖。

## 6. 与既有链路的组合

执行顺序固定为：

1. 解析并授权 Scenario/World 精确引用；
2. 校验状态声明和 Scenario 初值；
3. 编译 World fixture、状态访问计划和函数控制计划；
4. 完成生产目的、容量和安全准入；
5. 创建 run-scoped `WorldStateSession` 与函数 resolver；
6. 执行统一内核；
7. 原子形成执行服务、fixture 消费和 World State checkpoint；
8. 聚合断言、状态观察、函数观察和证据等级；
9. 持久化 evidence；
10. 释放 admission guard。

任何第 1 至 4 步失败时，引擎、World Fragment 和函数替身调用均为零。第 5 至 8 步失败时不得持久化“通过”证据，状态 checkpoint 只能标为失败或不存在。

## 7. 实施切片

### S2-A：版本化状态资产

- `WorldStateSpec`、`StateSpecV2`、`WorldStateInit`；
- World Model 单键单写者和声明一致性校验；
- codec、治理资产往返和旧 v1 无状态兼容；
- 状态声明、初值和访问计划进入稳定指纹。

### S2-B：独立状态片段试跑

- 有状态片段输入/输出信封；
- 原子写集校验；
- `WorldFragmentTestKit` 返回响应与新状态；
- 重放 N 次响应、状态和指纹逐位一致。

开发验证证据：提交 `7af01cb1d`；最终聚焦回归 61/61，全量 `resource-gateway-examples clean verify` 为 7298 tests、0 failures、0 errors、28 skipped。该证据只关闭独立片段边界，不替代 `S2-C` 的 run-scoped 状态会话与整图运行证明。

### S2-C：统一运行时状态会话

- `WorldStateSession`、snapshot/restore、事务观察；
- 编译期冲突可达性证明；
- `WorldDelegateRuntime` 和 `WorldScenarioRunService` 接线；
- 跨剧情隔离、写后读、失败不提交和并发可交换性证明。

开发验证证据：提交 `226765b41`；最终聚焦回归 125/125，全量 `resource-gateway-examples clean verify` 为 7335 tests、0 failures、0 errors、28 skipped。实现以编译期冻结的 `StateAccessPlan` 约束运行期读写，session 严格绑定 Scenario、World、Graph 和 runId；状态转移在响应、写集、schema 和指纹全部校验后一次提交，快照支持普通 JSON 往返并拒绝跨绑定或篡改恢复。动态调用坐标、防重复执行、防重入、事务上限、状态大小上限、无冲突事务规范排序和历史 v1 指纹 golden 均有固定测试。

当前 BLOGE 尚未公开 foreach 的稳定顺序事实，因此 S2-C 对包含有状态子图的所有 foreach 失败关闭，不以私有字段、反射或实际线程顺序推断安全性。`S2-D` 补齐公开执行模式事实后，只重新开放可证明为顺序执行且坐标稳定的循环；并行或无法证明的循环继续以 `WORLD_STATE_ACCESS_ORDER_AMBIGUOUS` 拒绝。

### S2-D：函数调用点与 BLOGE resolver

- BLOGE 全函数 resolver 钩子及兼容测试；
- BLOGE 类型化静态函数调用清单及嵌套图遍历身份；
- 精确 `FunctionInvocationSite`；
- 函数库纯度和执行服务声明；
- `CompiledFunctionControlPlan`、运行时 resolver 和消费证据。

`S2-D1` BLOGE 通用内核前置已完成开发验证，嵌套仓库提交为 `8d514a7f6`。所有表达式函数均经过 resolver，replacement 的名称、纯度和执行服务声明必须与注册事实一致；根图历史 invocation scope 保持不变，foreach、loop、import、transform 和 decision table 的真实引擎测试证明 graphPath/nodeId/源码坐标完整且不进入业务 context。新增 `CompiledGraph` sidecar 在不修改 `Graph` / `NodeSpec` record 结构的前提下保存根图、导入图和内联子图的静态函数调用清单；两类 foreach 公开稳定 `sequential()` 事实。`bloge-core` 1959 项、`bloge-dsl` 1567 项测试均为 0 failures / 0 errors，其中 DSL 保留 1 项既有跳过。

`S2-D2a` 已关闭 Resource Gateway 静态控制面：`CompiledGraph` 与运行时 Invocation Inventory 按图对象身份对拍，可为根图、嵌套图和复用子图生成完整调用点；函数库声明与运行时名称、alias、纯度和执行服务事实逐项对拍，漂移失败关闭。控制规则支持精确参数候选与最终 wildcard fallback，冻结 RETURN、THROW、DELAY、TIMEOUT、消费上下限和纯函数强注入；精确候选始终优先，零命中、重复精确参数、多个 fallback 和宽匹配歧义均拒绝。显式 JSON `null` 与“未提供返回值”保持不同语义，schema/value、深度、数量、文本和 duration 均受限。公开计划只包含指纹和候选元数据，不暴露参数、返回值、错误文本或 schema；UNKNOWN/LEGACY、纯函数强注入和非纯函数受控替身具有明确证据上限。架构测试禁止该模块依赖 operator fixture 的 `FixtureRule`、`SelectorResolver` 或 BLOGE `NodeSpec`。聚焦验证为 18/18，0 failures、0 errors、0 skipped。

该证据只证明函数控制可被静态、确定性、失败关闭地编译，不代表 Resource Gateway 已可在 DAG 运行中注入函数。运行 wrapper、精确参数运行期选择、RETURN/THROW/DELAY/TIMEOUT 行为、线程安全消费观察、未消费/耗尽判定、证据降级和服务端集成仍须由 `S2-D2b` 闭合。

### S2-E：证据、系统测试和里程碑

- 状态与函数 payload-free evidence；
- 三类函数证据等级矩阵；
- 真实 HTTP Scenario 引用的有状态整链测试；
- production profile/服务端旁路拒绝；
- Resource Gateway 与 Test Kit 双项目 `clean verify`。

## 8. 固定验收矩阵

| 编号 | 必须证明的事实 |
|---|---|
| `S2-EXIT-01` | v1 无状态资产不改指纹、不改运行结果，可被旧 codec 恢复 |
| `S2-EXIT-02` | 非法 key、schema/default 漂移、多写者在资产构造或编译期失败 |
| `S2-EXIT-03` | 冲突状态访问无 DAG 顺序时失败，不能退化为抢锁顺序 |
| `S2-EXIT-04` | 写后读得到新值，Fragment/Schema/超时失败不提交任何写入 |
| `S2-EXIT-05` | 两个 Scenario Run 使用同一 World 时状态对象、快照和事务完全隔离 |
| `S2-EXIT-06` | 同一请求连续重放 20 次，响应、最终状态和语义证据指纹一致 |
| `S2-EXIT-07` | 并行无冲突访问的最终状态和规范事务投影与调度顺序无关 |
| `S2-EXIT-08` | 状态快照可序列化、指纹可验、错误绑定和篡改恢复失败关闭 |
| `S2-EXIT-09` | 所有函数均经过 resolver；默认 DIRECT 与改造前行为等价 |
| `S2-EXIT-10` | 外部依赖函数可注入并保留契约级证据，声明/运行事实漂移失败 |
| `S2-EXIT-11` | 纯函数只有显式强制时可注入，且 evidence 必定为 `EXPLORATORY` |
| `S2-EXIT-12` | 精确调用点、多调用点歧义、消费次数和未命中语义均有负向测试 |
| `S2-EXIT-13` | 默认 evidence、异常和日志不含状态值、函数参数或函数返回值 |
| `S2-EXIT-14` | 生产入口与服务端任一层单独失守，状态/函数控制仍不能执行 |
| `S2-EXIT-15` | 真实 HTTP 引用链和双项目全量回归通过 |

## 9. 风险与根治手段

| 风险 | 表面补丁 | 根治手段 |
|---|---|---|
| 并行状态结果漂移 | 公平锁 | 编译期冲突可达性证明，模糊顺序直接拒绝 |
| 失败后部分写入 | 回滚 Map | Fragment 返回写集，完整校验后一次提交 |
| 状态跨剧情污染 | 清空全局缓存 | run-scoped session，无静态状态 |
| 快照被串用 | 只校验 runId | 绑定 Scenario、World、Graph 与内容指纹 |
| 函数 selector 与算子混淆 | 给 NodeSpec 造假节点 | 独立函数调用点和控制计划 |
| 纯函数替换被误认证 | UI 警告 | 服务端强制 evidence 降级，不接受调用方覆盖 |
| 库声明撒谎 | 相信 schema | 与运行时 `ExpressionFunction` 事实对拍，漂移失败 |
| 嵌套图调用点碰撞 | 用 nodeId 猜 | 完整 graphPath 身份；缺失时失败关闭 |
| evidence 泄漏状态 | 统一脱敏字符串 | 默认只记录 key 和指纹，原值走治理 payload |
| BLOGE 扩展侵入业务 | 在引擎认识 fixture | 仅扩展通用 resolver 覆盖面，RG 语义留在应用层 |

## 10. 设计自审

按边界清晰度、确定性、安全隔离、兼容性、可测试性、可运维性和实施可拆分性七个维度审阅：

| 维度 | 得分 | 剩余扣分 |
|---|---:|---|
| 边界与职责 | 98 | BLOGE 与 RG 需要两个仓库协调发布 |
| 确定性 | 97 | 受控循环需在 S2-D 取得 BLOGE 公开稳定顺序事实后才能开放 |
| 安全与隔离 | 97 | 原始状态快照的长期治理留到后续阶段 |
| 兼容性 | 96 | `WorldSlice` 状态接口迁移需要兼容构造器和 codec 双读 |
| 可测试性 | 98 | 系统级故障注入耗时较高 |
| 可运维性 | 95 | 尚未定义跨进程状态快照保留和清理策略 |
| 实施可拆分性 | 98 | S2-D 依赖 BLOGE 前置提交 |

综合评分 **97/100**。未达到 100 分的部分属于明确推迟的跨进程状态治理和双仓发布协调，不影响阶段二的开发出口。任何实现若取消“冲突访问编译期拒绝”“纯函数强注入证据降级”或“默认 evidence 不含状态值”三项不变量，设计评分直接降到 80 分以下，不得进入实现验收。
