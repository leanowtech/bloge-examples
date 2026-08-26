# Resource Gateway 1.2.1 实施与验收台账

本文是 [`rg-evolution-design-1.2.1.md`](./rg-evolution-design-1.2.1.md) 的实施伴随文档。设计文档定义目标和约束；本文只记录可复现的实现证据，不以代码存在、局部测试或人工判断替代阶段出口验收。

## 1. 状态规则

| 状态 | 含义 |
|---|---|
| `NOT_STARTED` | 尚无实现或验收证据 |
| `IN_PROGRESS` | 已开始实现，但阶段出口尚未全部闭合 |
| `DEVELOPMENT_VERIFIED` | 仓库内实现和固定自动化分母全部通过；不代表企业环境正式验收 |
| `BLOCKED_EXTERNAL` | 仓库内工作已完成，但缺少真实环境、真实资产或责任人签署 |

状态只能由本表列出的固定证据推进。阶段内任一必选项缺失时，阶段不得标记为 `DEVELOPMENT_VERIFIED`。

## 2. 总体状态

| 阶段 | 设计目标 | 当前状态 | 当前判断 |
|---|---|---|---|
| 阶段零 | 收敛执行内核、描述符传输替换、控制面隔离、旧路径适配 | `DEVELOPMENT_VERIFIED` | 八项阶段出口全部闭合；受治理大负载引用已通过真实 HTTP 链进入统一执行与证据管线，双项目最终回归全绿 |
| 阶段一 | 逻辑契约、世界模型、剧情、编译下沉和资产持久化 | `DEVELOPMENT_VERIFIED` | 四个切片全部闭合；无状态世界、Scenario、治理资产、两阶段授权和引用式端到端运行均形成固定开发证据 |
| 阶段二 | 有状态世界和函数返回值注入 | `IN_PROGRESS` | `S2-A..D` 已通过开发验证，状态会话与函数控制可在统一运行时组合；对外协议、生产隔离和系统里程碑仍由 `S2-E` 交付 |
| 阶段三 | 线上沉淀、影响分析和存量迁移 | `NOT_STARTED` | [S3 实施设计](./rg-evolution-design-1.2.1-s3-world-fidelity-and-migration-closure.md) 已冻结为 6 个切片和 17 项出口；现有 replay/corpus/review/impact/mutation 将被复用，领域闭环代码尚未开始 |

## 3. 阶段零验收矩阵

### 3.1 实现切片

| 切片 | 交付物 | 状态 | 证明要求 |
|---|---|---|---|
| `S0-A` | 七态 `ExecutionMode`；编译期模式固定；`DESCRIPTOR_PROTOCOL` / `DESCRIPTOR_TRANSPORT` 显式运行分派 | `DEVELOPMENT_VERIFIED` | 60/60：模式分类、compiler 到 runtime 的动态多规则实际选择、混合规则 fingerprint 稳定、提取语义对拍、模式不一致失败关闭、legacy graph-contract 兼容 |
| `S0-P` | 四个 `X-BLOGE-Test-*` 控制头的纯协议解析 | `DEVELOPMENT_VERIFIED` | 19/19：strict base64url/UTF-8/JSON、大小和复杂度边界、多值拒绝、错误不泄漏、inline canonicalization |
| `S0-B` | 服务端执行目的铸造与受控入口 admission | `DEVELOPMENT_VERIFIED` | 七类 Body 入口在反序列化前按固定 `IntegrationOperation` 认证；bounded inline fixture 进入既有解析链；caller purpose 不可改写内核 purpose |
| `S0-C` | visual 设计期模拟适配统一内核 | `DEVELOPMENT_VERIFIED` | `SCHEMA_STANDIN` 经 server-owned Java hint 在 compiler/runtime/evidence 中显式闭合；visual-owned port 隔离 testing 类型；Spring 默认服务走统一内核；新旧路径对 stand-in、纯原语混合、逐节点 fixture 与输入不匹配保持语义等价；生产拒绝和超时仍在服务边界生效 |
| `S0-D` | 生产入口控制头拒绝/剥离及服务端二次拒绝 | `DEVELOPMENT_VERIFIED` | profile/服务端环境双判定、context-path 安全执行路由全集、真实 Spring Filter 链与 visual service 独立 403 拒绝；server-owned policy Bean 不可由普通业务 Bean 条件替换 |
| `S0-E` | 新旧路径等价对拍、确定性和架构约束 | `DEVELOPMENT_VERIFIED` | visual 固定差分矩阵、朴素独立编译 oracle、每模式 20 次编译、20 次内核重放、八类核心代码非确定 API 门禁、visual/descriptor 系统级零网络和 ReDoS 负向矩阵全部固定 |

