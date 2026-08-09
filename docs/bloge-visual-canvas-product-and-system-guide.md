# BLOGE 通用可视化编排画布产品与系统说明

> Scope: `resource-gateway-examples` 新版通用编排画布 · Primary UI: `/author/` · Companion UI: `/showcase/`

> 截图/标注图说明：本文后续页面截图来自本地演示服务的真实 `/author/` 与 `/showcase/` 页面；布局说明图用于标注本轮新增交互。蓝色框表示重点区域，橙色编号对应正文中的操作说明。

## 1. 产品定位

BLOGE 通用可视化编排画布是一套面向复杂业务编排的 topology-first、schema-backed 工作台。它以 resource gateway 的资源编排能力为基版，但不再把画布绑定到固定几个内置算子，而是允许用户直接粘贴已有 `.bloge` DSL 先可视化整体拓扑，也可以导入自己的算子库定义，把 topology-only draft 渐进增强成可校验、可模拟、可发布和可安全改写的 schema-backed 业务流程。

一句话：用户可以直接粘贴既有 DSL 先看完整业务拓扑，也可以导入结构合法的算子/函数 schema 从空白画布编排；schema 会把 topology-only draft 渐进增强为可校验、可模拟、可发布和可安全改写的 `GraphDraft`。

它解决的问题不是“把图画出来”，而是把以下闭环产品化：

```text
导入/采用算子库
  -> 拖拽算子
    -> schema 约束连线
      -> 服务端校验
        -> mock/real 混合模拟
          -> 导出草稿或发布物
```

这次改进的重点，是把旧版 Custom Composer 中“能用但不够直观”的能力，收束成更清晰的 React Flow authoring workspace：有可搜索 palette、 typed handles、自动布局、下一步行动提示、连接候选高亮、节点级 fixture、模拟 trace 和明确的 real/mocked 标记。

## 2. 面向谁

**业务编排者**：把风控、营销、订单、资源聚合、AI 工具链等业务逻辑按 DAG 编排出来，先验证逻辑，再交给工程实现。

**平台工程师**：维护算子库、资源描述、schema 合同、执行绑定和发布治理，把业务编排从手写代码拆成可审阅的合同资产。

**解决方案/售前/演示人员**：用 `/showcase/` 讲清楚 resource gateway 的典型场景，用 `/author/` 展示“用户自带算子库也能编排”。

## 3. 系统入口

| 入口 | 用途 | 推荐人群 |
| --- | --- | --- |
| `/author/` | 新版通用可视化编排画布，支持导入算子库、Legacy DSL preview、拖拽、连线、校验、模拟、导出 | 主要使用入口 |
| `/libraries/` | 从 durable draft 队列恢复 exact revision，创建/发现算子与 built-in function 库，校验 runtime/test readiness | 算子库作者与平台工程师 |
| `/rehearsals/` | 批量 Scenario 排练、失败分类、签名 evidence 与治理修复 | 测试、质量与治理人员 |
| `/showcase/` | React 版 resource gateway 场景目录，按后端场景顺序展示案例、图、请求执行和 SSE 流 | 演示与验证 |
| `/examples/gateway` | 旧版 Custom Composer/Showcase，保留兼容和功能回归价值 | 兼容入口 |

#### 中英文界面切换

新版 React 工作区顶部提供 `EN / 中文` 分段控件，支持 `/author/`、`/libraries/`、
`/rehearsals/` 和 `/showcase/` 统一切换。切换后当前页面立即更新，选择会保存到浏览器，
跨页面导航和刷新后继续生效；HTML 的 `lang` 属性也会同步更新，便于读屏软件采用正确语言。

演示或 Deep Link 可以显式指定语言：

```text
http://localhost:8080/author/?lang=zh-CN
http://localhost:8080/libraries/?lang=en
```

语言解析优先级为 URL `lang` 参数、已保存偏好、浏览器语言、英文默认值。URL 参数会覆盖
旧偏好，并在用户再次切换时随当前路由更新，不会把用户带离正在编辑的 Graph、draft 或 run。

界面文案、按钮、状态说明和无障碍标签会翻译；用户资产名称、算子/函数引用、DSL、JSON
Schema、fixture payload、JSONPath、fingerprint、draft/run ID 和后端诊断原文保持不变。
因此切换语言不会修改草稿、改变协议枚举值或使证据 fingerprint 漂移。扩展约束见
[可视化画布多语言设计与扩展指南](bloge-visual-canvas-localization.md)。

#### 测试矩阵里的证明权威怎么看

载入 **贷款策略与降级**，进入 **测试场景 -> 矩阵**，每一行需要分四步阅读：

1. **行为结论**：运行是否完成、业务断言是否通过；
2. **证明**：只做了 Schema 检查、Mock 模拟、沙盒/真实运行，还是形成了可认证证据；
3. **新鲜度**：证据是否仍对应当前 Graph/Contract；没有执行证据时显示“未评估”，不会显示“当前”；
4. **门禁资格**：只有当前、可认证、执行成功且业务断言全部通过的证据才可用于发布门禁。

![中文测试矩阵的行为与证明权威](assets/resource-gateway-ux-round3-s3-matrix-authority-zh.png)

Mock 用例可以显示业务断言通过，但仍明确标为“Mock 模拟 / 不可用于发布门禁”。选择一行后切换
`EN / 中文`，同一 Graph、case selection、Matrix 视图和运行作用域保持不变：

![英文测试矩阵保持同一选择](assets/resource-gateway-ux-round3-s3-matrix-authority-en.png)

失败协议 code、原始服务端 message 和 assertion technical observation 默认不占据业务列表；需要排障
时展开 **技术详情**。完整设计与验证证据见
[S3 证明语义与本地化实现说明](resource-gateway-ux-round3-s3-proof-semantics-localization-implementation.md)。

打开 `/author/` 后先按下面这张图定位页面：

![Author 工作台总览标注](assets/bloge-author-overview-annotated.svg)

图中 7 个区域分别承担不同任务：

1. **算子库导入**：粘贴 JSON/YAML，标准 `bloge.visualOperatorLibrary.v1` 先 Validate，再 Import；如果粘贴的是 `bloge.capabilityCatalog.v1`，先点击 `Adapt Catalog` 生成标准 visual library 草稿。导入成功后算子会出现在下方 palette；如果当前粘贴内容是 JSON 形式的合法 visual operator library，也可以作为本次 Legacy DSL preview 的 inline schema 使用。
2. **Legacy DSL**：粘贴既有 `.bloge` DSL，点击 Render DSL，服务端会先按 DSL AST 推演拓扑并投影成可编辑画布 draft。这个入口不要求先准备完整 operator/function schema；schema 只是后续把 topology-only draft 增强为 schema-backed draft 的精确层。
3. **内置复杂示例**：直接加载可编辑的复杂业务 graph，适合第一次理解 fan-out、decision table、transform、fixture 的组合方式。
4. **编排动作条**：执行 Simulate、Auto Layout、Canvas Focus、Validate、Export Draft，并查看节点数、边数、输出节点和 fixture 数。
5. **Graph Contract**：显示当前 graph 的 input/output schema 摘要，告诉系统集成方这张图需要什么上下文、会产出什么结果。
6. **Contract / Data**：`Contract` 定义 graph input/output schema；`Data` 根据 input schema
   生成这次运行的 Run Input controls，并在选中节点后显示真实入边和直接绑定。额外
   context 与 Raw JSON 只在 `Advanced` 中按需展开。
7. **Scenarios / Evidence 正式工作面**：顶部切换 Scenarios 后，中央区域直接显示
   schema 表单化的 Given、依赖行为和 Then 断言；Run 后原地切换 Evidence。右侧只保留
   当前 Graph/Operator、节点与直接上下游的轻量拓扑上下文。旧 Test Suite 浮层只在
   Legacy Workspace 保留。

#### 用一套语言创建 Graph、Operator 和 Function 测试

Graph Scenario、Operator contract test 和 built-in Function test 现在共享同一条主路径：

1. 左侧选择或新增一个 case，右侧只编辑当前 case；
2. **Given** 根据 input schema 或 function signature 生成具名控件；
3. Graph 的 **Dependencies** 只列出本场景主动控制的调用；未列出的节点照常运行；
4. **Then** 编辑 mocked output、return schema、expected error 或 Graph assertion；
5. 点击 **Run case** 或 **Run all**，结论明确区分 `Schema valid` 与 `Runtime passed`；
6. 只有复杂或暂不可投影的值才展开 **Advanced JSON**。

Loan 示例的 Prime 场景有 5 个节点，但只有 3 个 fixture，因此默认只显示 3 张
`controlled dependencies` 卡。完整卡以目标和 Return/Error 等行为摘要折叠；缺 selector、
Return output、Replay reference、duration 或 error code 的卡会自动展开。

在 **Libraries → Complete examples → Customer Support Triage** 中选择 Operator 或
Function，再点 **Open test table**，可以看到同样的 Given / Dependencies / Then 分段。
Function signature 会把有序 `args[]` 投影为具名字段，运行时再无损恢复原协议顺序。
点击 **Save fixture** 时，右侧打开非模态 side sheet；先检查分类、保留期限、脱敏路径和
脱敏后 payload preview，再确认加密保存。父测试窗口不会再叠加第二个 dialog。

#### 从 Library Home 恢复存量资产

`/libraries/` 的第一屏现在是资产工作队列，而不是创建向导。先从以下视角收敛任务：

| 队列 | 含义 | 推荐动作 |
| --- | --- | --- |
| Recent drafts | 14 天内更新的 durable draft | Resume exact revision |
| My libraries | owner 或最后保存者是当前认证 actor | 继续编辑或检查 test gate |
| Needs confirmation | server preview 仍有显式声明决策 | 进入 Workbench 完成确认 |
| Runtime drift | 声明合同与当前 runtime inventory 不一致 | 核对 runtime binding 后重验 |
| Test gate incomplete | 当前 exact revision 未达到 TEST_EVIDENCED | 打开 operator/function test table |
| Ownership conflict | library owner 未解析 | 先补 owner，避免错误发布责任边界 |

每行 `Resume rN` 同时携带 `draftId` 与 `revision`。如果该 revision 仍是 mutable head，
直接进入 Workbench；如果链接指向历史 revision，则打开只读快照并明确显示当前 head。
历史页面只能选择 **Resume latest** 或 **Fork this revision**，不会把旧内容用过期
`If-Match` 写回当前 draft。

Home 的 `My libraries` 使用服务端认证后的 actor：

```text
GET /admin/visual-operator-library-authoring/drafts/context
GET /admin/visual-operator-library-authoring/drafts/{draftId}/revisions/{revision}
```

不存在的历史 revision 返回明确 404，不会退回 latest。fingerprint、创建时间和 source mode
折叠在 **Technical coordinates**，首屏优先显示 owner、readiness、更新时间和下一步动作。

当业务图已经有多层依赖或边标签较多时，优先点击工具条里的 **Canvas Focus**。它会临时收起左侧 Library/Legacy DSL/Palette、右侧 Checklist/Runtime/Test Suite inspector、顶部 workflow 和示例卡，只保留 toolbar、Graph Contract 和主画布。这个模式适合做拓扑审阅、Auto Layout 后验收、拖线调试和演示复杂图。

![Author Canvas Focus 模式标注](assets/bloge-author-canvas-focus-annotated.svg)

1. **Focus toggle**：`Canvas Focus` / `Exit Focus` 是同一个按钮，切换后画布会重新 fit view，避免节点仍停在旧视窗位置。
2. **辅助栏临时收起**：左侧算子库和右侧 inspector 不销毁状态，只在 Focus 模式下隐藏；退出后继续从原上下文编辑。
3. **示例区压缩/隐藏**：默认态示例卡更紧凑，Focus 模式完全隐藏示例区，把垂直空间还给 React Flow。
4. **主画布高度目标**：桌面真实浏览器回归要求标准态 author flow 至少 620px 高，Focus 态至少 760px 且比标准态多 100px 以上。

#### 复杂图导航：先看形状，再看路径，最后看字段

画布导航条现在提供三个明确的阅读任务，不再要求用户猜测“缩放到多少才会看到什么”：

| 阅读模式 | 画布保留的信息 | 适合任务 |
| --- | --- | --- |
| **Overview** | 节点、连线、stage/lane 和全图形状；字段 label 固定为 0 | 判断 25/100 节点图结构 |
| **Focus** | 当前节点的完整上游/下游闭包；旁路降噪，最多 12 个语义 label | 排障和解释一条业务路径 |
| **Inspect** | 完整字段依赖、选中节点详情和精确坐标 | 核对 contract 与字段映射 |

缩放档位仍控制节点卡内部的视觉密度，但不再承担任务模式的全部语义。导航条同时显示
`Readable` 或 `Review`，其判定包含等效标题字号、zoom、节点/标签碰撞、label 数量和屏幕
密度，而不只是“节点有没有重叠”。

并行字段边不会各自占一张标签。同一 source 的字段会聚合成一张 bundle，例如
`6 fields / 4 targets · applicantId, income +2 -> userId, income +3`；悬停 title 仍保留
每条原始 `source.path -> target.path`，因此降噪不会丢失可追溯语义。

推荐按下面的顺序审阅复杂图：

1. 点击 **Auto Layout**。画布先生成预览，并对比当前布局与候选布局的 zoom、可读状态和有效
   标题字号。候选不退化时才能点击 **Apply**；如果小图低于 80%、标题低于 12px、碰撞增加
   或图面积异常膨胀，系统会建议 **Keep current layout** 并禁用默认 Apply。确有需要时只能从
   **Advanced** 显式选择 **Apply anyway**，该决定会留下不含业务 payload 的审计事件。
   对小型分层图，生成器使用与质量门禁一致的 `24px` 双侧节点安全带，并为边标签保留额外
   `8px` 安全带；因此中文换行、边框和缩放取整不会让两张标签在浏览器里悄悄相贴。
2. 点击 **Overview** 或使用 Map 判断全图形状。这个阶段字段 label 固定隐藏。
3. 选中关键节点，再点击 **Focus**。画布只显示该节点完整上游/下游闭包的语义 label，
   旁路节点与边会降噪。
4. 点击 **Inspect** 恢复完整字段语义。三种模式都只改变阅读投影，不删除节点、边或 fixture。
5. 如果布局结果不合适，立即点击 **Undo layout** 恢复布局前的精确坐标。新增、删除或手动
   移动节点后，旧撤销快照会失效，防止把后来编辑的图错误回滚。
6. 在 Inspect 中检查端口、contract 和字段映射。竞争的长标签会在上下安全带间分流；
   `Fit` 最多恢复到 100%，不会意外放大后把标签挤出视口。

常用桌面断点下，内置 Loan 示例的 12 条字段依赖显示为 4 个语义 bundle。在
`1472 x 920` 页面、实际 `1024 x 434` 画布区域中，Auto Layout 实测为 `80% / PASS / 12px`
有效标题，节点覆盖、标签覆盖和视口裁切均为 0。窄视口若完整 Inspect 低于可读性地板会
诚实显示 Review；先用 Overview 看形状，再选节点进入 Inspect。

`Canvas Focus` 与 `Focus Path` 不是同一能力：前者扩大可用画布面积，后者从业务语义上
筛选当前要读的依赖路径。复杂图通常先进入 Canvas Focus，再使用 Auto Layout、Overview
和 Focus Path。

下图是 `Loan policy fallback` 在 49% Compact 档选择 `Primary credit` 后的真实页面：
Map 显示全图 5 个节点、当前路径 4 个节点；`Show All` 用于退出路径聚焦；不在路径上的
secondary credit 被降为背景层，关键路径字段坐标仍然完整显示。

![Author 语义缩放与 Focus Path](assets/bloge-author-semantic-navigation-v2.png)

#### 键盘、窄视口和宿主遥测

Start/Import 与 Operator details 两类临时浮层遵循同一个键盘协议：

1. 打开后焦点进入当前主任务，而不是留在被遮挡的页面；
2. `Tab` / `Shift+Tab` 被约束在当前浮层；
3. `Escape` 等价于该浮层的取消/关闭动作；
4. 关闭后焦点回到打开浮层的控件，作者可以继续键盘操作；
5. 所有 Author v2 主控件使用统一的高对比 `focus-visible` 轮廓，状态同时有文字，
   不依赖颜色单独表达。

Contract、Scenarios 和 Evidence 不属于临时浮层：它们由顶部任务模式直接占用中央工作面，
浏览器 Back / Forward 会恢复准确 mode、target、Scenario 和 run。

#### 工作区恢复与权威保存

Author v2 的草稿身份旁会直接显示工作区生命周期：

- **DIRTY / 有未保存修改**：当前内容尚未形成恢复快照；
- **RECOVERABLE / 可恢复**：当前标签页已经保存 TTL 恢复包，但尚不等于服务端 revision；
- **SAVING / 保存中**：Save 或 autosave 正在提交权威 revision；
- **SAVED / 已保存**：当前内容指纹与服务端 revision 一致；
- **CONFLICTED / 冲突**：服务端 revision 已前进，禁止旧响应覆盖新编辑；
- **RECOVERABLE_OFFLINE / 离线可恢复**：服务端不可达，但本地恢复包仍有效。

加载完整示例后等待 **可恢复**，可以直接切到 **算子库** 再返回 **编排**；Graph、fixture、Scenario set、
算子测试套件和运行输入会回到离开前状态。草稿身份旁的 Save 图标用于创建或更新服务端权威 revision。
跨工作区导航会先 flush 最新快照，只有存储失败时才弹出保存、导出、放弃或留在此处的决策。

浏览器演示使用当前标签页 `sessionStorage`，按 tenant/namespace/environment 分区并设置 8 小时 TTL；它不是
企业加密存储。VS Code 或企业宿主应通过 `setWorkspaceRecoveryStore` 注入 `HOST_ENCRYPTED` 实现。详细协议、
不变量和测试方式见
[S0 工作区连续性实现说明](resource-gateway-ux-round3-s0-workspace-continuity-implementation.md)。

#### 删除影响预览与通用 Undo / Redo

Author v2 会把 Graph、binding、fixture、算子测试和契约编辑统一记录为可逆事务。选择画布节点后按
Delete / Backspace；如果该节点带有 fixture、test suite、test result、publication 或 Graph output
binding，系统会先列出每类受影响资产和关联连线数量。选择 **保留节点** 不改变任何数据；选择
**删除节点及关联资产** 才原子提交删除。

![中文删除影响预览](assets/resource-gateway-ux-round3-s1-delete-impact-zh.png)

删除后可以点击草稿身份旁的 Undo 图标、右下角 Undo，或按 `Cmd/Ctrl+Z`。节点、连线、fixture 与
测试资产会一次恢复；随后提示条动作变为 Redo，也可按 `Cmd/Ctrl+Shift+Z` 重做。Save 建立权威
checkpoint，但不清空本地 Undo 历史。算子详情和布局预览只有在点击 **Apply** 后才成为一个事务，
Cancel 不会制造无意义历史。

![一次撤销后的完整恢复](assets/resource-gateway-ux-round3-s1-undo-restored.png)

完整 mutation 范围、恢复预算和安全边界见
[S1 可逆编辑与删除影响控制实现说明](resource-gateway-ux-round3-s1-reversible-mutations-implementation.md)。

#### 企业任务坐标、权限与生产保护

Author、Libraries 和 Rehearsals 顶部现在使用同一条 Workspace Context Bar。执行命令前先核对当前资产、
tenant、namespace、environment、role、scope/target 数量和 owner；这些值同时来自当前 `TaskCoordinate`，
不是与实际命令分离的装饰标签。

![Author 的企业任务坐标与单一命令条](assets/resource-gateway-ux-round3-s2-author-context.png)

常用策略如下：

| 当前上下文 | 页面行为 |
| --- | --- |
| `VIEWER` / `REVIEWER` | 查看和导航保留；导入、编辑、删除等 mutation 禁用并说明原因 |
| session tenant 与任务 tenant 不一致 | mutation fail closed，先返回所属 tenant |
| production destructive mutation | 显示 environment/target，必须键入 `PRODUCTION` 后才能确认 |
| test/staging 且角色允许 | 按当前可见 scope 直接执行 |

![生产环境载入示例的显式确认](assets/resource-gateway-ux-round3-s2-production-safeguard.png)

Scenario Matrix 不再同时陈列多个主运行按钮：没有选中 Case 时唯一主命令是 **Run all**；选中 Case 后
变为 **Run selected**。Run failed/changed/affected 等范围进入同一个菜单。Rehearsal evidence 返回 Author
时会恢复 draft、node、Scenario 与 run；返回原页面后继续恢复条目焦点和滚动位置。`returnTo` 只接受同源
应用路径，不能借 deep link 绕过 tenant/role policy。

![Library exact revision 使用相同企业坐标](assets/resource-gateway-ux-round3-s2-library-context.png)

实现边界、URL 字段和测试证据见
[S2 企业任务坐标实现说明](resource-gateway-ux-round3-s2-enterprise-task-coordinate-implementation.md)。

在 `390 x 844` 视口，命令条改为单列、状态与辅助动作改为两列；Contract、Scenarios 和
Evidence 继续是中央工作面，Topology Context Rail 变为按需抽屉。Author 使用全高应用壳，
中央内容独立滚动，底部 Run 与 Diagnostics 不会互相覆盖。这个模式定位为**查看、运行、
轻量修改和故障回看**；大规模拖拽、密集字段连线和 100 节点结构调整仍建议使用桌面视口。

顶栏还提供 **Comfortable / Compact** 密度切换。Comfortable 适合首次使用、审阅和触摸设备；
Compact 适合熟练作者在桌面处理高信息密度任务。密度选择会跨刷新保持，但只改变 padding、gap
和桌面控件高度，不缩小正文与辅助文案。840px 以下以及触摸设备中，两种模式的关键控件均不低于
40px。窄屏顶层导航通过 Menu 明确展开为两列，不再依赖隐形横向滚动。

390px Compose 中，**Readiness** 汇总五个状态，**Tools** 汇总辅助命令，点击后再展开；切换到
Contract、Scenarios 或 Evidence 后，这两组重复摘要自动隐藏，让中央任务面优先占用高度。
这些正式任务默认收起 **Topology**；点击后它从右侧覆盖打开，不会把中央任务挤窄，点击抽屉
顶部的收起按钮即可回到原任务位置。
进入 Scenarios / Case 后默认选择 **Run** 移动任务：顶部 picker 切换 Case，摘要显示输入字段、
受控依赖、检查项和最近结果，底部只有一个 **Run current case** 主命令。需要修改时点击
**Build**，再在 **Input / Fixtures / Expected / Run** 四步中一次编辑一步；点击摘要中的
**Edit input / Edit fixtures / Edit expected** 会直接进入对应步骤。未选步骤不会进入键盘 tab
顺序；**Run current case** 固定在首屏任务预算内，执行成功后直接进入该 Case 的 Evidence。
Scenario Matrix 保持 Case 列冻结，并用左右阴影提示还有横向内容。完整设计边界与验收证据见
[Stage 5 视觉系统与响应式任务布局](resource-gateway-ux-stage5-visual-responsive-system.md)。

在 390px 打开 **Operator Libraries** 后，完整草稿默认进入 **Review**：使用 **Current asset**
picker 在 Library、Named Type、Operator 和 Built-in Function 间切换，中央只显示该资产的规模、
契约 readiness、诊断和 runtime 状态。Operator/Function 可直接 **Open test table**；点击 **Edit
basics** 只编辑名称、描述、类别、版本或负责人，并继续自动保存与服务端校验。输入输出 Schema、
嵌套字段、签名重载和 runtime governance 不会在窄屏降级呈现；Named Type 会禁用轻编辑并提供
精确到 draft revision 和 asset ref 的 **Open desktop editor** 链接。

Library 的保存、校验与 runtime 结论使用稳定的双语消息协议。中文界面会直接显示“已保存修订版
N”“设计有效，运行时未绑定”“仅有文档”等产品结论；服务端原始英文 message 不再作为翻译 key，
只保留在默认收起的 **技术详情** 中，便于排障时结合 reason code 查看。切换语言会重新渲染当前
动态状态，不需要重新校验或重新打开草稿。算子名、字段名、operatorRef、Schema path 和协议 code
属于用户资产或机器坐标，按原文保留。

Auto Layout 的质量结论也遵循同一协议。布局算法只返回节点重叠、标签碰撞、缩放、有效标题字号、
标签密度和稳定原因码，界面再按当前语言生成产品结论。点击 **Auto Layout** 后，候选审阅区会同时
显示当前/候选缩放对比，以及候选自己的几何和感知指标；不会用当前画布的指标冒充候选证据。
中文界面会显示“几何通过”“可读性需检查”等结论，数量和字号会格式化为可读值；`PASS / REVIEW`
仅作为内部状态，不会混入中文句子。切换语言不触发布局重算，也不改变待接受的候选坐标。

**运行示例（Run examples）** 和 **Rehearsals > Samples** 的内置演示内容也会随界面语言切换。运行示例的标题、
架构模式、说明和概念标签来自 `graphName` 对应的双语 presentation；`graphName`、算子引用和字段路径
仍按原文显示。客户或服务端新增但前端尚不认识的场景不会被错误翻译，而是忠实呈现其元数据。
Rehearsal 样例的标题、业务情境和学习重点同样使用稳定 descriptor。样例中的 Deadline、Attempt、
Started、Completed 按批次开始时间显示相对关系，例如“4分钟后”，避免固定演示日期逐渐显得陈旧；
Live 模式中的真实运行证据仍显示完整的本地化绝对时间。

对开发者而言，多语言接口分为三类：静态字面量用 `t()`，类型化产品消息用 `m(messageId,
params)`，状态机或协议投影的动态值用 `d(value)`。关键产品面禁止 `t(variable)`；未登记动态值会
显示“未识别的产品状态，请查看技术详情”，不会把新服务端英文句子直接暴露给中文用户。
排障信息并未丢失：failure code 以机器坐标显示，raw message 放在默认收起的 **技术详情**。

Author Workspace v2 会在浏览器中派发 `bloge:author-task` `CustomEvent`。宿主应用或
VS Code webview 可以选择监听它来计算任务漏斗，Resource Gateway 前端本身不会把事件
主动发送到网络：

```javascript
window.addEventListener('bloge:author-task', ({ detail }) => {
  taskMetrics.accept(detail);
});
```

事件 envelope 固定为 `bloge.authorTaskEvent.v1`，当前事件包括
`WORKSPACE_OPENED`、`START_CHOICE_SELECTED`、`EXAMPLE_LOADED`、`MODE_CHANGED`、
`AUTO_LAYOUT_COMPLETED`、`AUTO_LAYOUT_UNDONE`、`RUN_STARTED`、`RUN_COMPLETED` 和
`FIRST_SUCCESS`。metadata 只允许 workspace 版本、模式、入口类型、节点/边/用例数量、
运行类型、状态和耗时等低基数信息。包含 `context`、`fixture`、`payload`、`schema`、
`dsl`、`config`、`input/output`、secret/token/credential 的 key 会被拒绝；字符串还有
64 字符上限。无效遥测被丢弃，不能中断作者操作。

`/author/` 默认进入 Author Workspace v2。顶栏的 **Legacy** 入口或
`?authorWorkspace=legacy` 会立即回到旧 Shell；`?authorWorkspace=v1` 继续作为历史书签
兼容别名。切换链接会保留 `draftId`、`nodeId`、`runId` 等 deep-link 坐标，只改变 UI
投影，不迁移或回滚 GraphDraft、Contract、Scenario 和运行证据。未知或格式错误的显式
`authorWorkspace` 值仍 fail closed 到 Legacy，避免错误灰度参数静默进入新版。

### 3.1 调用 Integration API 前先建立受信身份

`/api/integration/capabilities` 和 evidence 验签公钥是公开探针；其余 `/api/integration/*` 接口必须先验证 workload credential。`X-Tenant-Id`、`X-Organization-Id`、`X-Environment-Id` 和 `X-Actor-Id` 不再构成身份，只是迁移期一致性 hint：缺失不影响服务端 claims，填入值与受信 claims 冲突则返回 `403 RG.INTEGRATION.IDENTITY_CLAIM_MISMATCH`。

![Resource Gateway Integration 受信身份与纵深授权](assets/resource-gateway-integration-trusted-identity.svg)

图源文件：[`assets/drawio/resource-gateway-integration-trusted-identity.drawio`](assets/drawio/resource-gateway-integration-trusted-identity.drawio)

企业动态信任、轮换、撤销和故障语义如下图。它与上图不是重复关系：上图说明请求授权边界，本图说明信任材料如何在
不重启 Resource Gateway 的情况下演进，以及为什么身份权威故障不能伪装成“用户 token 错了”。

![Resource Gateway 动态 JWKS 信任生命周期](assets/resource-gateway-dynamic-jwks-trust-lifecycle.svg)

