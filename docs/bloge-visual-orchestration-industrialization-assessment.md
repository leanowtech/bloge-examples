# BLOGE 可视化编排工业化评估报告

状态：Current Gap Assessment
日期：2026-07-04
基线：`resource-gateway-examples`

关联文档：

- [通用 BLOGE 可视化编排系统设计方案](./bloge-visual-orchestration-system-design.md)
- [BLOGE 可视化编排实现状态审计](./bloge-visual-orchestration-implementation-status.md)
- [BLOGE 可视化编排协议草案 v1](./bloge-visual-orchestration-protocol-v1.md)
- [BLOGE 可视化编排系统关键决策记录](./bloge-visual-orchestration-decision-record.md)

## 1. 评估结论

当前 `resource-gateway-examples` 已经不是普通 demo。它已经具备通用可视化编排画布的核心控制面骨架：

```text
用户算子库 / ResourceDesignContract
  -> OperatorDefinition catalog
  -> GraphDraft authoring
  -> schema-aware candidate / connection gate
  -> validation / action-readiness
  -> DESIGN publication / EXECUTABLE gate
  -> runtime-binding handoff
```

但它仍不能被宣称为完整工业级低代码平台。准确判断是：

> 当前是“严肃生产级示例项目 / industrializable reference implementation”，不是“完整生产级平台”。

最关键的方向判断没有变化：**无 runtime 实现的 schema-only 算子不能运行是正确设计，不是缺陷。** 工业化的真正缺口在于：schema contract、候选发现、runtime binding handoff、治理证据、运行态回流和观测闭环必须长期一致，不能只让画布在 happy path 上能拖线。

## 2. 多维度评分

评分解释：

- 0-3：概念或局部原型。
- 4-6：可演示，有核心路径，但生产边界薄。
- 7-8：严肃示例项目，可作为平台化基线。
- 9-10：可按生产平台标准交付，有完整运维、治理和稳定性闭环。

| 维度 | 当前分 | 证据 | 主要缺口 | 下一步 |
| --- | ---: | --- | --- | --- |
| 算子库合同与导入 | 8.0 | `OperatorLibrary`、JSON/YAML validate/import、revision、impact、bundle fingerprint、design-only lowering | 复杂第三方协议包 diff、跨环境治理策略还需继续深化 | OpenAPI/AsyncAPI diff 与 runtime binding handoff 对齐 |
| Schema 约束与拖线裁决 | 8.4 | `VisualSchemaCompatibility`、`VisualSchemaValidator`、`GraphDraftValidator`、connection check/candidates、fit candidates、`VisualSchemaIntrospection`，以及浏览器 schema mirror 对 required-only / contains-only typeless schema 的回归 | JSON Schema 语义仍是受限子集，深层 compatibility diff 与 value matching 还没有完全抽成可复用策略 | 持续收敛 shared schema/value helper，补更多 schema 子集回归 |
| 画布产品化体验 | 7.5 | Browser Composer palette、schema-aware picker、hover preflight、readiness panel、diagnostic queue、impact inspector，前端本地 schema type/validator mirror 已覆盖 required-only object 与 contains-only array；selected-node Connectability 直接展示服务端候选 schema 类型、替换影响、target runtime-binding debt，并在候选窗口被截断时显式提示 partial server window / local fallback 风险，已提供 Prev/Next 候选窗口控制，并支持窗口内按 target/reason/schema 文本与 ready/blocked/wired 状态过滤 | 单文件前端复杂度高，过滤仍是当前窗口内 UI 检索，不是全局服务端候选表；大图 DOM 交互矩阵仍未完全自动化 | 抽更小 UI 模块，补大画布候选窗口/filter browser regression 与真实 250+ target DOM |
| Design-only artifact 生命周期 | 8.0 | `DESIGN` publication、action-readiness gate、run/golden 禁用、runtime-binding requirements | DESIGN 到 external runtime bound 的组织流程仍依赖外部协作 | handoff bundle 与外部工单/事件系统对接 |
| Runtime binding 闭环 | 6.5 | requirement index、handoff bundle、implementation proposal、bind/supersede/unbind、activation、rollout observation、lowering integration、readiness recompute | 跨 repository partial-failure、异步 workflow idempotency、指标消费闭环仍未全覆盖 | 继续硬化 runtime evidence lifecycle 和 replay/compensation |
| 发布、可迁移性与版本治理 | 7.5 | draft/publication bundles、fingerprint gate、immutable publication、revision guard、operator/resource impact | 还有协议命名与当前 wire contract 的历史漂移 | 协议草案按现状收敛，保留平台化 ADR |
| 观测、回归和认证 | 6.8 | run history、SLO stats、golden case、suite run、certification status | 事件流回放、趋势分析、长运行实例观测不足 | run trace/golden trend 与 durable runtime 对齐 |
| 安全与治理 | 5.0 | tenant/namespace/environment policy、secret capability、actor/reason evidence gate | 不是完整 IAM/RBAC/secret/egress/admin audit 后台 | 平台化阶段引入权限模型和安全边界 |
| Runtime 扩展族 | 5.8 | remote-worker、AI-tool、event-source、message-handler、webhook、streaming/durable contract 已可设计态编排 | 真正 dispatcher、ingress runtime、AI tool invocation、durable instance 尚未落地 | 从 runtime-binding handoff 开始逐类接 executor |
| 工程可维护性 | 7.2 | 服务端测试丰富，完整 `clean verify` 可跑通，Java 侧读模型、GraphDraftValidator、VisualSchemaCompatibility 与 VisualSchemaValidator 的结构类型推断已开始共享 schema helper；浏览器 helper probe 覆盖了本地 mirror 与服务端语义一致性 | 深层 compatibility/value matching 仍分散，前端 `app.js` 过大 | 继续迁移 compatibility/validator 深层校验 helpers，逐步拆分前端 authoring helpers |