### 3.2 阶段出口

| 编号 | 必须满足的事实 | 当前证据 | 状态 |
|---|---|---|---|
| `S0-EXIT-01` | 无部署业务算子绑定时，描述符驱动的 `httpResource` 只替换传输并完整执行映射、URL、Header、协议和负载提取 | 图内 embedded binding、compiler registry 和 isolated engine registry 均为空时，run-scoped fail-closed binding 允许统一编译与执行；真实 `ResourceFixtureRuntime` 完成参数、URL、Header、Body、协议和负载管线，共享 registry 保持不变 | `MET` |
| `S0-EXIT-02` | 设计期模拟通过 adapter 进入统一内核 | Spring 默认 `VisualGraphSimulationService` 通过 visual-owned `VisualSimulationExecutor` 委托统一内核；旧四参数路径只保留为差分 oracle | `MET` |
| `S0-EXIT-03` | 控制走 Header，业务走 Body；有界 inline 可用，大负载只走引用 | bounded inline 与 Scenario/World 精确引用均进入既有执行链；真实 HTTP 系统测试证明 Header → 授权 → DB → 编译 → 运行 → 证据闭合，业务 Body 只承载输入 | `MET` |
| `S0-EXIT-04` | 生产入口和服务端目的形成两道独立拒绝 | 6 种固定执行模式分别证明：production Filter 在 DTO 反序列化前拒绝且下游编译/执行为零；绕过 Filter 后 production TestExecution service 在规划、registry、post-compile admission 和 executor 前独立拒绝；visual production service 在 kernel 前独立拒绝 | `MET` |
| `S0-EXIT-05` | 新内核与旧路径在节点/边上限、超时、逐节点 fixture 和混合分类上对等 | 14 项固定差分矩阵逐字段对拍 stand-in、纯原语混合、多节点分类、fixture 优先级/null/输入不匹配、输出覆盖、节点/边上限、校验/DSL 失败、双路径超时中断和异常净化 | `MET` |
| `S0-EXIT-06` | 模拟和描述符传输路径具备 SSRF 零网络证明，匹配器具备 ReDoS 负向证明 | descriptor 与 visual kernel 对本地可计数真实端点均保持零请求；危险嵌套量词/分组/交替/lookaround/backreference、超长/畸形 pattern 与超长 candidate 在匹配前拒绝；类文件规则禁止模拟关键类构造生产网络客户端 | `MET` |
| `S0-EXIT-07` | 编译等价、N 次重放确定性和禁非确定 API 架构规则通过 | 独立 oracle 对拍合法模式及失败关闭；每模式 20 次编译保持 plan/binding fingerprint 稳定且 planId 唯一；20 次运行保持语义与服务状态指纹稳定；八个阶段零核心类及关键嵌套类禁止直接时间、UUID、随机与并行流 API | `MET` |
| `S0-EXIT-08` | 旧路径未被提前删除，公开 endpoint 和既有协议保持兼容 | 旧路径仍保留；Resource Gateway 7145 项测试与 Test Kit 1905 项单测、2 项集成测试全绿，A1 协议归档边界通过 | `MET` |

阶段零只有 `S0-EXIT-01..08` 全部为 `MET`，且本项目与 Test Kit 的里程碑命令全绿时，才能标记为 `DEVELOPMENT_VERIFIED`。

## 4. 后续阶段出口摘要

### 4.1 阶段一

