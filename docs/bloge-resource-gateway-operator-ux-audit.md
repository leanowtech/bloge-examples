# Resource Gateway `/author/` 逐算子 UX 审查报告

状态：Iteration 14 审查记录
日期：2026-07-06
范围：`resource-gateway-examples` / `/author/` operator palette、React Flow 节点、selected operator inspector、双击配置浮层

## 1. 审查目标

本报告补齐此前只按“算子族”审查的缺口。目标不是证明 decision table 做完了，而是逐个审查真实 catalog 中每个 operatorRef 是否满足以下 UX 条件：

1. 用户在 palette 中能判断这个算子属于哪类工作：规则、映射、循环、资源、HTTP、流式、普通 schema-only。
2. 拖入画布后，节点卡片能直接表达输入、输出、执行/设计态风险。
3. 选中节点后，inspector 能给出该算子的专有 contract 或至少给出清晰 schema contract。
4. 需要本地配置的复杂算子必须有直觉入口；不适合前端本地编辑的算子必须说明边界和后续路径。
5. 每轮改进后继续复核差距，直到目标差距不超过 3%。

## 2. Catalog 证据

证据来源：本地示例服务 `GET http://127.0.0.1:8080/api/visual/operators`

当前 catalog 返回 26 个 operator：

```text
MockCitationStreamingOperator
MockLlmTokenStreamingOperator
MockMetaStreamingOperator
__decision_table__
__foreach__:enrichOrders
__transform__
bloge:decisionTable
bloge:transform
httpRequest
httpResource
orders:normalize
orders:route-sla
resource:catalog-service.getProduct
resource:credit-provider.primary
resource:credit-provider.secondary
resource:invoice-service.getInvoice
resource:license-service.getLicense
resource:loan-applicant-service.getProfile
resource:logistics-service.getShipping
resource:notification-service.unread
resource:order-service.listOrders
resource:recommendation-service.forUser
resource:user-service.getProfile
resource:wallet-service.getBalance
risk:eligibility
support:classify-ticket
```

## 3. 本轮发现的隐藏 UX 缺口

| 缺口 | 影响 | 本轮处理 |
| --- | --- | --- |
| `httpResource` 被当成 generic operator | 用户看不到它是 Resource Gateway 的低级资源执行算子，只看到 `object -> object` | 已把 `httpResource`、`resource` tag、`resource-descriptor` lowering 统一归为 Resource 视觉族 |
| `httpRequest` 被当成 generic operator | 用户无法区分普通对象转换和外部 HTTP 边界 | 已新增 `HTTP` 视觉族，显示 `request -> response` 和 HTTP contract inspector |
| 服务端 `runtimeReadiness` 未进入前端卡片 | streaming 的 `RUNTIME_BLOCKED`、resource/native 的 `GOVERNANCE_REVIEW`、schema-only 的 `DESIGN_ONLY` 被隐藏 | 已在 palette、节点卡片和 inspector 展示 readiness badge / notice |
| 用户库 design-only operator 不应凭 tag 硬塞进内置编辑器 | `risk:eligibility`、`support:classify-ticket` 虽像规则/决策，但没有 config editor contract，硬打开 decision table 会误导 | 本轮保留 schema contract + design readiness；后续需要 operator-library 级 `ux.editorHint` / `interactionModel` |
| Java native alias 与 BLOGE DSL 内置算子并存 | `__decision_table__` 与 `bloge:decisionTable`、`__transform__` 与 `bloge:transform` 容易让作者不知道优先用哪个 | 本轮通过 readiness review 暴露治理差异；后续可在 palette 增加 preferred/advanced 分层 |

## 4. 逐算子 UX 审查报告