综合分：**72/100**。

这个分数不是贬低当前成果。相反，它说明项目已经跨过“画布玩具”阶段，但离完整工业平台还差治理、runtime、观测和维护性闭环。

## 3. 当前事实边界

### 已经成立

1. 用户可以导入 schema-only operator library。
2. operator input/output/config schema 会进入 catalog、画布、校验和发布流程。
3. 服务端能用 schema 约束连接，而不是只靠前端提示。
4. 没有 runtime 实现的 operator 可以保存、导出和发布为 DESIGN artifact。
5. compile/run/default EXECUTABLE publish 会被 action-readiness 明确阻断，而不是伪运行。
6. runtime-binding gap 可以被导出为 handoff material，给外部 runtime-plane 团队处理。
7. 浏览器本地 schema mirror 已与服务端对齐 required-only object、dependent* 和 contains-only array 的 typeless schema 基础语义，避免合法外部 schema 在前端被旧风格规则误拒。
8. selected-node Connectability 面板现在不只在 tooltip 中藏服务端候选解释，而是可见展示 schema type hint、replacement summary 和 target runtime-binding requirement，让 schema-only/design-only 算子拖线前就暴露 executable promotion debt。
9. selected-node Connectability 的服务端候选状态现在会把返回窗口边界显性化：如果 `/api/visual/connections/candidates` 因 `limit/offset` 只覆盖部分目标，UI 会展示 partial server window，并提示窗口之外仍会 local fallback，避免大画布用户误以为全量目标都已被服务端裁决。
10. selected-node Connectability 已能对服务端候选窗口做基本 Prev/Next 翻页，翻页请求把同一 selected node 的所有 source 统一切到新的 `offset/limit`，避免不同 source 混用不同裁决窗口。
11. selected-node Connectability 已支持候选窗口内文本过滤和 ready/blocked/wired 状态过滤，用户可以按 target、port、schema hint、阻断原因或 runtime-binding debt 收敛 chip，而不是在大图窗口里盲扫。

### 尚未成立

1. 不是所有 JSON Schema 语义都被完整证明；当前是受限且保守的 schema compatibility subset。
2. runtime-plane 从 handoff 到 executor bridge 的跨系统生命周期仍未完整产品化。
3. IAM/RBAC/secret/egress/审计查询还不是生产后台级别。
4. durable、event、message、webhook、AI tool 的真实运行时还没有完整闭环。
5. 前端仍是示例项目形态，复杂度已经接近需要模块化拆分的边界。

## 4. 本轮迭代复盘

### 2026-07-04：Connectability 候选窗口过滤