| 切片 | 交付物 | 状态 | 当前证据 |
|---|---|---|---|
| `S1-A` | 版本无关的逻辑资源契约、描述符投影、具体实现绑定与兼容性分析 | `DEVELOPMENT_VERIFIED` | 18/18：canonical fingerprint、Map/集合语义顺序归一化、防御拷贝、待确认投影、provider/API 版本隔离、结构化输出证明、输入/输出双向兼容、UNKNOWN 失败关闭与错误净化 |
| `S1-B` | 无状态世界模型、纯 BLOGE 世界片段、纯净校验和片段单测台 | `DEVELOPMENT_VERIFIED` | 41/41：不可变模型、版本化片段、稳定指纹、契约与 binding 一致性、AST/compiled-inventory 双重纯净准入、真实 BLOGE 隔离执行、FIRST/default、UNIQUE 歧义、20 次确定性重放、资源上限、超时线程终止、外部算子零执行和净化错误 |
| `S1-C` | Scenario、编译下沉、逻辑契约寻址与双向来源映射 | `DEVELOPMENT_VERIFIED` | `S1-C1..C2c`：63/63 资产/编译聚焦集与最终 106/106 受影响集全绿；精确 target/world、稳定 Scenario、assertion lowering、规范契约标签、真实 selector 对拍、多节点复用、fail-closed sentinel、无负载编译指纹、双向来源映射、统一内核 `WORLD_DELEGATE`、20 次确定性重放、真实算子/loopback 网络零调用及 compiler 三重 oracle 全部闭合 |
| `S1-D` | 授权引用解析、分级负载托管与三类资产版本化持久化 | `DEVELOPMENT_VERIFIED` | D1/D2/D3 全部闭合；真实 HTTP 链、production profile 隔离、旧库升级失败关闭、115/115 受影响集和双项目全量回归均通过 |

- 逻辑资源契约可由描述符投影初稿，并由人确认业务语义。
- 具体实现登记时必须证明输出满足逻辑契约。
- 世界片段只允许 BLOGE 纯原语，歧义匹配失败关闭，并可独立单元测试。
- `(WorldSlice + Scenario)` 确定性编译为现有 FixtureBundle 与断言，并保留双向来源映射。
- 契约兼容只在不兼容变更时使剧情失效。
- 大负载仅按授权引用解析，未授权引用在读取资产前失败。
- 逻辑契约、世界模型和剧情均采用不可变递增版本及稳定指纹持久化。
- 编译器通过结构性质、独立参考实现差分和往返三重验证。

### 4.2 阶段二

| 切片 | 交付物 | 状态 | 当前证据 |
|---|---|---|---|
| `S2-A` | 版本化 `WorldStateSpec` / `StateSpecV2`、Scenario 初始状态覆盖、跨切片单写者证明、治理 codec 双读写 | `DEVELOPMENT_VERIFIED` | 提交 `dd3034db6`；51/51 聚焦测试与 516/516 World、Scenario、Governed Catalog 受影响回归全绿；旧 `StateSpec` / `WorldSlice.state()` 源兼容、v1 无状态指纹不变、v2 schema/default/override/tamper/上限和 null 边界均有固定证明 |
| `S2-B` | 有状态片段信封、原子写集校验和独立试跑 | `DEVELOPMENT_VERIFIED` | 提交 `7af01cb1d`；61/61 最终聚焦回归与 7298 项全量测试全绿；固定 `{request,state}` / `{response,stateWrites}` 信封、WRITE-only 隔离、JSON Pointer、完整写集先验校验、失败零提交、20 次确定性重放及 payload-free 转换指纹均有固定证明 |
| `S2-C` | run-scoped 状态会话、快照恢复、冲突可达性与统一运行时接线 | `DEVELOPMENT_VERIFIED` | 提交 `226765b41`；125/125 最终聚焦回归与 7335 项全量测试全绿；编译期访问计划、严格绑定快照、原子提交、动态坐标、防重复/重入、无冲突规范投影、失败零提交、20 次确定性运行和 v1 指纹 golden 均有固定证明 |
| `S2-D` | BLOGE 全函数 resolver、精确调用点和函数控制计划 | `DEVELOPMENT_VERIFIED` | `S2-D1` BLOGE 通用内核提交 `8d514a7f6` 已验证：core 1959 项、DSL 1567 项测试全绿；`S2-D2a` 静态控制面 19/19 全绿；`S2-D2b` 运行期 RETURN/THROW/DELAY/TIMEOUT、精确参数、线程安全消费、治理审计、payload-free evidence、证据降级及 World Session 组合闭合。受影响集 98/98、Resource Gateway 全量 7371 项全绿；详见 [S2-D 验证说明](./rg-evolution-design-1.2.1-s2-function-control-verification.md) |
| `S2-E` | payload-free evidence、生产隔离、真实 HTTP 系统测试和双项目里程碑 | `NOT_STARTED` | 尚无实现证据 |