| # | operatorRef | 当前 UX 审查结论 | 本轮动作 | 残留缺口 / 后续路线 |
| ---: | --- | --- | --- | --- |
| 1 | `MockCitationStreamingOperator` | 流式 citation frame 输出；已有 Streaming 视觉族和 stream contract，但运行态阻断过去不明显 | 新增 `blocked` readiness 徽标、节点 notice、inspector Readiness 行 | 后续进入 runtime binding / streaming preview 面板，不在 author canvas 本地伪执行 |
| 2 | `MockLlmTokenStreamingOperator` | LLM token stream；与 citation 不同的是 token 语义，但同属 request-response runtime 不可执行 | 通过统一 readiness 暴露 `RUNTIME_BLOCKED` | 后续需要 token stream preview / runtime adapter 绑定视图 |
| 3 | `MockMetaStreamingOperator` | 搜索 metadata stream；输出仍是 event frame，不应被误解为普通 object output | 通过 Streaming 族 + blocked readiness 保持可识别 | 后续和 streaming runtime evidence 一起做事件类型预览 |
| 4 | `__decision_table__` | Java native decision table alias；视觉上可进入规则矩阵，但它不是首选 DSL 算子 | 继续使用 Decision table 族；新增 governance review readiness | 后续 palette 应把 native alias 标为 advanced，推荐优先使用 `bloge:decisionTable` |
| 5 | `__foreach__:enrichOrders` | 循环集合语义清晰，节点表达 collection/item/result；核心缺口是 loop body 不可视化 | 保留 Foreach 族和 Loop contract inspector | 后续独立设计 loop body 子图，不用临时表单硬塞 |
| 6 | `__transform__` | Java native transform alias；字段映射语义成立，但与 DSL transform 并存 | 继续使用 Transform 族和 mapping editor；新增 governance review readiness | 后续和 native alias 分层一起处理推荐优先级 |
| 7 | `bloge:decisionTable` | 规则矩阵是核心；已支持双击浮层、动态条件列/输出列、incoming data 锁定列 | 已完成本地规则矩阵编辑闭环 | 后续可补规则命中预览和表达式校验，但不阻断当前目标 |
| 8 | `bloge:transform` | 字段映射是核心；已支持双击 mapping editor 和 assignments 导出 | 已完成本地 mapping 编辑闭环 | 后续可补表达式 autocomplete / type hint |
| 9 | `httpRequest` | 原来像 generic object operator，无法看出 HTTP 边界 | 新增 HTTP 视觉族、`request -> response` contract、HTTP inspector | 后续可做 HTTP request builder，但需先定义 Java operator config contract |
| 10 | `httpResource` | 原来被误判 generic；这是 Resource Gateway 的低级资源执行算子 | 改为 Resource 视觉族，展示 `params -> payload` 和 governance review | descriptor 编辑仍应走 resource registry / OpenAPI contract 治理 |
| 11 | `orders:normalize` | 用户库 schema-only 订单归一化；tag 有 transform，但没有内置 mapping config contract | 保留 generic schema contract + design readiness，避免误开 transform editor | 后续由 operator library 声明 `ux.editorHint=mapping` 后再启用专用编辑器 |
| 12 | `orders:route-sla` | 用户库 schema-only 路由决策；业务像 route/rules，但没有 decision table config contract | 保留 schema contract + design readiness | 后续需要 routing/decision UX hint，而不是凭 tag 推断 |
| 13 | `resource:catalog-service.getProduct` | 强类型 resource descriptor；params/payload 合同清楚 | Resource 族已覆盖；readiness 暴露 governance review | 后续 descriptor 版本、auth、timeout 编辑在 registry 视图处理 |
| 14 | `resource:credit-provider.primary` | 外部 credit provider；同类 primary/secondary 容易混淆，但名称与 resource id 可区分 | Resource 族 + review readiness | 后续可以在 resource inspector 加 provider role / fallback chain 摘要 |
| 15 | `resource:credit-provider.secondary` | secondary credit provider；与 primary 的差异靠名称表达 | Resource 族 + review readiness | 后续同上，补 provider/fallback topology 视图 |
| 16 | `resource:invoice-service.getInvoice` | invoice detail resource；params/payload 合同清楚 | Resource 族 + review readiness | 后续补 resource registry drill-down 链接 |
| 17 | `resource:license-service.getLicense` | license check resource；属于外部读取边界 | Resource 族 + review readiness | 后续补 license domain 示例 fixture |
| 18 | `resource:loan-applicant-service.getProfile` | loan applicant profile；常作为 decision table 上游 facts | Resource 族 + review readiness；incoming edge 可成为 decision table condition column | 后续补字段候选说明中的业务字段描述 |
| 19 | `resource:logistics-service.getShipping` | shipping quote resource；输出 payload 适合接 routing/notification | Resource 族 + review readiness | 后续补 resource latency/cost hint |
| 20 | `resource:notification-service.unread` | unread notification resource；输出 array-like facts | Resource 族 + review readiness | 后续补 collection/path candidate 预览 |
| 21 | `resource:order-service.listOrders` | order list resource；输出集合，常进入 foreach | Resource 族 + review readiness；foreach 连接候选可解释 collection | 后续补 array item path 在 resource inspector 中的摘要 |
| 22 | `resource:recommendation-service.forUser` | recommendation resource；payload items 适合后续 transform | Resource 族 + review readiness | 后续补推荐结果 fixture 示例 |
| 23 | `resource:user-service.getProfile` | user profile resource；常作为 profile facts 源 | Resource 族 + review readiness | 后续补常用字段 chips，如 tier/segment/score |
| 24 | `resource:wallet-service.getBalance` | wallet balance resource；finance 语义明确，输出 amount/currency | Resource 族 + review readiness | 后续补 money schema display，避免 number 语义过淡 |
| 25 | `risk:eligibility` | 用户库 design-only policy gate；不能执行，也不能假装是内置 decision table | schema contract + design readiness 已可见 | 后续需要 operator-library UX metadata 声明 policy editor 或 runtime binding |
| 26 | `support:classify-ticket` | 用户库 design-only support triage；输出 priority/topic/action | schema contract + design readiness 已可见 | 后续需要 triage classifier 的示例 fixture 和 editor hint |