触发问题：

上一轮补了服务端候选窗口翻页，但大画布用户仍然要在窗口内肉眼扫 chip。翻页解决的是“能到下一页”，过滤解决的是“能在当前服务端裁决窗口里定位目标”。如果没有过滤，复杂业务编排的 selected-node inspector 仍会在几十到数百个 target 下迅速失控。

本轮完成：

1. 新增 `state.nodeConnectabilityFilter`，包含自由文本 `query` 和 `ready/blocked/wired` 状态筛选，不参与 draft artifact，不改变图语义。
2. 新增 `renderNodeConnectabilityFilterControls`，在 Connectability 面板渲染 `Find`、`Status`、匹配计数和清除按钮。
3. `nodeConnectabilityDisplayTargets` 在过滤激活时改为展示匹配到的 ready/wired/blocked target，并以 `nodeConnectabilityFilterDisplayLimit()` 限制单个 source 的 chip 数，避免过滤结果直接撑爆 inspector。
4. `nodeConnectabilityTargetSearchText` 统一索引 target label、title、detail、server explanation、schema type、binding key、阻断原因和 replacement/runtime-binding summary，让 schema-only/design-only 候选的运行时债务也能被搜索命中。
5. `renderSelectedOperatorEditor` 绑定 `data-connectability-filter-query`、`data-connectability-filter-status` 和 `data-connectability-filter-clear`，过滤只重绘 selected-node editor，不触发连接裁决或 draft 历史写入。
6. `VisualAuthoringAppJsTest` 覆盖文本+状态组合过滤、blocked/wired 状态判定、搜索文本包含阻断原因、过滤栏渲染、无匹配反馈和清除状态。

验证：

```bash
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringAppJsTest test
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringBrowserDomTest#composerShowsServerCandidateSchemaAndRuntimeDebtInConnectabilityPanelInRealBrowser test
mvn -q -f resource-gateway-examples/pom.xml clean verify
```

剩余风险：

这仍是“当前窗口内检索”，不是服务端全局候选查询，也不是虚拟化候选表。它不会改变 `/api/visual/connections/candidates` 的 `limit/offset` 语义：窗口外目标仍需要翻页后才能进入服务端裁决窗口。下一步要补真实 250+ target DOM 回归和 filter browser interaction，证明长文案、状态筛选、Prev/Next、loading 与无匹配反馈在真实浏览器里不会破坏 inspector 布局。

### 2026-07-04：Connectability 服务端候选窗口翻页

触发问题：

上一轮已经把 `/api/visual/connections/candidates` 的 partial window 风险显性化，但用户仍只能看到第一个服务端候选窗口。对大画布来说，“知道还有窗口之外的本地 fallback”只是止损，不是生产级操作能力。严肃的编排画布至少要允许用户翻到下一批服务端裁决候选，否则 250 个目标之后的连接仍然回到本地 mirror 猜测。

本轮完成：

1. `ensureNodeConnectabilityServerCandidates` 支持显式 `offset/limit/force`，并把窗口参数纳入 `requestKey`，避免 page 1 和 page 2 的 server candidate cache 互相污染。
2. selected-node Connectability server state 持有 `draftKey`、`offset` 和 `limit`，同一 selected node 的所有 source 在翻页时统一重新拉取同一个候选窗口。
3. 新增 `renderNodeConnectabilityServerControls`、`nodeConnectabilityServerWindowStats` 和 `nodeConnectabilityServerWindowLabel`，在 partial 或非零 offset 时渲染 Prev/Next 和 `Window x-y of total`。
4. `renderSelectedOperatorEditor` 绑定 `data-connectability-window` 事件，按钮触发强制刷新并立即回到 loading 状态，最终仍由服务端 candidates API 裁决。
5. `VisualAuthoringAppJsTest` 覆盖 page request key 差异、page one/page two hasNext/hasPrevious、控件渲染，以及下一页请求对所有 source 发出 `offset=250&limit=250`。
6. 真实浏览器 Connectability DOM 用例继续通过，证明新增控件没有破坏可见 schema/runtime debt 展示。

验证：

```bash
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringAppJsTest test
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringBrowserDomTest#composerShowsServerCandidateSchemaAndRuntimeDebtInConnectabilityPanelInRealBrowser test
```