- 每个剧情拥有隔离、单写者、确定性排序、可序列化的世界状态。
- 写后读、跨剧情隔离和并发确定顺序具有固定自动化证明。
- 环境事实型、声明为非纯的自定义函数和纯函数强注入三类准入语义被严格区分。
- 纯函数强注入必须把证据上限降为探索级。

### 4.3 阶段三

- 生产观测只能经显式黄金捕获或受治理重放进入草稿，默认脱敏，晋升前人工复核。
- 静态影响图与运行时 fixture 消费互相校验，声明漂移可被发现。
- 存量测试套件只能单向抬升为剧情和世界模型草稿，旧协议继续兼容。
- 周期性真实环境重放能够检测世界保真度漂移。
- 两层变异门禁能发现“错误世界仍通过”的无效剧情。
- 系统测试床覆盖执行隔离边界故障注入和验证器反面对照。

## 5. 固定验证命令

聚焦反馈先运行受影响测试类；阶段切片提交前至少执行：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

涉及公开测试协议、客户端解析或 Schema 时，还必须执行：

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

截至 2026-08-25 的阶段零改造前基线：

```text
ExecutionControlCompilerTest        27/27
ResourceFixtureRuntimeTest           5/5
OperatorMicroGraphRunnerTest         4/4
合计                                36/36
```

该基线只证明改造前相关测试为绿色，不证明任何阶段出口已经完成。

阶段零新增开发证据：

```text
TestControlHeaderCodecTest          19/19
ExecutionControlCompilerTest        43/43
InvocationInventoryBuilderTest       5/5
ResourceFixtureRuntimeTest           9/9
OperatorMicroGraphRunnerTest        10/10
TestRunServiceTest                  45/45
TestRunServiceArchitectureTest       1/1
Stage0Exit06SecurityProofTest        4/4
Stage0Exit07FixedProofTest           3/3
GatewayGraphContractTestServiceTest 14/14
TestExecutionIngressAdapterTest     25/25
TestExecutionControllerTest         15/15
TestExecutionApiServiceTest         38/38
TestExecutionAuthenticationInterceptorIntegrationTest 22/22
TestRuntimeProfileIsolationTest     26/26
ExecutionControlBoundaryGuardFilterTest               309/309
ExecutionControlBoundaryGuardApplicationIntegrationTest 2/2
ExecutionControlBoundaryGuardNonProductionApplicationIntegrationTest 1/1
VisualGraphSimulationServiceTest    12/12
VisualGraphSimulationKernelIntegrationTest 14/14
VisualGraphSimulationSpringWiringTest       2/2
VisualGraphSimulationControllerTest  2/2
VisualGraphSimulationProductionAdmissionTest 7/7
VisualRuntimeBoundaryTest            1/1
ServerDeploymentPolicySpringWiringTest 4/4
ExecutionModeHintsTest               3/3
VisualSimulationPlanTest             3/3
VisualSimulationKernelAdapterTest    9/9
```

上述 60 项内核测试证明 `S0-A` 开发切片闭合；19 项协议测试证明 `S0-P` 纯解析协议闭合。S0-C 的 schema stand-in 内核聚焦集为 88/88：普通 output fixture 保持 `OUTPUT_LEVEL`，只有精确 site/rule 的 server-owned Java hint 才能冻结 `SCHEMA_STANDIN`，显式 `null` 仍可作为合法最终输出，真实算子不执行，证据降为探索级。6/6 port/hint 测试证明 visual-owned plan 不携带 testing/governance 类型，且多节点 hint 只能冻结精确 `SCHEMA_STANDIN`；9/9 adapter 测试证明单/多节点、共享 operatorRef、expected-input、纯原语、编译失败净化和十次语义确定性经过统一内核。14/14 新旧路径固定差分矩阵覆盖 stand-in、纯原语混合、多节点分类、持久化/请求 fixture 优先级、null、输入不匹配、输出覆盖、节点/边上限、校验/DSL 失败、双路径超时中断和异常净化；2/2 真实 Spring 接线测试证明默认 `@Service` 由容器注入 kernel adapter，并在生产 evidence 下失败关闭。该差分切片联合聚焦集为 47/47。S0-B/S0-D 及相关隔离回归的串行聚焦集为 463/463，其中包含 3/3 mirror 事务时钟基线；另有 4/4 policy Spring wiring 测试证明 production profile 或服务端 production environment evidence 下，普通业务 Bean 注册非生产 policy 不能替换最终准入证据（冲突时 fail closed）。阶段零安全、差分、确定性和双项目全量回归均已闭合；当前唯一未闭合出口是 `S0-EXIT-03` 的授权引用式大负载解析，该能力由阶段一 `S1-D` 交付。