图源文件：[`assets/drawio/resource-gateway-dynamic-jwks-trust-lifecycle.drawio`](assets/drawio/resource-gateway-dynamic-jwks-trust-lifecycle.drawio)

本地演示默认启用一个**仅供 demo** 的 server-side identity registry：

```text
Bearer token: bloge-aneke-demo-token
tenant:       tenant-a
organization: knowledge-governance
project:      tool-studio
environment:  prod
actor:        aneke-sync
```

因此最小同步请求是：

```bash
curl -sS 'http://localhost:8080/api/integration/reconciliation' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: CHANGE_SYNC'
```

建议保留 matching hints，便于在代理迁移或配置漂移时 fail fast：

```bash
curl -sS 'http://localhost:8080/api/integration/reconciliation' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-Organization-Id: knowledge-governance' \
  -H 'X-Project-Id: tool-studio' \
  -H 'X-Environment-Id: prod' \
  -H 'X-Actor-Id: aneke-sync' \
  -H 'X-Purpose: CHANGE_SYNC' \
  -H 'X-Correlation-Id: sync-0001'
```

服务端会执行两层 purpose allowlist：credential identity 必须允许该 purpose，目标 endpoint 也必须接受该 purpose。

| operation | 允许的 purpose |
| --- | --- |
| draft export | `GOVERNANCE_EVIDENCE_INGESTION`、`CHANGE_SYNC` |
| run evidence | `GOVERNANCE_EVIDENCE_INGESTION` |
| side-effect reconciliation summary | `GOVERNANCE_EVIDENCE_INGESTION`、`SIDE_EFFECT_RECONCILIATION` |
| execute side-effect reconciliation | `SIDE_EFFECT_RECONCILIATION` |
| recorded payload read | `GOVERNANCE_EVIDENCE_INGESTION`、`PAYLOAD_REPLAY` |
| recorded assertion replay | `PAYLOAD_REPLAY` |
| gate result write | `GOVERNANCE_GATE_FEEDBACK` |
| gate result read | `GOVERNANCE_EVIDENCE_INGESTION`、`GOVERNANCE_GATE_FEEDBACK` |
| events / reconciliation / library / suite | `CHANGE_SYNC` |

每次允许或拒绝都会写入 `integration_access_audit`，记录 identity、tenant/environment、organization、actor、operation、
purpose、outcome 和 reason code。signed JWT 还会记录验证它的 `kid`、不可重放的 `jti`、delegation grant id、clearance、
group 数量和排序后 group 集合的 SHA-256 指纹；不会保存 Bearer token、签名内容或原始 group 名单。认证在资源查询之前
完成；service 和 repository 随后仍按 tenant/environment 做二次 scope predicate，避免只靠入口层。

企业部署必须关闭公开 demo credential。推荐直接接企业 JWKS 和版本化撤销 feed；不需要自定义 Spring Bean，也不需要
重启 Resource Gateway 才能轮换 key：

```bash
export RG_INTEGRATION_DEMO_IDENTITY_ENABLED=false
export RG_INTEGRATION_JWT_ENABLED=true
export RG_INTEGRATION_JWT_ISSUER='https://iam.example.com/'
export RG_INTEGRATION_JWT_AUDIENCE='resource-gateway'
export RG_INTEGRATION_JWT_JWKS_URI='https://iam.example.com/.well-known/jwks.json'
export RG_INTEGRATION_JWT_REVOCATIONS_URI='https://iam.example.com/resource-gateway-revocations.json'
export RG_INTEGRATION_JWT_REFRESH_INTERVAL_SECONDS=30
export RG_INTEGRATION_JWT_UNKNOWN_KEY_REFRESH_INTERVAL_SECONDS=5
export RG_INTEGRATION_JWT_REQUEST_TIMEOUT_SECONDS=3
export RG_INTEGRATION_JWT_OUTAGE_POLICY=FAIL_CLOSED
export RG_INTEGRATION_JWT_MAXIMUM_STALE_SECONDS=0
export RG_INTEGRATION_JWT_MAXIMUM_LIFETIME_SECONDS=900
```

JWKS 使用标准 `keys` 数组。当前接收 `RSA + RS256` 和 `OKP + Ed25519 + EdDSA`，只允许 `use=sig`、
`key_ops=["verify"]` 和至少 2048 bit RSA；出现 private JWK 字段、重复 `kid`、弱 key、redirect、非 JSON 或超过
256 KB 的响应都会使本次快照 fail closed。HTTP 只允许在显式测试开关下访问 loopback，生产 URI 必须是 HTTPS。

撤销 feed 与 JWKS 作为一份原子快照发布，任一文档失败都不会发布另一份的新内容：

```json
{
  "schemaVersion": "resourceGateway.integrationJwtRevocations.v1",
  "generatedAt": "2026-07-13T00:00:00Z",
  "expiresAt": "2026-07-13T00:05:00Z",
  "revokedKeyIds": ["aneke-sync-2026-06"],
  "revokedTokenIds": ["01J2TOKEN-REVOKED"]
}
```

`FAIL_CLOSED` 是默认生产策略：刷新到期后若权威不可达，受保护接口返回可重试的
`503 RG.INTEGRATION.IDENTITY_PROVIDER_UNAVAILABLE`。确有可用性需求时可选择 `BOUNDED_STALE` 并设置非零
`MAXIMUM_STALE_SECONDS`，但它只对网络、timeout 和远端 5xx 生效；畸形、过大或超过权威 `expiresAt` 的文档永远立即
进入 `EXPIRED`，不能用 stale 窗口掩盖。持续有认证请求时，key/jti 撤销的判定上界为
`refresh interval + request timeout`；unknown `kid` 会触发经过节流的 single-flight 刷新以支持无停机轮换。

静态 public-key JSON 仍可用于本地、隔离环境或迁移期基线，但它只在启动时装载：

```bash
export RG_INTEGRATION_DEMO_IDENTITY_ENABLED=false
export RG_INTEGRATION_JWT_ENABLED=true
export RG_INTEGRATION_JWT_ISSUER='https://iam.example.com/'
export RG_INTEGRATION_JWT_AUDIENCE='resource-gateway'
export RG_INTEGRATION_JWT_MAXIMUM_LIFETIME_SECONDS=900
export RG_INTEGRATION_JWT_TRUSTED_KEYS_JSON='[
  {
    "keyId": "aneke-sync-2026-07",
    "algorithm": "RS256",
    "publicKeyBase64": "<base64-of-X509-DER-public-key>",
    "notBefore": "2026-07-12T00:00:00Z",
    "expiresAt": "2026-10-12T00:00:00Z",
    "enabled": true
  }
]'
```

`publicKeyBase64` 是 X.509 SubjectPublicKeyInfo DER 的普通 Base64，只能放公钥；private key 必须留在企业 IAM/KMS。
静态模式可用 `RG_INTEGRATION_JWT_REVOKED_KEY_IDS` 和 `RG_INTEGRATION_JWT_REVOKED_TOKEN_IDS`，但变更需要重启，
因此不能用它证明企业撤销传播 SLO。自建 KMS、mTLS 或 service-mesh identity 仍可通过替换
`IntegrationJwtTrustStore` / `IntegrationIdentityResolver` SPI 接入。

ANEKE 发送的 JWT header 至少包含：

```json
{
  "alg": "RS256",
  "kid": "aneke-sync-2026-07",
  "typ": "JWT"
}
```

JWT payload 合同如下；`aud` 可以是字符串或数组，`purposes` 必须是非空数组：

```json
{
  "iss": "https://iam.example.com/",
  "aud": ["resource-gateway"],
  "sub": "aneke-sync-workload",
  "jti": "01J2TOKEN7YQ4M1",
  "iat": 1783843200,
  "nbf": 1783843200,
  "exp": 1783843500,
  "tenant_id": "tenant-a",
  "organization_id": "knowledge-governance",
  "project_id": "tool-studio",
  "environment_id": "prod",
  "region": "ap-southeast-1",
  "actor_type": "WORKLOAD",
  "actor_id": "aneke-sync",
  "groups": ["knowledge-owners", "tool-authors"],
  "clearance": "CONFIDENTIAL",
  "delegated_by": "alice@example.com",
  "delegation_grant_id": "grant-2026-0001",
  "delegation_exp": 1783843440,
  "delegation_purposes": ["CHANGE_SYNC", "GOVERNANCE_EVIDENCE_INGESTION"],
  "purposes": ["CHANGE_SYNC", "GOVERNANCE_EVIDENCE_INGESTION"]
}
```

`groups` 最多 64 项，`clearance` 支持 `PUBLIC / INTERNAL / CONFIDENTIAL / RESTRICTED`。当
`delegated_by` 非空时，grant id、grant expiry 和 grant purpose 集合都必填；token 的 purpose 必须是 grant purpose 的
子集，grant 不能晚于 token 过期，也不能自委托。非委托 token 应省略四个 delegation 字段。

服务端会拒绝 `alg=none`、算法/key 类型不一致、未知/停用/撤销 `kid`、撤销 `jti`、错误 issuer/audience、未来生效、
过期、超过最大 TTL、重复 JSON 字段、非法 purpose/group/clearance 和无效委托链。整个 Bearer credential 上限为
4096 字符。凭证可确定为无效时返回 401 且不暴露具体验签分支；只有权威不可用、系统无法安全作出判断时才返回可重试 503。

启动后先检查公开 capability。动态模式应看到 `providerType=SIGNED_JWT`、`claimsSource=DYNAMIC_JWKS`、
`demoMode=false`、`features.dynamicCredentialTrust=true` 和 `features.credentialRevocationPropagationSlo=true`；同时检查
`properties.refreshState`、`lastSuccessfulRefreshAt`、`refreshSuccessCount/failureCount`、`lastFailureCode`、
`activeKeyCount`、`revokedKeyCount/revokedTokenCount`、`outageFailClosed` 和
`revocationPropagationSloSeconds`。`STALE` 必须触发告警，`EXPIRED/UNAVAILABLE` 或没有 active key 时
`available=false`。不要把 `RG_INTEGRATION_DEMO_TOKEN` 的默认值带到共享或生产环境。

### 3.2 为运行证据启用 KMS/HSM 托管签名

本地演示默认使用 `DatabaseVisualEvidenceSigner`：它把 Ed25519 key pair 写入 H2，便于重启后验证演示 evidence，
但 `providerType=LOCAL_DATABASE`、`privateKeyExportable=true`、`productionReady=false` 会诚实暴露这不是生产 custody。
企业部署应启用 managed signer，让 Resource Gateway 只缓存公钥和 key lifecycle metadata，私钥始终留在 KMS/HSM：

![Resource Gateway 托管 evidence 签名保管链](assets/resource-gateway-managed-evidence-signing-custody.svg)

图源文件：[`assets/drawio/resource-gateway-managed-evidence-signing-custody.drawio`](assets/drawio/resource-gateway-managed-evidence-signing-custody.drawio)

最小生产配置如下。`base-uri` 指向企业内网 signing sidecar；生产必须使用 HTTPS，HTTP 只允许显式测试开关下的 loopback：

```bash
export RG_EVIDENCE_SIGNING_MANAGED_ENABLED=true
export RG_EVIDENCE_SIGNING_MANAGED_BASE_URI='https://evidence-signing.internal.example.com/'
export RG_EVIDENCE_SIGNING_MANAGED_PROVIDER_NAME='corp-hsm-prod-sg'
export RG_EVIDENCE_SIGNING_MANAGED_REQUEST_TIMEOUT_SECONDS=3
export RG_EVIDENCE_SIGNING_MANAGED_REFRESH_INTERVAL_SECONDS=30
export RG_EVIDENCE_SIGNING_MANAGED_UNKNOWN_KEY_REFRESH_INTERVAL_SECONDS=5
export RG_EVIDENCE_SIGNING_MANAGED_MAXIMUM_SNAPSHOT_LIFETIME_SECONDS=86400
```

sidecar 的 `GET /v1/evidence-signing/keys` 返回一个不可拆分的 key generation 快照。v2 必须恰好有一把
`ACTIVE` key，并携带有效期、历史完整性和有序生命周期事件；`VERIFY_ONLY`、`DISABLED`、前向撤销与追溯
compromise 会按 evidence 的签名时刻产生不同结论：

```json
{
  "schemaVersion": "resourceGateway.managedEvidenceSigningKeys.v2",
  "generatedAt": "2026-07-13T00:00:01Z",
  "expiresAt": "2026-07-13T00:05:00Z",
  "activeKeyId": "kms://evidence-signing/18",
  "policyCompleteness": "COMPLETE",
  "keys": [
    {
      "keyId": "kms://evidence-signing/17",
      "algorithm": "Ed25519",
      "encodedPublicKey": "<base64-X509-public-key>",
      "createdAt": "2026-06-13T00:00:00Z",
      "notBefore": "2026-06-13T00:00:00Z",
      "notAfter": null,
      "state": "VERIFY_ONLY",
      "providerKeyVersion": "projects/.../cryptoKeyVersions/17"
    },
    {
      "keyId": "kms://evidence-signing/18",
      "algorithm": "Ed25519",
      "encodedPublicKey": "<base64-X509-public-key>",
      "createdAt": "2026-07-13T00:00:00Z",
      "notBefore": "2026-07-13T00:00:00Z",
      "notAfter": null,
      "state": "ACTIVE",
      "providerKeyVersion": "projects/.../cryptoKeyVersions/18"
    }
  ],
  "lifecycleEvents": [
    {"sequence": 1, "eventId": "created-17", "keyId": "kms://evidence-signing/17",
      "type": "CREATED", "occurredAt": "2026-06-13T00:00:00Z",
      "effectiveAt": "2026-06-13T00:00:00Z", "revocationMode": null,
      "invalidFrom": null, "reasonCode": "KEY_CREATED"},
    {"sequence": 2, "eventId": "activated-17", "keyId": "kms://evidence-signing/17",
      "type": "ACTIVATED", "occurredAt": "2026-06-13T00:00:00Z",
      "effectiveAt": "2026-06-13T00:00:00Z", "revocationMode": null,
      "invalidFrom": null, "reasonCode": "KEY_ACTIVATED"},
    {"sequence": 3, "eventId": "retired-17", "keyId": "kms://evidence-signing/17",
      "type": "RETIRED", "occurredAt": "2026-07-13T00:00:00Z",
      "effectiveAt": "2026-07-13T00:00:00Z", "revocationMode": null,
      "invalidFrom": null, "reasonCode": "KEY_RETIRED"},
    {"sequence": 4, "eventId": "created-18", "keyId": "kms://evidence-signing/18",
      "type": "CREATED", "occurredAt": "2026-07-13T00:00:00Z",
      "effectiveAt": "2026-07-13T00:00:00Z", "revocationMode": null,
      "invalidFrom": null, "reasonCode": "KEY_CREATED"},
    {"sequence": 5, "eventId": "activated-18", "keyId": "kms://evidence-signing/18",
      "type": "ACTIVATED", "occurredAt": "2026-07-13T00:00:00Z",
      "effectiveAt": "2026-07-13T00:00:00Z", "revocationMode": null,
      "invalidFrom": null, "reasonCode": "KEY_ACTIVATED"}
  ]
}
```

`REVOKED/COMPROMISE_DECLARED` 必须额外给出 `PROSPECTIVE/RETROACTIVE`；追溯撤销还必须给出
`invalidFrom`。v1 sidecar 在迁移期仍可读取，但 Gateway 会强制标为 `CURRENT_STATE_ONLY`，不能用于历史发布裁决。

Resource Gateway 调用 `POST /v1/evidence-signing/sign` 时只发送 canonical fingerprint、随机 requestId、算法和预期
keyId，不发送 evidence payload，更不会请求或接收 private key：

```json
{
  "schemaVersion": "resourceGateway.managedEvidenceSignRequest.v1",
  "requestId": "f386bb24-c852-4adc-8e90-e7654d58fd4a",
  "keyId": "kms://evidence-signing/18",
  "algorithm": "Ed25519",
  "materialFingerprint": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
}
```

响应使用 `resourceGateway.managedEvidenceSignResponse.v1`，必须回显这四个绑定字段并带 `signedAt/signature`。
Gateway 会用当前快照公钥本地反验签名，只有反验成功才把 seal 写入 run、cursor 或 reconciliation record。
sidecar 返回 `409` 表示 rotation race，Gateway 强制刷新并只重试一次；连续漂移会以 `ROTATION_UNSTABLE` 失败，
不会偷偷改用旧 key。

sidecar 和 ANEKE CI 可直接校验以下机器合同：
[managed key snapshot v2](schemas/tool-studio-resource-gateway/managed-evidence-signing-keys-v2.schema.json)、
[managed key snapshot v1 compatibility](schemas/tool-studio-resource-gateway/managed-evidence-signing-keys-v1.schema.json)、
[sign request](schemas/tool-studio-resource-gateway/managed-evidence-sign-request-v1.schema.json)、
[sign response](schemas/tool-studio-resource-gateway/managed-evidence-sign-response-v1.schema.json) 和
[capability descriptor](schemas/tool-studio-resource-gateway/evidence-signer-descriptor-v1.schema.json)。Gateway 对治理消费者发布的
原子合同是 [evidence verification key set v1](schemas/tool-studio-resource-gateway/evidence-verification-key-set-v1.schema.json)。

启动后检查：

```bash
curl -sS http://localhost:8080/api/integration/capabilities | jq '.payload |
  {features: {managedEvidenceSigning: .features.managedEvidenceSigning,
              nonExportableEvidenceSigningKey: .features.nonExportableEvidenceSigningKey,
              evidenceSigningFailClosed: .features.evidenceSigningFailClosed,
              evidenceVerificationKeySet: .features.evidenceVerificationKeySet,
              timeAwareEvidenceKeyRevocation: .features.timeAwareEvidenceKeyRevocation},
   signer: .evidenceSigner}'
```

发布门禁再读取原子 public snapshot：

```bash
curl -sS http://localhost:8080/api/integration/evidence-keys \
  | jq '.payload | {snapshotFingerprint, generatedAt, expiresAt, activeKeyId,
                     policyCompleteness, keys, events, attestation}'
```

这里的 `snapshotFingerprint` 只是待治理系统登记的候选值，不能从同一响应读取后立刻当作 trust pin。
ANEKE registry、受保护 CI 变量或签名 deployment manifest 必须通过独立通道提供精确 pin；test-kit 的
`verifySuiteEvidence(suiteRunId, trustedPin)` 会验证 pin、snapshot 签名、完整事件历史，并按 evidence
签名时间判断退役/禁用/前向撤销/追溯 compromise。完整威胁模型见
[Stage 3 evidence key lifecycle verification](resource-gateway-execution-data-control-plane-stage3-key-lifecycle-verification.md)。

生产结果应满足 `providerType=MANAGED_KMS_HSM`、`managedKeyCustody=true`、`privateKeyExportable=false`，并检查
`state/activeKeyId/snapshotExpiresAt/refreshSuccessCount/refreshFailureCount/lastFailureCode`。网络 timeout、429 或 5xx
只允许在 authority 自己声明的 `expiresAt` 前使用已缓存公钥做历史验签，状态为 `DEGRADED`；新签名仍必须实时调用
provider，绝不本地降级。畸形 JSON、重复字段、private material、错误 key 集合或无法通过本地反验的签名立即进入
`UNAVAILABLE`。快照过期后 exact evidence key lookup 返回可重试
`503 RG.INTEGRATION.EVIDENCE_KEY_PROVIDER_UNAVAILABLE`，key-set 返回
`503 RG.INTEGRATION.EVIDENCE_KEY_SET_PROVIDER_UNAVAILABLE`，而真正不存在的 exact key 返回 404。

通用 HTTP adapter 使用 JVM TLS context，部署方可通过 JVM trust store/client key store 建立 mTLS。直接调用云 KMS SDK、
SPIFFE workload identity 或厂商 HSM session 的团队可注册自己的 `ManagedEvidenceSigningProvider` Bean；上层轮换、反验、
capability 和 fail-closed 语义保持不变。生产验收还必须导出 provider 侧 key-use audit，并演练跨地域 authority 故障、
key disable/revoke、备份恢复和历史公钥保留期。

### 3.3 导出可系统化导入的 GraphDraft 一致依赖快照

ANEKE 不应在导入后再猜 `operatorRef` 属于哪个 library，也不能把组包期间刚好读到的 draft、binding 和 suite
拼成一个“字段齐全但代际混杂”的对象。`GET /api/integration/drafts/{draftId}/export` 现在先冻结当前 draft 实际引用的
operator、library、runtime binding、activation 和 contract suite，再在组包后重新读取 draft 与 dependency fingerprint；
任一相关资产变化都会丢弃整个候选包并返回 409。

![GraphDraft 一致依赖快照与导出门禁](assets/resource-gateway-graph-draft-consistent-dependency-snapshot.svg)

图源文件：[`assets/drawio/resource-gateway-graph-draft-consistent-dependency-snapshot.drawio`](assets/drawio/resource-gateway-graph-draft-consistent-dependency-snapshot.drawio)

固定 revision 导出的推荐调用如下。`revision=0` 表示读取最新 revision；治理同步和发布门禁应优先传明确 revision，
避免“最新”在重试期间自然前移：

```bash
curl -sS 'http://localhost:8080/api/integration/drafts/<draftId>/export?revision=7' \
  -H 'Authorization: Bearer <workload-jwt>' \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-Organization-Id: knowledge-governance' \
  -H 'X-Project-Id: tool-studio' \
  -H 'X-Environment-Id: prod' \
  -H 'X-Region: ap-southeast-1' \
  -H 'X-Actor-Type: WORKLOAD' \
  -H 'X-Actor-Id: aneke-sync' \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION' \
  -H 'X-Correlation-Id: sync-draft-7' | jq '.payload | {
    draftFingerprint,
    snapshot: .dependencyProfile.snapshot,
    operators: [.dependencyProfile.operatorDependencies[] | {
      nodeId, operatorRef, operatorLibrary, runtimeBindings, contractSuites, readiness
    }]
  }'
```

`dependencyProfile.schemaVersion` 当前为 `toolStudio.resourceGateway.graphDraftDependencyProfile.v2`。v2 保留 v1 的
`runtimeBindingRefs` 和 `contractSuiteRefs` 字符串数组，供旧 adapter 渐进迁移，同时增加以下结构化字段：

| 字段 | ANEKE 应如何消费 |
| --- | --- |
| `snapshot.fingerprint` | 整份相关依赖观察的稳定 SHA-256；用于幂等导入、缓存键和 drift 对比 |
| `snapshot.capturedAt` | draft revision 的逻辑更新时间，不是每次 HTTP 请求的墙钟时间；同一 revision 重复导出保持稳定 |
| `snapshot.operatorCount` | draft 引用的 distinct `operatorRef` 数，包括 catalog missing 和 scope mismatch 项 |
| `operatorLibrary` | 明确的 library id、revision、version、owner、status 和 fingerprint；`present=false` 不得自行补猜 |
| `runtimeBindings[]` | binding revision/fingerprint，以及匹配的 activation revision、environment、health 和 `ready` |
| `contractSuites[]` | suite id、不可变 revision、case count 和 fingerprint；workbook 应引用 revision，不只引用可变 suite id |
| `readiness` | Resource Gateway 的事实性可执行准备状态；ANEKE 可以叠加治理结论，但不能改写这份运行事实 |

readiness 的标准状态及作者/治理动作如下：

| 状态 | 根因 | 正确动作 |
| --- | --- | --- |
| `RUNTIME_EXECUTABLE` | 当前算子可由本地 runtime 执行，library 与 suite 均满足 | 继续执行 workbook 和 publish gate |
| `EXTERNAL_RUNTIME_BOUND` | 精确 fingerprint 的 bound binding 存在，且 activation revision/environment/health 匹配 | 校验外部 runtime owner、SLA 和环境发布策略 |
| `LIBRARY_MISSING` | draft/snapshot 声明 library，但 registry 当前不存在 | 阻断并恢复指定 library revision，不能按同名库猜测 |
| `LIBRARY_NOT_ACTIVE` | library 为 deprecated/disabled 等非 active 状态 | 阻断新发布，进入迁移或显式例外审批 |
| `CONTRACT_SUITE_MISSING` | imported operator 没有 contract suite | 阻断 correctness gate，先建立可版本化测试资产 |
| `RUNTIME_BINDING_MISSING` | design/external operator 没有可用 binding | 保留 DESIGN 交付，不能宣称 runtime ready |
| `ACTIVATION_MISSING_OR_STALE` | binding 已 bound，但 activation 缺失、revision/fingerprint/environment/health 不匹配 | 由 runtime owner 重新激活并提交证据 |
| `CATALOG_MISSING` | draft 保存过算子 snapshot，但当前 catalog 已无该 operatorRef | 只用于历史解释；恢复或迁移后再发布 |
| `SCOPE_MISMATCH` | 当前算子存在，但不允许该 draft 的 tenant/namespace/environment | 不返回当前算子、binding、activation、suite 或 owner；修正授权/迁移，不得绕过 scope |

scope mismatch 是最小披露边界，不是普通 readiness warning。导出包最多携带该 draft 自己保存的历史 operator snapshot
和 saved fingerprint；当前受限 schema、library owner、运行 binding、activation 与 suite 不会进入 payload。catalog missing
同样不会把仍残留在其它仓储中的运行资产拼回去。

组包期间发生相关变更时，响应为：

```json
{
  "status": 409,
  "code": "RG.INTEGRATION.DRAFT_SNAPSHOT_CHANGED",
  "retryable": true,
  "details": {
    "draftId": "draft-...",
    "observedRevision": 7,
    "requestedRevision": 7,
    "beforeDependencyFingerprint": "sha256:...",
    "afterDependencyFingerprint": "sha256:...",
    "draftStable": true
  }
}
```

consumer 必须丢弃本次候选 payload，在自己的 retry budget 内重新导出；不得忽略 409，也不得只保留其中看起来没变的
节点。无关 operator 的 suite/binding 变化不会改变 fingerprint，仓储返回顺序也不会制造假 drift。

机器合同为 [dependency profile v2](schemas/tool-studio-resource-gateway/graph-draft-dependency-profile-v2.schema.json)
和 [dependency snapshot v1](schemas/tool-studio-resource-gateway/graph-draft-dependency-snapshot-v1.schema.json)。启动后先从
`/api/integration/capabilities` 检查 `features.graphDraftConsistentDependencySnapshot=true`、
`features.graphDraftStructuredDependencyRefs=true`，并协商 `supportedObjects.graphDraftDependencyProfile` 中的 v1/v2。

### 3.4 从 ANEKE 治理问题直达画布

ANEKE Tool Studio 可以把治理问题链接回 `/author/`，画布会读取服务端保存的草稿，自动布局并定位到具体节点或算子。作者不需要先打开 Author 首页再手工搜索草稿。

![ANEKE 治理反馈与 Deep Link 标注](assets/bloge-governance-deep-link-authoring-annotated.svg)

1. **Deep Link 定位确认**：页面显示实际打开的 `draftId@revision` 和聚焦节点；目标在当前修订中不存在时会明确警告，不会悄悄选中错误节点。
2. **ANEKE 门禁反馈**：门禁带显示 `BLOCKED/PASSED` 结论和 `CURRENT/STALE/EXPIRED/MISSING` 新鲜度。`STALE` 或 `EXPIRED` 只作为历史反馈展示，不能冒充当前修订的发布结论。
3. **问题目标节点自动聚焦**：点击门禁问题会读取其 `targetPath` 或 `deepLink`，选中对应节点；截图中 `approvalPolicy` 因 correctness workbook 覆盖不足被直接定位。
4. **全图缩略图与视口**：Deep Link 载入后同样执行 Auto Layout 和 Fit All；复杂图仍可从 Map 判断整体形状和当前视口。

支持的查询参数：

| 参数 | 作用 | 解析规则 |
| --- | --- | --- |
| `draftId` | 打开存量草稿 | 读取 `GET /api/visual/drafts/{draftId}` |
| `nodeId` | 聚焦具体节点 | 优先级最高；必须存在于该 draft revision |
| `operatorRef` | 聚焦某类算子 | 选择当前图中第一个匹配 `operatorRef` 的节点 |
| `runId` | 从一次运行回到作者上下文 | 先读取 `GET /api/visual/runs/{runId}`，再由运行记录恢复 `draftId`、revision 和 output node |
| `gateIssueId` | 聚焦一条 ANEKE 门禁问题 | 从 gate result 中找到 issue，再由 `targetPath/deepLink` 解析节点 |

典型链接：

```text
/author/?draftId=<draftId>&nodeId=approvalPolicy
/author/?draftId=<draftId>&operatorRef=knowledge%3Aretrieve
/author/?runId=<runId>
/author/?draftId=<draftId>&gateIssueId=missing-workbook
```