剩余风险：

这轮解决的是窗口可达性，不是高效检索；后一轮已经补了当前窗口内 target/name/schema/reason 文本过滤和 ready/blocked/wired 状态筛选。但它仍不是完整候选表：还缺真实 250+ target 大图 DOM 回归、服务端全局候选查询和虚拟化列表。

### 2026-07-04：Connectability 服务端候选窗口边界显性化

触发问题：

`/api/visual/connections/candidates` 服务端合同已经支持 `limit/offset`，但 selected-node Connectability 面板固定拉取一个候选窗口后只显示 `Server candidates synced`。在大画布场景下，这会制造一个很危险的产品错觉：用户以为所有可连目标都已经经过服务端 schema/readiness 裁决，实际窗口之外的目标会退回浏览器本地 compatibility mirror。对工业级画布来说，局部服务端裁决必须被标成局部，不能伪装成全量确定性。

本轮完成：

1. 新增 `nodeConnectabilityServerWindowSummary`，从每个 source 的 candidate result 聚合 `totalCandidateCount`、`displayedCount`、`offset`、`truncated`。
2. `renderNodeConnectabilityServerStatus` 在完整窗口时展示候选总数；在截断或非零 offset 时展示 `partial server window x-y of total`。
3. 截断状态文案明确提示 `local fallback beyond server window`，让大图用户知道窗口之外的 chip 可能不是服务端最终裁决。
4. `VisualAuthoringAppJsTest` 的 Node probe 覆盖 `truncated=true` 场景，断言 Connectability 状态包含 partial window 和 local fallback 风险。

验证：

```bash
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringAppJsTest test
```

剩余风险：

这轮只把窗口边界显性化；后续一轮已经补了基本 Prev/Next 窗口翻页。但它仍没有解决候选过滤、真实 250+ target DOM 视觉稳定性，以及大图下的检索效率问题。

### 2026-07-04：Connectability 候选解释进入可扫描 UI

触发问题：

服务端 `/api/visual/connections/candidates` 已经能返回候选级 schema 类型、阻断原因、替换影响和 target runtime-binding debt，但 selected-node Connectability 面板主要把这些信息放在 `title/aria-label` 中。对真实画布用户来说，这不够工业化：用户能看到“ready/blocked”，却必须 hover 才知道这条连接会不会留下 external runtime implementation debt，或者为什么被服务端拒绝。

本轮完成：

1. `renderNodeConnectabilityTarget` 改为 label + detail 两行结构，保留原按钮/标签语义和 `data-connectability-action` 行为。
2. 新增 `nodeConnectabilityTargetDetail`，统一从 server candidate explanation 中提取 schema hint、runtime-binding summary、replacement summary 和 rejected message。
3. `.node-connectability-chip` 改为受约束的 `inline-grid`，新增 detail 样式，避免长 runtime debt 文案撑破 Connectability 面板。
4. `VisualAuthoringAppJsTest` 增加 Node probe，证明 server candidate detail 不再只存在于 title，渲染 HTML 中可见包含 schema hint 和 runtime-binding requirement。
5. `VisualAuthoringBrowserDomTest` 增加真实浏览器用例：导入单输入 design-only 算子，拖入画布，选择上游节点后等待服务端候选同步，并断言 Connectability 面板可见展示 `any -> integer`、`target runtime binding requirement`、`Executable Lowering: 1` 和 `risk-score-design library: 1`。

验证：

```bash
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringAppJsTest test
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringBrowserDomTest#composerShowsServerCandidateSchemaAndRuntimeDebtInConnectabilityPanelInRealBrowser test
```

剩余风险：

这轮只是把服务端候选解释在 selected-node Connectability 面板显性化。更大的浏览器矩阵仍未完全覆盖：导入面板 negative path、candidate discovery 在大型画布下的分页/过滤、DOM schema field rendering 的 drift 失败路径、以及 mobile/窄屏下长候选解释的视觉稳定性仍需要继续补。

### 2026-07-04：Browser schema mirror effective kind 收敛

触发问题：