S0-EXIT-01 的联合聚焦集为 127/127。新增证据覆盖：缺失 `httpResource` binding 时调用清单冻结为稳定且不可直接执行的外部边界；已有真实 binding 仍优先；其他缺失算子继续失败关闭；无 fixture 自动生成隐式 `DENY`；图内 embedded operator、compiler registry 与 isolated engine registry 同时为空时，run-scoped overlay 不修改共享 registry，`DESCRIPTOR_TRANSPORT` 仍经真实 descriptor 管线完成参数映射、URL、Header、Body、响应协议与 payload 提取并产生 `TRANSPORT_LEVEL` 可认证证据；缺描述符和协议拒绝保持确定性失败。

S0-EXIT-07 的当前确定性切片联合聚焦集为 115/115。运行身份与 evidence 壁钟已收敛到显式不可变依赖，默认构造器只在边界绑定系统适配器；同一固定逻辑时钟请求连续运行 20 次，plan fingerprint、semantic projection、semantic result fingerprint 与 execution-service state fingerprint 全部逐次相同，而 runId 和包含运行事实的完整 evidence fingerprint 均保持唯一。类文件架构测试禁止 `TestRunService` 直接调用 `Instant.now`、`UUID.randomUUID`、`Math.random`、`ThreadLocalRandom.current` 或分配 `Random`。该证据尚未覆盖其余阶段零核心类及独立编译等价台，因此 `S0-EXIT-07` 仍为部分满足。

S0-EXIT-06 的固定安全台为 4/4，相关联合回归为 143/143。descriptor 与 visual kernel 测试均启动真实本地计数端点并将资源 URL 指向该端点，执行后请求数严格为零；`StubHttpRequestOperator` 已从继承生产 `HttpRequestOperator` 改为实现 Resource Gateway 应用层的最小 `HttpRequestTransport`，不再为测试构造真实 `HttpClient`，生产 Spring 构造器仍保持原 `HttpRequestOperator` 依赖。ReDoS 矩阵覆盖典型灾难回溯结构、超长/畸形 pattern 与超长 candidate，同时保留安全字符类和锚点表达式。类文件规则进一步证明 `VisualSimulationKernelAdapter`、stub 与 `ResourceFixtureRuntime` 不创建生产网络 transport。本切片 `resource-gateway-examples clean verify` 为 7104 tests / 0 failures / 0 errors。

S0-EXIT-07 的最终固定台为 3/3，相关联合回归为 129/129。测试内朴素 oracle 不调用生产 `ExecutionMode.resolve` 或 compiler 内部分类器，独立声明并逐字段对拍纯只读 real、schema stand-in、descriptor protocol、descriptor transport、output-level return 与 external implicit deny，同时覆盖 descriptor 混淆和畸形 fixture 的失败关闭。每个代表模式连续编译 20 次，plan/runtime-binding fingerprint 完全稳定，而默认 planId 保持唯一；显式 identity source 可确定性控制 ID 序列，null/blank 则以 `CONTROL_PLAN_ID_UNAVAILABLE` 拒绝。plan identity 与 run identity 均只在边界适配器使用系统 UUID/时间。类文件规则覆盖 `ExecutionControlCompiler`、`InvocationInventoryBuilder`、`SelectorResolver`、`SafetyPreflight`、`TestDoubleFactory`（含 observed operator）、`ResourceFixtureRuntime`、`VisualSimulationKernelAdapter` 和 `TestRunService`。

S0-EXIT-04 的固定生产旁路矩阵为 18/18，覆盖 real/read-only、schema stand-in、descriptor protocol、descriptor transport、output-level return 与 implicit deny。入口证据使用自定义 DTO 反序列化哨兵，证明 production Filter 在读取业务 Body 前拒绝，非生产下游 compiler/executor 计数为零；服务证据直接绕过 Filter，证明 production `TestExecutionApiService` 在目标规划、registry、post-compile admission 与 executor 前独立拒绝；visual 证据证明 production service 在 kernel adapter 前拒绝。所有失败响应均不含业务 payload、控制内容、模式或堆栈。该矩阵与既有边界/服务/visual 测试的联合聚焦集为 386/386；加入 S1-A 契约切片后的独立复跑为 404/404。