使用 `runId` 打开时，画布还会显示运行结果、source kind、draft revision、耗时和第一条错误。若运行来自 transient draft 且没有可恢复的 `draftId`，页面会保留运行上下文并明确提示无法恢复存量草稿。

治理反馈由 ANEKE 通过 `POST /api/integration/gate-results` 写入，并绑定不可变的 `draftId + revision + draftFingerprint`。同一个 `gateResultId` 重复提交相同内容是幂等成功，内容不同则返回冲突；画布只通过只读接口 `GET /api/visual/governance-gates/drafts/{draftId}` 消费结果。

### 3.5 用 recorded replay 重算正确性断言

`GET /api/integration/runs/{runId}/replay` 只读取已脱敏的 recorded payload；`POST` 同一路径才是 replay command。command 不会重新运行 DSL，也不会调用任何 operator 或外部资源，而是基于父运行保存的 context、node input/output、terminal output 和 evidence 状态重算断言，并生成新的 replay run/evidence。

从 v9 开始，payload 不再嵌在不可变 `run_json` 中。系统把长期 evidence 与短期 replay payload 拆成两个生命周期：

![Resource Gateway 受治理 payload 生命周期](assets/resource-gateway-governed-payload-lifecycle.svg)

图源：[resource-gateway-governed-payload-lifecycle.drawio](assets/drawio/resource-gateway-governed-payload-lifecycle.drawio)。

1. run 提交时先应用 `VisualPayloadGovernancePolicy`。`PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED` 决定所需
   clearance，`requiredGroups` 是必须全部满足的附加边界。
2. 允许保留时，脱敏后的 context/output/node I/O 进入独立 payload vault；run evidence v9 只保存
   `payloadRef + payloadFingerprint + policyId/version + classification + expiresAt` 和无值 availability marker。
3. run、payload、首个 `CAPTURED/NOT_RETAINED` 签名事件与 outbox 在同一数据库事务中提交。任一步失败全部回滚。
4. replay 读取先校验 tenant/environment/purpose，再校验 clearance 和 required groups，最后校验 lifecycle state。
   clearance 不足返回 `403`；`PURGED/NOT_RETAINED/expired` 返回 `410 RG.INTEGRATION.PAYLOAD_NOT_AVAILABLE`。
5. 到期由后台 bounded sweep 清理，读取路径也会同步执行 expiry，因而不会出现“清理任务积压时旧接口仍能读”的窗口。
6. legal hold 把状态切到 `LEGAL_HOLD` 并冻结 purge；解除时若 retention deadline 已过，系统立即删除 payload。
7. purge 只删除 vault blob，并追加带 previous-event fingerprint 的签名 `PURGED` 事件。历史 run evidence 的签名仍可验证。

默认 demo 配置为 `CONFIDENTIAL + 7 days`；生产代码的缺省值是 `RESTRICTED + 0 days`，即未显式配置时不保留：

```yaml
gateway:
  integration:
    payload-governance:
      policy-id: customer-correctness-payload
      policy-version: 2026-07
      default-classification: CONFIDENTIAL
      required-groups: correctness-reviewers
      retention-days:
        public: 30
        internal: 14
        confidential: 7
        restricted: 0
      sweep-interval-ms: 60000
      sweep-batch-size: 200
```

企业可用自定义 `VisualPayloadGovernancePolicy` bean 对接自己的分类引擎；Resource Gateway 通用画布不接管
ANEKE 的治理资产，只执行已注入的版本化 policy decision。`redaction`、`classification`、`retention` 必须分别理解：

| 控制 | 回答的问题 | 当前行为 |
|---|---|---|
| Redaction | 值里哪些字段必须替换/截断？ | 始终在落 vault 前执行，raw payload 不进入 integration API |
| Classification | 哪类 identity 可以读取？ | trusted clearance 必须不低于 classification，且包含全部 required groups |
| Retention | 脱敏值是否存在、保留多久？ | 按 classification 选择性保留；RESTRICTED 默认不留；到期 fail closed |
| Legal hold | 到期删除是否暂时冻结？ | 专用 `LEGAL_HOLD` purpose；hold/release/purge 都进入签名 hash chain |

查看和管理生命周期：

```bash
# 查看状态和完整签名事件链
curl -H "Authorization: Bearer $TOKEN" \
  -H "X-Purpose: GOVERNANCE_EVIDENCE_INGESTION" \
  http://localhost:8080/api/integration/runs/$RUN_ID/payload-retention

# 创建 legal hold
curl -X POST -H "Authorization: Bearer $TOKEN" -H "X-Purpose: LEGAL_HOLD" \
  -H "Content-Type: application/json" \
  -d '{"requestId":"hold-request-1","holdId":"case-42","reason":"litigation"}' \
  http://localhost:8080/api/integration/runs/$RUN_ID/payload-retention/holds

# 解除 hold；若已经过期会在同一调用中转为 PURGED
curl -X POST -H "Authorization: Bearer $TOKEN" -H "X-Purpose: LEGAL_HOLD" \
  -H "Content-Type: application/json" \
  -d '{"requestId":"release-request-1","reason":"case closed"}' \
  http://localhost:8080/api/integration/runs/$RUN_ID/payload-retention/holds/case-42/release
```

`requestId` 是 lifecycle command 的持久幂等键，不是日志 correlation id。同一 run 上用相同 requestId 和完全相同的
hold/release/purge 内容重试，不增加 revision，也不重复发 change event；同键改写 `holdId`、actor 或 reason 返回 `409`。
该绑定写入签名 lifecycle event，服务重启或切换实例后仍然有效。

机器合同：[payload replay v2](schemas/tool-studio-resource-gateway/payload-replay-bundle-v2.schema.json)、
[payload retention view v1](schemas/tool-studio-resource-gateway/payload-retention-view-v1.schema.json)、
[payload lifecycle command v1](schemas/tool-studio-resource-gateway/payload-lifecycle-command-v1.schema.json) 和
[run evidence v7](schemas/tool-studio-resource-gateway/run-evidence-bundle-v7.schema.json)。

请求必须使用 `X-Purpose: PAYLOAD_REPLAY`，并显式声明副作用策略 `DENY`：

```json
{
  "schemaVersion": "toolStudio.resourceGateway.replayExecutionRequest.v1",
  "requestId": "workbook-case-2026-07-12-001",
  "mode": "RECORDED_ASSERTIONS",
  "caseType": "REGRESSION",
  "externalSideEffectPolicy": "DENY",
  "assertions": [
    {
      "assertionId": "terminal-decision",
      "scope": "OUTPUT",
      "mode": "PATH_EQUALS",
      "path": "/decision",
      "expectedValue": "APPROVE"
    },
    {
      "assertionId": "eligibility-node",
      "scope": "NODE",
      "nodeId": "eligibility",
      "mode": "PATH_EXISTS",
      "path": "/eligible"
    },
    {
      "assertionId": "evidence-ready",
      "scope": "RUN",
      "mode": "GOVERNANCE_EXPECTATION",
      "expectedValue": "EVIDENCE_READY"
    }
  ]
}
```

支持的 `caseType` 是 `GOLDEN`、`NEGATIVE`、`BOUNDARY`、`REGRESSION`。断言支持：

| mode | scope | 含义 |
| --- | --- | --- |
| `EQUALS` | OUTPUT/NODE | 完整 JSON 结构相等 |
| `PATH_EQUALS` | OUTPUT/NODE | JSON Pointer 路径值相等 |
| `PATH_EXISTS` / `PATH_ABSENT` | OUTPUT/NODE | 路径存在性 |
| `MATCHES_SCHEMA` | OUTPUT/NODE | 满足 JSON Schema 或 `SchemaEnvelope` |
| `ERROR_CONTAINS` | RUN | 父运行错误包含指定 code/text |
| `GOVERNANCE_EXPECTATION` | RUN | `EVIDENCE_READY`、`SIGNATURE_VERIFIED`、`NO_MOCKS` 或 `NO_ERRORS` |

成功受理后系统生成确定性的 replay runId，并返回 `parentRunId`、request fingerprint、断言结果、`externalInvocationCount=0`、evidence 状态和 evidence endpoint。相同 `requestId + parentRunId + request content` 重试返回同一 replay run；数据库唯一键裁决两个实例的并发创建，输家回读并核对 parent/request fingerprint 后返回同一结果。同一 tenant/environment 内同一个 `requestId` 指向不同内容返回 `409`；不同 tenant 不共享幂等命名空间。期望值不会原样写入 assertion evidence，只保存 fingerprint，降低测试数据泄露风险。

当前 replay command 只支持 `RECORDED_ASSERTIONS + DENY`。`shadow/live` 重放仍未开放，因为它们需要独立的审批、隔离环境、幂等能力证明和 unknown-commit 处理，不能复用这个安全接口悄悄开启。

### 3.5.1 把 contract suite 和 run evidence 交给 ANEKE workbook

Resource Gateway 不创建 ANEKE registry，也不决定 publish gate 是否通过。它新增的是一份可确定性重建的
`CorrectnessWorkbookBundle.v1`：把当前 GraphDraft dependency snapshot、精确 operator suite revision、脱敏的
case/assertion 表格和同一 draft snapshot 的签名 run evidence refs 组合起来。ANEKE 导入这份 seed 后，仍由自己的
workbook、组织 policy 和审批体系产生治理结论。

![Resource Gateway 与 ANEKE workbook/gate 证据闭环](assets/resource-gateway-workbook-gate-evidence-loop.svg)

图源：[resource-gateway-workbook-gate-evidence-loop.drawio](assets/drawio/resource-gateway-workbook-gate-evidence-loop.drawio)。

**第一步：导出 workbook seed。** 使用 exact revision，避免用户编辑 draft 后把新旧测试混在一起：

```bash
curl -H "Authorization: Bearer $TOKEN" \
  -H "X-Purpose: WORKBOOK_SYNC" \
  "http://localhost:8080/api/integration/drafts/$DRAFT_ID/correctness-workbook?revision=$REVISION"
```

ANEKE 至少保存以下关联：

| 字段 | 用途 |
| --- | --- |
| `target.draftId/revision/draftFingerprint` | 锁定被治理的 GraphDraft 快照 |
| `dependencySnapshotFingerprint` | 锁定 operator/library/binding/activation/suite readiness 代际 |
| `suites[].suiteId/revision/suiteFingerprint` | 无歧义回读测试资产；不能只保存 suiteId |
| `cases[].caseId/caseKind/mappingStatus` | 建立稳定 workbook 行；`DEFAULTED` 表示旧 suite 未显式声明 case kind |
| `evidence[].runId/evidenceFingerprint/signatureStatus` | 把 workbook 结论追溯到签名运行事实 |
| `manifest.bundleFingerprint` | 证明 ANEKE workbook 是从哪一份 seed 导入 |

suite tag 可用 `case-kind:golden`、`case-kind:negative`、`case-kind:boundary` 或
`case-kind:regression` 显式设置当前 suite 的 case kind；没有标签时系统保守映射为 `REGRESSION + DEFAULTED`，不会冒充
无损转换。测试输入、config、mocked output 和 expected value 在离开 Resource Gateway 前经过与 run payload 相同的
有界 sanitizer，`redaction` 会列出命中路径。

**如果 suite 来自 testing control plane，使用 semantic seed。** 不要把 `bloge.testSuite.v2`
塞回 draft-oriented v1 形状，也不要按 graphName 猜它属于哪份 draft。使用精确 suite id/revision：

```bash
curl -H "Authorization: Bearer $TOKEN" \
  -H "X-Purpose: WORKBOOK_SYNC" \
  "http://localhost:8080/api/integration/test-suites/$SUITE_ID/revisions/$SUITE_REVISION/semantic-correctness-workbook"
```

`SemanticCorrectnessWorkbookBundle.v1` 返回 payload-free case/fixture identity、structural coverage、typed
branch/decision/retry/fallback/timeout/compensation requirements、verified terminal v2 verdict、attestation ref 和
portable evidence endpoint。它不会返回 case input、fixture value、child payload、suite metadata value 或 free-text
diagnostic。structural `bloge.testSuite.v1` 会被明确拒绝，不会伪装成“没有语义要求”。

先看 `manifest.projectionStatus`：`NO_TERMINAL_EVIDENCE` 表示尚无终态运行，
`VERIFICATION_UNAVAILABLE` 表示验签权威暂不可用，`NO_ELIGIBLE_EVIDENCE` 表示证据已验证但未满足语义/晋级策略；
只有 `READY` 可进入 ANEKE 下一步。即便 READY，ANEKE 仍要逐条读取 `evidence[].endpoint`，使用通过独立渠道分发的
key-set fingerprint pin 验证 portable bundle；不能把 producer 状态当作信任根。投影最多包含最新 100 条，
`candidateEvidenceCount` 与 `evidenceTruncated` 必须随 workbook 一起保存。

ANEKE 必须把实际消费的 semantic seed 固化到 `GovernanceGateResult.v3`：保存 exact suite target、bundle
fingerprint、完整有序 evidence closure、candidate/unavailable/truncation manifest 事实。Resource Gateway 收到后会按
exact suite run 重建原 bundle，并把 graph target 与 exact GraphDraft 编译结果绑定；后续新增 run 不会误伤旧决定，
但 evidence 被删除、验签失败、target 漂移或 bundle 无法重建都会 fail closed。

**第二步：ANEKE 运行自己的 workbook 和 publish-gate policy。** 它生成不可变
`GovernanceGateResult.v3`，`decisionBasis` 必须带回 workbook ref、source bundle fingerprint、dependency snapshot、
suite refs、evidence refs、semantic workbook refs、policy id/version、required checks 和每项结果。例如：

```json
{
  "schemaVersion": "toolStudio.resourceGateway.gateResult.v3",
  "gateResultId": "gate-knowledge-tool-2026-07-13-01",
  "target": {
    "kind": "GRAPH_DRAFT",
    "draftId": "draft-knowledge-tool",
    "revision": 7,
    "draftFingerprint": "sha256:...",
    "tenantId": "tenant-a",
    "namespace": "knowledge",
    "environment": "prod"
  },
  "status": "PASSED",
  "issues": [],
  "producedAt": "2026-07-13T00:00:00Z",
  "expiresAt": "2026-07-20T00:00:00Z",
  "decisionBasis": {
    "workbook": {
      "workbookId": "wb-knowledge-tool",
      "revision": 12,
      "workbookFingerprint": "sha256:...",
      "sourceBundleFingerprint": "sha256:..."
    },
    "dependencySnapshotFingerprint": "sha256:...",
    "contractSuites": [{"suiteId":"suite-risk","revision":3,"fingerprint":"sha256:..."}],
    "evidence": [{"runId":"run-...","evidenceFingerprint":"sha256:..."}],
    "semanticWorkbooks": [{
      "suite": {"suiteId":"suite-risk-semantic","revision":4,"fingerprint":"sha256:..."},
      "target": {"kind":"GRAPH","id":"knowledge-tool","fingerprint":"sha256:..."},
      "bundleFingerprint": "sha256:...",
      "projectionStatus": "READY",
      "candidateEvidenceCount": 1,
      "unavailableEvidenceCount": 0,
      "evidenceTruncated": false,
      "evidence": [{"suiteRunId":"suite-run-...","evidenceFingerprint":"sha256:..."}]
    }],
    "policy": {
      "policyId": "enterprise-publish-gate",
      "version": "2026-07",
      "requiredChecks": ["WORKBOOK","CONTRACT_COVERAGE","EVIDENCE","SEMANTIC_CORRECTNESS","RUNTIME_READINESS","OWNER_APPROVAL","BREAKING_MIGRATION"]
    },
    "checks": [
      {"kind":"WORKBOOK","status":"PASSED","reason":"verified","refs":["wb-knowledge-tool@12"]},
      {"kind":"CONTRACT_COVERAGE","status":"PASSED","reason":"coverage met","refs":["suite-risk@3"]},
      {"kind":"EVIDENCE","status":"PASSED","reason":"signed run verified","refs":["run-..."]},
      {"kind":"SEMANTIC_CORRECTNESS","status":"PASSED","reason":"exact semantic bundle verified","refs":["sha256:..."]},
      {"kind":"RUNTIME_READINESS","status":"PASSED","reason":"binding ready","refs":[]},
      {"kind":"OWNER_APPROVAL","status":"PASSED","reason":"approved","refs":[]},
      {"kind":"BREAKING_MIGRATION","status":"PASSED","reason":"no breaking change","refs":[]}
    ]
  },
  "resultFingerprint": "sha256:..."
}
```

提交到 `POST /api/integration/gate-results` 时使用 `X-Purpose: GOVERNANCE_GATE_FEEDBACK`。Resource Gateway 不重跑
ANEKE policy，但会 fail closed 地验证 basis：target scope、draft/snapshot/suite fingerprint、source bundle fingerprint、
run evidence 签名、semantic bundle 精确重建、GraphDraft 编译 target binding 以及所有 `policy.requiredChecks`。v3 的
`PASSED` 至少需要一个 gate-ready `GRAPH` semantic workbook，并要求 `SEMANTIC_CORRECTNESS` check 精确引用全部 bundle
fingerprint。通过结论缺依据返回
`409 RG.INTEGRATION.GATE_BASIS_INCOMPLETE`；依据已漂移返回 `409 RG.INTEGRATION.GATE_BASIS_STALE`；跨租户 run ref
按 scope-safe `404` 处理；验签权威、证据存储或编译 target verifier 暂不可用返回稳定
`503 RG.INTEGRATION.SEMANTIC_GATE_VERIFICATION_UNAVAILABLE`。合法结果和
`GOVERNANCE_GATE_RESULT_RECEIVED` event 同事务提交；source/target 漂移后 Author 侧 freshness 变为 `STALE`，验证权威
不可用时变为 `UNVERIFIABLE`，不会被误显示成当前有效。

v1 gate result 继续可用于 `BLOCKED/WARNING` 兼容反馈；v2 保留原 wire shape 与 fingerprint 兼容。v1 `PASSED` 会被
拒绝，因为它无法证明 decision basis；新的 semantic `PASSED` 使用 v3。
机器合同：[correctness workbook bundle v1](schemas/tool-studio-resource-gateway/correctness-workbook-bundle-v1.schema.json)、
[semantic correctness workbook bundle v1](schemas/tool-studio-resource-gateway/semantic-correctness-workbook-bundle-v1.schema.json)、
[governance gate result v2](schemas/tool-studio-resource-gateway/governance-gate-result-v2.schema.json) 和
[governance gate result v3](schemas/tool-studio-resource-gateway/governance-gate-result-v3.schema.json)。

### 3.6 读取 timeout、fallback 与 partial failure 证据

画布点击 **Run** 后，系统不再只保存一个最终 `SUCCESS/FAILED`。BLOGE 的 node lifecycle 与
`ResilienceListener` 会在同一个 run capture 中记录 retry、timeout 和 fallback 事件；Resource Gateway
再结合不可变拓扑解释 skip/cancel 的因果关系，最后写入 `VisualGraphRunRecord.v9` 并导出
`runEvidenceBundle.v7`。v9/v7 在既有 control/recovery fact 之外增加 detached payload descriptor 与当前 lifecycle
状态，使正常完成记录、系统恢复记录和 payload 删除记录
可以被机器明确区分。

![Resource Gateway 运行失败事实链](assets/resource-gateway-run-failure-semantics.svg)

图源：[resource-gateway-run-failure-semantics.drawio](assets/drawio/resource-gateway-run-failure-semantics.drawio)。

作者和 ANEKE 都按以下步骤查看：

1. 在画布运行图，记下响应中的 `runId`；也可通过 `/author/?runId=<runId>` 回到对应运行上下文。
2. 用具备 `GOVERNANCE_EVIDENCE_INGESTION` purpose 的 workload token 调用
   `GET /api/integration/runs/{runId}/evidence`。
3. 先检查 `manifest.evidenceStatus` 和 `signatureStatus`。只有 `READY + VERIFIED` 才表示当前 bundle
   结构完整且签名可信；`QUARANTINED` 必须先处理 `manifest.gaps`，不能进入发布门禁的通过证据集。
4. 再检查 `execution.criticalOutputReached`。失败 run 中，只有关键输出已经产生且同时存在其他失败时，
   图级状态才是 `PARTIAL`；关键输出自身超时则是 `TIMEOUT`，不会因为某个上游成功而误判为 `PARTIAL`。
5. 对每个节点读取 `reasonCode`、`observationSource`、`retry`、`timeout`、`fallback`、
   `causedByNodeIds` 和 `sideEffectOutcome`；对每条边读取 `propagated`，不要只看颜色或最终状态。

典型节点语义如下：

| 场景 | `status` | `reasonCode` | 事实来源 |
|---|---|---|---|
| 正常完成 | `SUCCESS` | `NONE` | `ENGINE_STATUS` |
| 重试耗尽后 fallback 返回 | `FALLBACK` | `FALLBACK_SUCCEEDED` | `ENGINE_RESILIENCE_EVENT`；retry 仍标记 `exhausted=true` |
| 节点实际超时 | `TIMEOUT` | `NODE_TIMEOUT` | BLOGE timeout event，不解析错误文本 |
| 条件分支未命中 | `SKIPPED` | `BRANCH_NOT_TAKEN` | `TOPOLOGY_DERIVATION` |
| 上游失败导致未执行 | `CANCELLED` | `UPSTREAM_FAILED` | `TOPOLOGY_DERIVATION`，`causedByNodeIds` 指向上游 |
| 同名图并发导致旧 listener 无法唯一关联 | 保留引擎最终状态 | `RESILIENCE_EVENT_CORRELATION_AMBIGUOUS` | `ENGINE_STATUS_WITH_EVENT_GAP`，evidence 隔离 |

边的 `status` 表示**传播事实**，不是目标节点的执行结果。例如 A 成功把值传给 B，随后 B 超时：
A→B 的边仍为 `SUCCESS + propagated=true + VALUE_PROPAGATED`，B 才是 `TIMEOUT`。若 A 超时导致 B
未执行，边为 `CANCELLED + propagated=false + UPSTREAM_FAILURE_PROPAGATED`，且没有虚构的 edge payload ref。

`sideEffectOutcome=UNKNOWN_COMMIT` 不是普通失败，而是诚实的不确定性。新版 BLOGE operator 可以在跨越
外部写边界前调用 `ctx.beginSideEffect(...)`：journal 先保存 `PREPARED`，再由 adapter 明确写入
`COMMITTED + receipt`、`NOT_COMMITTED` 或 `UNKNOWN_COMMIT`。run record v8 和 evidence v6 会导出每个
attempt 的 fingerprint、脱敏 idempotency fingerprint、reconciler/lookup ref、receipt 和完整 transitions；不会
保存原始 idempotency key。

若节点返回时仍有 `PREPARED/UNKNOWN_COMMIT`，Resource Gateway 的 DAG guard 会抛出 non-retryable failure：
配置的 retry 和 fallback 都不会启动，依赖该结果的下游节点也不会执行。这样可以避免“扣款结果未知，但履约节点
继续发货”或“UNKNOWN 被普通 retry 再扣一次”。base evidence 会保持 `QUARANTINED`，直到独立的签名
reconciliation record 闭合该缺口；系统不会修改或重新签署原 evidence。

能力探针会明确返回：

| capability | 当前值 | 含义 |
|---|---:|---|
| `structuredExecutionFacts` | `true` | node/edge/graph 标准事实已实现 |
| `graphDeadline` | `true` | 画布 run 支持绝对 deadline，并为业务执行预留 evidence/terminal-state 收尾时间 |
| `operatorContextDeadlineBudget` | `true` | 每个 BLOGE operator 可读取同一绝对 deadline 和只减不增的 remaining budget |
| `deadlineAdmissionControl` | `true` | 剩余业务预算耗尽时，scheduler 在算子执行前 fail closed，并记录 `DEADLINE_EXHAUSTED` |
| `retryBudgetEnforcement` | `true` | timeout 和 retry backoff 都会被剩余预算截短；预算不足时不会启动下一次 retry |
| `httpRemainingBudget` | `true` | Resource Gateway 与 BLOGE common HTTP operator 使用较短的有效 timeout，并向下游传递 deadline/budget header |
| `remoteWorkerDeadlineBudget` | `true` | remote worker envelope 携带 deadline、捕获时剩余预算、reserve 与 capturedAt，worker 可按时钟偏差 fail closed |
| `userRunCancellation` | `true` | 支持带 fencing token 的协作式 cancel command |
| `runTerminationConfirmation` | `true` | owner 已退出且 operator in-flight 归零后才确认终止 |
| `hardRunTermination` | `false` | Java 进程不能安全强杀忽略中断的任意业务代码 |
| `durableRunControl` | `true` | control state、fence digest、owner epoch、revision 和 lease 持久化；跨实例 lookup/cancel 可用 |
| `crossInstanceRunCancellation` | `true` | 非 owner 实例可用共享数据库提交 fenced cancel，owner 在续租循环中观察并中断本地线程 |
| `runOwnerLease` / `runOwnerEpochFencing` | `true` | owner 只能在有效 lease 和匹配 epoch 下推进状态，旧 owner 不能覆盖恢复结论 |
| `expiredOwnerQuarantine` | `true` | owner 租约过期后持久化为 `OWNER_LEASE_EXPIRED + TERMINATION_UNCONFIRMED` |
| `restartRunResumption` | `false` | 进程崩溃后不会盲目重跑未知副作用；当前恢复策略是 abandonment + quarantine，而不是自动续跑 |
| `runControlEvidence` | `true` | control fact 已进入 run record、签名和 evidence v7 |
| `runEvidenceRecoveryReservation` | `true` | managed run 执行前先持久化脱敏 lineage reservation，绑定确定性 runId、draft/scope/input fingerprint |
| `abandonedRunEvidenceRecovery` | `true` | owner abandonment、terminal evidence gap 和过期 missing-control reservation 会自动形成签名但 fail-closed 的 run evidence |
| `recoveryTransactionalOutbox` | `true` | 恢复 run record、reservation 终态与 `RUN_ABANDONED/RUN_EVIDENCE_RECOVERED` 事件同事务提交 |
| `sideEffectJournal` / `sideEffectCommitReceipts` | `true` | operator 可在执行期记录 side-effect attempt、显式 receipt 和 UNKNOWN_COMMIT |
| `sideEffectReconciliation` / `sideEffectReconciliationEvidence` | `true` | 具备持久 claim/fencing、SPI、签名 refinement、summary 和 outbox 协议 |
| `sideEffectReconcilerAdapters` | 默认 `false` | 当前示例不伪造任意 provider 的权威状态查询；注册业务 adapter 后动态变为 `true` |
| `sideEffectCommitConfirmation` | `false` | 不是所有 operator/binding 都已声明 receipt 与 reconciler，不能做全局承诺 |
| `detachedPayloadVault` / `selectivePayloadRetention` | `true` | payload 与不可变 evidence 分离，并按分类决定是否保留 |
| `payloadClassificationPolicy` | `true` | policy id/version、clearance、groups 和 retention decision 已进入证据 |
| `payloadLegalHold` / `signedPayloadLifecycle` | `true` | hold/release/purge 使用行锁与 revision fencing，并形成签名 hash chain |
| `managedEvidenceSigning` | 按部署动态 | `true` 表示私钥由 managed provider 托管，不能仅凭 `evidenceSignature=true` 推断 |
| `nonExportableEvidenceSigningKey` | 按部署动态 | managed custody 且 provider 声明私钥不可导出；本地 H2 demo 必须为 `false` |
| `evidenceSigningKeyRotation` / `evidenceSigningKeyRevocation` | 按部署动态 | key generation snapshot 支持 `ACTIVE/VERIFY_ONLY/DISABLED/REVOKED` 生命周期 |
| `evidenceSigningFailClosed` | 按部署动态 | authority 快照过期或协议污染后禁止继续签名/验签，且 key lookup 区分 503 与 404 |

旧 `runEvidenceBundle.v1/v2/v3/v4/v5/v6` 仍在 capability 的兼容列表中；新 consumer 应优先协商 v7。旧 run 若没有
每个节点的结构化 execution fact，会得到
`Structured execution semantics were not captured for every node.` gap 并被隔离，而不是由服务端静默猜测。
ANEKE 可直接使用 [run-evidence-bundle-v7.schema.json](schemas/tool-studio-resource-gateway/run-evidence-bundle-v7.schema.json)
做 producer/consumer contract 校验，不需要解析 Resource Gateway 内部 Java DTO。

#### 从 UNKNOWN_COMMIT 对账到治理 READY

![Resource Gateway 外部副作用确认与对账闭环](assets/resource-gateway-side-effect-reconciliation-lifecycle.svg)

图源：[resource-gateway-side-effect-reconciliation-lifecycle.drawio](assets/drawio/resource-gateway-side-effect-reconciliation-lifecycle.drawio)。

这条链路刻意把三种事实分开：原 run evidence 永远不可变；provider adapter 只查询、不重放写操作；对账结论以
独立签名 record 追加。ANEKE 或运维工作流按以下顺序操作：