服务端已经接受更接近 JSON Schema 语义的外部 operator schema：`required`、`dependentRequired`、`dependentSchemas` 不要求字段预先出现在 `properties`，`contains/minContains/maxContains` 可以把 typeless schema 识别为 array。但浏览器 `app.js` 的本地 schema mirror 仍保留旧规则：本地预检可能产出 `requiredUnknown`、`dependentRequiredUnknown`、`dependentSchemasUnknown`，并且 `schemaType()` 对 required-only object / contains-only array 仍可能显示为空类型。这是典型的工业级画布漂移：服务端能保存，前端却误报或展示不可信。

本轮完成：

1. `app.js.rawSchemaType` 对齐服务端结构类型入口：object 关键字覆盖 `required`、`additionalProperties`、`patternProperties`、`propertyNames`、`dependentRequired`、`dependentSchemas`、`min/maxProperties` 等；array 关键字覆盖 `contains`、`min/maxContains`、`min/maxItems`、`uniqueItems` 等。
2. 浏览器本地 `validateSchemaStructure` 不再把 `required` 缺少显式 `properties` 声明作为 blocking diagnostic，只保留 required 数组形态、空值和重复项校验。
3. 浏览器本地 `validateSchemaObjectDependentRequired` / `validateSchemaObjectDependentSchemas` 不再把 trigger/dependency 未声明在 `properties` 中作为 blocking diagnostic，但仍保留非空 key、array shape、duplicate dependency 和 dependent schema shape 校验。
4. `VisualAuthoringAppJsTest` 的 Node probe 增加 browser-facing 回归：typeless required-only schema 显示为 `object`，typeless contains-only schema 显示为 `array`，本地 mirror 不再产生旧 unknown diagnostics，同时 duplicate dependent item 仍然被保留为错误。

验证：

```bash
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringAppJsTest test
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualSchemaCompatibilityTest,VisualSchemaValidatorTest,VisualSchemaIntrospectionTest,VisualAuthoringAppJsTest test
```

剩余风险：

这次只是把浏览器 mirror 的结构类型与旧 unknown-property 规则补齐。`app.js` 仍然承载大量 schema/value/compatibility 逻辑，长期仍会漂移；下一步更好的方向不是继续无限加 if，而是把 browser helper 拆成更小的可测模块，或让更多高风险判断回到服务端候选/validate API 后只在前端做解释性展示。

### 2026-07-04：Compatibility / Validator effective kind 收敛

触发问题：

上一轮已经让读模型、publication projector 和 GraphDraftValidator 使用 `VisualSchemaIntrospection`，但 `VisualSchemaCompatibility` 与 `VisualSchemaValidator` 仍保留自己的 `schemaType/schemaKind/hasSchemaKeyword/nullableTypePrimary`。这会在用户导入外部 operator schema 时制造产品级裂缝：画布候选可能认为一个 typeless schema 是 object/array，保存或 value validation 却可能把它当 opaque 或按更窄的内部风格拒绝。

本轮完成：

1. `VisualSchemaCompatibility.schemaType` 改为委托 `VisualSchemaIntrospection.schemaType`，`effectiveSchemaType` 只保留 string/number 这类 compatibility 专属推断。
2. `VisualSchemaValidator.schemaKind` 改为委托 `VisualSchemaIntrospection.schemaType`，`effectiveNotSchemaKind` 继续保留 `not` 约束下的 string/number/enum 专属判断。
3. `objectProperty`、`propertiesOf`、`prefixItemsOf` 等基础结构读取改为委托共享 helper，删除重复 nullable type primary、const-value type inference 和局部 hasSchemaKeyword。
4. 放宽 `required`、`dependentRequired`、`dependentSchemas` 必须提前出现在 `properties` 的内部风格限制；这符合 JSON Schema 合法表达，更适合用户导入外部 operator schema。非法 shape、重复 dependent item、default/const/enum 违反约束仍然被阻断。
5. `contains/minContains/maxContains` 现在可以作为 typeless array schema 的有效数组合同，不再被 `arrayItemsMissing` 误拒。
6. 增加 value validation 与 compatibility 回归，覆盖 required-only object schema、contains-only array schema，以及 GraphDraft/OperatorLibrary 旧拒绝路径的新语义。

验证：