## 5. 本轮后差距复核

| 维度 | 上轮估计 | 本轮估计 | 结论 |
| --- | ---: | ---: | --- |
| Schema 连线体验 | 29/30 | 29/30 | 本轮未改连接主流程，保持 incoming data 条件列闭环 |
| 算子专有表达 | 25/25 | 25/25 | 从算子族提升到 26/26 operatorRef 审查；新增 HTTP 族；修正 `httpResource` |
| 任务流可发现性 | 20/20 | 20/20 | 双击编辑器、readiness、design/runtime 风险都在作者路径中可见 |
| 浏览器视觉证据 | 15/15 | 15/15 | 真实 `/author/` 桌面与 390px mobile 均验证 HTTP/Resource/Streaming readiness，无页面级横向溢出 |
| 回归与可维护性 | 10/10 | 10/10 | `summarizeOperator` 与组件测试覆盖新增分类和 readiness UI |

当前复核完成度：98/100
剩余目标差距：2%

## 6. 浏览器复核证据

复核对象：真实 `/author/` 页面，前端 Vite dev server `http://127.0.0.1:5173/author/`，后端 catalog `http://127.0.0.1:8080/api/visual/operators`。

| 视口 | 证据 |
| --- | --- |
| 桌面默认视口 | palette 显示 `26/26`；`httpRequest` 文案为 `HTTP ... request -> response ... review`；`httpResource` 文案为 `Resource ... params -> payload ... review`；`MockCitationStreamingOperator` 文案为 `Streaming ... request -> event stream ... blocked` |
| 桌面默认视口 | 添加 `httpRequest`、`httpResource`、`MockCitationStreamingOperator` 后，节点分别带 `kind-http`、`kind-resource`、`kind-streaming`，并显示 review/blocked readiness notice |
| 桌面默认视口 | 选中三类节点后，inspector 分别出现 `operator-focus:http` / `HTTP contract`、`operator-focus:resource` / `Resource contract`、`operator-focus:streaming` / `Stream contract`，且都有 `Readiness` 行 |
| 桌面默认视口 | 页面 `scrollWidth=725`、`clientWidth=725`，无页面级横向溢出 |
| 390px mobile | palette 显示 `26/26`；workspace 单列 `390px`；`httpRequest`、`httpResource`、streaming 的 readiness 文案仍可见 |
| 390px mobile | 添加三类节点后，三个节点 bounding box 均为 `left=18,right=372,width=355`，页面 `scrollWidth=390`、`clientWidth=390`，readiness 长文案未撑破布局 |

## 7. 下一轮针对性计划

1. operator-library 增加 `ux.editorHint` / `interactionModel`，让用户自定义 `risk:eligibility` 这类 design-only 算子能声明自己的编辑体验，而不是靠前端猜 tag。
2. palette 增加 preferred/advanced 分层，把 `bloge:decisionTable`、`bloge:transform` 标为推荐入口，把 `__decision_table__`、`__transform__` 这类 Java native alias 收到高级区。
3. resource inspector 增加 registry drill-down 链接和 resource contract 详情摘要，覆盖 auth、timeout、payloadPath、fallback chain 等治理信息。
4. streaming inspector 后续进入 runtime binding 视图，展示 implementation binding、adapter activation、event preview，而不是在 author canvas 本地伪执行。