截至 2026-08-26，本切片最终串行里程碑结果：

```text
resource-gateway-examples clean verify  7145 tests / 0 failures / 0 errors / 28 skipped
resource-gateway-test-kit clean verify  1905 tests + 2 integration tests / all green
A1 protocol archive boundary            PASS
```

该次全量结果证明当时的仓库回归分母为绿色，并闭合 `S0-EXIT-08`。在该里程碑上，阶段零仍因 `S0-EXIT-03` 为 `PARTIAL` 而保持 `IN_PROGRESS`；后续由 `S1-D/D3b3` 的真实引用运行链闭合，最终状态见本节末尾。

完成 `S1-C` 场景编译、统一内核世界委托及三重 compiler oracle 后，再次执行双项目串行里程碑：

```text
resource-gateway-examples clean verify  7199 tests / 0 failures / 0 errors / 28 skipped
resource-gateway-test-kit clean verify  1905 tests + 2 integration tests / all green
A1 protocol archive boundary            PASS
```

首轮全量回归准确发现一项旧测试预期仍把 `WORLD_DELEGATE` 视为未实现；测试已更新为区分“已实现但缺少 run-scoped runtime”与真正不支持的执行模式。修正后完整分母重新执行并全绿，没有以聚焦测试替代里程碑证据。

`S1-D/D1` 的治理资产目录聚焦集为 12/12。统一头表与历史表保存逻辑资源契约、世界模型和剧情；CAS 只允许 `N -> N + 1`，真实双线程竞争只有一个赢家。精确地址固定为 `tenant + kind + id + revision + domain fingerprint`，独立 `record_fingerprint` 覆盖完整规范 JSON。测试同时证明旧版本不可变、租户与类型隔离、跨仓库实例恢复、头行与历史行篡改失败关闭，以及 binding 通过受控工厂恢复而不绕过领域构造边界。加入阶段一既有测试后的世界层回归为 90/90。自动世界切片选择另有 7/7 聚焦测试，零候选和多候选均失败，不采用输入顺序或“第一条优先”语义。

`S1-D/D2a` 的读取前授权与精确依赖解析聚焦集为 22/22，阶段一联合回归为 102/102。认证上下文提供唯一租户来源；purpose、correlation 和环境在授权及仓库前校验。顶层拒绝时仓库调用为零；Scenario 的 World 依赖通过受控回调先授权再读取；错误 Scenario 指纹不会触发 World 授权或读取。数据库信封先做无负载 preflight，再比较外部精确指纹，避免把权威行损坏误报为普通未命中。只有固定、无负载的依赖中断类型可以穿过 codec 净化。

`S1-D/D2b` 将来源、安全分级、保留期、访问策略和审批引用保存为独立治理元数据，治理指纹覆盖精确坐标和全部治理字段。默认兼容写入为 `SYNTHETIC + PUBLIC`；`REAL` 写入要求审批引用和相对固定仓库时钟仍在未来的保留期限。读取严格执行坐标授权、无 payload 元数据查询、完整性与到期检查、分级策略授权、payload 查询五步顺序；未显式配置第二阶段策略时失败关闭。错误坐标、空或错误治理指纹、策略拒绝与过期均在 payload 前停止，Scenario 的 World 依赖重复同一链路。D2 联合聚焦集为 34/34，阶段一联合回归为 114/114。治理类型只存在于资产与授权层，未进入编译控制或执行内核类型。

`S1-D/D3a-D3b` 已把结构化 Scenario/World 引用从入口接入统一执行服务。引用和请求体 fixture 严格互斥；服务端以原始 `TEST_EXECUTION` 身份完成准入和证据持久化，只为治理资产解析铸造 `GRAPH_CONTRACT_TEST` 上下文。规划器验证 Scenario 的图工件和业务 context 精确绑定；仅有 World 引用时按图节点的规范逻辑契约标签生成确定性临时 Scenario，零标签、多标签、契约指纹漂移和切片非唯一均失败。运行服务按“编译 → 准入 → 世界委托执行 → 租约 checkpoint → 释放”排序，准入拒绝或空 guard 时引擎调用为零。入口、解析、规划、运行和 API 服务最终联合聚焦集为 115/115。