```bash
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualSchemaCompatibilityTest,VisualSchemaValidatorTest,VisualSchemaIntrospectionTest test
mvn -q -f resource-gateway-examples/pom.xml -Dtest=GraphDraftValidatorTest,OperatorLibraryValidatorTest,ResourceDesignContractValidatorTest,OperatorLibraryAdminControllerTest,ResourceDesignContractAdminControllerTest test
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualSchemaCompatibilityTest,VisualSchemaValidatorTest,VisualSchemaIntrospectionTest,VisualConnectionCheckServiceTest,OperatorFitCandidateServiceTest,DefaultVisualOperatorCatalogTest test
```

剩余风险：

这不是完整 JSON Schema 引擎。它只是把结构类型识别的权威入口收敛了。`VisualSchemaCompatibility` 里深层 diff、finite domain、not/conditional、patternProperties、dependent schema 的兼容性推理，和 `VisualSchemaValidator` 里的 value diagnostics 仍是领域判断层；下一轮如果继续迁移，必须区分“共享结构读取”和“业务语义裁决”，不能为了去重把保守门禁削弱掉。

### 2026-07-04：GraphDraftValidator 结构类型推断接入共享 helper

触发问题：

上一轮已经让 connection candidates、operator fit 和 publication projection 使用 `VisualSchemaIntrospection`。但最终服务端 gate `GraphDraftValidator` 仍保留自己的 `schemaType` 与 canonical array index 判断。只要最终 gate 和读模型继续各自维护结构类型推断，工业级画布仍然会有漂移风险：读模型可能推荐一个 target，最终 gate 也许用另一套推断解释它；或者最终 gate 支持了某个 typeless schema 写法，而 hover/palette/publication 还没有同步。

本轮完成：

1. `GraphDraftValidator.schemaType` 改为委托 `VisualSchemaIntrospection.schemaType`。
2. `GraphDraftValidator.arrayIndexSegment` 改为委托 `VisualSchemaIntrospection.arrayIndexSegment`。
3. 删除 validator 内重复的 `hasSchemaKeyword`、nullable type primary、const-value type inference 和独立 array index regex。
4. 保留 validator 自己的 `isIntegerValue` 和 config value 校验逻辑，因为那属于值校验语义，不是纯 schema path/type 读模型。

验证：

```bash
mvn -q -f resource-gateway-examples/pom.xml -Dtest=GraphDraftValidatorTest,VisualSchemaIntrospectionTest,VisualConnectionCheckServiceTest,OperatorFitCandidateServiceTest,DefaultVisualOperatorCatalogTest test
```

剩余风险：

`VisualSchemaCompatibility` 和 `VisualSchemaValidator` 仍有自己的 effective type/kind 推断，因为它们承担 compatibility diff、schema validation 和 value matching 语义。下一轮不能粗暴替换，应先把“纯结构推断”和“值/兼容性业务判断”切清楚，再迁移可等价部分。

### 2026-07-04：Java 侧 schema path/type helper 收敛

触发问题：

上一轮修复了 typeless schema 在 candidate discovery 与 operator fit discovery 中的行为一致性，但修复方式仍然是在两个服务中各自维护一份相似的 `schemaType`、`schemaAtPath` 和 candidate path 枚举逻辑。继续这样做会导致一个很现实的工业化问题：每新增一种 JSON Schema 子集，都必须记得同步多个读模型，否则画布会出现“validator 接受、hover 不显示、palette 不推荐、publication operator 丢 schema”的漂移。

本轮完成：

1. 新增 `VisualSchemaIntrospection`，作为 Java 侧 schema path/type 读模型共享入口。
2. `VisualConnectionCheckService` 的 candidate discovery、source/target schema resolution、config path 下标处理和 DSL reference 下标判断开始使用共享 helper。
3. `OperatorFitCandidateService` 的 target path 枚举、target schema resolution、source schema resolution 和多 output port disambiguation 开始使用共享 helper。
4. `VisualGraphPublicationOperatorProjector` 的 selected output path 投影开始使用共享 `schemaAtPath`，避免发布后的 reusable publication operator 丢失 typeless array item schema。
5. 增加 `VisualSchemaIntrospectionTest`，覆盖 typeless array tuple/remainder path、非 canonical array index 和 typeless object `propertyNames` + `patternProperties` gate。
6. 增加 publication projector 回归：`projectsPublishedVisualGraphSelectedTypelessArrayOutputPathSchema`。