1. 调用 `GET /api/integration/runs/{runId}/evidence`，找到
   `nodes[].sideEffectAttempts[]` 中的 `UNKNOWN_COMMIT`。保存顶层 `manifest.manifestHash` 和 attempt 的
   `attemptFingerprint`，先验证 base evidence 签名。
2. 确认 attempt 同时带有 `reconcilerRef` 与 `reconciliationLookupRef`。lookup ref 只能是无 query、fragment、
   user-info 的 evidence-safe opaque URI，例如 `vault://commands/charge-42`；缺少它时系统返回
   `RG.INTEGRATION.SIDE_EFFECT_NOT_RECONCILABLE`，不会拿哈希猜远端请求。
3. 使用专用 `X-Purpose: SIDE_EFFECT_RECONCILIATION` 提交 reconcile command。服务端比较两个 expected
   fingerprint，避免操作过期或错 run 的证据。
4. 服务端在共享数据库中创建 30 秒 claim，使用 owner token 和 lease fencing 保证多实例只有一个 provider
   查询赢家；adapter 调用上限 20 秒。超时或失败返回可重试的 `503`，不会重放原写操作。
5. provider 返回 `COMMITTED` 时必须携带 receipt；返回 `NOT_COMMITTED` 可以没有 receipt。结果、actor、base
   evidence、attempt fingerprint 和 lookup ref 进入 Ed25519 签名的 reconciliation record。
6. record、resolved head 和 `SIDE_EFFECT_RECONCILED` outbox event 在同一事务提交。任一步失败全部回滚。
7. 调用 `GET /api/integration/runs/{runId}/side-effects/reconciliations`。`status=RESOLVED` 表示所有可见 unknown
   attempt 已有签名 refinement；只有 `remainingEvidenceGaps=[]` 且所有签名验证通过时，补充治理视图才会成为
   `governanceStatus=READY`。原 base evidence 仍保持原来的 `QUARANTINED`，这不是矛盾。

请求示例：

```bash
curl -sS -X POST \
  'http://localhost:8080/api/integration/runs/<runId>/side-effects/<attemptId>/reconcile' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: SIDE_EFFECT_RECONCILIATION' \
  -H 'X-Correlation-Id: reconcile-0001' \
  -H 'Content-Type: application/json' \
  -d '{
    "schemaVersion": "toolStudio.resourceGateway.sideEffectReconciliationRequest.v1",
    "requestId": "reconcile-charge-0001",
    "expectedEvidenceFingerprint": "sha256:<64-hex>",
    "expectedAttemptFingerprint": "sha256:<64-hex>"
  }'
```

同一个 `requestId + request content` 重试会返回同一 record；同一 requestId 指向不同 target、同一 attempt 被不同
request 再次解决、或 expected fingerprint 过期都会返回 `409`。claim 正在被其他实例持有时返回
`RG.INTEGRATION.SIDE_EFFECT_RECONCILIATION_IN_PROGRESS`，响应 detail 给出 `retryAfter`。

Resource Gateway 不内置一个“永远返回已提交”的假 adapter。业务团队必须注册 provider-owned Bean：

```java
@Component
final class PaymentsStatusReconciler implements SideEffectReconciler {
    @Override
    public String reconcilerRef() {
        return "payments.status";
    }

    @Override
    public Resolution reconcile(Query query) throws Exception {
        // 只用 query.attempt().reconciliationLookupRef() 查询 provider command/status API。
        // 不得重新发送 charge/write 请求；COMMITTED 必须返回 evidence-safe receipt。
        return lookupAuthoritativeResult(query);
    }
}
```

注册至少一个 Bean 后，capability 的 `sideEffectReconcilerAdapters` 变为 `true`。三份独立 schema 可用于 consumer
contract 和离线校验：

- [side-effect-reconciliation-request-v1.schema.json](schemas/tool-studio-resource-gateway/side-effect-reconciliation-request-v1.schema.json)
- [side-effect-reconciliation-record-v1.schema.json](schemas/tool-studio-resource-gateway/side-effect-reconciliation-record-v1.schema.json)
- [side-effect-reconciliation-summary-v1.schema.json](schemas/tool-studio-resource-gateway/side-effect-reconciliation-summary-v1.schema.json)

#### 在画布设置 deadline 和取消运行

Custom Composer 的 **Run** 区现在直接提供 `Deadline` 数值框。点击 **Run** 时，页面自动生成
`requestId + fencingToken`，把相对毫秒数转换为绝对 `deadlineAt`；运行期间 Run 按钮会锁定，右侧出现停止按钮。
用户点击停止即可发出 fenced cancellation，不需要手写控制 JSON。

![Custom Composer 的 Deadline 与 Run 控件](assets/resource-gateway-run-control-ui.jpg)

上图聚焦右侧运行区：`Deadline` 接受 `100-300000 ms`，蓝色按钮启动受控运行；运行进入 active 状态后，
同一行会出现方形停止按钮。窄屏布局会自动把命令区收束为单列，输入框、按钮和规则表不会产生横向滚动：

![移动端 Deadline 与 Run 控件](assets/resource-gateway-run-control-ui-mobile.jpg)

![Resource Gateway run control 生命周期](assets/resource-gateway-run-control-lifecycle.svg)

图源：[resource-gateway-run-control-lifecycle.drawio](assets/drawio/resource-gateway-run-control-lifecycle.drawio)。

#### Deadline 如何进入算子和下游调用

页面上的 `Deadline` 是整次 run 的绝对边界，不是每个节点可独占的 timeout。Gateway 在启动 BLOGE 前创建共享
`ExecutionBudget`，并从绝对 deadline 中扣除 `finalizationReserve`；默认 reserve 为 `100 ms`。所有 scoped
`GraphContext`、嵌套图和 `OperatorContext` 共享同一预算上限，后续代码只能收紧，不能重新绑定一个更晚的
deadline。即使操作系统时钟向后校正，已经观察到的 remaining budget 也不会增大。

自定义 operator 可直接使用：

```java
Instant deadline = ctx.deadlineAt().orElse(null);
Duration remaining = ctx.remainingBudget().orElse(null);
Duration effectiveTimeout = ctx.capTimeout(configuredTimeout, "customer profile lookup");
```

调用链遵循以下规则：

1. scheduler 在 node admission 前检查预算；为零时不调用 operator，node fact 为
   `CANCELLED + DEADLINE_EXHAUSTED + ENGINE_ADMISSION`。
2. resilience timeout、suspend timeout 和 retry backoff 都不得超过剩余预算；下一次退避已经放不下时立即停止重试。
3. `HttpResourceOperator` 和 BLOGE `HttpRequestOperator` 取“配置 timeout 与剩余预算的较小值”。common HTTP
   operator 还发送 `X-Bloge-Deadline` 与 `X-Bloge-Remaining-Budget-Ms`，让下游继续收紧自己的子调用。
4. `RemoteWorkerEnvelope.Budget` 携带 `deadlineAt`、`remainingBudgetMillis`、`finalizationReserveMillis` 和
   `capturedAt`。worker 必须结合允许的 clock skew 判断 envelope 是否已过期，不能用传输前的剩余值重新放宽预算。

`remainingBudget` 是“还允许投入业务工作的时间”，已经扣除了 evidence 签名、终态持久化和 outbox 提交的
reserve。生产环境应根据 p99 evidence finalization 延迟配置该值；reserve 过小会在 deadline 边缘丢失治理证据，
过大则会过早拒绝业务节点：

```properties
resource-gateway.run-control.finalization-reserve-ms=100
```

当前协议仍不承诺强杀任意忽略中断的 Java 代码，也没有定义客户端断开后的 `detachPolicy`。自定义 operator 若绕过
`ctx.capTimeout(...)` 发起不可取消的私有 I/O，外层 run-control 只能中断线程并在 grace 后诚实地进入
`TERMINATION_UNCONFIRMED`；这类 binding 在生产准入时仍应被合规测试阻断。

执行完成后，在 Output 中展开 `payload.runControl`：

| status | 如何理解 | evidence 处理 |
|---|---|---|
| `SUCCEEDED` / `FAILED` | 调度线程和所有算子线程均已退出 | 继续按 node/edge facts 判断 |
| `CANCELLED` | 用户取消，协作终止已经确认 | 可消费，但仍检查外部写的 `sideEffectOutcome` |
| `TIMED_OUT` | 绝对 graph deadline 已到且协作终止已确认 | 可消费为 timeout 事实 |
| `CANCEL_REQUESTED` / `TIMING_OUT` | 停止信号已受理，仍在等待线程退出 | 非终态，不应发布 |
| `TERMINATION_UNCONFIRMED` | grace 已过或 owner 退出时仍有 operator in-flight | `manifest=QUARANTINED`，门禁必须阻断 |

`terminationConfirmed` 不是“HTTP 请求已经返回”的同义词。系统同时跟踪 scheduler owner 和实际进入
operator interceptor 的虚拟线程；只有 owner 退出且 in-flight 计数为零，才会清除
`sideEffectsMayBeInFlight`。若业务算子吞掉 `InterruptedException`，页面会及时返回
`TERMINATION_UNCONFIRMED`，而不是伪造 `CANCELLED`。

#### 多实例与重启时如何解释

Spring 产品服务使用 `dynamic_run_controls` 作为权威状态表，JVM 内存只保存当前 owner 的线程句柄。表中保存
`requestId`、fence 的 SHA-256 digest、owner id/epoch、monotonic revision、绝对 deadline、lease、状态和终止事实；
不会保存原始 fencing token。所有变更在数据库行锁内完成，因此两个实例同时取消时只有一个命令成为状态迁移赢家。

典型过程如下：

1. 画布 API 在进入 BLOGE 前先写 `visual_run_recovery_reservations`：保存去除 fixture 后的 draft、租户/环境、
   已脱敏输入、material fingerprint 和由 requestId 确定的 runId；不保存原始 fencing token。
2. 实例 A 以唯一 `requestId` 创建 durable claim，并获得 owner epoch。
3. A 每次轮询 deadline/cancel 时续租；本地线程句柄不写数据库。
4. 实例 B 可以处理同一 requestId 的 GET/cancel。B 只提交 fenced 状态迁移，不尝试操作 A 的线程。
5. A 观察到 `CANCEL_REQUESTED` 后中断 owner 和 operator，最终提交 `CANCELLED` 或
   `TERMINATION_UNCONFIRMED`。
6. A 若崩溃，lease 到期后的首次读取会原子地写入 `OWNER_LEASE_EXPIRED`、
   `recoveryDisposition=ABANDONED` 和 `sideEffectsMayBeInFlight=true`；旧 owner/epoch 后续不能覆盖它。
7. bounded sweeper 发现 abandonment 后锁定 reservation。正常完成路径与 recovery 路径只能有一个赢家；赢家
   创建 `VisualGraphRunRecord.v8`、签名 evidence v6、提交 reservation 终态并原子追加 integration outbox。

在 evidence v6 中先看顶层 `recovery`：

| `recovery.mode` | 含义 | 治理处理 |
|---|---|---|
| `NONE` | 请求线程完成了正常 run-record 提交 | 按 manifest、node/edge facts 和 assertion 继续判断 |
| `OWNER_ABANDONED` | owner lease 过期，执行终止和外部副作用无法确认 | `QUARANTINED`；消费 `RUN_ABANDONED`，发布门禁必须阻断 |
| `TERMINAL_EVIDENCE_GAP` | control 已终态，但进程在 evidence 事务前死亡 | 保留图级终态，精确 payload/facts 缺口仍隔离；消费 `RUN_EVIDENCE_RECOVERED` |
| `CONTROL_MISSING` | reservation 已提交，但 grace 后仍没有 control row | 视为 admission/进程异常，自动收敛 pending 状态并隔离 |

`reservationFingerprint`、`controlRevision`、`attempt` 和 `recoveredAt` 都进入 evidence 签名材料。恢复 evidence
可以被审计和对账，但不会因为“签名有效”就变成 `READY`：缺失精确 node payload、终止未确认或
`UNKNOWN_COMMIT` 仍会出现在 `manifest.gaps`。

恢复扫描默认每 5 秒运行一次；terminal control 额外等待 5 秒，让正常 evidence 事务优先完成；reservation 在
30 秒后仍没有 control 才进入 `CONTROL_MISSING`。演示环境可通过以下配置调整，但生产值应由 SLO、最大正常
提交时延和数据库容量测试决定：

```properties
resource-gateway.run-control.finalization-reserve-ms=100
resource-gateway.run-recovery.fixed-delay-ms=5000
resource-gateway.run-recovery.initial-delay-ms=5000
resource-gateway.run-recovery.evidence-commit-grace-ms=5000
resource-gateway.run-recovery.missing-control-grace-ms=30000
```

这里刻意不做自动重跑。进程死亡时，系统无法仅凭线程消失判断远端写是否提交；盲目从头执行可能造成重复扣款、
重复发布或重复通知。业务要恢复执行，必须先通过当前的 commit receipt/reconciliation 协议消解 unknown commit；
若 operator 没有 evidence-safe lookup ref 或业务 provider 尚未注册 reconciler，则由上层走人工调查、明确审批后的
补偿或新 requestId 流程，不能退化为自动重跑。

自动化客户端也可以调用：

```http
GET /api/visual/run-controls/{requestId}
X-Run-Fencing-Token: <fencingToken>

POST /api/visual/run-controls/{requestId}/cancel
Content-Type: application/json

{
  "fencingToken": "<fencingToken>",
  "expectedRevision": 0,
  "reason": "AUTHOR_CANCELLED_FROM_CANVAS"
}
```

`expectedRevision>0` 启用乐观并发检查；过期 revision 返回 `409`，错误 fence 返回 `403`。当前控制表只在
单个 Resource Gateway 进程内保留一小时终态，因此不能把它当作跨重启 workflow store；跨实例接管仍需
BLOGE durable execution lease 与持久 fencing owner。

### 3.7 让 ANEKE 持续同步且可对账

Resource Gateway 现在不要求 ANEKE 依赖 webhook 才能持续治理。draft、operator library、run 和 operator contract test suite 的权威写入会在**同一个数据库事务**中追加 integration event；任一 Outbox 写入失败时，资产写入也会回滚，不会产生“资产已经存在、治理侧永远不知道”的静默裂缝。

![Resource Gateway 与 ANEKE 持续同步闭环](assets/resource-gateway-aneke-continuous-sync.svg)

图源文件：[`assets/drawio/resource-gateway-aneke-continuous-sync.drawio`](assets/drawio/resource-gateway-aneke-continuous-sync.drawio)

ANEKE 侧按以下顺序接入：

1. 使用 `X-Purpose: CHANGE_SYNC` 调用 `GET /api/integration/reconciliation`，取得当前租户和环境的权威资产快照、各类数量、rolling fingerprint 和 `checkpointCursor`。
2. 用快照重建或校正 ANEKE projection。`GRAPH_DRAFT`、`GRAPH_CONTRACT`、`RUN` 和 gate result 按 tenant/environment 隔离；当前 operator library 和 contract suite 是共享资产，会以 global scope 出现在每个租户的同步视图中。
3. 保存快照里的 `checkpointCursor`，随后调用 `GET /api/integration/events?cursor=...&limit=100`。
4. 按 `eventId` 去重，并按 `aggregate.kind + aggregate.id + aggregate.sequence` 防止旧 revision 覆盖新 revision。事件只携带 fingerprint 和稳定 `payloadRef`；draft、library、suite 可按事件 revision 读取不可变快照，run 按 runId 读取 evidence。
5. 将本页 projection 更新和 `nextCursor` 放在 ANEKE 自己的同一事务中提交。`hasMore=true` 时继续拉取；`false` 时保存 `checkpointCursor` 并进入下一轮 polling。
6. Polling 中断、请求超时或通知丢失时，从最后已提交 cursor 继续。重复读取同一个 cursor 会得到同一个固定 high-water 窗口，不会把处理中途的新事件混进本页。
7. cursor 被篡改、跨租户/环境复用时返回 `400 RG.INTEGRATION.CURSOR_INVALID`；cursor 超过当前 7 天有效期返回 `410 RG.INTEGRATION.CURSOR_EXPIRED`，此时重新调用 reconciliation，不要猜测数据库 offset。

首次拉取示例：

```bash
curl -sS 'http://localhost:8080/api/integration/events?limit=100' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-Organization-Id: knowledge-governance' \
  -H 'X-Project-Id: tool-studio' \
  -H 'X-Environment-Id: prod' \
  -H 'X-Actor-Id: aneke-sync' \
  -H 'X-Purpose: CHANGE_SYNC' \
  -H 'X-Correlation-Id: sync-0001'
```

反熵快照示例：

```bash
curl -sS 'http://localhost:8080/api/integration/reconciliation' \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Tenant-Id: tenant-a' \
  -H 'X-Organization-Id: knowledge-governance' \
  -H 'X-Project-Id: tool-studio' \
  -H 'X-Environment-Id: prod' \
  -H 'X-Actor-Id: aneke-sync' \
  -H 'X-Purpose: CHANGE_SYNC'
```

这里不承诺网络级 exactly-once。系统采用“生产端 transactional outbox + ANEKE 幂等消费 + opaque cursor + reconciliation”的可恢复最终一致性模型。Webhook 仍未开放；后续即使增加 webhook，它也只负责低延迟提醒，不能替代 cursor 和对账快照。

### 3.8 演示脚本启动方式

推荐演示时直接使用仓库根目录下的专用脚本。它默认执行 `resource-gateway-examples` 的 `-Pfrontend package`，把 React UI 打进 Spring Boot 静态资源，然后以 `test` profile 在 `8080` 启动服务，因此隔离的 `/api/testing/**` 也可用于演示；`production` profile 中这些 bean 和路由不存在。为缩短演示准备时间，脚本默认给 Maven 打包加 `-DskipTests`；需要把测试也跑进去时使用 `--run-tests`。

```bash
./scripts/start-visual-canvas-demo.sh
```

需要演示 Scenario 批量回归时，显式启用地域隔离的自治 worker：

```bash
./scripts/start-visual-canvas-demo.sh --scenario-batch
```

脚本会同时打开 Mirror runtime，把 scheduler 与 demo identity 固定到同一个
`region/environment`，并等待 capability 中
`mirrorScenarioRehearsalBatchApi=true`、
`mirrorScenarioRehearsalBatchCooperativeControl=true`、
`mirrorScenarioRehearsalBatchEvidence=true`、
`mirrorScenarioRehearsalBatchRetentionApi=true`、
`mirrorScenarioRehearsalBatchLegalHold=true`、
`mirrorScenarioRehearsalBatchDeletionProof=true`，以及
`mirrorScenarioRehearsalBatchScheduling=true` 后才报告 ready。默认仍不开启后台
worker，普通画布演示不会意外消费历史队列。

需要演示 detached Shadow 的精确来源链时使用：

```bash
./scripts/start-visual-canvas-demo.sh --shadow-detached-data-plane
```

该模式安装 exact signed-binding baseline/candidate connector、payload-free equality policy、
独立二次来源复核器与 source-resolution proof exact-read API。它不会生成企业 root-policy、
online grant/kill-switch 或 egress authority；这些依赖缺失时 worker 仍失败关闭，不会为了演示而
读取外部系统。成功 comparison 所引用的 proof 可从
`/api/mirror/shadow/source-resolutions/{attestationId}/revisions/{revision}?fingerprint=...`
读取，并用独立 Test Kit 复核 `executionId`、source binding、candidate evidence、policy facts、
零写闭包、内容地址和签名。

升级 Test Kit、Jackson、JDK 或 crypto provider 时，执行
`CapabilityMirrorProtocol.readOnlyShadowSourceResolutionCompatibilityFixture().verify()`。
该 public-only fixture 由服务端生成并以 candidate evidence、source binding、source resolution
三把独立公钥闭合，可在启动前发现 deterministic id、canonicalization、key role 和签名协议漂移；
通过只代表 wire compatibility，不代表在线 authority 或真实 baseline 已 ready。

详细协议见
[领域保真度与 Shadow 证据指南](resource-gateway-domain-fidelity-profile.md)。

批次运行期间，系统会在每个 Scenario case 前后写入 payload-free heartbeat 与
next-case cursor，并读取数据库权威的 cancel/deadline 决策。点击取消后，当前
受 case timeout 限制的调用会先完成并写入可恢复进度，随后任务停止；画面上当前项
应显示为 `INDETERMINATE`，尚未开始的项显示为 `CANCELLED`。这表示“可能已经发生
外部效果，需要人工或补偿确认”，而不是把取消伪装成从未执行。

批次进入 `SUCCEEDED`、`FAILED`、`CANCELLED` 或 `EXPIRED` 终态前，系统会在同一
数据库事务中生成并立即复验签名 evidence；签名或存储失败时，job 与当前 item 的
终态一起回滚，不会出现“页面显示完成但没有证据”的窗口。可使用治理读取目的获取：

```bash
curl -sS \
  -H 'Authorization: Bearer bloge-aneke-demo-token' \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION' \
  'http://localhost:8080/api/mirror/rehearsal-jobs/<jobId>/evidence'
```

返回的 `resourceGateway.scenarioRehearsalBatchEvidenceBundle.v1` 不包含 fixture、
context、node input/output 或异常文本。它签名绑定原始 request、冻结 manifest、
终态 job、稳定顺序 item 结果及每个 child aggregate 的 evidence/workbook 指纹；
子 aggregate bundle 仍保持独立内容寻址，ANEKE 或 CI 可按引用继续取证。

同一终态事务还会注册不可缩短的批次保留下限。治理方可读取
`/api/mirror/rehearsal-jobs/<jobId>/retention`，使用 `LEGAL_HOLD` purpose 在
`/retention/holds` 和 `/retention/hold-releases` 管理多个独立法律保全，并在到期
且无 active hold 时以 `PAYLOAD_RETENTION_ADMIN` 调用 `/retention/purge`。签名删除
证明会记录 job、item 和 batch evidence 的精确逻辑删除计数，同时明确保留 child
Scenario evidence 与 operation/lifecycle audit。完整命令示例和 Test Kit 验签方式见
[场景演练注册、编译、耐久批次与证据指南](resource-gateway-scenario-rehearsal-compiler.md)。

启动成功后脚本会打印：

```text
Author canvas:   http://localhost:8080/author/
Rehearsals:      http://localhost:8080/rehearsals/
Run examples:    http://localhost:8080/showcase/
Legacy composer: http://localhost:8080/examples/gateway
  Capability probe: http://localhost:8080/api/integration/capabilities
  Active profile:   test

Integration API templates:
  Draft workbook:    GET  /api/integration/drafts/{draftId}/correctness-workbook?revision={revision}
  Semantic workbook: GET  /api/integration/test-suites/{suiteId}/revisions/{revision}/semantic-correctness-workbook
  Gate feedback:     POST /api/integration/gate-results
  Test execution:    POST /api/testing/executions  (Bearer token + X-Purpose: TEST_EXECUTION)
```

脚本不会把“端口已监听”当成启动成功。它会等待公开的 capability endpoint 返回 2xx，确认协议、路由和 Spring
依赖都已完成装配后才报告 ready。可独立复查：

```bash
curl -fsS http://localhost:8080/api/integration/capabilities
```

查看状态和日志位置：

```bash
./scripts/visual-canvas-demo.sh status
./scripts/visual-canvas-demo.sh restart
```

停止演示服务：

```bash
./scripts/stop-visual-canvas-demo.sh
```

停止脚本默认等待 40 秒，以覆盖后台 worker 默认 30 秒的有界排空窗口；可通过
`BLOGE_VISUAL_CANVAS_STOP_TIMEOUT=60` 调整，但脚本只接受 `1..300` 秒，超时后才会强制终止。

已经安装企业 source cut、scope、disposition、outcome authority 的客户装配，可以在
`test/staging` 启用 selected-population 持续 assessment：

```bash
./scripts/start-visual-canvas-demo.sh --profile staging \
  --outcome-continuous-assessment
```

该开关不会伪造客户 authority。stock demo 会因 capability readiness 不完整而 fail-closed；
`production` profile 会在构建和启动前直接拒绝该模式。

#### 3.8.1 使用 Owner 演练工作台

启动时增加 `--scenario-batch`，然后打开
`http://localhost:8080/rehearsals/`：

```bash
./scripts/start-visual-canvas-demo.sh --scenario-batch
```

页面顶部可在 `Author / Rehearsals / 运行示例` 三个工作面间切换。Rehearsals
不是另一套治理系统，而是 Resource Gateway 对受保护 Scenario 运行与签名证据的
证据优先任务投影：

首次打开不再停在空白队列。左侧 `Live / Samples` 是显式数据源切换：

- `Live` 读取当前认证 scope 中的真实批次。存在真实批次时默认进入该模式。
- `Samples` 使用浏览器内置、与正式展示协议同形的只读样例。API 未启用或当前
  scope 没有批次时会自动进入该模式。
- 样例顶部始终显示 `Illustrative sample`，不会调用 batch、child workbook 或
  remediation 写接口，也不产生服务端签名、审批事实或发布证据。
- 点击 `Retry live` 会重新探测服务端；发现真实批次后自动回到 `Live`。

内置样例不是四份同质的成功数据，而是四种常见工作状态：

| 样例 | 状态 | 可观察内容 |
| --- | --- | --- |
| Grounding policy regression | `PARTIAL` | 同批覆盖执行失败、证据不完整、blocker assertion、治理阻断、warning 和通过项 |
| Release candidate ready | `SUCCEEDED` | gate ready、全部 blocker 通过、保留一个不阻断发布的 freshness warning |
| Live dependency degradation | `RUNNING` | 运行中、排队、已通过与 CRM 限流失败并存；明确标记为非发布证据 |
| Evidence finalization quarantine | `QUARANTINED` | 业务子运行与 evidence signer、retention proof 故障分离展示 |

样例也支持可分享定位：
`/rehearsals/?sample=sample-release-ready&entry=1` 会直接恢复指定样例和证据抽屉。
这个链接只用于产品讲解；真实治理协作仍使用 `jobId` deep link。

最快的“失败 -> 恢复 -> 重置”演示路径：

1. 保持 `Samples`，选择 **Grounding policy regression**；
2. 点击 Execution 分组中的 `#0` 超时条目，先读业务化阻断原因；原始协议 code 在默认收起的
   **技术详情** 中；
3. 点击 **运行样例重试**。工作台会切换到确定性的 **Release candidate ready** 后继，并显示
   前序/后继坐标回执；
4. 回执会明确说明这是浏览器本地演示，不调用治理接口、不产生签名证据；
5. 点击 **重置样例** 恢复原始失败，可重复讲解。

1. 左侧选择当前认证 tenant/organization/project/environment/region 中的批次；
   `Load older batches` 使用稳定 keyset，不会因运行进度变化而重复或漏项。
2. 中间先看批次进度和 gate。运行中标为 `Live projection`，不能用于发布门禁；
   终态标为 `Signed workbook`，并显示 root blocker。
3. 使用 Execution、Evidence、Assertions、Governance、Warnings、Passed 分段控制
   缩小范围。这里按处理责任分组，不改写服务端签名 outcome。
4. 点击 entry 打开右侧证据抽屉。首屏先展示 scope-aware verdict、业务影响、责任人
   和下一动作，再展示 attempt budget、deadline、batch fallback、last observation。
   `Exact lifecycle` 表示逐次 claim/retry/terminal 来自数据库 audit；
   `Aggregate projection` 表示旧任务只保留总次数，界面不会猜测缺失历史。
5. 终态 entry 才会按需拉取 child workbook，展示 case 与 handling assertion；
   默认打开整批不会产生 N+1。fingerprint 和技术坐标默认折叠。
6. 只有企业宿主提供 exact compiled-plan 到 source revision/fingerprint 的不可变
   Author binding 时，失败项才显示 `Open exact Author target`；无绑定、无权限和
   Illustrative sample 都只显示责任人，不制造一个错误链接。
7. 将地址栏中的
   `/rehearsals/?jobId=<jobId>&entry=<manifest-index>` 交给 ANEKE 或排障人员，
   对方会回到同一批次和条目；跨 scope 的 job 不会被定位或读取。

被阻断的终态批次会在根 blocker 下显示 `Reviewed remediation`：

8. `Retry exact` 用于暂态执行/证据复查；`Replace plans` 允许勾选 entry，并填写
   已存在 compiled plan 的 exact id、revision 和 fingerprint。两种方式都必须绑定
   exact governance ticket，不能输入自由 DSL/JSON。
9. `Freeze for review` 把 predecessor workbook fingerprint、完整 successor request、
   server policy 和 review deadline 冻结成 content-addressed plan。
10. Owner 先批准或拒绝。Owner 批准后，独立 Reviewer 使用另一个 human identity
   追加第二代决定；页面显示的是服务端绑定的 actor/time，而不是表单自报身份。
