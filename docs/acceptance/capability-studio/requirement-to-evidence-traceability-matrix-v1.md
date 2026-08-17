# Capability Studio Requirement-to-Evidence Traceability Matrix v1

> 当前发布结论：`NO_GO`。本矩阵把每项要求绑定到实现、数据、测试和待补证据；空证据不能按通过处理。

## Golden Path

| Requirement | 当前实现 / 数据 | 自动化证据 | 仍缺证据 | Owner | Gate |
|---|---|---|---|---|---|
| `GP-01` | `/capabilities/`；4/1/1/9 Demo Pack | 组件、Loader/Controller；中文与英文 1440/1024/390 真实 Chrome；页面级无横向溢出和内部状态泄漏 | 异常状态完整浏览器矩阵、产品签署；英文演示数据仍保留中文权威业务名称 | Product + QA | `PARTIAL` |
| `GP-02` | 四个可独立选择的业务契约投影 | 前端选择/契约测试、后端投影；中文 1440 Chrome 选择第二个 API | Schema round-trip、键盘和读屏 | Product + API Owner | `PENDING` |
| `GP-03` | Golden Pack 确定性 Scenario Dataset 投影；根/Case/Behavior 内容指纹；Dataset 摘要、五项质量覆盖、搜索筛选和 Case 主从详情 | 严格 Schema；独立 Test Kit 指纹/Scope/引用闭包/质量/Active readiness；前后端语义 fail-closed 测试；中英文三视口 Chrome；真实 Tab/Space 选择 Case；Dataset 经适配器进入既有 `ScenarioGovernedCompiler`，确定性生成 FixtureBundle/TestSuite 与 payload-free source map | 持久化 Dataset Authority、权限投影、注册与执行编译产物、完整读屏、Correctness Owner 签署 | Correctness Owner | `PARTIAL` |
| `GP-04` | Tutorial Branch 业务句式编辑器；数据库 head 与 immutable revision；保存与隔离预检闭环 | 组件/API/Controller；SQL 原子 CAS、同版本并发单赢家、stale retry 幂等、Authority 重建恢复、Baseline 漂移失败关闭；中英文真实 Chrome；Test Kit 严格 Schema、内容指纹重算、revision/baseline/preflight exact binding；真实 HTTP 三制品互验 | Tutorial 全键盘/读屏、409/断网真实浏览器、并发浏览器、业务签署 | Correctness Owner | `PARTIAL` |
| `GP-05` | test/staging Feature Rehearsal API 执行实际 4 API + 聚合 + 决策 BLOGE Graph；工作区显示同一次 Trace 的 6 节点、5 条边、结构/受控数据权限态和 Data Lens | structure-only/payload-visible、稳定坐标、首差异、重试/回退、截断和确定性投影；中文 1440/1024/390 与英文 1024 Chrome；桌面零横向截断、节点/边中心对齐、移动端内部滚动、键盘权限切换和 axe | 字段级 source map/血缘、基于身份而非查询参数的权限决策、人工视觉与读屏签署 | Canvas Owner | `PARTIAL` |
| `GP-06` | 9 个 Canonical Case 可选择；超时场景经真实 `TestRunService` 运行，四个 `HttpResourceOperator` 受 Fixture 控制，下游按 Trace 取消，真实调用计数为 0 | 9 Case selector、24 并发运行 ID 隔离、稳定 Graph/semantic fingerprint、TIMEOUT/RETURN/ERROR/control-plan-reject/fixture-unmatched 失败关闭、真实 Chrome 超时因果链 | 运行 SPIKE-A 注册的同一资产闭包；duplicate/idempotency 与 forbidden-write 完整 Oracle；9/9 × 3；部署级 network deny | Runtime Owner | `PARTIAL` |
| `GP-07` | Tool 业务摘要和契约元数据 | Demo Pack contract invariant | 禁止结果、exact dependency、影响报告 | Product + Runtime | `NO_GO` |
| `GP-08` | 现有 batch/test-kit 基础 | 相邻批量与确定性测试 | Canonical 9/9 × 3、零外呼和五轴证据 | QA + Runtime | `NO_GO` |
| `GP-09` | 每 Case 有 Owner、Source、Oracle、Contract | Loader 闭包测试 | 多维质量、复用、stale 和影响图 | Data Owner | `NO_GO` |
| `GP-10` | 现有 trace/deep-link 基础 | 相邻 Evidence/Deep Link 测试 | Capability/Dataset/Binding exact closure | QA + Integration | `NO_GO` |

## Technical Spikes