验证：

```bash
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualSchemaIntrospectionTest,VisualConnectionCheckServiceTest,OperatorFitCandidateServiceTest,DefaultVisualOperatorCatalogTest test
```

剩余风险：

这还不是 schema 逻辑的完全统一。`GraphDraftValidator`、`VisualSchemaCompatibility` 和 `VisualSchemaValidator` 仍有各自的 `schemaType` / effective kind 逻辑，因为它们承载更深的兼容性判断、schema validation 和值匹配语义，不能在一轮里粗暴替换。下一步应迁移其中“路径导航和结构类型推断”部分，保留各自的业务判断层。

### 2026-07-04：typeless schema 在候选发现链路的一致化

触发问题：

上一轮已经让 `GraphDraftValidator` 和浏览器字段树理解 typeless JSON Schema，例如：

```json
{
  "items": { "type": "integer" }
}
```

或：

```json
{
  "prefixItems": [{ "type": "integer" }],
  "items": { "type": "string" }
}
```

但服务端 candidate discovery 和 operator fit discovery 仍有自己的 `schemaType` 推断，只从显式 `type`、`properties`、`items` 判断。这会产生生产级不可接受的不一致：

- validator 最终允许 `items.0`。
- `/api/visual/connections/candidates` 不一定发现 `items.0`。
- `/api/visual/operators/fit-candidates` 不一定推荐可接收该 source 的 operator。

本轮完成：

1. `VisualConnectionCheckService` 的 schema path 枚举和 `schemaAtPath` 现在会从 object/array applicator 推断 typeless schema。
2. `OperatorFitCandidateService` 使用同一类推断，避免“可连接但不推荐”。
3. 增加 `VisualConnectionCheckServiceTest.connectionCandidatesDiscoverTypelessArrayTargetPaths`。
4. 增加 `OperatorFitCandidateServiceTest.fitCandidatesDiscoverTypelessArrayTargetPaths`。

验证：

```bash
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualConnectionCheckServiceTest,OperatorFitCandidateServiceTest test
```

剩余风险：

schema type/path 逻辑仍分散在多个类中。短期可接受；中期应抽出 shared schema path/type utility，统一 GraphDraftValidator、connection candidates、fit candidates、publication projector、operator-library validator 和 browser mirror 的行为。

## 5. 下一轮优先级

| 优先级 | 方向 | 理由 | 最小可交付 |
| --- | --- | --- | --- |
| P0 | 深层 compatibility / value diagnostics 策略收敛 | effective kind 已统一，但 not/conditional/patternProperties/dependent schema 等深层判断仍在类内分散 | 选一个高风险 schema 子集，抽共享 value/schema policy 或补明确不可迁移边界 |
| P0 | Runtime binding partial-failure 硬化 | 这是 DESIGN artifact 走向可执行 runtime 的主干 | 选一个尚未补偿的跨 repository mutation，补 replay/compensation/诊断 |
| P1 | Browser regression matrix | required-only / contains-only typeless schema、Connectability 可见候选解释、design-only target runtime debt、候选窗口截断提示、基本 Prev/Next 窗口翻页和 JS 层候选过滤已覆盖，但 UI 能力多，DOM smoke 仍需继续扩大 | 覆盖导入面板、filter browser interaction、真实 250+ target DOM、大量 schema field rendering 的更多负路径和漂移路径 |
| P1 | 协议文档收敛 | 设计草案与当前 wire contract 名称仍有历史漂移 | 把 candidate/fit/readiness 当前字段写入 protocol v1 |
| P2 | 前端模块化 | `app.js` 已承载太多 authoring 逻辑 | 先抽 schema helper 或 readiness helper，保持测试覆盖 |

## 6. 评估报告维护规则

每轮迭代后必须更新本报告的第 4 章：

1. 写清楚本轮修复的是哪个工业化缺口。
2. 写清楚代码证据和测试证据。
3. 写清楚本轮以后离工业级还差什么。
4. 不允许只写“已完成”；必须说明仍未覆盖的风险面。

这份报告的作用不是给项目贴金，而是阻止示例项目在能力堆叠中失去边界感。
