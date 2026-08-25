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
| 阶段零 | 收敛执行内核、描述符传输替换、控制面隔离、旧路径适配 | `IN_PROGRESS` | 执行模式、控制头协议、受控入口与生产双重拒绝已形成开发证据；visual adapter、无 binding 执行与差分台仍未完成 |
| 阶段一 | 逻辑契约、世界模型、剧情、编译下沉和资产持久化 | `NOT_STARTED` | 现有 Scenario/Mirror 资产不能自动等同于 1.2.1 定义的世界模型闭包 |
| 阶段二 | 有状态世界和函数返回值注入 | `NOT_STARTED` | 现有 mirror state 机制尚未按本方案出口标准验收 |
| 阶段三 | 线上沉淀、影响分析和存量迁移 | `NOT_STARTED` | 现有 replay/corpus 零件尚未形成本文要求的端到端闭环 |

## 3. 阶段零验收矩阵

### 3.1 实现切片

| 切片 | 交付物 | 状态 | 证明要求 |
|---|---|---|---|
| `S0-A` | 七态 `ExecutionMode`；编译期模式固定；`DESCRIPTOR_PROTOCOL` / `DESCRIPTOR_TRANSPORT` 显式运行分派 | `DEVELOPMENT_VERIFIED` | 60/60：模式分类、compiler 到 runtime 的动态多规则实际选择、混合规则 fingerprint 稳定、提取语义对拍、模式不一致失败关闭、legacy graph-contract 兼容 |
| `S0-P` | 四个 `X-BLOGE-Test-*` 控制头的纯协议解析 | `DEVELOPMENT_VERIFIED` | 19/19：strict base64url/UTF-8/JSON、大小和复杂度边界、多值拒绝、错误不泄漏、inline canonicalization |
| `S0-B` | 服务端执行目的铸造与受控入口 admission | `DEVELOPMENT_VERIFIED` | 七类 Body 入口在反序列化前按固定 `IntegrationOperation` 认证；bounded inline fixture 进入既有解析链；caller purpose 不可改写内核 purpose |
| `S0-C` | visual 设计期模拟适配统一内核 | `NOT_STARTED` | 保留 schema stand-in、纯原语、逐节点 fixture、资源上限、超时和响应兼容 |
| `S0-D` | 生产入口控制头拒绝/剥离及服务端二次拒绝 | `DEVELOPMENT_VERIFIED` | profile/服务端环境双判定、context-path 安全执行路由全集、真实 Spring Filter 链与 visual service 独立 403 拒绝；server-owned policy Bean 不可由普通业务 Bean 条件替换 |
| `S0-E` | 新旧路径等价对拍、确定性和架构约束 | `NOT_STARTED` | 正常/异常矩阵、N 次同指纹、禁非确定 API、ReDoS 与零网络证明 |

### 3.2 阶段出口

| 编号 | 必须满足的事实 | 当前证据 | 状态 |
|---|---|---|---|
| `S0-EXIT-01` | 无部署业务算子绑定时，描述符驱动的 `httpResource` 只替换传输并完整执行映射、URL、Header、协议和负载提取 | 显式模式和完整 transport pipeline 已验证；统一 compiler 仍要求 `httpResource` registry binding，尚缺真正无 binding 的 adapter | `PARTIAL` |
| `S0-EXIT-02` | 设计期模拟通过 adapter 进入统一内核 | `VisualGraphSimulationService` 仍使用 `__sim_*` 独立路径 | `NOT_MET` |
| `S0-EXIT-03` | 控制走 Header，业务走 Body；有界 inline 可用，大负载只走引用 | Header admission 和 bounded inline fixture 已进入既有执行链；legacy Body 控制为兼容保留，Scenario/World Model 授权引用属于阶段一 | `PARTIAL` |
| `S0-EXIT-04` | 生产入口和服务端目的形成两道独立拒绝 | 入口 Filter 已覆盖已识别执行路由；visual service 与 TestExecution service 可独立拒绝 production；尚待统一 visual adapter 后对所有模式做端到端旁路证明 | `PARTIAL` |
| `S0-EXIT-05` | 新内核与旧路径在节点/边上限、超时、逐节点 fixture 和混合分类上对等 | 尚无固定差分矩阵 | `NOT_MET` |
| `S0-EXIT-06` | 模拟和描述符传输路径具备 SSRF 零网络证明，匹配器具备 ReDoS 负向证明 | transport 使用 stub；尚缺系统级攻击测试 | `PARTIAL` |
| `S0-EXIT-07` | 编译等价、N 次重放确定性和禁非确定 API 架构规则通过 | 尚无阶段零固定测试台 | `NOT_MET` |
| `S0-EXIT-08` | 旧路径未被提前删除，公开 endpoint 和既有协议保持兼容 | 当前未删除旧路径 | `MET_PENDING_REGRESSION` |

阶段零只有 `S0-EXIT-01..08` 全部为 `MET`，且本项目与 Test Kit 的里程碑命令全绿时，才能标记为 `DEVELOPMENT_VERIFIED`。

## 4. 后续阶段出口摘要

### 4.1 阶段一

- 逻辑资源契约可由描述符投影初稿，并由人确认业务语义。
- 具体实现登记时必须证明输出满足逻辑契约。
- 世界片段只允许 BLOGE 纯原语，歧义匹配失败关闭，并可独立单元测试。
- `(WorldSlice + Scenario)` 确定性编译为现有 FixtureBundle 与断言，并保留双向来源映射。
- 契约兼容只在不兼容变更时使剧情失效。
- 大负载仅按授权引用解析，未授权引用在读取资产前失败。
- 逻辑契约、世界模型和剧情均采用不可变递增版本及稳定指纹持久化。
- 编译器通过结构性质、独立参考实现差分和往返三重验证。

### 4.2 阶段二

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
ExecutionControlCompilerTest        32/32
ResourceFixtureRuntimeTest           8/8
OperatorMicroGraphRunnerTest         6/6
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
VisualGraphSimulationControllerTest  2/2
VisualGraphSimulationProductionAdmissionTest 7/7
VisualRuntimeBoundaryTest            1/1
ServerDeploymentPolicySpringWiringTest 4/4
```

上述 60 项内核测试证明 `S0-A` 开发切片闭合；它不证明 visual adapter、未来五种执行模式或无 registry binding 执行已经完成。19 项协议测试证明 `S0-P` 纯解析协议闭合。S0-B/S0-D 及相关隔离回归的串行聚焦集为 463/463，其中包含 3/3 mirror 事务时钟基线；另有 4/4 Spring wiring 测试证明 production profile 或服务端 production environment evidence 下，普通业务 Bean 注册非生产 policy 不能替换最终准入证据（冲突时 fail closed）。阶段一引用解析及统一 visual kernel 尚未完成，因此阶段零出口仍保持部分满足。

截至 2026-08-26，本切片最终串行里程碑结果：

```text
resource-gateway-examples clean verify  7047 tests / 0 failures / 0 errors / 28 skipped
resource-gateway-test-kit clean verify  1905 tests + 2 integration tests / all green
A1 protocol archive boundary            PASS
```

该全量结果证明当前仓库回归分母为绿色；它仍不能代替 `S0-EXIT-01..08` 的逐项事实验收。