11. 两代批准完成后由 Owner 点击 `Admit successor`。拒绝事实不可覆盖，拒绝后不会
   出现提交按钮。
12. 后继形成 root-signed terminal workbook 后，点击 `Compare signed evidence`，
    查看根和逐 entry 的 blocker 集合差、plan changed 和 gate transition。对比没有
    “综合质量分”，只展示两份签名来源可证明的变化。

创建计划后 deep link 扩展为
`/rehearsals/?jobId=<jobId>&remediationId=<remediationId>&entry=<optional-index>`，
刷新会读取 content-addressed lineage 恢复审批上下文。

进入这条写侧前，宿主必须通过
`setRehearsalRemediationCredentialsProvider(...)` 分别提供 `READ`、`OWNER` 和
`INDEPENDENT_REVIEWER` 短期身份。缺少或过期的槽位会显示 `Not connected` 并禁用
对应动作；默认 demo workload token 刻意不能审批，`--scenario-batch` 不会生成
万能令牌。VSCode 方案应再通过 `setBlogeApiTransport(...)` 让扩展宿主持有 bearer
material，Webview 只接收 log-safe principal label。

工作台不读取业务 payload、worker identity 或异常文本，也不提供取消、quarantine finalization remediation、
legal hold、purge、通用 JSON/DSL 或 raw payload 按钮。当前版本闭合“找得到、分得清、
能取证、双人审阅、准入后继、签名对账”；“零 DSL 调整 case”仍是后续能力。

常用参数：

| 参数 | 用途 |
| --- | --- |
| `--open` | 启动后自动打开 `/author/` |
| `--port 18080` | 改用指定端口 |
| `--profile test\|staging\|production` | 选择 Spring profile；默认 `test`，`production` 不装配 testing API |
| `--no-build` | 跳过打包，复用已有 jar |
| `--api-only` | 不启用 `-Pfrontend`，只打包后端 API |
| `--run-tests` | 打包时不跳过 Maven 测试 |
| `--scenario-batch` | 启用单地域分区、固定并发度的 Scenario batch worker（仅 test/staging） |
| `-- --gateway.base-url=http://localhost:9091` | `--` 后面的参数透传给 Spring Boot 应用 |

`--scenario-batch` 的进程级参数可通过
`RG_MIRROR_SCENARIO_BATCH_INSTANCE_ID`、
`RG_MIRROR_SCENARIO_BATCH_REGION`、
`RG_MIRROR_SCENARIO_BATCH_ENVIRONMENT` 和
`RG_MIRROR_SCENARIO_BATCH_MAXIMUM_POLLERS` 覆盖。environment 只能是 `test` 或
`staging`；region/environment 必须与 integration identity 一致，否则脚本在构建
和启动前失败。停止仍使用同一个命令。停止时 scheduler 先禁止新 claim，再按配置
的 drain timeout 等待当前 worker turn；超时中断只是最佳努力，数据库 lease/epoch
仍是防止旧 worker 发布结果的最终 fence。

`staging` 还要求显式注入 claim-token key ring、独立 request-index key ring，以及
`RG_TEST_WORKER_QUARANTINE_REQUEST_INDEX_WRITE_MODE`。此外，隔离区销毁审批必须配置独立的
`RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_TRUST_DOMAIN`、
`RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_POLICY_FINGERPRINTS`、
`RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_SIGNATURE_THRESHOLD` 和
`RG_TEST_WORKER_QUARANTINE_CHANGE_AUTH_AUTHORITY_KEYS_JSON`；最后一项只能包含 Ed25519 公钥，
私钥必须留在外部治理系统。脚本会在启动 Spring 前检查这些值是否齐全及基本格式，应用再执行完整密钥、阈值和策略校验。
若 staging 启用 `RG_TEST_SECRET_AUTHORITY_COHORT_ENABLED`，脚本还会强制 dynamic JWKS、remote
signed inventory、managed roots 和
`RG_TEST_SECRET_AUTHORITY_COHORT_INVENTORY_EXTERNAL_ANCHOR_ENABLED` 同时开启，并预检外部
notary trust/set、非空 endpoint、managed trust publication、bootstrap root quorum、HTTPS、
`3f+1 / 2f+1` quorum 和 receipt timing。staging 禁止静态 notary key array；notary 验签公钥通过
bootstrap-quorum-signed HTTPS/ETag publication 免重启轮换。bootstrap roots 也不再是 staging 静态
key array：每个业务域必须提供 `...EXTERNAL_ANCHOR_BOOTSTRAP_ROOT_GENESIS_JSON`、accepted root
policy 和 strict HTTPS complete-chain bundle URI；应用从 pinned genesis 完整重放 successor chain，并在
专用 durable floor 成功后才授权 notary publication。脚本和 Java 都拒绝 legacy root fallback、非
Byzantine genesis、跨域 trust-domain/floor alias；root 或 publication rollback/fork/gap/刷新失败会立即
关闭外部锚定。最小 notary 与 root 拓扑均为四个独立 authority、三个有效签名。完整变量见脚本
`--help`、Testing Control Plane API 与
[bootstrap-root ceremony and runtime wiring verification](resource-gateway-execution-data-control-plane-stage4-bootstrap-root-ceremony-kernel-verification.md)。
首次 request-index 跨版本升级应从 `LEGACY_READ_WRITE` 开始；完整配置和三阶段切换门禁见
[Testing Control Plane API](resource-gateway-testing-control-plane-api.md) 与
[request-index rolling-upgrade verification](resource-gateway-execution-data-control-plane-stage4-worker-quarantine-request-index-upgrade-verification.md)。

若 staging 同时启用 `RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_ENABLED=true`，则 recovery fleet 不允许
退回静态 inventory runtime keys。必须同时开启
`RG_TEST_BOOTSTRAP_ROOT_RECOVERY_FLEET_DYNAMIC_INVENTORY_ENABLED=true` 和
`...DYNAMIC_INVENTORY_TRUST_ROOTS_ENABLED=true`，提供互不相同的 inventory/root HTTPS URI、稳定的
root-set id、两组相互独立的 bootstrap-root domain、各自的 Ed25519 public-key JSON 与 threshold；
父级 `TRUST_DOMAIN`、`SIGNATURE_THRESHOLD`、`AUTHORITY_KEYS_JSON` 及 witness 对应静态变量必须分别
为空、`0`、`[]`。脚本会在 build 前检查 profile downgrade、同 URI、HTTP loopback、静态/动态混用、
artifact 不一致和时间边界，Spring 再执行 strict JSON、密码学、floor 与 signed binding 校验。启动后应在
capability 的 `testability.recoveryFleet` 中看到 v2、`managedTrustRootRefresh=true`、
`managedTrustRootAvailable=true`、`managedTrustRootStatus=HEALTHY` 和正数 root sequence；否则不能把
fleet 的 `ready` 当作可发布证据。变量全集及剩余 mTLS、external Byzantine floor、HA/DR 门禁见
[managed recovery-fleet trust-root Spring verification](resource-gateway-execution-data-control-plane-stage4-bootstrap-root-recovery-fleet-managed-trust-root-spring-verification.md)。

测试控制面的 target fingerprint 获取、fixture 注册、执行、批量、证据查询、脱敏和生产隔离操作见
[Resource Gateway Testing Control Plane API](resource-gateway-testing-control-plane-api.md)。

脚本使用 `target/example-pids/visual-canvas-demo.pid` 记录进程，使用 `target/example-logs/visual-canvas-demo.log` 记录日志；
`status` 会同时报告 capability probe 是否健康。停止时会校验 PID/端口上的进程确实像 Resource Gateway demo，避免
误停其它服务。演示新的 payload 保留策略时，可直接透传 Spring 参数，例如：

```bash
./scripts/start-visual-canvas-demo.sh --port 18080 -- \
  --gateway.integration.payload-governance.default-classification=CONFIDENTIAL \
  --gateway.integration.payload-governance.retention-days.confidential=7
```

### 3.9 手动启动方式

如果只运行后端 API 或旧版静态资源：

```bash
mvn -f resource-gateway-examples/pom.xml spring-boot:run
```

如果要使用新版 `/author/` 和 `/showcase/` 的打包版 React UI，先启用 frontend profile：

```bash
mvn -f resource-gateway-examples/pom.xml -Pfrontend package
java --enable-preview -jar resource-gateway-examples/target/bloge-examples-resource-gateway-1.0.0.jar
```

然后访问：

```text
http://localhost:8080/author/
http://localhost:8080/showcase/
http://localhost:8080/examples/gateway
```

说明：默认 Maven 构建不会打包 React UI，目的是让 Java 验证保持快速、离线。`-Pfrontend` 会安装本地 Node、执行 `npm ci` 和 `npm run build`，再把同一份 Vite 产物复制到 `static/author` 与 `static/showcase`。

本地前端调试也可以进入 `resource-gateway-examples/src/main/frontend` 执行：

```bash
npm ci
npm run dev
```

当前 Vite dev proxy 只代理 `/api` 到 Spring Boot。算子库导入使用 `/admin/visual-operator-libraries/*`，所以完整体验建议优先使用 Maven 打包后的 `/author/`。

### 3.10 VSCode 插件轻量化方向

如果用户只是想在业务仓库里看懂 `.bloge` 拓扑、导入本地算子 schema、配置 mock 数据并跑表格测试，每次都启动 Resource Gateway 服务端会显得偏重。后续推荐把 `/author/` 的核心能力下沉成 VSCode 插件入口：

```text
打开业务仓库
  -> Visualize 当前 .bloge
    -> 本地 topology-only 渲染
      -> 扫描 workspace schema 后渐进增强
        -> 本地 mock simulation / test suite
          -> 可选切换 JVM 或远程服务端做权威校验
```

这条路线不会替代现有服务端。插件负责低门槛 authoring 和本地理解；Resource Gateway / Studio 服务端继续负责权威 validation、rewrite gate、governed commit、发布治理和真实运行。

当前代码已经先补了一个插件化地基：前端 `api.ts` 支持 `BlogeApiTransport`，浏览器 demo 默认仍走 `fetch`，VSCode Webview 未来可以把同一批 API 请求通过 `postMessage` 交给 extension host 本地处理。详细方案见 [BLOGE VSCode 插件轻量化可视化编排方案](./bloge-vscode-extension-lightweight-authoring-plan.md)。

## 4. 核心概念

| 概念 | 含义 |
| --- | --- |
| Operator Library | 用户或系统提供的算子库，合同版本为 `bloge.visualOperatorLibrary.v1`；字段定义见 [BLOGE 可视化算子库 Schema 定义](./bloge-visual-operator-library-schema.md) |
| Operator | 单个可编排算子，至少有 `operatorRef`，通常包含展示信息、输入/输出端口、schema、lowering |
| Side-effect Protocol | 外部写算子的执行合同；声明 journal、幂等键、对账 lookup、commit receipt 和 reconciler。缺失时仍可设计，但不能真实运行或发布为 EXECUTABLE |
| Built-in Function | 算子库或系统默认目录提供的 BLOGE 表达式函数，用于 transform/branch 等表达式输入框的函数名补全和签名提示 |
| Port Schema | 输入/输出端口的 JSON Schema envelope，画布用它判断可连接性 |
| GraphDraft | 画布中的业务流程草稿，合同版本为 `bloge.visualGraphDraft.v1` |
| Connection Candidate | 服务端根据当前 draft 和 schema 枚举出的可连接目标 |
| Validate | 对当前 draft 做结构、schema、readiness、action readiness 校验 |
| Node Fixture | 节点级模拟样本，可 pin mock 输出，也可断言该节点收到的 expected input |
| Test Suite | 画布内的表格测试浮层；每行是一组 runtime context、节点 fixture override 和 expected terminal output |
| Simulate | 混合模拟运行。安全且已实现的内置算子可 real-run；design-only 或高风险算子会 mock-run |
| Export | 导出当前 draft、publication bundle 或内置 operator library bundle |

关键原则：浏览器负责交互体验，规则由服务端兜底。客户端可以做提示和高亮，但连接是否有效、草稿是否可运行、模拟是否可信，都以服务端结果为准。

### 4.1 Graph 级 input/output schema 在哪里看

Resource Gateway 内置 graph 的正式合同定义在：

- 代码：`resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/gateway/GatewayGraphContractCatalog.java`
- API：`GET /api/gateway/graphs/contracts`
- 示例场景 API：`GET /api/gateway/examples/scenarios`，每个 scenario 会携带自己的 `inputSchema` 和 `outputSchema`

新版 `/author/` 和旧版 `/examples/gateway` 都把 graph 合同作为一等信息看待：

- `/author/`：画布工具栏下方有 **Graph Contract** 条，显示当前 draft 的 Input/Output 摘要。3 个内置复杂示例各自携带 `inputSchema` 和 `outputSchema`；加载示例时会同步设置当前 graph contract，并用 input schema 生成一份 runtime context 样本。从 Legacy DSL 导入时，graph 级 `input { ... }` 会进入 `draft.inputSchema`，graph 级 `output { ... }` 会进入一等 `draft.outputSchema`。`visualLayout.graphContract.outputSchema` 仍会保留一份兼容副本，供旧导出、UI 摘要和历史 draft 回读使用。
- `/examples/gateway`：右侧 Inspector 顶部有 **Graph Contract** 区块，会显示当前 showcase/composer 的 Input/Output 摘要。

Graph Contract 会同时显示：

- **Input / ctx**：这张 graph 执行前要求的上下文字段。
- **Output / public result**：这张 graph 对系统集成暴露的终态输出字段。

对于 Resource Gateway showcase 示例，Graph Contract 来自 `GatewayGraphContractCatalog`，所以 `User Dashboard`、`Loan Decision Policy`、`Product Detail` 等示例各自有独立的 input/output schema。对于 `/author/` 的 3 个可编辑复杂示例，Graph Contract 定义在 `resource-gateway-examples/src/main/frontend/src/canvasExamples.ts`，并会随 draft 一起导出 `inputSchema`、一等 `outputSchema` 和兼容用 `visualLayout.graphContract.outputSchema`。对于 Legacy DSL，Graph Contract 来自 `.bloge` 文件里的 `input` / `output` 声明。对于 `Custom Composer`，Input 来自当前画布的 `Graph Input Schema`，Output 来自当前 `Graph Output` 选中的输出节点和 path；修改 schema 或切换输出节点后，Graph Contract 摘要会同步刷新。

加载 `Loan policy fallback` 后，Graph Contract 与画布状态会像下图这样联动：

![Author 加载 Loan policy fallback 后的 Graph Contract 标注](assets/bloge-author-loan-example-annotated.svg)

1. **示例元数据**：每个内置示例直接显示节点数、边数、Input 字段数、Output 字段数；点击 Load 会把完整 draft 加载进画布。
2. **运行/导出工具栏**：加载后节点、边、输出节点和 fixture 数会同步刷新，确认当前不是空草稿。
3. **输入输出 schema**：这里就是 graph 级 input/output schema 的可视化入口；例子中输入需要 `applicantId`，输出暴露 `decision`、`tier`、`primaryScore` 等公共结果字段。
4. **节点 mock/real 状态**：右侧 Mock Setup 告诉你哪些节点有 fixture、哪些节点可真实执行、哪些还只是 server sample。
5. **可编辑 DAG**：图不是静态展示，节点、边、output node、fixture 和配置都仍然可以继续编辑。

## 5. `/author/` 怎么用

### 5.1 第一步：准备算子库

最小可用算子库可以是 schema-only 的 design operator。它还没有运行时实现，也能进入画布参与设计、连接校验和模拟。算子库也可以声明 `builtInFunctions`，用于补充 transform/branch 表达式里的业务函数；导入后这些函数会和系统默认函数一起出现在表达式编辑器中。
完整字段合同、lowering 约束和机器校验 schema 见 [BLOGE 可视化算子库 Schema 定义](./bloge-visual-operator-library-schema.md) 与 [bloge-visual-operator-library.schema.json](./schemas/bloge-visual-operator-library.schema.json)。

```yaml
schemaVersion: bloge.visualOperatorLibrary.v1
libraryId: risk-policy
displayName: Risk Policy
version: 1.0.0
operators:
  - operatorRef: risk:eligibility
    display:
      name: Eligibility
      description: Decides whether an applicant is eligible.
      tags: [risk, policy]
    lowering:
      mode: design
    ports:
      inputs:
        - name: inputs
          required: true
          description: Applicant facts.
          schema:
            format: json-schema
            version: "2020-12"
            schema:
              type: object
              properties:
                score:
                  type: integer
                amount:
                  type: number
              required: [score, amount]
      outputs:
        - name: output
          description: Eligibility decision.
          schema:
            format: json-schema
            version: "2020-12"
            schema:
              type: object
              properties:
                eligible:
                  type: boolean
                reason:
                  type: string
              required: [eligible]
```

`lowering.mode: design` 表示它是设计期算子：可以拖拽、连接、保存、导出、模拟，但不能作为真实 request-response 运行时直接执行。未来接入 Java/native/remote worker/AI tool 等执行绑定后，readiness 会变化。

### 5.1.1 外部写算子必须声明什么

读取类算子不需要额外配置。会向订单、支付、工单、消息或其他外部系统写入的算子必须声明
`capabilities.effect: WRITE_EXTERNAL`，并用 `bloge.sideEffectProtocol.v1` 说明成功凭什么确认、超时后凭什么查询。
下面是可进入 runtime binding 流程的最小完整示例：

```yaml
capabilities:
  effect: WRITE_EXTERNAL
  idempotency: IDEMPOTENT
  sideEffectProtocol:
    schemaVersion: bloge.sideEffectProtocol.v1
    mode: JOURNALED
    commitReceiptRequired: true
    reconciliationRequired: true
    reconcilerRef: orders.status
    idempotencyKeySource: input.params.idempotencyKey
    reconciliationLookupSource: input.params.reconciliationLookupRef
    commitReceiptSource: response.headers.x-receipt-id
```

导入后的页面反馈不是一句泛化的 warning：

1. 左侧 palette 的绿色 **managed write** 表示合同字段完整；红色 **write protocol required** 表示只能作为 DESIGN 资产使用。
2. 将算子拖到画布后，节点标题区会显示 **write ok** 或 **write blocked**，不用打开 JSON 才能判断。
3. 单击节点，在右侧 Inspector 的 **Side-effect protocol** 行查看原因和 `reconcilerRef`；双击节点仍进入普通节点详情与 Operator Test Suite。
4. `write protocol required` 不妨碍拓扑设计、schema 连线和 mock simulation，但真实 Run、runtime binding 和 EXECUTABLE publication 会 fail closed。

![Author 外部写协议状态标注](assets/bloge-author-side-effect-protocol-annotated.svg)

1. **Palette 合同状态**：同一个 library 内可同时存在 managed 与 DESIGN-only 外部写；红绿标签来自服务端 operator contract。
2. **节点运行状态**：节点本身继续显示 `write ok` / `write blocked`，Auto Layout、缩放或过滤 palette 后不会丢失。
3. **Inspector 协议原因**：选中节点即可看到为何只能 DESIGN，不需要回到原始 library JSON 猜测。

runtime binding 不是“勾选已支持”即可。提交 implementation binding 时必须同时提供
`SIDE_EFFECT_JOURNAL_V1`、`COMMIT_RECEIPT_V1`、`RECONCILIATION_LOOKUP_V1` 三项 capability，以及
`side-effect-conformance`、`unknown-commit-fault` 两类测试证据；adapter activation 还必须提供当前
`reconciler-health` 证据。对应服务端入口位于
`/api/visual/assets/runtime-binding-requirements/implementation-bindings` 和
`/api/visual/assets/runtime-binding-requirements/adapter-activations`。这些是平台/运行时团队的治理步骤，不要求普通画布作者手工伪造。

descriptor-backed HTTP mutation 使用同一协议，但合同定义在 `ResourceDescriptor.externalWriteContract`：至少声明
输入参数中的幂等键名、实际 idempotency header、预生成且可安全落证据的 lookup ref 参数、provider、receipt header
和 provider-owned reconciler。运行时 context/input 的 `params` 必须携带这两个值：

```json
{
  "resourceId": "orders.create",
  "params": {
    "idempotencyKey": "order-create-20260712-001",
    "reconciliationLookupRef": "orders://transactions/tx-20260712-001"
  }
}
```

不要把带 query、credential 或 token 的 URL 当作 lookup ref。Resource Gateway 会在网络请求前拒绝不安全引用；
POST/PUT/PATCH/DELETE descriptor 没有完整 `externalWriteContract` 时也不会发送请求。底层 `httpRequest` 已从可视化
palette 隐藏，手写 DSL 若直接调用不安全 HTTP method 且没有当前节点的 `PREPARED` attempt，同样会在网络前被阻断。

![Resource Gateway 外部写一致性准入链](assets/resource-gateway-side-effect-conformance-chain.svg)

图源：[resource-gateway-side-effect-conformance-chain.drawio](assets/drawio/resource-gateway-side-effect-conformance-chain.drawio)。

### 5.2 第二步：导入或采用算子

在 `/author/` 左侧的 operator library intake 中粘贴 JSON/YAML：

1. 点击 Validate Library，先让服务端解析和校验合同。
2. 校验无 warning 时直接点击 Import Library；存在 warning 时，先逐条阅读 diagnostics，勾选 **I reviewed the warning diagnostics**，填写 **Audit reason**，Import 才会启用并以 `ackWarnings=true` 留下治理证据。
3. 画布会刷新 `/api/visual/operators`，新算子进入 palette。
4. palette 可以按 library 分组，也可以通过 source、tag、runtime/design facet 过滤。
5. 使用 Cmd/Ctrl-K 可以快速聚焦搜索框并按关键字过滤。

校验不只是 JSON/YAML 语法检查。服务端还会做 namespace、operatorRef、端口、JSON Schema、lowering、远程 `$ref`、高风险 runtime capability 等检查。warning 需要显式确认时，服务端会在 validation/import response 中返回 readiness 和 diagnostics。

### 5.2.1 直接从内置复杂示例开始

新版 `/author/` 在画布上方内置了复杂编排示例入口。它不是只展示图片或说明文字，而是把一张可编辑的 `GraphDraft` 直接加载到当前画布，包括节点、连线、字段绑定、规则表/转换配置、输出节点和 mock fixtures。

当前内置示例：

| 示例 | 覆盖模式 | 典型学习点 |
| --- | --- | --- |
| Loan policy fallback | 风控 fan-out、双 provider、decision table、response transform | 多资源并行取数、字段级条件绑定、规则表输出进入最终响应 |
| Order fulfillment lane | 订单列表、foreach enrich、shipping quote、SLA decision | 列表 enrichment、资源参数从上游字段派生、履约 lane 规则 |
| Personalized dashboard | 用户画像 fan-out 到钱包、推荐、通知，再聚合成 dashboard | 多资源聚合、最终响应映射、mock resource + real transform 的混合模拟 |

这 3 个示例现在不再只是“看结构”。每个示例都内置了两类资产：

- **Built-in function transform**：最终 `bloge:transform` 使用 `coalesce(...)`、`toNumber(...)`、`round(...)` 这类 BLOGE 表达式函数，展示如何把空值兜底、类型转换和数值规整写进可视化映射。
- **Test Suite cases**：每个示例提供 2 行表格测试。第一行通常是 happy path；第二行通过 fixture override 改变下游 mock 数据，覆盖 decline、standard lane、fallback default 等分支。

如果示例依赖的 operatorRef 不在当前 catalog 中，Load 按钮会禁用并提示缺失数量。此时先导入对应算子库，或确认 resource descriptor / built-in operator catalog 是否已经启动完成。示例加载后会替换当前画布；需要保留当前草稿时，先使用 Export Draft 导出。

### 5.2.2 存量手写 DSL 业务升级路径

还有一类常见业务不是从空白画布开始：业务系统已经集成 BLOGE engine 和 DSL，已经实现了自定义算子与 built-in function，并通过手写 `.bloge` 文件完成业务逻辑。这类团队的升级目标不是“重新拖一遍图”，而是把存量代码库和 DSL 迁移成可视化交付资产。

对通用画布来说，schema 怎么生成不是核心问题，甚至 schema 是否一开始就齐全也不应该成为看图门槛。画布的第一层只关心 DSL 能否被 parser 接收，并从 AST 推演出拓扑、依赖、输入绑定、输出引用和函数调用；第二层才用 operator/function schema 增强精确校验、补全、rewrite gate 和执行能力。存量 BLOGE 业务最推荐的迁移路径如下：

```text
业务代码库
  -> 画布粘贴 .bloge DSL
    -> Render DSL
      -> 先生成 topology-only GraphDraft + source map + diagnostics
        -> 可选导入或 inline 提供 bloge.visualOperatorLibrary.v1
          -> 增强为 schema-backed draft / 精确校验 / rewrite gate
            -> 给出是否允许自动覆盖源 DSL 的 gate 结论
```

这里有两个输入：

| 输入 | 来源 | 作用 |
| --- | --- | --- |
| `bloge-capability-catalog.json` | 业务项目执行 `bloge-maven-plugin:export-schema` 生成，schemaVersion 为 `bloge.capabilityCatalog.v1` | 描述业务 operator、input/output schema、config schema、表达式函数签名和导出诊断 |
| `.bloge` DSL 源码 | 业务项目已有 `src/main/resources/bloge/*.bloge` | 描述真实业务编排逻辑，由官方 DSL parser/compiler 解析后投影为 `GraphDraft` |

`bloge-capability-catalog.json` 不是唯一入口。如果团队已经有手写的 `bloge.visualOperatorLibrary.v1`、平台接口下发的 catalog、OpenAPI/AsyncAPI/resource descriptor 投影后的 visual library，或者其他工具生成的合法 schema，画布都按同一套 validator 接收，并用它增强对应 DSL。更进一步，**schema acquisition 是增强层，不是入场门槛**：没有 operator/function schema 时，服务端仍会从 DSL AST 提取 graph、node、transform、decision table、input binding、node output reference、route/data/dependency edge、built-in function 调用和 graph input/output，先渲染 `topology-only` draft；补齐 schema 后再进入 schema-backed 连线校验、表达式补全、rewrite gate 和执行/发布。

当前状态要分开看：

- 已落地：`/author/` 支持导入 `bloge.visualOperatorLibrary.v1` 和编辑 `GraphDraft`。
- 已落地：后端提供 schema-neutral DSL preview API：`POST /api/visual/dsl-imports/preview`。它接受 `.bloge` 源码、当前已导入的 visual library id，或本次 preview 临时传入的 `inlineLibraries`，然后返回 `GraphDraft + sourceMap + diagnostics + coverage + roundTrip`。没有 operator/function schema 也会返回 topology-only draft；缺失项以 warning diagnostics 和 `draft.visualLayout.import.projectionMode=topology-only` 标识。
- 已落地：后端提供 schema-neutral DSL commit API：`POST /api/visual/dsl-imports/commit`。它接受与 preview 相同的 request，服务端重新投影 DSL，而不是信任浏览器临时 draft，然后保存为 governed `GraphDraft` revision，并返回 validation / dependency report。
- 已落地：浏览器 `/author/` 左侧提供 **Legacy DSL** 面板。用户粘贴 DSL 后点击 `Render DSL`，画布会直接渲染 preview draft，并同步节点、边、Graph Contract、Runtime Context 变量表、Test Suite 初始行和 Export Draft。
- 已落地：用户点击 `Commit Draft` 后，画布会调用 commit API 保存正式 draft；成功后提示 `Stored draft <draftId> @<revision>`，当前 Export Draft 会带上 stored draft identity 和 source map。
- 已落地：如果 Library 面板当前内容是 JSON 形式的合法 `bloge.visualOperatorLibrary.v1`，Render DSL 会把它作为 `inlineLibraries` 随 preview 一起提交；如果已经 Import 入库，则会通过当前 catalog 的 `operatorLibraryIds` 参与解析。
- 已落地：如果 Library 面板当前内容是 `bloge.capabilityCatalog.v1`，点击 `Adapt Catalog` 会调用 `POST /admin/visual-operator-libraries/from-capability-catalog-text`，把 framework export 预览投影为标准 `bloge.visualOperatorLibrary.v1` 草稿并回填到输入框。后续仍然点击 `Validate` / `Import`，因此画布渲染边界没有绑定到 capability catalog。
- 已落地：Legacy DSL 面板会展示 source map 行列表。点击 `node` / `binding` / `edge` 行可以选中对应画布节点，导出的 draft 也会在 `visualLayout.import.sourceMap` 保留源码行列映射。
- 已落地：Legacy DSL preview 会返回 `roundTrip` 状态。服务端会把源 DSL 投影成 draft，再用 `GraphDraftDslGenerator` 生成 DSL、重新解析并再次投影，比较两份 canonical visual semantics。状态会在页面显示为 `SUPPORTED`、`DRIFT`、`PARTIAL` 或 `NOT_ASSESSED`。topology-only draft 通常会停在 `PARTIAL` 或 warning 状态：可用于理解拓扑和迁移审阅，但不能当作自动源码替换或可执行发布证据。
- 已落地：`POST /api/visual/dsl-imports/rewrite-gate` 和 `/author/` 的 `Check Rewrite` 按钮。它复用同一个 schema-neutral request，返回 `ALLOW_REWRITE`、`BLOCK_SEMANTIC_DRIFT`、`BLOCK_INCOMPLETE_EVIDENCE` 等判定和 generated DSL；这个 gate 只做预检，不保存 draft，也不会改写源码文件。
- 已落地：后端提供仓库级批量迁移报告 API：`POST /api/visual/dsl-imports/batch-report`。它复用同一套 schema-neutral catalog/inlineLibraries 输入，但接受多份 `sources[]`，逐份返回 renderable / fullyProjected / needsRepair / rewrite decision，并聚合 coverage、round-trip status、diagnostic level 和 rewrite decision 计数，适合 CI 或迁移前评估。
- 已落地：后端提供批量迁移保存 API：`POST /api/visual/dsl-imports/batch-commit`。它接受与 batch-report 相同的 `sources[]` 和 schema-neutral catalog view，并按 `commitPolicy=renderable|fully-projected|rewrite-allowed` 把合格 source 服务端重投影后保存为 governed draft revision；它不写 `.bloge` 源文件，也不创建 VCS PR。
- 已落地：仓库根目录提供 `scripts/bloge-dsl-batch-import.sh`，迁移负责人可以从命令行或 CI 直接调用 batch-report / batch-commit。脚本支持 `--dsl-dir` 扫描 `.bloge` 文件、`--operator-library` 引用已导入算子库、`--inline-library-json` 临时传入标准 visual library、`--fail-on` 设置 CI gate，以及 `--dry-run` 生成请求体。
- 已落地：React authoring API client 已有 `batchReportDslImports()` / `batchCommitDslImports()` 类型化封装，后续 Studio dashboard 可以直接复用同一 wire contract。
- 未落地：真正写回业务代码库或覆盖原 `.bloge` 文件的 source writer / VCS 集成。当前系统只告诉调用方“是否可安全自动替换”，不直接动用户源码。

