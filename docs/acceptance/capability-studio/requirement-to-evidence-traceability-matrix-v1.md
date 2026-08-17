# Capability Studio Requirement-to-Evidence Traceability Matrix v1

> 当前发布结论：`NO_GO`。本矩阵把每项要求绑定到实现、数据、测试和待补证据；空证据不能按通过处理。

## Golden Path

| Requirement | 当前实现 / 数据 | 自动化证据 | 仍缺证据 | Owner | Gate |
|---|---|---|---|---|---|
| `GP-01` | `/capabilities/`；4/1/1/9 Demo Pack | 组件、Loader/Controller；中文 1440/1024/390 真实 Chrome | 英文三视口、异常状态、产品签署 | Product + QA | `PENDING` |
| `GP-02` | 四个可独立选择的业务契约投影 | 前端选择/契约测试、后端投影；中文 1440 Chrome 选择第二个 API | Schema round-trip、键盘和读屏 | Product + API Owner | `PENDING` |
| `GP-03` | 九条场景元数据、搜索和筛选 | 前端九行测试、Pack 闭包；中文 1440/390 Chrome | Dataset authority、质量与权限投影、移动表格效率 | Correctness Owner | `PENDING` |
| `GP-04` | Tutorial Branch 业务句式编辑器；进程内 immutable revision；保存与隔离预检闭环 | 组件/API/Controller；中文 1440 Chrome；Test Kit 严格 Schema、内容指纹重算、revision/baseline/preflight exact binding；真实 HTTP 三制品互验 | 持久化 Authority、英文三视口、键盘/读屏、并发浏览器、业务签署 | Correctness Owner | `PARTIAL` |
| `GP-05` | 现有 Graph/Canvas 可复用 | 既有 layout 单元测试 | 取消争议 Feature Graph、Data Lens、像素验收 | Canvas Owner | `NO_GO` |
| `GP-06` | 现有 TIMEOUT lowerer 可复用 | 相邻 Correctness 编译测试 | Dataset 编译、Feature Oracle、零外呼运行 | Runtime Owner | `NO_GO` |
| `GP-07` | Tool 业务摘要和契约元数据 | Demo Pack contract invariant | 禁止结果、exact dependency、影响报告 | Product + Runtime | `NO_GO` |
| `GP-08` | 现有 batch/test-kit 基础 | 相邻批量与确定性测试 | Canonical 9/9 × 3、零外呼和五轴证据 | QA + Runtime | `NO_GO` |
| `GP-09` | 每 Case 有 Owner、Source、Oracle、Contract | Loader 闭包测试 | 多维质量、复用、stale 和影响图 | Data Owner | `NO_GO` |
| `GP-10` | 现有 trace/deep-link 基础 | 相邻 Evidence/Deep Link 测试 | Capability/Dataset/Binding exact closure | QA + Integration | `NO_GO` |

## Technical Spikes

| Requirement | 根问题 | 通过标准 | 当前证据 | 缺口 | Gate |
|---|---|---|---|---|---|
| `SPIKE-A` | Dataset 是否能无损下沉到现有 Runtime | 确定性编译；RETURN/ERROR/TIMEOUT/MUST_NOT_CALL 保真；拒绝 REAL fallback | `ScenarioDraftSetV2`、Correctness lowerer 已存在 | 新 Dataset/Binding 协议与端到端编译未实现 | `NO_GO` |
| `SPIKE-B` | Data Lens 能否可读且不泄露 Payload | 字段来源、边值、差异、权限投影和无遮挡 | Canvas/Trace 邻接能力 | 取消争议图和浏览器像素证据缺失 | `NO_GO` |
| `SPIKE-C` | 注入能力能否从生产物理消失 | production route/bean/DTO 缺席；network deny + counter | Demo Pack 已使用 profile/property 隔离 | Capability Run 面尚未建立，零 egress 未证明 | `NO_GO` |

## Security

| Requirement | 威胁 | 验收 | 当前证据 | Owner | Gate |
|---|---|---|---|---|---|
| `SEC-01` | 生产误用 Fixture/Mock | production 不装配注入面 | Controller 与 Pack 同条件装配；default/production 缺席测试 | Security | `PARTIAL` |
| `SEC-02` | 隔离运行静默访问真实服务 | `realExternalCallCount=0` 且 egress deny | 仅有相邻 Mirror 测试 | Security + Runtime | `NO_GO` |
| `SEC-03` | Payload 经日志、URL、Evidence 泄露 | 普通投影和验收制品 payload-free | Controller 响应负向断言 | Data Security | `PARTIAL` |
| `SEC-04` | 跨 Scope 引用 | tenant/org/project/env/region 全维 fail closed | 现有 Correctness/Mirror Scope 模型 | 新 Capability/Dataset API 未接入 | `NO_GO` |
| `SEC-05` | 撤销或过期数据继续运行 | stale/quarantine/revoke 传播到 Binding 与 Evidence | 仅设计文档 | Data Security | `NO_GO` |

## Non-Functional Requirements

| Requirement | 阈值 | 当前证据 | 缺口 | Owner | Gate |
|---|---|---|---|---|---|
| `NFR-01` | 黄金样例离线可读取和运行 | Pack 为本地资源 | 运行尚未接入 | Delivery | `PARTIAL` |
| `NFR-02` | 双语、三视口、完整键盘路径 | 双语组件测试；中文三视口 Chrome 无页面级溢出 | 英文三视口、键盘全路径和可访问性报告 | UX + QA | `PARTIAL` |
| `NFR-03` | 三次运行语义 fingerprint 一致 | Pack fingerprint 加载确定 | Runtime semantic fingerprint 尚无 | Runtime | `NO_GO` |
| `NFR-04` | 无手工 ID、无 Raw JSON | 默认视图折叠技术引用 | 六人可用性测试 | UX | `PENDING` |
| `NFR-05` | 404/400/409 有恢复动作且不丢内容 | Demo Pack 加载重试；GP-04 冲突保留输入；服务端统一返回原因、影响、恢复动作和可选字段；传输失败单独分类 | 冲突/断网真实浏览器矩阵和跨重启草稿恢复 | Frontend + API | `PARTIAL` |
| `NFR-06` | 复杂 DAG 节点、边标签和数据摘要无遮挡 | 既有 layout quality 单测 | Feature Data Lens 真实像素验收 | Canvas | `NO_GO` |

## 收口规则

只有以下条件同时成立时，Manifest 才能从 `NO_GO` 进入 `ACCEPTED`：`GP-01` 至 `GP-10` 全部有可复验证据；Canonical Baseline 9/9 连续运行三次语义一致；真实调用已观测为 0；三项 Spike、安全和 NFR 门禁通过；六人测试至少 5 人在 15 分钟内独立完成；所有 P0/P1 关闭；七类 Owner 完成签署。