`S1-D/D3b3` 以真实 Spring HTTP 系统测试闭合装配事实：合成公开 World 经隔离数据库写入，编码控制头和纯业务 Body 经过认证、两阶段授权、数据库读取、规划、编译、`WORLD_DELEGATE` 与证据持久化；真实算子调用为零，响应及签名 `FULL` evidence 均绑定图工件和 World 来源。测试/预发布 profile 才装配治理引用链，production profile 无相关 Bean；既有 18/18 生产旁路矩阵继续证明入口和服务端独立失败关闭。D1 空库可升级，含缺失治理封印的旧数据在启动时拒绝。

截至 2026-08-26，阶段零和阶段一最终串行里程碑结果：

```text
resource-gateway-examples clean verify  7272 tests / 0 failures / 0 errors / 28 skipped
resource-gateway-test-kit clean verify  1905 tests + 2 integration tests / all green
A1 protocol archive boundary            PASS
```

该结果关闭 `S1-D-10..12`、`S1-D` 和 `S0-EXIT-03`，并使阶段零、阶段一进入 `DEVELOPMENT_VERIFIED`。它是仓库内开发验证，不替代企业生产环境的容量、灾备、权限、密钥和运维认证。

`S2-A` 于 2026-08-26 完成开发验证。版本化状态声明使用统一 JSON Schema 校验器，状态 key 的 access、schema、default 和写者切片坐标进入稳定模型；Scenario override 只允许引用已声明 key，并在构造和治理反序列化时失败关闭。v2 空声明、null override、畸形 wire object、重复或缺失写者、schema/default 漂移和治理内容篡改均被拒绝。该切片只建立可治理状态资产，不代表状态片段、运行时会话或函数注入已经可用。

```text
S2-A focused clean test       51 tests / 0 failures / 0 errors / 0 skipped
S2-A affected regression     516 tests / 0 failures / 0 errors / 0 skipped
implementation commit        dd3034db6
```

`S2-B` 于 2026-08-26 完成开发验证。有状态片段使用固定输入输出信封；默认值与 Scenario override 在片段执行前完成规范化和 schema 校验，`WRITE` 状态不会进入片段可见输入。片段返回的嵌套写集先被确定性展平为规范 JSON Pointer，再整体校验未知 key、只读写入和 schema；只有全部写入合法时才一次构造新快照。重放每次都从同一初态开始，并对响应、写集、终态和绑定 fragment/stateSpec/request/initialState 的转换指纹逐项对拍。错误、默认 evidence 材料和异常信息不携带原始请求或状态值。

本切片只证明单个有状态 World Fragment 可被隔离、受限、确定性地试跑；整图状态会话由后续 `S2-C` 闭合，状态 evidence 持久化和函数注入仍属于 `S2-D..E`。

```text
S2-B final focused regression   61 tests / 0 failures / 0 errors / 0 skipped
resource-gateway clean verify 7298 tests / 0 failures / 0 errors / 28 skipped
implementation commit        7af01cb1d
```

`S2-C` 于 2026-08-26 完成开发验证。编译器把每个有状态切片的精确结构调用点、读集和可能写集冻结为 `StateAccessPlan`；同一 key 上至少一个写者且没有 DAG 可达顺序时直接拒绝，不允许把抢锁顺序解释为业务顺序。运行期每个 Scenario Run 只创建一个 server-owned `WorldStateSession`，并绑定 Scenario、World、Graph 工件和 runId。Fragment 只能读取计划授权的冻结视图，返回的实际写集可以是声明写集的子集，但必须在节点输出、状态 schema、大小与指纹校验全部通过后原子提交。

快照包含最终状态、规范排序的 payload-free 事务观察和完整绑定指纹；普通 Jackson 往返、错误绑定、内容篡改、伪造或缩减访问观察、重复动态坐标均有失败关闭测试。无冲突事务按规范坐标投影，因此不同提交顺序得到相同快照指纹；失败、嵌套 transition、restore/close 重入均不产生部分提交。无状态 v1 编译指纹由硬编码 golden 保护。当前 BLOGE 未公开 foreach 的稳定顺序事实，所有包含有状态子图的 foreach 暂时失败关闭，待 `S2-D` 通过通用内核 API 提供可证明事实后再开放顺序循环。

```text
S2-C final focused regression  125 tests / 0 failures / 0 errors / 0 skipped
resource-gateway clean verify 7335 tests / 0 failures / 0 errors / 28 skipped
implementation commit        226765b41
```