设计方案见 [存量 BLOGE DSL 业务迁移到可视化编排设计方案](./bloge-legacy-dsl-visual-migration-design.md)。

从 framework capability catalog 生成 visual library 草稿后，Library 面板会像下图这样展示：

![Capability catalog adapter 标注](assets/bloge-author-capability-adapter-annotated.svg)

1. **Generated visual library draft**：`Adapt Catalog` 成功后，输入框会从 `bloge.capabilityCatalog.v1` 自动回填成标准 `bloge.visualOperatorLibrary.v1`，后续所有画布能力都基于这个标准合同。
2. **Capability catalog example**：内置示例展示了业务代码导出的 framework catalog 形态，适合演示存量业务从代码 schema 进入画布。
3. **Adapt Catalog**：只做 schema acquisition preview，不写入 registry；要正式加入 palette 仍需继续 `Validate` / `Import`。
4. **Adapter result notice**：显示 projected operator/function 数和 coverage；如果有端口 schema 无法投影，会显示 opaque schema fallback 数。

Legacy DSL 面板会像下图这样展示；没有 schema 时先看 topology-only，补齐 schema 后再看 schema-backed：

![Legacy DSL source map 标注](assets/bloge-author-legacy-dsl-source-map-annotated.svg)

1. **Existing .bloge DSL**：这里粘贴或加载存量 `.bloge` 文件。示例中 DSL 声明了 graph 级 `input` / `output`，这些 schema 会进入 Graph Contract。
2. **Render topology first**：点击 `Render DSL` 后，服务端先按 DSL AST 渲染拓扑。没有 operator/function schema 时仍会显示节点、边、输入绑定和 source map，并标记为 `topology-only projection`；导入 schema 后再增强为更精确的 schema-backed draft。`Check Rewrite` 会基于同一份 DSL + schema view 判断 generated DSL 是否可安全替换源文件；`Commit Draft` 会用同一 request 在服务端重新投影并保存为正式 draft revision。
3. **Round trip 状态**：覆盖率数字下方会出现 Round trip 面板。`SUPPORTED` 表示生成 DSL 再解析后仍得到同一份 canonical visual semantics；`DRIFT` 表示生成 DSL 可解析但语义指纹不同；`PARTIAL` 表示生成、解析或投影证据不足，需要先修复诊断。
4. **Source map refs**：source map 会列出节点、输入绑定和数据边对应的 DSL 行列与源码片段，便于迁移审阅。
5. **Click row to select node**：点击 source map 行会选中对应画布节点；如果缺 operator/function schema，系统仍尽量渲染图，并在同一区域显示 diagnostics。

页面上的当前操作方式：

1. 在左侧 **Legacy DSL** 面板确认 `Source`，粘贴 `.bloge` 文件内容，或使用内置 `Eligibility DSL` 示例。
2. 点击 `Render DSL`。服务端解析 DSL，并先按 DSL AST 投影成可读的 `GraphDraft` 拓扑。
3. 如果还没有 operator/function schema，画布会提示 `topology-only projection`。这不是失败：节点、边、输入绑定、函数调用文本和 source map 已可用于理解整体业务逻辑。
4. 需要更精确校验时，再到左侧 **Library** 面板导入或粘贴一份合法 `bloge.visualOperatorLibrary.v1`。如果只是想临时 preview，可以粘贴 JSON 形式 library，不必先 Import。
5. 如果当前输入是业务项目导出的 `bloge.capabilityCatalog.v1`，点击 **Adapt Catalog**。系统会生成标准 `bloge.visualOperatorLibrary.v1` JSON 草稿；检查 notice 中的 operator/function/opaque schema 数后，再点击 `Validate` / `Import`。
6. 画布渲染节点和边；Graph Contract 同步显示 DSL `input` / `output`；Runtime Context 会根据 input schema 生成变量行。
7. 若出现 missing operator/function，画布仍会尽量渲染结构，并在 Legacy DSL 面板显示 warning diagnostics；补齐 schema 后再次 Render。
8. 查看 Round trip 面板：`SUPPORTED` 可作为后续回写的低风险证据；`DRIFT` / `PARTIAL` 说明当前更适合先作为可视化迁移 draft 审阅，不应直接覆盖原 DSL。
9. 点击 `Check Rewrite`。如果返回 `ALLOW_REWRITE`，说明 generated DSL 与源 projection 具有相同 canonical visual semantics，可交给外部源码回写工具继续处理；如果返回 drift/partial/import diagnostic block，不要自动覆盖原 `.bloge`。
10. 在 Source map 中点击行定位节点，确认 DSL 片段和画布元素对应关系。
11. 如果确认迁移结果可作为资产继续协作，点击 `Commit Draft`，把 DSL 投影保存为 stored draft/revision；如果只是临时审阅，可以跳过保存。
12. 继续使用 Auto Layout、Validate、Simulate、Operator Test Suite、全图 Test Suite 和 Export Draft。

对应 API 的最小调用方式：

```http
POST /api/visual/dsl-imports/preview
Content-Type: application/json
```

```json
{
  "sourceId": "loan-approval.bloge",
  "dsl": "graph loanApproval { node eligibility : \"risk:eligibility\" { input { score = ctx.score } } }",
  "catalogIds": ["risk-policy"],
  "inlineLibraries": [],
  "mode": "preview",
  "layout": {}
}
```

保存为正式 draft 时使用同一请求体，只需改调用路径和 `mode`：

```http
POST /api/visual/dsl-imports/commit
Content-Type: application/json
```

```json
{
  "sourceId": "loan-approval.bloge",
  "dsl": "graph loanApproval { node eligibility : \"risk:eligibility\" { input { score = ctx.score } } }",
  "catalogIds": ["risk-policy"],
  "inlineLibraries": [],
  "mode": "commit"
}
```

commit 会返回 `bloge.visualGraphDraftImportResult.v1`，其中 `draft.draftId` / `draft.revision` 是 repository 分配的正式身份，`draft.visualLayout.import.sourceMap` 会保留源码映射。parse failure 或 unsupported root 会拒绝保存；missing operator/function 会保存为可修复迁移 draft，并通过 validation/dependency diagnostics 暴露。

检查 generated DSL 是否可作为源码替换候选时，使用同一请求体，只需改调用路径和 `mode`：

```http
POST /api/visual/dsl-imports/rewrite-gate
Content-Type: application/json
```

```json
{
  "sourceId": "loan-approval.bloge",
  "dsl": "graph loanApproval { node eligibility : \"risk:eligibility\" { input { score = ctx.score } } }",
  "catalogIds": ["risk-policy"],
  "inlineLibraries": [],
  "mode": "rewrite-gate"
}
```

rewrite gate 返回 `bloge.dslRewriteGate.v1`。`allowed=true` / `decision=ALLOW_REWRITE` 表示 generated DSL 可交给外部工具进入源码替换流程；其他 decision 会携带 round-trip 和 diagnostics 说明阻断原因。这个接口不会持久化 draft，也不会直接修改 `.bloge` 文件。

如果要评估一个业务仓库里的多份 DSL，不要循环调用 UI。使用批量报告接口：

```http
POST /api/visual/dsl-imports/batch-report
Content-Type: application/json
```

```json
{
  "catalogIds": ["risk-policy"],
  "inlineLibraries": [],
  "mode": "batch-report",
  "includeDrafts": false,
  "sources": [
    {
      "sourceId": "loan-approval.bloge",
      "dsl": "graph loanApproval { ... }"
    },
    {
      "sourceId": "fraud-review.bloge",
      "dsl": "graph fraudReview { ... }"
    }
  ]
}
```

返回 `bloge.dslImportBatchReport.v1`。重点看：

| 字段 | 含义 |
| --- | --- |
| `summary.renderableSourceCount` | 能成功 parse/project 成 graph draft 的 DSL 数量 |
| `summary.fullyProjectedSourceCount` | 无 import error、无 missing operator/function、无 unsupported syntax 的 DSL 数量 |
| `summary.repairableSourceCount` | 已渲染但需要补 schema 或处理 loss-aware diagnostic 的 DSL 数量 |
| `summary.blockedSourceCount` | parse failure 或 unsupported root，当前不能进入可视化 draft 的 DSL 数量 |
| `summary.rewriteAllowedSourceCount` | 可进入 source replacement 流程的 DSL 数量 |
| `items[].rewriteDecision` | 每个文件的 `ALLOW_REWRITE` / `BLOCK_*` 机器可读结论 |
| `items[].coverage` | 每个文件的 member/node/edge/missing/unsupported 覆盖率 |

如果要把一批可接受的 DSL 直接沉淀成 governed draft，不要让迁移脚本循环调用单文件 commit。使用批量保存接口：

```http
POST /api/visual/dsl-imports/batch-commit
Content-Type: application/json
```

```json
{
  "catalogIds": ["risk-policy"],
  "inlineLibraries": [],
  "mode": "batch-commit",
  "commitPolicy": "renderable",
  "sources": [
    {
      "sourceId": "loan-approval.bloge",
      "dsl": "graph loanApproval { ... }"
    },
    {
      "sourceId": "fraud-review.bloge",
      "dsl": "graph fraudReview { ... }"
    }
  ]
}
```

返回 `bloge.dslImportBatchCommitResult.v1`。重点看：

| 字段 | 含义 |
| --- | --- |
| `summary.committedSourceCount` | 已保存为正式 draft revision 的 DSL 数量 |
| `summary.skippedSourceCount` | 因 parse/root/policy gate 被跳过的 DSL 数量 |
| `summary.failedSourceCount` | 满足保存策略但 repository 写入失败的 DSL 数量 |
| `summary.reportSummary` | 同一批 source 的 render/repair/rewrite readiness 汇总 |
| `items[].commitDecision` | `COMMITTED_*`、`SKIP_*` 或 `FAILED_PERSISTENCE` |
| `items[].importResult` | 单个 source 的 `bloge.visualGraphDraftImportResult.v1`，包含 draft identity、validation 和 dependency report |

`commitPolicy` 的选择：

| 策略 | 何时使用 |
| --- | --- |
| `renderable` | 默认策略。只要 DSL 能渲染成 graph draft 就保存；missing operator/function 会成为可修复迁移 draft |
| `fully-projected` | 只保存无 missing/unsupported/import error 的 DSL，适合迁移验收基线更严格的团队 |
| `rewrite-allowed` | 只保存 rewrite gate 通过的 DSL，适合后续马上接 source replacement / VCS PR 的低风险批次 |

命令行/CI 推荐直接使用批量迁移脚本。先启动服务并导入所需算子库，再运行：

```bash
./scripts/start-visual-canvas-demo.sh --port 18080

./scripts/bloge-dsl-batch-import.sh report \
  --base-url http://localhost:18080 \
  --operator-library risk-policy \
  --dsl-dir resource-gateway-examples/src/main/resources/bloge/gateway \
  --out target/dsl-batch-report.json \
  --fail-on blocked
```

如果迁移策略要求只有语义往返通过的 DSL 才能进入 governed draft，可以改用：

```bash
./scripts/bloge-dsl-batch-import.sh commit \
  --base-url http://localhost:18080 \
  --operator-library risk-policy \
  --dsl-dir resource-gateway-examples/src/main/resources/bloge/gateway \
  --commit-policy rewrite-allowed \
  --out target/dsl-batch-commit.json \
  --fail-on skipped-or-failed
```

脚本不会生成或绑定 schema。它只把已经合法的 schema view 与 `.bloge` 文件打包成同一份
batch API 请求：`--operator-library` 指向已导入的 visual library；
`--inline-library-json` 只接受标准 `bloge.visualOperatorLibrary.v1` JSON 对象；
如果上游手里是 `bloge.capabilityCatalog.v1`，仍需先通过 Library 面板或
`/admin/visual-operator-libraries/from-capability-catalog-text` 适配成 visual library。
调试时可以加 `--dry-run`，先确认最终请求体：

```bash
./scripts/bloge-dsl-batch-import.sh report --dry-run \
  --operator-library risk-policy \
  --dsl resource-gateway-examples/src/main/resources/bloge/gateway/loan-decision-policy.bloge
```

返回重点字段：

| 字段 | 含义 |
| --- | --- |
| `draft` | 可被画布渲染的 `bloge.visualGraphDraft.v1`；普通 node、transform、decision_table 会被投影成可编辑节点 |
| `draft.inputSchema` | DSL graph 级 `input { ... }` schema |
| `draft.outputSchema` | Graph 对外集成的正式 selected-payload 输出合同；从 DSL 导入时可来自 `output { ... }` |
| `draft.visualLayout.graphContract.outputSchema` | 输出 schema 的 UI/历史兼容副本；旧 draft 只有这个字段时，前后端会自动回填到一等 `outputSchema` |

需要区分两个同名但不同层级的概念：GraphDraft `outputSchema` 描述
`output.nodeId + output.path` 选中后的业务 payload；BLOGE DSL `output { ... }` 描述按 terminal
node id 聚合的引擎内部 Map。Visual DSL lowering 不会把前者直接写成后者，否则 transform 等
节点会产生虚假的字段缺失 warning。正式输出合同仍随 GraphDraft、publication 与 integration
bundle 导出，并由 simulation/publication runtime 在选中 payload 后做精确 schema 校验。
| `draft.visualLayout.import.projectionMode` | `schema-backed` 表示 operator/function schema 已绑定；`topology-only` 表示系统只从 DSL AST 推演拓扑、绑定和表达式文本 |
| `draft.visualLayout.import.operatorRefs/functionNames` | 本次 DSL 中扫描到的 operatorRef 与 built-in function 调用名 |
| `draft.visualLayout.import.missingOperatorRefs/missingFunctionNames` | 当前 effective catalog 中缺失的 operator/function；缺失时仍可看拓扑，但不能当作精确 schema 或自动回写证据 |
| `sourceMap.nodes/edges/bindings` | visual 元素到 DSL 行列的映射 |
| `coverage` | member、node、edge、missing operator/function、unsupported syntax 数量 |
| `roundTrip` | 本次 preview 的语义往返证据，包含 `status`、`message`、`generatedDsl`、`sourceFingerprint`、`generatedFingerprint` 和 generation/reparse diagnostics |
| `diagnostics` | parse、missing operator、missing function、unsupported syntax、schema ref 等迁移诊断 |
| `DslRewriteGateResult.allowed/decision/generatedDsl` | `rewrite-gate` 的源码替换预检结论、机器可读阻断原因和本次评估的 generated DSL |

`roundTrip.sourceFingerprint` / `generatedFingerprint` 比较的是 canonical visual semantics：
graph name、`draft.inputSchema`、一等 `draft.outputSchema`、节点输入/config、边和 graph output
selection。坐标、source map、fixtures、描述文本和 `visualLayout.graphContract.outputSchema`
兼容副本不参与语义等价判断。

注意：如果 DSL operatorRef 含有冒号，BLOGE DSL 里要用字符串形式，例如 `node eligibility : "risk:eligibility"`；否则冒号会被 DSL 语法当成节点 id 与 operatorRef 的分隔符。

更完整的迁移专线落地后，还应补齐：

1. 对 missing operator、missing function、opaque schema 或 unsupported syntax 给出更细的修复向导和 source snippet。
2. 需要真正覆盖原 `.bloge` 文件时，把 `rewrite-gate` / `batch-commit` 的 allow/commit 结论接到外部 source writer、VCS PR 或人工 reviewed 流程；不等价或证据不足时不能自动覆盖原文件。
3. 把 resource-gateway 示例里的 capability adapter 沉淀成 BLOGE framework / Studio 可复用的 adapter SPI。

这条路径的关键产品承诺是 **loss-aware import**：能结构化投影的 DSL 会变成可编辑画布节点；暂不支持的复杂语义会保留成带 source snippet 的 opaque 节点或诊断项，不会静默丢失。

### 5.3 第三步：把算子放到画布上

在 palette 中可以点击算子添加，也可以拖拽到 canvas。每个节点卡片会显示：

- 业务展示名和 `operatorRef`。
- 输入/输出端口数量。
- design-only/runtime-blocked/ready 等状态。
- typed handles：输出端口在一侧，输入端口在另一侧。

当画布变乱时，点击 Auto Layout。新版画布会用确定性布局生成候选，并在真正应用前展示
当前与候选的可读性差异。同一依赖层内按上游/下游重心排序，列间距会为边标签预留空间，
行间距会按节点卡片高度和必要空白展开。它的目标不是把所有节点压到最小面积，而是在保持
信息密度的同时避免算子挤在一起、边上的 `source.path -> target.path` 被遮挡。

小型分层图的节点安全带和生成间距使用同一组几何常量：列间保留两侧各 `24px`，同列节点
按卡片高度加两侧安全带排布；跨两列以上的长边走 bus lane。标签之间另有 `8px` 模型安全带，
用于抵消多语言换行、边框和小数缩放。内置贷款示例在生产包真实浏览器中达到
`80% / PASS / 12px title`，且节点覆盖、标签覆盖和视口裁切均为 0。

Auto Layout 不再把“没有节点重叠”当成唯一成功标准。候选如果降低小图缩放比例、让标题
小于 12px、增加标签碰撞或显著扩大图边界，默认 **Apply** 会被禁用，并建议保留当前布局。
高级覆盖仍然存在，但需要通过 **Advanced > Apply anyway** 明确触发；应用后仍可用
**Undo layout** 恢复精确坐标。

如果 Auto Layout 后仍需要更大视野审阅拓扑，点击工具条里的 `Canvas Focus`。Focus 模式会收起左右辅助栏、顶部 workflow 和示例卡，保留 toolbar、Graph Contract 与主画布；退出时点击 `Exit Focus`。这个模式适合检查跨层依赖、边标签、复杂 decision table 上下游，以及给业务方演示图结构。

### 5.3.1 定义 Graph Input、填写 Run Input、绑定节点输入

起始节点通常没有上游边，但仍然需要 `userId`、`orderId`、`applicant.score` 或租户
信息。任务式工作台把过去容易混淆的“schema、样例值、节点绑定”拆成三个独立问题：

- **Graph Input Contract**：这张图允许调用方传什么，是可导出、可版本化的接口定义。
- **Run Input Values**：这一次模拟实际传什么，是短生命周期的运行数据。
- **Node Input Source**：某个算子字段来自 Graph Input、上游节点还是常量，是
  GraphDraft 中持久化的依赖语义。

使用 `http://localhost:8080/author/`，加载 `Loan policy fallback`
示例、选中 `Decision response`，再打开右侧 `Data`，页面应与下图一致：

![Effective Contract、字段来源与 Graph Run Input](assets/resource-gateway-author-ux-stage2-effective-contract-1024.png)

对着图操作：

1. **稳定任务页签**：`Config / Data / Scenarios / Contract / Advanced` 不随内容跳动。
   修改接口定义进入 `Contract`；准备运行数据和检查来源进入 `Data`。
2. **Effective Contract 摘要**：四个数字分别是 declared、inferred、bound、observed。
   它们不会合并成一个看似精确的 schema。Loan 示例应稳定显示 `1 / 7 / 7 / 0`；
   运行后 observed 单独增加，不能静默进入 authored Contract。
3. **字段来源与追溯**：Input sources 逐字段显示 target、上游 source、类型、置信度和
   连接状态。点击行尾 `>` 会选中真实上游节点并更新 URL 的 `nodeId`，便于分享和排障。
   GraphDraft 同时携带 edge 与其 `nodePath` 投影时只显示一次；不同来源才报告冲突。
4. **推断输出与显式接受**：双击 Transform 或 Decision Table，进入 `Contract` 可比较
   declared / inferred / observed 输出。点击 **Accept as Graph Output Contract** 才会把
   inferred 字段写入开放式 output schema；系统不会推断 required，也不会接受 observed。
5. **来源编辑与输入绑定**：低频 `ctx`/constant/expression 配置收在
   **Edit direct bindings**。选择节点后，Run Input 字段旁出现 `Bind`，点击即可把该
   Graph Input 绑定到当前节点的默认 target port/path。
6. **Schema 生成运行输入**：`Run Input Values` 由 Graph Input Contract 自动生成
   string、number、enum、boolean、array/object 等控件；状态条显示
   `N required, M missing` 或 `N required, complete`。缺必填值、类型不匹配、
   enum/范围不合法或出现 schema 禁止的额外字段时，运行准备状态会 fail closed。

`Bind` 不是再叠一条模糊的数据来源。如果目标 port/path 已有上游边或直接绑定，画布
会替换该来源并删除冲突入边，确保导出的 GraphDraft 只有一个有效来源。默认 target
port 取算子的第一个输入端口，target path 取 Graph Input 路径的最后一段；复杂映射
仍可在 `Node Inputs` 中显式调整。

例如，一个风控起始节点要从运行上下文读取 `applicant.score`，导出的 draft 会包含：

```json
{
  "inputs": {
    "score": {
      "kind": "contextPath",
      "path": "applicant.score",
      "targetPort": "inputs",
      "targetPath": "score"
    }
  }
}
```

模拟时，`Run Input Values` 会生成本次 run 的 JSON context，例如：

```json
{
  "applicant": {
    "score": 720
  }
}
```

Run Input 会进入 `POST /api/visual/graphs/simulate` 的 `context` 字段，但不会写进导出的
`GraphDraft`。导出的 draft 只保存 `contextPath` / `constant` 等输入绑定语义，真实运行
时由调用方提供对应值。

`Advanced` 中有两层逃生口：

1. `Context Extras` 用于 schema 允许但暂未建模为主要业务字段的附加 context；它与
   Run Input 合并，字段冲突会 fail closed，不会静默覆盖。
2. `Use raw runtime context` 是显式 takeover。勾选后 raw JSON 完全替代 Run Input 和
   Context Extras；默认关闭，避免普通用户在无意间绕过 schema-driven controls。

敏感字段通过 `writeOnly`、`format: password`、`x-sensitive` 或数据分类标记识别，
控件默认遮罩且关闭浏览器自动填充。旧版 `authorWorkspace=legacy` 的 Context Variables
只保留用于兼容和回滚，不再是 v2 的推荐工作流。

### 5.4 第四步：连线

从一个节点的输出 handle 拖到另一个节点的输入 handle。拖拽过程中，画布会调用：

```text
POST /api/visual/connections/candidates
```

服务端返回哪些目标 ready、哪些 blocked、哪些 already wired。真正落线时再调用：

```text
POST /api/visual/connections/check
```

只有服务端 accepted 的连接会写入 draft。这样可以避免浏览器本地规则和后端 validator 分叉。

常见 blocked 原因：

- 输出 schema 不能赋给目标输入 schema。
- 目标 required input 已经被别的边占用。
- path/port 名称不是 DSL-safe。
- draft 里存在阻断级诊断，导致连线后图仍不可用。

### 5.4.1 双击查看算子详情与专属编辑

新版 `/author/` 的双击行为统一了：**每个画布节点都可以双击打开 Operator Detail 浮层**。浮层不只是“查看详情”，也承担节点级编辑入口：能直接改关键属性、配置 input binding、维护 output/expected input 样例、管理该节点独立的 Operator Test Suite，再根据算子族展开专属编辑器。

![Author Operator Detail 浮层标注](assets/bloge-author-operator-detail-annotated.svg)

1. **任意节点双击**：resource、http resource、foreach、decision table、transform、用户导入的 design operator 都走同一个详情浮层入口。
2. **关键属性可编辑**：所有节点都能改显示 label；resource/http 节点还提供 Resource ID、Method、URL/route、Timeout 等常用运行属性输入框，写回节点 `config`。
3. **图形化输入绑定**：浮层内置 `Node Inputs`，可以 Add Binding、选择 `ctx` 或 `constant`、配置 Target port/path，也能接收 Runtime Context 变量 chip 拖拽。
4. **Input/Output 样例**：Output sample 和 Expected input 可以在浮层内直接维护，写入 `GraphDraft.nodeFixtures`，用于 mock simulate 和表格测试。
5. **Executable Operator Suite**：每个节点都有自己的表格测试数据，按行维护 Input case、Expected output 和 `Golden / Negative / Boundary / Regression` 测试意图；resource/http 算子还会显示 Transport response。`Run Case / Run Exploratory` 以 inline fixture 快速执行单节点微图；`Publish Case + Run / Publish Suite + Run` 把行发布为内容寻址的不可变 fixture 与一等 TestSuite revision，再执行该精确 revision；Apply Fixture 只把该行套用为当前节点的 Expected input / Output sample。
6. **Schema 摘要优先**：每个端口先显示 schema 类型、字段数和字段表；Raw schema 仍可展开查看，避免用户一上来就读大段 JSON。
7. **专属交互区**：decision table 和 transform 会在同一浮层内展开可编辑区域；foreach 会展开循环向导；generic/design operator 保留高级 config JSON 入口。

#### 用 Scenario 固化业务正确性并阅读 Run Evidence

在 v2 工作区加载内置复杂示例后，点击顶部 **Contract** 会直接切换中央 Contract 工作面；
再点击顶部 **Scenarios** 进入同级 Scenario 工作面，不会打开 modal。示例自带
两个可运行场景。多场景默认打开 **Matrix**，单场景才默认打开 **Case**：

1. Matrix 按 `CASE / GIVEN / DEPENDENCY / THEN / PROOF` 分组展示。搜索、类型/判定筛选、
   排序与列显示只改变视图，不改变测试资产或已选 case。
2. 勾选任意行后使用 **Run selected**；**Run all** 始终按 canonical 顺序建立完整 baseline；
   **Run failed (N) / Run changed (N) / Run affected (N)** 分别按完整 baseline 的失败、case
   fingerprint 变化，以及失败/变化/Graph 或 Contract 影响选择。数量为 0 时按钮解释性禁用，
   当前筛选结果永远不会被误当运行集合。
   按钮会直接显示数量，例如 **Run selected (2)**、**Run all (3)**。顶部全局栏不会再从
   Matrix 偷偷运行一个不可见的“当前 Case”；进入 Contract、Scenarios 或 Evidence 后，动作
   由当前工作面所有。Case 详情的按钮则明确写成 **Run current case**。
3. Name、Case type、tags 和可标量编辑的 Given 单元格直接写回 canonical Scenario。点击
   **Open** 或聚焦行后按 Enter 进入同一个 case 的详情，不会维护第二份表格值。
4. 每行 Proof 独立显示 Execution、Assertions、Freshness、Proof strength 和 Duration；
   **Why** 展开首个失败的类别、路径与原因。`Schema valid`、`Mock behavior matched` 和
   runtime assertion pass 不会被压扁成含义不清的 `Passed`。
5. 500 case 只先渲染 50 行，再由 **Show next 50 cases** 显式推进。390px 视口保留表格
   局部横向滚动和完整批量操作，不让宽表撑宽页面。