| Requirement | 根问题 | 通过标准 | 当前证据 | 缺口 | Gate |
|---|---|---|---|---|---|
| `SPIKE-A` | Dataset 是否能无损下沉到现有 Runtime | 确定性编译；RETURN/ERROR/TIMEOUT/顺序消费/MUST_NOT_CALL 保真；拒绝 REAL fallback；runtime rule 可回溯 Dataset/Case/Behavior | Dataset 先确定性适配到既有 `ScenarioDraftSet`，再委托既有 `ScenarioGovernedCompiler`；Canonical 9 Case 生成 9 个 FixtureBundle 注册和 1 个 TestSuite 注册；三次 fingerprint、exact target/contract/Scope/Authority、编译 rule 与 source map 闭包及失败关闭测试通过；通用服务不硬编码 Case 数 | 编译产物尚未注册并作为同一资产闭包执行；UI 字段级 source map 和完整 9/9 Evidence 未证明 | `PARTIAL` |
| `SPIKE-B` | Data Lens 能否可读且不泄露 Payload | 字段来源、边值、差异、权限投影和无遮挡 | 直接投影既有 `TestRunEvidence.nodeTrace/edgeTrace`；Capability API 和取消争议 Feature Graph 已接入；structure-only 隐藏值但保留 fingerprint，payload-visible 显示受控值；稳定执行坐标、状态、重试/回退、首差异、上限/截断均有测试；真实 Chrome 已证明 6 节点、5 边完整展开、稳定排序和节点/边中心对齐 | 仍缺字段级 source map/映射血缘、企业身份授权、人工视觉签署和发布候选数据验证 | `PARTIAL` |
| `SPIKE-C` | 注入能力能否从生产物理消失 | production route/bean/DTO 缺席；network deny + counter | 三组 production profile 不装配演示 Bean；五类入口在 DTO 前拒绝控制字段并安全审计；9 个 Canonical Case metadata 通过真实 `TestRunService`、带计数且 fail-fast 的真实 HTTP delegate 边界运行，connector 调用为 0；另证 ERROR、顺序尝试、MUST_NOT_CALL 和 fallback-to-real 预执行拒绝 | 当前运行材料是 test-owned closure，不是 SPIKE-A 注册产物；只有 in-process connector counter，缺部署级 OS/network policy、复杂 DAG 和发布候选环境观测 | `PARTIAL` |

## Security

| Requirement | 威胁 | 验收 | 当前证据 | Owner | Gate |
|---|---|---|---|---|---|
| `SEC-01` | 生产误用 Fixture/Mock | production 不装配注入面，普通运行协议在反序列化前失败关闭 | 三组 production profile 装配否定测试；五类入口、16 类控制字段、嵌套与命名变体、审计失败关闭和 Payload 不泄漏测试 | Security | `PARTIAL` |
| `SEC-02` | 隔离运行静默访问真实服务 | `realExternalCallCount=0` 且 egress deny | Capability Studio in-process 真实 TestRun 路径使用 fail-fast HTTP delegate observer；取消争议 4 API DAG 和 9 个 metadata Case 的 connector counter 为 0；fallback-to-real 在执行前拒绝 | 缺发布候选环境 network deny、OS/sidecar 计数和 SPIKE-A 注册资产的完整调用点闭包 | Security + Runtime | `PARTIAL` |
| `SEC-03` | Payload 经日志、URL、Evidence 泄露 | 普通投影和验收制品 payload-free | Controller 响应负向断言 | Data Security | `PARTIAL` |
| `SEC-04` | 跨 Scope 引用 | tenant/org/project/env/region 全维 fail closed | 现有 Correctness/Mirror Scope 模型 | 新 Capability/Dataset API 未接入 | `NO_GO` |
| `SEC-05` | 撤销或过期数据继续运行 | stale/quarantine/revoke 传播到 Binding 与 Evidence | 仅设计文档 | Data Security | `NO_GO` |

## Non-Functional Requirements

| Requirement | 阈值 | 当前证据 | 缺口 | Owner | Gate |
|---|---|---|---|---|---|
| `NFR-01` | 黄金样例离线可读取和运行 | Pack 为本地资源；Feature Rehearsal 用本地 test-owned material 离线运行并由启动脚本探测 | 受治理同闭包运行、Tool 和完整发布候选仍未接入 | Delivery | `PARTIAL` |
| `NFR-02` | 双语、三视口、完整键盘路径 | 双语组件测试；中英文 1440/1024/390 Chrome；真实 Tab/Enter/Space Dataset 路径和 Feature 权限切换；六种组件状态和真实 Chrome 完整 axe-core serious/critical 为 0；已修复选中行对比度与 DAG 滚动区焦点缺陷 | 契约与 Tutorial 的完整键盘路径、人工屏幕阅读器、异常状态三视口；业务资产内容未本地化 | UX + QA | `PARTIAL` |
| `NFR-03` | 三次运行语义 fingerprint 一致 | Pack fingerprint 加载确定；窄 SPIKE-C 中每个 Case 两次执行的 semantic fingerprint 与 plan fingerprint 一致 | 尚未执行 Canonical Baseline 9/9 连续三轮与聚合 fingerprint | Runtime | `PARTIAL` |
| `NFR-04` | 无手工 ID、无 Raw JSON | 默认视图折叠技术引用 | 六人可用性测试 | UX | `PENDING` |
| `NFR-05` | 404/400/409 有恢复动作且不丢内容 | Demo Pack 加载重试；GP-04 冲突保留输入；服务端统一返回原因、影响、恢复动作和可选字段；传输失败单独分类；Authority 重建恢复已自动化证明 | 冲突/断网真实浏览器矩阵 | Frontend + API | `PARTIAL` |
| `NFR-06` | 复杂 DAG 节点、边标签和数据摘要无遮挡 | 取消争议 6 节点、5 边 DAG 的真实 Chrome 截图；1440/1024 零内部截断、节点/边中心误差不超过 1px；390 内部滚动且页面无横向溢出 | 更大复杂度基准、缩略图/缩放行为、人工视觉签署 | Canvas | `PARTIAL` |

## 收口规则

只有以下条件同时成立时，Manifest 才能从 `NO_GO` 进入 `ACCEPTED`：`GP-01` 至 `GP-10` 全部有可复验证据；Canonical Baseline 9/9 连续运行三次语义一致；真实调用已观测为 0；三项 Spike、安全和 NFR 门禁通过；六人测试至少 5 人在 15 分钟内独立完成；所有 P0/P1 关闭；七类 Owner 完成签署。