点击任一 Matrix Run 后仍留在 Matrix。顶部 **Server batch** 显示 exact closure、Passed、Failed、
Waiting 和 Promotion；运行中可 **Cancel**，终态失败可 **Retry failed**。retry 追加 attempt，失败后
成功会显示 flaky；partial selection 永远显示 **Partial only**，不能冒充 full-suite promotion。
刷新/导航恢复同一 Scenario coordinate 后，页面按 batch id 恢复 durable progress 并继续消费
revision delta。全局 Diagnostics 不会抢占表格空间。需要审阅一条 case 的完整 request/response 与断言证据时，
点击 **Open** 进入 Case，再执行 **Run current case**。单 case 运行完成后才自动进入
**Run Evidence**。

运行前，Matrix 主按钮在 DOM 与可访问名称中同时携带 `SELECTION / SUITE`、精确 Case 数量和
不含 payload 的 preview fingerprint；运行开始后选择框锁定，筛选变化也不会改写已提交计划。
运行后以 Server batch 中的 exact case ids 与 canonical selection fingerprint 为权威回执。
preview fingerprint 用于界面状态关联，不应冒充服务端 canonical fingerprint。点击后 Matrix
顶部会出现 **Command receipt / 命令回执**：`SUBMITTED` 表示请求已发出，`ADMITTED` 表示服务端
已接纳或本地执行已开始，`TERMINAL` 表示该命令已到达终态。`Correlation ID` 从提交请求一直
保留到 Server batch 与每一行的 **Inspect -> Proof**；展开行时可以用它确认当前结果确实属于
刚才的批次。单 Case 的 **Run Evidence** 也显示同样的 scope、Case 数量和关联 ID。

`Intent fingerprint` 是点击时的 UI 意图坐标，`Canonical fingerprint` 是服务端按 baseline 和
canonical order 解析后的实际闭包坐标；两者可能不同但必须由同一 `Correlation ID` 关联。本地
示例没有服务端 admission，因此二者相同并标为 `LOCAL`。receipt 只携带 ID、数量、状态和指纹，
不会把 Scenario input、fixture 或 actual output 复制进 Matrix 或浏览器持久化。

第一次建立 baseline 的完整操作顺序是：加载复杂示例 -> **Scenarios** -> **Save Graph** ->
**Review compatibility** -> 勾选已审阅并 **Rebase local draft** -> 返回 **Scenarios / Matrix** ->
**Run all**。只有所有行都有结论且通过，Server batch 才显示 **Promotion Eligible**；取消或达到
failure budget 的残缺 full selection 不会成为后续差分基线。Matrix 和浏览器存储只保留
payload-free fingerprints/status；Expected/Actual 业务值只在有权限的 Case Evidence 中读取。

#### 从 CSV / JSON 导入表格测试数据

外部表格不会成为运行时数据源。系统先把一个受限 source snapshot 物化为 canonical Scenario，
后续运行只读取 Scenario：

1. 首次加载内置 Graph 时先到 **Contract** 点击 **Save Graph**；若顶部提示 Contract changed，
   到 **Compatibility** 完成 review 并点击 **Rebase local draft**。未保存或 stale 时 Matrix 的
   **Import cases** 会禁用，并在悬停时说明阻断原因。
2. 进入 **Scenarios → Matrix → Import cases**。选择 CSV/JSON 文件、粘贴文本，或点
   **Load sample** 生成与当前 Scenario template 对齐的 5 行 JSON。
3. **Inspect source** 只生成 ephemeral preview：显示行列、类型、missing/null/empty 统计、
   formula warning；敏感字段只显示 `[masked]`。
4. **Map columns** 自动匹配 case metadata、Given、dependency output 和 assertion expected。
   exact path/name 自动确认；normalized guess 必须勾选 Confirm。每列可选 converter，并明确
   empty cell 是保留空串、设 null、删除字段、使用 default，还是普通 value。
5. **Review plan** 检查行数、binding、业务身份列、classification 与更新 diff，再点击
   **Materialize N cases**。服务端会用 Commons CSV/Jackson 重新解析原 source，并重新核对
   source/mapping/Contract/target/selection 指纹。
6. **Receipt** 显示 accepted/rejected 数、plan/source/mapping/Contract fingerprint 和 actor。
   点击 **Done** 返回 Matrix；新增 case 自动选中，可立即 **Run selected**。

receipt 不保存文件、cell value 或业务主键原文；每行只保留 `identityFingerprint`、
`rowFingerprint`、生成的 scenarioId、status 和 payload-free diagnostic code。同一 exact plan
重试返回同一 durable result。四份协议 schema 位于 `docs/schemas/bloge-scenario-*materialization*`。

![Scenario 表格导入 receipt（桌面）](assets/resource-gateway-scenario-import-receipt-1280.png)

该物化端点为 `POST /api/visual/scenario-imports/materialize`，只在 `test` / `staging` testing
control plane 组装。`/api/integration/capabilities` 中
`scenarioImportMaterializationProtocol=true` 表示部署理解协议；只有
`scenarioImportMaterializationApi=true` 才表示当前部署允许调用。

每个 Case 都由三段组成：

1. **Given** 按 Graph/Operator input Contract 生成业务输入控件。
2. **Dependencies** 的 Target type、具体 Canvas node / Operator / Resource / Built-in
   function，以及 `Real / Return / Error / Delay / Timeout / Replay / Observe / Deny` 都在
   卡片首屏。只有 graph path、correlation、attempt/occurrence、input matching 和
   consumption 才放在 **Selector, matching & consumption**。
3. **Then** 的 Result field / Node output field 来自对应 output schema。用户选择字段后，
   系统按字段类型生成 expected value；只有 schema 外路径才需要 **Custom path**。

在 Case 中点击 **Run & Compare** 后会自动进入 **Run Evidence**。阅读顺序固定为：

1. 先看全局结论：`Promotion blocked`、`Review required`、`Evidence incomplete` 或
   `Ready for promotion`；
2. 再看 **Next actions**：每条动作直接说明 root cause、business impact、owner、
   required role 和可执行入口；
3. 再看 Execution、Assertions、Contract、Governance 四维状态；
4. blocking finding 与 warning 位于通过证据之前；
5. 失败断言默认展开 Expected / Actual / 路径级 Diff，并可直接
   **Repair Scenario assertions**；
6. `Technical coordinates` 默认折叠，展开后才显示 fingerprint 和 request coordinate；
7. 通过断言默认折叠，Terminal output 与 Node status 作为底层明细。

动作遵循权限诚实原则。当前工作区能处理的 Scenario、Contract、Compose 或诊断问题会显示
按钮并保留 exact draft/scenario/node 坐标；外部治理系统提供 `deepLink` 时显示真实
handoff。若当前 revision 没有 handoff，页面只显示治理 owner、required role 和缺失原因，
不会提供一个无法完成的假按钮。

只有四个维度都通过且没有 blocking/warning 时，页面才显示 `Ready for promotion`。
Assertion 通过但 Contract 或 Governance 尚未检查时只能显示 `Evidence incomplete`；服务端
返回 warning 时显示 `Review required`。这一规则同时用于 Graph 和 Operator workspace，
避免“试跑成功”被误解为“可以发布”。

顶部 **Run scenario** 会运行当前选中的 Scenario，并直接打开这一页，不需要先打开或
关闭另一个 Test Suite 浮层。顶部 **Review result** 会回到同一份 Evidence，并自动选择
最后实际执行的 Scenario。Evidence 同时绑定该 Scenario 的 assertion comparison 和
terminal output，不会把两条 case 的结果混在一起。未保存的示例运行会显示：

```text
Exploratory run · <content fingerprint> · simulation evidence only
```

这类运行适合调试和演示，但 Contract / Governance 未检查时仍显示
`Evidence incomplete`。只有显式保存 Graph、固定 revision 并完成治理闭环后，运行证据
才具备晋级资格。正常切换 Compose / Contract / Scenarios / Evidence 或选择节点不会
触发 deep-link 告警；只有 URL 中存在 `draftId` 或 `runId` 时才会恢复持久化外部坐标。
工作台 URL 还会保留 `authorMode`、`target`、`nodeId`、`workspaceView`、`scenarioId`
与 `runId`，便于从
治理系统直接回到准确的 Graph/Operator、Scenario 和 Evidence。

![Author Workspace v2 的统一 Scenario Evidence](assets/resource-gateway-author-ux-stage1-evidence-1024.png)

上图是 1024px 真实浏览器结果：`loan-prime-approval` 的 Execution 与 Assertions 通过，
但 Contract / Governance 尚未检查，所以总判定保持 `Evidence incomplete`。顶部没有
第二套 Test Suite 入口，长 Graph 名完整可读，且页面没有横向溢出。

#### 直接为单个 Operator 编写 Contract Scenario

Operator Detail 标题栏现在提供 **Contract & Scenarios**。它不是旧表格测试的别名，而是把
Operator 当成一等 Contract target，复用与 Graph 相同的四页工作台：

1. 双击任意目录中存在的节点，点击 **Contract & Scenarios**。
2. **Interface** 从服务端算子目录读取权威 input/output port Schema，并显示 effect、
   idempotency、streaming 和 durable 语义。Operator Contract 在这里只读；要修改定义，
   应更新算子库而不是在某一个 Graph 节点上制造分叉。
3. **Scenarios** 根据 input Contract 自动生成 Given 表单，并用 output Contract 生成
   Happy path 的 expected-output 断言；即使该 Operator 从未保存过 Scenario，也能直接看到
   一条输入、输出均完整的可编辑演示数据。Operator target 没有 Graph node/edge scope，
   因此只提供 target output 与 invocation 语义，不允许伪造 node/edge selector。
4. **Run & Compare** 用一个临时单节点图提供快速反馈；需要证明真实单算子 runtime
   composability 时，仍使用同一详情浮层中的 **Executable Operator Suite**。
5. **Save Scenario** 将草稿绑定到 operator fingerprint 和 Contract fingerprint；
   **Publish** 通过独立 publisher 权限生成不可变 FixtureBundle + TestSuite，并从 testing
   control plane 独立发现 OPERATOR runtime target。
6. 已经保存过的 Operator Scenario 会在工作台打开时自动恢复最新 revision；**Load
   Scenario** 仍可用于显式刷新。首次创建时，自动探测或手动点击 **Load Scenario** 得到的
   服务端 404 都会被解释为正常的“尚无已保存 revision”，保留当前 Happy path 并提示点击
   **Save Scenario** 创建 revision 1，不再显示 `RG.SCENARIO.NOT_FOUND` 请求错误。
7. 对 resource virtual operator，Given 仍按业务 Contract 填写，例如
   `{params: {userId}}`；发布编译器会根据 catalog lowering 自动生成
   `{resourceId, params}` 并绑定 `httpResource` runtime。发布回执同时保留业务
   operator target 和 runtime target，不能把二者混为一个 ref。
8. 算子库版本、端口或 capability 变化后，旧 Scenario 会显示 stale，必须显式 rebase。
9. **+ Dependency** 可直接添加 operator/resource/built-in function 依赖；Operator 工作台
   默认打开 Operator selector。每条依赖都可以移除，不需要通过 Advanced JSON 维护列表。

Graph 的 **Save Graph** 和 Graph workspace bundle 导入导出不会出现在 Operator 工作台。
这是有意的生命周期边界：Operator 定义归算子目录所有，Scenario 是独立 authoring asset；
当前 `bloge.visualAuthoringWorkspaceBundle.v1` 仍是 Graph bundle。

Operator Scenario 的存储 id 使用可读前缀和完整 operator-ref SHA-256 摘要，因此标点不同但
归一化后相似的算子名不会互相覆盖。自动恢复与手动加载都会再次核对 exact target；若响应
属于另一个算子，工作台拒绝接纳并明确报错。

#### Contract 变化后如何迁移 Scenario

已有 Scenario revision 绑定的 Graph 或 Operator Contract 变化后，顶部不会再提供直接
rebase。正确操作是：

1. 点击 **Review compatibility** 进入 Compatibility。
2. 先看顶部 `UNCHANGED / COMPATIBLE / BREAKING / REVIEW_REQUIRED` 结论，再看每条 INPUT、
   OUTPUT 或 CONTRACT finding 的字段路径。
3. **Scenario impact** 会列出受影响的具体场景，而不是只显示“Contract 已变化”。
4. **Migration plan** 中的 `SAFE EDIT` 只包括 Contract default、删除不再接收的输入、
   `x-bloge-renamed-from` 明确声明的改名和输出断言重绑定；点击 **Apply safe migrations**
   后草稿仍保持 stale，便于检查。
5. `MANUAL` 项需要在 Scenarios 中补值或调整断言。对 BREAKING/REVIEW_REQUIRED，勾选人工
   复核确认后才能点击 **Record review & rebase**。
6. rebase 只更新草稿坐标并记录 report fingerprint、源 revision、finding ids 和时间；
   之后仍须 **Save Scenario**、**Run & Compare**，通过后才可 **Publish**。

Scenario revision 1 起，服务端会随 revision 保存不可变 Contract baseline。旧数据没有
baseline 时统一显示 `REVIEW_REQUIRED`。未知 Schema keyword、组合/条件 Schema 也不会被
误判为 compatible。尚未保存的 revision 0 需要人工核对当前 Contract 后执行
**Rebase local draft**，首次保存会建立 baseline。

| 算子族 | 双击后的浮层能力 | 写入 draft 的配置 |
| --- | --- | --- |
| `bloge:decisionTable` | 详情 + 规则矩阵 + 节点级 Executable Operator Suite。可编辑 hit policy、output type、条件列、输出列、规则行和 otherwise fallback | `config.hitPolicy`、`config.outputType`、`config.conditionColumns`、`config.outputColumns`、`config.rules[]`、`nodeFixtures[nodeId]` |
| `bloge:transform` | 详情 + 字段映射表 + 节点级 Executable Operator Suite。可编辑输出字段名和 BLOGE 表达式，可新增/删除 assignment，并在 Expression 下方使用函数 chip、函数名补全和签名提示 | `config.assignments`、`nodeFixtures[nodeId]` |
| `__foreach__:*` | 详情 + Loop guide + 节点级 Executable Operator Suite。按 `Bind collection -> Run per item -> Collect result list` 展示循环语义，帮助用户理解 array 输入、item context 和 list 输出 | 通常由 operator contract / runtime 定义；测试行可套用为 `nodeFixtures[nodeId]` |
| resource / http operator | 详情 + 可编辑 Resource ID、Method、URL/route、Timeout、Node Inputs、Input/Output samples、节点级 Executable Operator Suite、schema 摘要和高级 config JSON | `config.resourceId/method/url/timeoutMs`、`inputs.*`、`nodeFixtures[nodeId]` |
| generic / design operator | 详情 + label、Node Inputs、Input/Output samples、节点级 Executable Operator Suite、schema 摘要和高级 config JSON | `label`、`inputs.*`、`config`、`nodeFixtures[nodeId]` |

Operator Test Suite 和右侧全图 Test Suite 的边界不同：

| 测试入口 | 粒度 | 主要数据 | 用途 |
| --- | --- | --- | --- |
| Operator Detail 内的 Executable Operator Suite | 单个节点/算子 | Input case、Expected output；resource 另有 Transport response | 发现冻结 runtime target，真实执行单节点微图，并可把样例套用为节点 fixture |
| 右侧 inspector 的 Test Suite | 整张 graph | Runtime context、fixture overrides、Expected graph output | 批量验证端到端编排路径和最终业务结果 |

这里有一个重要边界：`Executable Operator Suite` 不再调用旧的 schema-only table runner。点击 Run Case / Run Exploratory 时，
画布先调用 operator target discovery，冻结 runtime fingerprint 并检查 testability class，再调用公共 micro-graph execution：
native operator 使用 `SPY` 观察真实主体；resource operator 降级到 `httpResource`，只以表格中的 Transport response 替换
transport I/O，因此 request mapping、协议解析和 payload extraction 仍会执行。`OPAQUE_RUNTIME`、不支持的 execution model
或缺失 runtime lowering 会在执行前 fail closed。

Contract Scenario 的 governed publish 与上面的快速表格试跑使用同一条 lowering 语义：
resource descriptor 的 `resourceId` 来自权威 catalog，不要求用户在 Given 里重复填写；
testing control plane 返回的 fixture/suite 会被再次读取并按 canonical JSON 指纹复验。
因此数据库 JSON round-trip 带来的 Java 数字类型变化不会造成假冲突，任何真正的协议内容
变化仍会失败关闭。

`Run Case / Run Exploratory` 使用 inline fixture，适合快速试验，因此 passing 行显示真实 run id 和 `EXPLORATORY` evidence，不等于可发布认证。
`Publish Case + Run / Publish Suite + Run` 会冻结同一 target，为每行把 lowered input、fixture 和 case metadata 规范化成内容寻址的
immutable fixture revision，再把所有行组织成一份 `bloge.testSuite.v1`。套件保留每行 `GOLDEN / NEGATIVE / BOUNDARY / REGRESSION`
意图，coverage policy 要求所有行和已声明意图都被完成，promotion policy 要求所有 case 通过且 evidence 可认证。画布以
`TEST_FIXTURE_WRITE` 写 fixture、以 `TEST_SUITE_WRITE` 写 suite，逐层校验 registry 返回的 ID/revision/fingerprint/target/cases，最后
以 `TEST_EXECUTION` 执行该精确 suite revision。画布会校验完整 suite value，并检查 child run、assertion、coverage、promotion 与 aggregate
能否互相印证；任何身份、策略、case intent 或证据逻辑漂移都会失败关闭，不进入结果展示。请求执行期间整张测试表只读，避免返回结果错绑到
中途编辑过的行；随后点击 `Run Case / Run Exploratory` 时，会先清除旧的 governed publication 聚合条。

点击 `Publish Suite + Run` 后，标题栏先显示 `n/n passed`；绿色聚合条显示 `PASSED · coverage SATISFIED · promotion ELIGIBLE`，下一行给出
`suiteId@revision` 和 `suiteRunId`，每个 case 则显示 payload-free child run id 与 evidence class。未改动内容会幂等复用同一 fixture、suite
和执行意图；修改 target、输入、期望、transport response 或 case intent 都会产生新的内容地址，不覆盖历史。单行
`Publish Case + Run` 走同一协议，只是发布一份合法的一行 suite，而不是绕过治理的特殊通道。

Published provenance 只是 `CERTIFIABLE` 的必要条件，不是保证：runtime composability、schema strictness、resource fixture fidelity
仍由服务端最终裁决；`ELIGIBLE` 也只表示当前 suite policy 满足，不是签名认证、ANEKE 审批或生产发布。右侧全图 Test Suite 仍复用
mock simulation，真实执行纯 DSL primitive，其他 operator 由 fixture 或 schema sample 替换，也不能被解释为 production-real execution。
这条限制只针对画布里尚未发布的临时 graph 表格。Resource Gateway 内置七张正式 resource graph 的 14 个 contract case 已可通过
`PUT /api/testing/catalogs/gateway-graph-contract-v1` 一键物化为内容寻址 fixture 和一等 `bloge.testSuite.v1`，随后由公共 runner、Java
test-kit 或 CI CLI 执行；物化结果会保留 `Golden / Negative / Boundary / Regression` 意图、F3 transport fixture、重试消费约束、
numeric tolerance 和结构 coverage。
完整的 graph contract、fault injection、replay regression 和生产隔离目标见
[工业级可测试性与执行数据控制反转演进方案](resource-gateway-industrial-testability-evolution-plan.md)。

resource 行的 Expected output 表示算子应交付的逻辑 payload；Transport response 表示外部系统原始协议响应。系统首次生成时会保持二者联动，但一旦用户手工编辑 Transport response，后续修改 Expected output 不会覆盖这份自定义 fixture。

演示服务以 `test` profile 启动时使用专用 demo identity；运行使用 `X-Purpose: TEST_EXECUTION`，fixture 写入使用
`X-Purpose: TEST_FIXTURE_WRITE`，suite 写入使用 `X-Purpose: TEST_SUITE_WRITE`，从 Scenario
发布受治理测试资产则使用独立的 `X-Purpose: TEST_SCENARIO_PUBLISH`。VSCode/嵌入式宿主必须替换凭证 provider，并应把 author、publisher 与 runner 权限拆分；production profile
不暴露测试 endpoint。因而不能通过复制前端请求把控制字段带入生产运行面。

因此，当你要验证某个 http resource、transform 或 decision table 的真实可组合 runtime 行为时，优先在双击浮层里运行 Executable Operator Suite；当你要验证整张图的 happy path、fallback path 或分支组合时，再进入右侧全图 Test Suite。

Decision table 双击后的页面重点如下：

![Author Decision Table 浮层编辑器标注](assets/bloge-author-decision-table-editor-annotated.svg)

1. **双击浮层编辑器**：双击 `bloge:decisionTable` 节点后打开，不需要在右侧 inspector 里找隐藏 JSON。
2. **来自传入边的条件列**：`score`、`income`、`employmentYears` 这类列来自上游边绑定，会以锁定列展示，避免规则表和边上的数据合同脱节。
3. **输出列**：规则命中后产出的结构化字段，例如 `decision`、`tier`、`reason`。
4. **规则行/otherwise**：每一行是一条匹配规则；otherwise 行作为 fallback，条件单元格禁用，只保留输出编辑。
5. **Done 保存到节点**：点击 Done 后，表格配置写回当前节点的 `config`，画布节点上的 input/output 数量也会同步刷新。

Decision table 的规则矩阵支持“加行”和“加列”：

1. 先把上游节点输出连到 decision table 的输入字段，例如连到 `inputs.score`。
2. 双击 decision table 后，规则矩阵会把传入边暴露为锁定条件列，例如 `score`。锁定列可以填写规则表达式，但不能改名或删除，因为列名就是后端 DSL 使用的 input key。
3. 点击 Add Condition Column 增加手工条件列，例如 `segment`、`amount`。
4. 点击 Add Output Column 增加输出列，例如 `tier`、`reason`。
5. 在规则行中填写每个条件表达式，例如 `score >= 700`。
6. 在输出列中填写匹配后的结构化结果，例如 `decision=approve`、`tier=platinum`。
7. 勾选 Otherwise 的行会作为 fallback，条件列会禁用，只保留输出编辑。

导出的 draft 会保持 schema-friendly 结构，而不是把整张表压成一段不可解析字符串：

```json
{
  "inputs": {
    "score": {
      "kind": "nodePath",
      "nodeId": "riskScore",
      "sourcePort": "decision",
      "path": "score",
      "targetPort": "inputs",
      "targetPath": "score"
    }
  },
  "config": {
    "hitPolicy": "unique",
    "outputType": "{ decision: String, ruleId: String, tier: String }",
    "conditionColumns": ["score"],
    "outputColumns": ["decision", "ruleId", "tier"],
    "rules": [
      {
        "conditions": {
          "score": "score >= 700"
        },
        "output": {
          "decision": "approve",
          "ruleId": "prime",
          "tier": "platinum"
        }
      },
      {
        "otherwise": true,
        "output": {
          "decision": "fallback",
          "ruleId": "otherwise",
          "tier": ""
        }
      }
    ]
  }
}
```

Transform 映射表则会导出为：

```json
{
  "config": {
    "assignments": {
      "tier": "inputs.score >= 700 ? \"prime\" : \"standard\"",
      "reason": "coalesce(inputs.reason, \"score policy\")"
    }
  }
}
```

Transform 浮层中的 Expression 输入框来自 `GET /api/visual/operators` 下发的 `builtInFunctions`：

1. 双击 `bloge:transform` 节点，打开 `Transform mapping` 浮层。
2. 在某一行 assignment 的 Expression 输入框下方，点击 `coalesce`、`jsonPath`、`round` 等函数 chip，系统会把调用片段插入当前表达式。
3. 当表达式为空时，签名提示会显示常用函数；当输入里出现 `coalesce(` 这类函数调用时，提示区会聚焦对应 signature。
4. 点击 Done 后，函数调用文本会作为普通 BLOGE 表达式写入 `config.assignments`，后续 validate、simulate、export 都读取同一份配置。

当前系统默认函数包括：

| 函数 | 典型用途 |
| --- | --- |
| `coalesce(value, fallback)` | 空值兜底，例如主评分缺失时使用备用评分 |
| `defaultIfBlank(text, fallback)` | 文本为空或 blank 时兜底 |
| `toNumber(value)` / `toString(value)` | 标量类型转换 |
| `jsonPath(object, path, fallback?)` | 从 object 中按路径读取字段 |
| `contains(collection, candidate)` | 判断字符串或集合是否包含某值 |
| `round(value, scale?)` | 数值四舍五入 |
| `formatDate(value, pattern)` | 日期/时间格式化 |

Foreach 的浮层不是另一个隐藏 JSON 编辑器，而是一个循环解释器：

1. **Bind collection**：告诉用户应该把上游 array 接到哪个 input port。
2. **Run per item**：说明每个 item 在运行期会成为单次处理的 item context。
3. **Collect result list**：说明 foreach 的输出仍是 array，供下游 transform、decision 或 resource 节点继续消费。

这解决了过去的问题：用户看到 `__foreach__:enrichOrders` 时不知道“循环在哪里发生”。现在双击节点即可看到集合输入、单项上下文、结果列表三段语义。

### 5.5 第五步：选择输出节点

选中节点后，在 inspector 中使用 Set Output。GraphDraft 的 `output.nodeId` 决定 validate、simulate、export 时哪个节点代表整张图的业务结果。

如果不选输出节点，系统无法判断哪些节点是有效业务链路、哪些只是旁路草稿，因此 Validate 会给出缺失 output 的诊断。

### 5.6 第六步：配置 fixture

对 design-only 或尚未实现的算子，模拟时需要 mock output。新版画布同时在 selected-node inspector 的 Simulation 区域和双击 Operator Detail 浮层里提供 fixture 编辑：

- Output fixture：指定该节点模拟时产出的值。
- Expected input：断言该节点模拟时应该收到的输入。
- Use Sample：根据输出 JSON Schema 生成一个确定性样本。

样本生成顺序是：

```text
用户 fixture
  -> schema examples/default/const/enum[0]
    -> 确定性 canonical sample
```

fixture 会写入 `GraphDraft.nodeFixtures`，属于 authoring/test evidence，不改变 DSL、fingerprint 或生产执行语义。

### 5.6.1 用 Scenarios 做可控 mock 回归

复杂业务编排不能只靠一次 Simulate 证明正确。Author Workspace v2 把 Graph 与 Operator
测试统一为 **Scenario**：点击顶部 **Scenarios**，或右侧 inspector 的
**Open Scenarios**，都会进入同一个 Contract/Scenario 工作台，不再打开 Raw JSON
Test Suite 浮层。

每个 Scenario 的结构固定为：

| 阶段 | 图形化能力 | 运行语义 |
| --- | --- | --- |
| Given | 按 target input schema 生成表单 | 本次 Graph/Operator 业务输入 |
| Dependencies | 为每个 node/operator/resource/function 选择 Real、Return、Error、Timeout、Replay 等行为 | 调用方控制数据流和失败边界 |
| Then | 从 output schema 选择字段和断言方式 | 比较 terminal、node、edge 或 invocation evidence |

使用方式：

1. 加载 `Loan policy fallback`，点击顶部 **Scenarios**。
2. Matrix 直接看到 `Prime approval path` 与 `Policy decline path`；它们由内置旧表格样例
   无损投影而来。勾选两行，点击 **Run selected**，两行会按固定顺序试跑并分别保留结果。
3. 用筛选器缩小视图，确认已选数量不变；点击 **Run failed** 只重跑上一轮失败项。
4. 点击任一行的 **Open** 进入 Case，在 **Given** 修改业务输入，在 **Dependencies** 修改
   节点返回值或切换为真实调用，在 **Then** 选择结果字段并填写期望值。
5. 点击 **Run & Compare**。页面进入 **Run Evidence**，失败断言默认展开 Expected/Actual，
   成功断言默认折叠。
6. 点击顶部 **Run scenario** 会运行当前 URL 中 `scenarioId` 指向的场景；示例投影尚未
   与当前 Contract 指纹对齐时按钮保持禁用，避免空运行。

节点基础 fixture 与 Scenario 控制行为的合并顺序是：

```text
GraphDraft.nodeFixtures
  -> Scenario Dependencies 的精确节点行为
    -> 本次 simulate request
```

旧 Graph Test Suite 和 Operator Test Suite 数据不会被静默丢弃：可投影行转为
Scenario；结构非法或无法无损表达的行保留在 **Advanced migration details**，且绝不会被
当成通过证据。v2 的 Operator Detail 也只在 **Advanced** 折叠区保留旧表格与 fixture
工具。需要原样操作旧 Raw JSON 表格时，使用顶栏 **Legacy** 进入旧工作区。

需要把测试资产治理起来时，继续使用后端已经落地的 schema-gated suite/golden 能力：

| 层级 | 入口 | 用途 |
| --- | --- | --- |
| 画布内调试 | `/author/` Scenarios | 作者用 schema 表单和依赖行为构造路径、调试 mock、验证编排逻辑 |
| Resource graph compatibility suite | `/api/gateway/graphs/contracts/tests/*` | 编辑或兼容运行旧 table asset；执行内核已统一，但资产身份仍是旧协议 |
| Governed built-in graph suite | `PUT /api/testing/catalogs/gateway-graph-contract-v1`，再执行返回的 exact suite ref | 把七图/14 case 物化为租户/环境隔离的不可变 fixture/TestSuite，供 API、test-kit、CLI 和 CI 共用 |
| Operator suite | `/api/visual/operators/tests/*` | 对单个 operator 的 input/config/output schema 和 mock output 断言做表格验证 |
| Published golden | `/api/visual/golden-cases/*` | 对不可变 publication 做发布级回归和认证 |

更完整的后端表格测试模型见 [Resource Graph Schema Mock Table Testing](./bloge-resource-graph-schema-mock-table-testing.md)。
物化操作在相同 graph dependency 与 source case 下幂等；任何 graph、resource descriptor、case intent、fixture、断言或 policy
变化都会生成新 revision，不覆盖旧证据。具体请求、响应和 CI 接法见
[Resource Gateway Testing Control Plane API](resource-gateway-testing-control-plane-api.md#424-materialize-the-built-in-graph-catalog)。

### 5.7 第七步：Validate

点击 Validate 后，前端调用：

```text
POST /api/visual/drafts/validate
```

结果中最重要的是三类信息：

- `valid`：合同和图结构是否通过。
- `readiness`：当前图整体是 executable、design-only、runtime-blocked 还是 catalog-repair required。
- `actionReadiness`：compile/run/publish design/publish executable 当前能不能做。

理解方式：

| 状态 | 说明 |
| --- | --- |
| Ready/valid | 图结构和 schema 约束通过，可以继续模拟或发布路径 |
| Design-only | schema 正确，但包含未绑定 runtime 的 design operator，只能作为设计资产或通过 simulate 验证 |
| Runtime-blocked | 存在 remote worker、AI tool、event source、message handler、webhook、streaming/durable 等当前 request-response runtime 不支持的边界 |
| Catalog repair required | 算子库或 operator projection 本身存在阻断问题，需要先修 catalog |

### 5.8 第八步：Simulate

点击 Simulate 后，前端调用：

```text
POST /api/visual/graphs/simulate
```

模拟不是生产运行。它的目标是验证编排逻辑、schema 形状、mock 输出传播、节点 trace 和终端输出是否符合预期。

系统采用 hybrid strategy：

- 安全、确定性、已实现的内置 DSL primitive 可以真实执行。
- 用户导入的 design-only operator、未绑定 runtime 的 operator、高风险副作用 operator 会用 `SimulationOperator` mock。
- 每个节点 trace 都会标记 `REAL` 或 `MOCKED`。
- 输出节点会额外标记 `OUTPUT`。

这能避免两个极端：一边是“所有东西都 mock 导致 transform/branch 逻辑没验证”，另一边是“设计期模拟误触真实外部副作用”。

模拟完成后，页面重点看这几个位置：

![Author 模拟结果标注](assets/bloge-author-simulation-result-annotated.svg)

1. **Simulate 成功**：顶部状态卡和工具栏会从 `not run` 变成 `success`。
2. **Run/Trust 检查**：Checklist 会显示 Run 是否成功，以及当前结果里有多少 real-run / mocked 节点。
3. **Mocked/Real 节点状态**：Mock Setup 区会按节点列出 `MOCKED` 或 `REAL`，方便判断哪些结果来自 fixture，哪些来自真实 transform/decision 执行。
4. **Graph ready 卡片**：画布左下角给出下一步行动提示；成功时会提示 graph ready，但仍标明 mocked 节点是否存在。
5. **节点徽标同步**：画布节点上的 badge 会同步显示 real/mock 状态，便于沿着 DAG 追踪模拟路径。

### 5.9 第九步：Export

当前 `/author/` 支持本地导出 draft JSON，包含：

```json
{
  "schemaVersion": "bloge.visualGraphDraft.v1",
  "graphName": "customGraph",
  "nodes": [],
  "edges": [],
  "nodeFixtures": {},
  "output": {
    "nodeId": "selectedNode"
  }
}
```

更完整的服务端资产流还包括：

| 资产 | API |
| --- | --- |
| 内置算子库导出 | `GET /api/visual/builtin-library/export` |
| 指定用户算子库导出 | `GET /admin/visual-operator-libraries/{libraryId}/export` |
| 用户算子库 bundle 导入 | `POST /admin/visual-operator-libraries/import-bundle` |
| Draft export/import | `GET /api/visual/drafts/{draftId}/export`, `POST /api/visual/drafts/import` |
| Publication export/import | `GET /api/visual/publications/{publicationId}/export`, `POST /api/visual/publications/import-bundle` |
| Golden case | `/api/visual/golden-cases/*` |

## 6. `/showcase/` 运行示例怎么用

`/showcase/` 在顶层导航中显示为 **运行示例**，是面向 Resource Gateway 的真实运行场景目录，
不是通用 authoring 工作台。Author 的完整示例用于载入后编辑；这里用于证明后端场景、图、请求
和 SSE 行为仍然可用。Diagram JSON 与 Legacy runner 默认收进 **Advanced**，避免干扰主运行任务。

页面重点如下：

![Showcase Loan Decision Policy 标注](assets/bloge-showcase-loan-policy-annotated.svg)

1. **场景目录**：左侧按后端返回顺序列出 resource gateway 示例，适合演示时快速切换 `User Dashboard`、`Loan Decision Policy`、`Product Detail` 等场景。
2. **场景说明/标签**：顶部展示业务模式、标签和解释文案，用于讲清这个 graph 证明了什么能力。
3. **后端 graph 图**：中间 Diagram 是后端示例 graph 的可视化，不是可编辑 canvas；它用于解释运行路径和节点关系。
4. **节点 Inspector**：点击图中的节点后，右侧显示 node kind、operator、payload、resourceId 等后端合同信息。
5. **运行输入**：下方 Sample Input/Run 区用于选择 preset、编辑请求参数、执行真实 gateway endpoint，并查看 expectation matched/missing。

使用方式：

1. 打开 `/showcase/`。
2. 从左侧场景列表选择一个示例。
3. 查看场景说明、运行参数、示意图和节点摘要。
4. 编辑 sample input。
5. 对普通请求点击 Run，系统会调用对应 public gateway endpoint。
6. 对 streaming 场景使用 SSE lane，必要时点击 Stop。
7. 查看 preset expectation matched/missing 反馈，判断演示输出是否符合预期。

它消费的核心 API：

```text
GET /api/gateway/examples/scenarios
GET /api/gateway/examples/scenarios/{graphName}
GET /api/gateway/examples/scenarios/{graphName}/diagram
```

## 7. 系统架构说明

![BLOGE 通用可视化编排系统架构](assets/bloge-visual-canvas-architecture.svg)

图源文件：[`assets/drawio/bloge-visual-canvas-architecture.drawio`](assets/drawio/bloge-visual-canvas-architecture.drawio)

### 7.1 前端职责

`resource-gateway-examples/src/main/frontend` 提供同一套 Vite/React bundle，并在 Spring Boot 打包时复制到 `/author/` 和 `/showcase/` 两个静态入口。

前端负责：

- React Flow 渲染和拖拽体验。
- Palette 搜索、分组、filter 和 Cmd/Ctrl-K。
- Node inspector、fixture 编辑、output 选择。
- Test Suite 浮层表格、fixture override 合并和逐行 transient simulate 调度。
- 调用服务端候选连接、连线确认、validate、simulate。
- 展示 readiness、diagnostics、trace、real/mocked badge。
- 解析 draft/node/operator/run/gate issue Deep Link，并回显 ANEKE gate freshness 与阻断原因。
- 导出本地 draft JSON。

前端不负责：

- 私自判定连接一定有效。
- 私自决定 draft 是否可运行。
- 私自相信用户导入的 runtime readiness。
- 在浏览器里直接执行 graph 或替代服务端模拟语义。
- 真实执行 design-only 或高风险 operator。

### 7.2 后端职责

后端 `visual/*` 包是核心：

| 后端模块 | 职责 |
| --- | --- |
| `visual/catalog` | operator catalog、算子库导入/导出、builtin library projection、profile、impact、revision |
| `visual/connection` | 服务端连接候选和连接预检 |
| `visual/importer` | schema-neutral `.bloge` DSL preview/commit/rewrite-gate/batch-report/batch-commit import，投影并保存 `GraphDraft`，输出 source map、coverage、round-trip、source replacement gate diagnostics、仓库级迁移 readiness report 和批量 governed draft 保存结果 |
| `visual/validation` | GraphDraft 合同、schema、runtime/design readiness、action readiness |
| `visual/simulation` | mock/real 混合模拟、fixture、trace、sample generator |
| `visual/publication` | publication 冻结、导入导出、依赖报告 |
| `visual/golden` | golden case 保存、运行、认证 |
| `visual/runtime` | `VisualGraphRunRecord.v8`、精确 node invocation attempt、side-effect attempt/receipt/transition、结构化 node/run-control/recovery fact、pre-run 脱敏 lineage reservation、自动 evidence recovery、managed KMS/HSM signer、公钥快照与持久 evidence seal、trace/replay 数据 |
| `visual/resource` | OpenAPI/resource contract 投影到 visual resource/operator surface |
| `integration` | Tool Studio versioned envelope、capability probe、draft dependency export、evidence/replay、side-effect reconciliation claim/SPI/签名 refinement、验签公钥、governance gate feedback、transactional outbox、签名 cursor 和 reconciliation snapshot |

resource gateway 自身继续保留：

- `HttpResourceOperator`：通用 HTTP resource 集成点。
- `ResourceDescriptor`：资源声明和参数映射。
- gateway example controllers：对外演示接口。
- DSL graphs：业务示例编排。

### 7.3 数据不变量

系统里有几个必须坚持的不变量：

1. `GraphDraft.visualLayout` 和 `nodeFixtures` 都是 authoring/test evidence，不定义生产业务语义。
2. `GraphDraft.output` 是图级结果选择，不能让前端隐式猜。
3. 连接写入前必须经过服务端 preflight。
4. `lowering.mode=design` 的 operator 可以设计和模拟，但不能冒充 executable operator。
5. 模拟 trace 必须明确标记 real/mocked，不能让 mock 输出看起来像真实生产结果。
6. 用户导入的 operator runtime readiness 不能被直接信任，服务端会重新派生。
7. 远程 `$ref`、不安全 schema、秘密字段、外部副作用和高风险 runtime capability 必须被 warning-gate 或 blocking-gate。
8. evidence 缺节点输入/输出或签名不可验证时必须进入 `QUARANTINED`，不能被 publish gate 当作可采纳证据。
9. governance gate result 必须绑定不可变 draft fingerprint；画布必须区分 `CURRENT`、`STALE`、`EXPIRED` 与 `MISSING`。
10. draft/operator/run/contract suite 的权威写入与 change event 必须同事务提交；Outbox 失败时资产也必须回滚。
11. event cursor 必须绑定 tenant/environment、签名并过期；客户端不能把数据库 offset 当协议。
12. Polling 或未来 webhook 都不是权威源；消费端漂移最终必须由 reconciliation snapshot 发现并收敛。
13. managed run 的 normal completion 与 recovery sweeper 必须锁定同一 reservation；run record、reservation
    终态和 integration event 必须原子提交，失败时三者一起回滚。
14. operator 必须在外部写之前记录 `PREPARED`；没有明确 receipt 的退出只能成为 `UNKNOWN_COMMIT`，不能成为
    `NOT_COMMITTED`。
15. `PREPARED/UNKNOWN_COMMIT` 是 non-retryable、non-fallback failure，并阻断依赖节点；对账只追加签名
    refinement，不能修改原 run evidence 或重放原写请求。
16. reconciliation lookup ref 必须是 evidence-safe opaque reference；原始 idempotency key、credential、provider
    payload 和带 query/user-info 的 URL 不得进入 run/evidence/reconciliation record。
17. 生产 evidence signer 只能向 provider 发送 canonical fingerprint 和请求绑定字段；private key/material 一旦出现在
    provider response 必须立即拒绝，不能写数据库、日志或 capability。
18. provider 返回的签名必须用原子公钥快照本地反验后才能持久化；transport 故障只能在 authority `expiresAt` 前降级，
    畸形快照、错误签名、disabled/revoked key 和过期快照必须 fail closed。

## 8. 典型业务流程

### 8.1 先设计、后实现

适合业务还没完全落地，但 schema 已经比较清楚的场景。

1. 平台或业务团队定义 `bloge.visualOperatorLibrary.v1`。
2. operator 使用 `lowering.mode=design`。
3. 在 `/author/` 导入 library。
4. 拖拽形成业务 DAG。
5. 设置 output node。
6. 为关键节点补 fixture。
7. Validate + Simulate。
8. 导出 draft，作为后续 runtime binding/工程实现输入。

这种模式的价值是：业务流程和数据合同可以先稳定下来，不必等所有 Java/operator/runtime 实现完。

### 8.2 资源网关场景演示

适合讲 resource gateway 能力。

1. 打开 `/showcase/`。
2. 选择 dashboard、product、order、credit、streaming 等场景。
3. 查看图和节点。
4. 调整 sample input。
5. 运行请求或 SSE stream。
6. 用 expectation 反馈说明结果。

### 8.3 把内置 registry 变成可移植 library

适合做环境迁移或示例复制。

1. 调用 `GET /api/visual/builtin-library/export`。
2. 拿到 portable bundle。
3. 在另一个环境通过 import bundle 导入。
4. 刷新 catalog，在画布中使用这些 operator。

### 8.4 从 OpenAPI/AsyncAPI 投影设计面

已有后端支持从协议文档生成 visual contract：

- OpenAPI resource contract：`POST /admin/resource-design-contracts/from-openapi`
- AsyncAPI operator library：`POST /admin/visual-operator-libraries/from-asyncapi`
- AsyncAPI operation discovery：`POST /admin/visual-operator-libraries/from-asyncapi/operations`

这些入口不会绕过 validator。未解析本地 `$ref`、远程 `$ref`、selector 未命中、blocked operation 都会被服务端诊断拦截。

## 9. 主要 API 速查

除 `GET /api/integration/capabilities` 和 evidence verification key 外，integration API 均要求 `Authorization: Bearer ...` 和与 operation 匹配的 `X-Purpose`。租户、组织、环境和 actor 以 resolver 返回的服务端 claims 为准。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/api/visual/operators` | 加载 operator catalog |
| `GET` | `/api/visual/operators/{operatorRef}` | 查看单个 operator detail |
| `POST` | `/api/visual/operators/fit-candidates` | 根据当前输出找可添加的候选 operator |
| `POST` | `/admin/visual-operator-libraries/validate-text` | 校验粘贴的算子库 JSON/YAML |
| `POST` | `/admin/visual-operator-libraries/import-text` | 导入粘贴的算子库 JSON/YAML |
| `POST` | `/admin/visual-operator-libraries/from-capability-catalog-text` | 将 `bloge.capabilityCatalog.v1` JSON/YAML 预览适配为标准 visual operator library 草稿，不自动存储 |
| `GET` | `/admin/visual-operator-libraries/{libraryId}/export` | 导出指定用户算子库 |
| `POST` | `/admin/visual-operator-libraries/import-bundle` | 导入算子库 bundle |
| `GET` | `/api/visual/builtin-library/export` | 导出内置 operator registry 为 portable library |
| `POST` | `/api/visual/dsl-imports/preview` | 以 schema-neutral 方式把 `.bloge` DSL + 当前 catalog/inline libraries 投影为 visual `GraphDraft` preview |
| `POST` | `/api/visual/dsl-imports/rewrite-gate` | 以同一 schema-neutral request 判断 generated DSL 是否可安全替换源 DSL；只返回 gate 结论，不持久化、不写源码 |
| `POST` | `/api/visual/dsl-imports/batch-report` | 以同一 schema-neutral catalog view 批量评估多份 DSL 的 render/repair/rewrite readiness 和覆盖率 |
| `POST` | `/api/visual/dsl-imports/batch-commit` | 按 `renderable` / `fully-projected` / `rewrite-allowed` 策略批量保存可接受 DSL projection 为 governed draft revision；不写源码 |
| `POST` | `/api/visual/dsl-imports/commit` | 以同一 schema-neutral request 重新投影 DSL，并保存为 governed stored draft revision |
| `POST` | `/api/visual/connections/candidates` | 枚举连接候选 |
| `POST` | `/api/visual/connections/check` | 预检单条连接 |
| `POST` | `/api/visual/drafts/validate` | 校验 transient draft |
| `GET` | `/api/visual/drafts/{draftId}` | 读取存量 draft，供 Author Deep Link 恢复画布 |
| `POST` | `/api/visual/graphs/simulate` | 模拟 transient draft |
| `POST` | `/api/visual/scenario-imports/materialize` | 在 test/staging 中重新解析 exact CSV/JSON plan，并物化 canonical Scenario + payload-free receipt |
| `GET` | `/api/visual/runs/{runId}` | 读取运行记录并恢复 draft/run Deep Link 上下文 |
| `GET` | `/api/visual/governance-gates/drafts/{draftId}` | Author 只读获取最新 ANEKE gate result 及快照新鲜度 |
| `GET` | `/api/integration/capabilities` | 查询 Tool Studio 协议版本、对象版本、端点和 feature flags |
| `GET` | `/api/integration/drafts/{draftId}/export` | 导出带依赖 fingerprint 的治理集成 bundle |
| `GET` | `/api/integration/runs/{runId}/evidence` | 导出带节点/边事实、完整性状态和持久签名的 evidence bundle |
| `GET` | `/api/integration/runs/{runId}/side-effects/reconciliations` | 合并不可变 base evidence 与已验证 refinement，返回 outstanding attempts 和有效治理状态 |
| `POST` | `/api/integration/runs/{runId}/side-effects/{attemptId}/reconcile` | 以 expected fingerprints、专用 purpose 和 durable claim 调用 provider-owned reconciler，追加签名结果 |
| `GET` | `/api/integration/runs/{runId}/replay` | 读取经脱敏的 recorded replay payload；当前不触发外部副作用 |
| `POST` | `/api/integration/runs/{runId}/replay` | 以 `DENY` 副作用策略重算 recorded payload 断言，生成带 parent lineage 的新 replay run/evidence |
| `GET` | `/api/integration/evidence-keys/{keyId}` | 获取 evidence seal 验签公钥 |
| `GET` | `/api/integration/evidence-keys` | 获取可外部 pin 的原子 evidence key 生命周期快照；发布门禁应使用此接口 |
| `POST` | `/api/integration/gate-results` | ANEKE 回写绑定 draft fingerprint 的治理门禁结果 |
| `GET` | `/api/integration/events` | 按签名 opaque cursor 拉取固定 high-water 事件窗口；仅允许 `CHANGE_SYNC` purpose |
| `GET` | `/api/integration/reconciliation` | 在一致数据库快照上返回租户/环境权威资产清单、计数、rolling fingerprint 和 checkpoint cursor |
| `GET` | `/api/integration/operator-libraries/{libraryId}` | 按可选 revision 获取事件引用的 operator library 不可变快照 |
| `GET` | `/api/integration/operator-test-suites/{suiteId}` | 按可选 revision 获取事件引用的 contract test suite 不可变快照 |
| `GET` | `/api/gateway/examples/scenarios` | showcase 场景列表 |
| `GET` | `/api/gateway/examples/scenarios/{graphName}/diagram` | showcase 场景图 |

## 10. 常见问题

**打开 `/author/` 是 404。**

大概率没有执行 `-Pfrontend package`，React 产物没有复制到 Spring Boot static resources。使用打包命令后再用 jar 启动。

**palette 为空。**

先确认 `/api/visual/operators` 是否有返回。若只有用户自定义算子，先在 library intake 导入算子库。若使用 deprecated library，默认 palette 会隐藏，需通过相应 catalog 参数或存量 draft 解析路径查看。

**算子库 Validate 失败。**

看第一条 blocking diagnostic。常见原因是 `schemaVersion` 不对、`libraryId` 缺失、`operatorRef` 冲突、port schema 格式不符合、远程 `$ref` 被拒绝、local `$ref` 无法解析、lowering mode 和字段不安全。

**拖线时目标是 blocked。**

看连接候选或 check response 的 message。多数是 schema 不兼容、目标 input 已被占用、path 不安全或目标 operator 当前不可用于该 scope。

**Validate 通过但 Run 不允许。**

这通常是 design-only 或 runtime-blocked。它说明 schema 编排成立，但当前 request-response runtime 没有真实执行绑定。此时使用 Simulate 验证逻辑，或补 runtime binding 后再发布 executable artifact。

**Simulate 结果都是 MOCKED。**

如果图里都是用户导入的 design-only operator，这是预期行为。mock 结果来自 fixture 或 schema sample。要看到 REAL，需要图中包含 allowlist 内、安全、确定性且已实现的内置 operator。

**Simulate 报 fixture JSON 无效。**

修正 selected-node Simulation 区域里的 Output fixture 或 Expected input。无效 fixture 不会发送到服务端，避免生成误导性的模拟证据。

**用 `npm run dev` 时导入算子库失败。**

当前 Vite dev proxy 只覆盖 `/api`，而导入算子库走 `/admin`。完整体验使用 Maven 打包后的 `/author/`，或在本地调试时补充 `/admin` proxy。

**ANEKE 链接打开后显示 target 不存在。**

先确认链接里的 `draftId` 指向哪个 revision。节点重命名、删除或 operator replacement 后，旧 `nodeId`、`operatorRef` 或 gate issue `targetPath` 可能已经失效；画布会继续打开草稿，但以 warning 显示未命中的目标。若 gate freshness 是 `STALE/EXPIRED`，应在 ANEKE 对当前 draft fingerprint 重新执行门禁，而不是修改链接绕过检查。

**ANEKE event cursor 返回 400 或 410。**

`400 RG.INTEGRATION.CURSOR_INVALID` 表示 token 被修改、格式损坏，或被拿到另一个 tenant/environment 使用；不要重试或尝试解析内部位置。`410 RG.INTEGRATION.CURSOR_EXPIRED` 表示离线时间超过 cursor 有效期，调用 `/api/integration/reconciliation` 重建 projection，并从返回的 `checkpointCursor` 恢复增量同步。

**Integration API 返回 401 或 403。**

`401 AUTHENTICATION_REQUIRED/AUTHENTICATION_FAILED` 表示没有 Bearer credential，或 credential 不在受信 resolver 中、已停用/过期。`403 PURPOSE_FORBIDDEN` 表示 identity 或 endpoint 不允许该 `X-Purpose`；`403 IDENTITY_CLAIM_MISMATCH` 表示请求仍携带了与服务端 claims 冲突的旧身份 header。先查看 `/api/integration/capabilities` 的 `identityProvider`，再检查本地 `RG_INTEGRATION_DEMO_TOKEN` 或企业 OIDC/mTLS adapter 配置，不要通过修改 `X-Tenant-Id` 绕过。

**事件处理成功，但 ANEKE 重启后又收到同一事件。**

这是消费端没有把 projection 更新和 cursor checkpoint 放在同一事务提交造成的。Resource Gateway 保证同一 cursor 的窗口稳定，但不替 ANEKE 管理消费事务。ANEKE 必须同时保存 `eventId` 去重记录、aggregate sequence 和 `nextCursor`；重复事件应成为幂等 no-op。

**evidence 显示 UNKNOWN_COMMIT，但 reconcile 返回 503。**

先看 capability 的 `sideEffectReconcilerAdapters`。`false` 表示当前部署没有注册 attempt 所需的 provider-owned
`SideEffectReconciler`；Resource Gateway 不会用 HTTP 状态码或线程异常猜提交结果。若 capability 为 `true`，再检查
attempt 的 `reconcilerRef` 是否有对应 Bean、`reconciliationLookupRef` 是否非空，以及 503 code 是 adapter unavailable、
timeout 还是 provider failure。provider 恢复后使用**同一个 requestId 和相同请求体**重试；claim 未过期时按
`retryAfter` 等待，不要生成新 request 热循环。

**reconciliation summary 是 READY，但原 evidence 仍是 QUARANTINED。**

这是设计不变量。原 evidence 的签名覆盖运行当时的 UNKNOWN_COMMIT，不能事后改写；summary 通过验证 base evidence
和追加的签名 reconciliation records 形成有效治理视图。ANEKE 应保存两者的 fingerprint/签名链，而不是只保存
summary 字符串。

**palette 显示 write protocol required，为什么还能拖到画布？**

这是刻意区分“可设计”和“可执行”。schema、拓扑和 mock fixture 仍然有设计价值，因此算子不会从目录消失；但节点会
保持 `RUNTIME_BLOCKED`，Validate/Run/EXECUTABLE promotion 会指出 `side-effect-conformance` requirement。补齐
`bloge.sideEffectProtocol.v1` 后必须重新导入/替换算子库，让 operator fingerprint 更新，再由平台团队提交 binding、
fault evidence 和 reconciler-health activation，不能只在旧 draft 上消掉红色标签。

## 11. 验证与回归命令

前端核心回归：

```bash
cd resource-gateway-examples/src/main/frontend
npm test
```

resource gateway 后端完整验证：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

带 React 打包和浏览器 smoke 的关键验证：

```bash
mvn -f resource-gateway-examples/pom.xml -Pfrontend \
  -Dtest=VisualAuthoringBrowserDomTest#reactAuthorCanvasLoadsPackagedBundleInRealBrowser,VisualAuthoringBrowserDomTest#reactShowcaseLoadsPackagedScenarioParityInRealBrowser \
  verify
```

## 12. 当前边界与后续方向

当前系统已经覆盖通用画布核心闭环，但它仍是 `resource-gateway-examples` 内的 example-grade 实现，不等于完整控制面产品。

当前不覆盖：

- 多人实时协作。
- 完整企业 IAM/RBAC 生命周期；当前已实现动态 JWKS/revocation、group/clearance/delegation claims、401/503 分流与
  撤销传播 SLO 探针，但客户 IdP 认证、resource-classification policy、group/orphan-owner 生命周期和 break-glass
  仍需部署级策略与演练。
- 持久化远程 worker runtime。
- 完整 AI tool/event/message/webhook 执行平面。
- 把 visual core 物理拆成独立 Maven artifact。
- 客户特定 KMS/HSM 认证与灾备；当前已内置 non-exportable managed-signing 协议、HTTP sidecar adapter、轮换/禁用/撤销、
  返回签名本地反验和 machine-readable custody。企业仍需接真实厂商 KMS/HSM、导出 provider key-use audit、固定历史
  公钥保留策略，并完成多地域 outage/restore 演练；本地 H2 signer 只用于 demo。
- webhook subscription、签名投递、重试与 DLQ；当前已具备 polling cursor 和 reconciliation，后续 webhook 只能作为低延迟提示层。
- 外部数据库集群、Outbox retention job 和灾备恢复编排；当前 H2 实现用于证明事务、游标和对账协议，不等于企业 HA 存储。
- `shadow/live` replay；当前已具备生成新 run lineage 的 `RECORDED_ASSERTIONS + DENY` 无副作用 replay command。
- 通用 provider reconciler adapter；当前已具备 SPI、持久 claim/fencing、签名 record 与 summary，但每个业务系统
  必须实现自己的只读 status lookup。没有 adapter 的 operator 不能被宣称为自动 commit-confirmable。
- 任意自定义算子的 effect 自动鉴别；当前对明确声明为 `WRITE_EXTERNAL` / `SideEffectType.WRITE` 的算子和通用 HTTP
  mutation 强制协议，但一个错误声明为 `MIXED`/`READ_ONLY`、内部再绕到私有 SDK 写入的实现仍需要制品扫描、运行时
  egress policy 和 provider conformance kit 共同发现。

后续可以继续推进：

- 建立 Execution Data Control Plane，区分 schema-contract 与 executable operator test，并统一 operator/graph/replay fixture、fault、coverage、evidence 和生产隔离；详见[工业级可测试性演进方案](resource-gateway-industrial-testability-evolution-plan.md)。
- 把 `visual/*` 抽出更干净的可复用 core + adapter SPI。
- 给 `/author/` 增加 stored draft 打开/保存/发布完整工作流。
- 把 runtime binding handoff 做成更直接的控制面。
- 增强复杂 schema 的表单化 fixture 编辑。
- 对大型 operator library 做更强的分页、分面和团队治理体验。

## 13. 一句话使用心法

先让 schema 成为真实边界，再让画布成为业务推理空间。不要急着追求所有算子都真实可执行；先用 design operator、fixture 和 simulate 把业务逻辑走通，再把稳定下来的图逐步绑定到 runtime。
