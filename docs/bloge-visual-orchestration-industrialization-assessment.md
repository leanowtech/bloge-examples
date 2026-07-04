# BLOGE 可视化编排工业化评估报告

状态：Current Gap Assessment
日期：2026-07-05
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
| 画布产品化体验 | 8.1 | Browser Composer palette、schema-aware picker、hover preflight、readiness panel、diagnostic queue、impact inspector，前端本地 schema type/validator mirror 已覆盖 required-only object 与 contains-only array；selected-node Connectability 直接展示服务端候选 schema 类型、替换影响、target runtime-binding debt，并在候选窗口被截断时显式提示 partial server window / local fallback 风险，已提供 Prev/Next 候选窗口控制，并支持按 target/reason/schema 文本发起服务端全局 query、按 ready/blocked/wired 做服务端全局状态过滤并返回 `statusCounts`；服务端候选结果现在返回 `facetCounts`，覆盖 surface、schemaType、operatorRef、operatorLibraryId、runtimeReadiness、sourceKind、loweringMode，并在 Connectability 状态区展示聚合 facet 摘要；真实浏览器已覆盖 filter 输入重绘后的焦点保留、状态筛选、Clear 恢复，以及 260 个 target 下的服务端窗口、服务端全局 query/status 命中末尾 target、Next 翻页和无横向溢出；服务端候选接口已把大窗口成本限制到当前页，并通过 `targetSurface=canvas` 与画布 target handle 语义对齐 | 单文件前端复杂度高，query/status/facet summary 已下沉到服务端候选合同，但 facet 还不是可交互筛选控件；大图回归仍缺窄屏、虚拟化列表和多算子族性能矩阵 | 抽更小 UI 模块，把 `facetCounts` 升级为交互式 facet filter，补虚拟化列表、窄屏布局和多 operator family 大画布回归 |
| Design-only artifact 生命周期 | 8.0 | `DESIGN` publication、action-readiness gate、run/golden 禁用、runtime-binding requirements | DESIGN 到 external runtime bound 的组织流程仍依赖外部协作 | handoff bundle 与外部工单/事件系统对接 |
| Runtime binding 闭环 | 6.5 | requirement index、handoff bundle、implementation proposal、bind/supersede/unbind、activation、rollout observation、lowering integration、readiness recompute | 跨 repository partial-failure、异步 workflow idempotency、指标消费闭环仍未全覆盖 | 继续硬化 runtime evidence lifecycle 和 replay/compensation |
| 发布、可迁移性与版本治理 | 7.5 | draft/publication bundles、fingerprint gate、immutable publication、revision guard、operator/resource impact | 还有协议命名与当前 wire contract 的历史漂移 | 协议草案按现状收敛，保留平台化 ADR |
| 观测、回归和认证 | 6.8 | run history、SLO stats、golden case、suite run、certification status | 事件流回放、趋势分析、长运行实例观测不足 | run trace/golden trend 与 durable runtime 对齐 |
| 安全与治理 | 5.0 | tenant/namespace/environment policy、secret capability、actor/reason evidence gate | 不是完整 IAM/RBAC/secret/egress/admin audit 后台 | 平台化阶段引入权限模型和安全边界 |
| Runtime 扩展族 | 5.8 | remote-worker、AI-tool、event-source、message-handler、webhook、streaming/durable contract 已可设计态编排 | 真正 dispatcher、ingress runtime、AI tool invocation、durable instance 尚未落地 | 从 runtime-binding handoff 开始逐类接 executor |
| 工程可维护性 | 7.2 | 服务端测试丰富，完整 `clean verify` 可跑通，Java 侧读模型、GraphDraftValidator、VisualSchemaCompatibility 与 VisualSchemaValidator 的结构类型推断已开始共享 schema helper；浏览器 helper probe 覆盖了本地 mirror 与服务端语义一致性 | 深层 compatibility/value matching 仍分散，前端 `app.js` 过大 | 继续迁移 compatibility/validator 深层校验 helpers，逐步拆分前端 authoring helpers |

综合分：**75/100**。

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
11. selected-node Connectability 已支持候选窗口内文本过滤和 ready/blocked/wired 状态过滤，用户可以按 target、port、schema hint、阻断原因或 runtime-binding debt 收敛 chip，而不是在大图窗口里盲扫；真实浏览器回归已证明输入框在 selected-node editor 重绘后仍保留焦点和值，状态过滤和 Clear 不会破坏可见候选解释。
12. selected-node Connectability 已有真实 260 target 大画布回归：服务端 candidates API 使用 canvas target 语义返回与画布一致的 260 个候选，窗口显示 `1-250 of 260`，Next 可进入 `251-260 of 260`，且 inspector 面板无横向溢出。
13. selected-node Connectability 已支持服务端全局 query：候选 API 在全量 target 上先按 query 过滤再分页，返回 filtered total 与 `unfilteredCandidateCount`；真实 260 target 浏览器回归证明在首窗口输入 `riskScoreReview260` 可直接得到 `1/260 matching candidate` 并展示 `integer -> integer`，且 query 输入有 debounce 防止逐字请求风暴。
14. selected-node Connectability 已支持服务端全局 ready/blocked/wired 状态筛选：`targetStatus` 参与候选 API、request key、server state 和真实浏览器等待条件；返回结果包含 `statusCounts`，并把 duplicate-but-existing 的候选显式标记为 `wired`，避免大画布跨页状态筛选退回当前窗口 UI 假象。
15. selected-node Connectability 候选 API 已返回服务端 `facetCounts`：统计口径是 query/status 过滤之后、分页之前，覆盖 surface、schemaType、operatorRef、operatorLibraryId、runtimeReadiness、sourceKind、loweringMode；前端会聚合同一 selected node 的 source 结果并展示短 facet 摘要，让用户在大画布候选窗口中看到当前结果集合由哪些 schema/runtime/library 构成。

### 尚未成立

1. 不是所有 JSON Schema 语义都被完整证明；当前是受限且保守的 schema compatibility subset。
2. runtime-plane 从 handoff 到 executor bridge 的跨系统生命周期仍未完整产品化。
3. IAM/RBAC/secret/egress/审计查询还不是生产后台级别。
4. durable、event、message、webhook、AI tool 的真实运行时还没有完整闭环。
5. 前端仍是示例项目形态，复杂度已经接近需要模块化拆分的边界。

## 4. 本轮迭代复盘

### 2026-07-05：Connectability 服务端候选 Facet Counts 与 UI 摘要

触发问题：

服务端全局 query 和 ready/blocked/wired 状态筛选已经把候选过滤从“当前窗口 UI 假象”推进到“全量候选集服务端语义”。但工业级候选表不能只知道状态，还必须解释候选集合的构成：这些 target 来自哪些 schema 类型、哪些 operator/library、哪些 runtime readiness。否则大画布用户只能看到数量变化，看不到为什么这批候选值得处理。

本轮完成：

1. `VisualConnectionCandidatesResult` 增加 `facetCounts`，作为候选 API 的稳定合约字段；旧构造器继续代理到空 facet，避免破坏既有调用方。
2. `VisualConnectionCheckService` 在 query 和 `targetStatus` 过滤之后、offset/limit 分页之前计算 facet，确保 facet 描述的是当前候选全集，而不是当前页窗口。
3. facet 维度覆盖 `surface`、`schemaType`、`operatorRef`、`operatorLibraryId`、`runtimeReadiness`、`sourceKind`、`loweringMode`；其中 `runtimeReadiness` 输出为稳定 kebab-case，例如 `design-only`。
4. 大候选集 fast paging 路径复用同一份 facet，保持 `FULL_CANDIDATE_CHECK_LIMIT` 之后仍只对当前窗口执行完整 `check()`，不会因为 facet 摘要退化为全量 validator。
5. 前端 `normalizeConnectionCandidatesResult` 归一化 `facetCounts`，Connectability server status 会聚合同一 selected node 的 source 结果并显示短摘要，例如 schema/runtime/library 的 top facet。
6. `VisualConnectionCheckServiceTest` 在 260 target 样本中断言 query 命中 1 个 target 和 runtime debt 命中 260 个 target 的 facet 分布；`VisualAuthoringAppJsTest` 断言 JS payload 归一化、facet 聚合摘要和 partial window 状态都能展示 facet。

验证：

```bash
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualConnectionCheckServiceTest test
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringAppJsTest test
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringBrowserDomTest#composerConnectabilityHandlesLargeTargetWindowInRealBrowser test
mvn -q -f resource-gateway-examples/pom.xml clean verify
```

剩余风险：

这轮把“更丰富服务端 facet”做成了候选合同和可见摘要，但还没有变成交互式 facet filter；也没有补上虚拟化候选列表、窄屏布局矩阵、多 operator family 大画布性能矩阵。准确说，它把 Connectability 候选表从 74 分推到 75 分，但离 95 分仍有明显工程距离。

### 2026-07-05：Connectability 服务端全局状态筛选与候选状态 Facet

触发问题：

服务端全局文本 query 已解决“第 260 个 target 需要先翻页才能搜索到”的问题，但 ready/blocked/wired 状态仍主要依赖当前窗口 UI 过滤。对工业级画布来说这是不可接受的：用户选择 `wired` 或 `blocked` 时，系统必须在全量候选集上筛选，而不是只在当前 250 个 chip 里做视觉过滤。

本轮完成：

1. `VisualConnectionCandidatesRequest` 增加 `targetStatus`，支持 `ready/blocked/wired` 全局候选状态过滤，并保留旧构造器兼容。
2. `VisualConnectionCandidatesResult` 增加 `statusCounts`，表达 query 过滤后、status 过滤前的 ready/blocked/wired 分布；`ConnectionCandidate` 增加 `targetStatus`，避免 duplicate-but-existing 的候选被 UI 误归类为 blocked。
3. `/api/visual/connections/candidates` 现在先枚举全量 target，再按 query 过滤、计算状态分布、按 `targetStatus` 过滤，最后执行 offset/limit；大候选集路径继续只对当前窗口执行完整 `check()`。
4. 服务端轻量状态分类把 `wired` 与 `ready` 分开：已经存在相同连接的 target 不再被 ready 筛选命中；data duplicate 仍保留 validator duplicate diagnostic，但候选行会显式标记 `targetStatus=wired`。
5. Connectability 前端把 active status 纳入 request body、request key、server state 和 debounce guard；状态切换不会复用旧 query/window cache。
6. 前端归一化 `statusCounts` 为稳定的 `ready/blocked/wired` 三键结构，并让 server-returned `wired` 候选在 UI 中保持 wired 状态，而不是被 duplicate diagnostic 误显示成 blocked。
7. 真实浏览器 260 target 回归现在先选择 `Ready`，再输入 `riskScoreReview260`，等待 server state 同时满足 `query=riskScoreReview260` 与 `targetStatus=ready`，证明 DOM 事件链路和服务端状态筛选一起工作。

验证：

```bash
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualConnectionCheckServiceTest test
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringAppJsTest test
```

剩余风险：

这轮补齐的是 connectability 候选表的核心状态 facet，不是完整 facet 系统。operator family、runtime readiness、schema type、runtime-binding debt 等 facet 仍没有统一服务端查询模型；候选列表还没有虚拟化；窄屏/移动端和多算子族大画布性能矩阵还缺自动化证明。

### 2026-07-04：Connectability 服务端全局候选 Query 与输入防抖

触发问题：

260 target 回归证明了服务端窗口和 Next 可用，但当过滤仍停留在当前窗口 UI 层时，用户要找到第 260 个 target 仍必须先翻页。把 query 直接接到服务端后，又暴露出另一个真实产品风险：每个字符都触发一次大候选请求会制造请求风暴，让面板长时间停在 loading。

本轮完成：

1. `VisualConnectionCandidatesRequest` 增加 `query`，`VisualConnectionCandidatesResult` 增加 `unfilteredCandidateCount`；`totalCandidateCount` 现在表达 query 过滤后的候选数，`unfilteredCandidateCount` 表达 query 前的全量候选数。
2. `/api/visual/connections/candidates` 在枚举全量 target 后先做轻量 query 过滤，再执行 offset/limit 和当前窗口完整 `check()`；query 索引覆盖 node/operator/endpoint/schema/readiness/runtime-binding debt 等字段，让 design-only/runtime debt target 也能被检索。
3. Connectability 前端把 active query 纳入 request key、server state 和窗口摘要，显示 `1/260 matching candidate` 这类 filtered/unfiltered 关系；Clear 后恢复无 query 的服务端窗口。
4. query 请求增加 180ms debounce，避免真实用户逐字输入时把服务端打成多轮大候选检查；非 query 翻页仍立即请求。
5. 真实 Chrome/Selenium 大画布用例现在在首窗口输入 `riskScoreReview260`，不用 Next 就能命中第 260 个 target，随后清空 query 再验证 Next 仍能进入 `251-260 of 260`。

验证：

```bash
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualConnectionCheckServiceTest test
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringAppJsTest test
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringBrowserDomTest#composerConnectabilityHandlesLargeTargetWindowInRealBrowser test
```

剩余风险：

服务端全局文本 query 已补上，但当时还不是完整候选表产品。后续已经补了 ready/blocked/wired 服务端全局状态筛选；更丰富 facet、列表虚拟化、多算子族大图性能矩阵、窄屏和移动端布局仍没有自动化证明。

### 2026-07-04：Connectability 260 target 大画布窗口回归与候选接口成本收敛

触发问题：

上一轮证明了小窗口 filter 的真实 DOM 交互，但工业画布最容易坏在大图：服务端候选分页如果只是 UI 上分页、后端仍对全量 target 做完整 graph validation，就会在几百个目标下退化成长时间 loading；同时服务端 candidate enumeration 不能和浏览器实际 canvas target handle 数量不一致，否则用户看到的 `260 connectable` 会被 `520 server candidates` 污染。

本轮完成：

1. 新增真实 Chrome/Selenium 回归 `composerConnectabilityHandlesLargeTargetWindowInRealBrowser`，导入一个专用 `risk:scoreSource` + `risk:scoreReview` operator library，在浏览器中构造 1 个 source 和 260 个 target。
2. 后端 `/api/visual/connections/candidates` 对 `includeRejected=true` 且大候选集启用分页优先路径：全局计数走轻量 schema compatibility，完整 `check()` 只对当前 `offset/limit` 窗口执行，避免 260 个 target 触发 260 次全图校验。
3. 前端全量候选请求使用 `targetSurface=canvas`，后端按画布 handle 语义过滤 input root，避免 server candidate count 与画布本地 connectability target count 分叉。
4. 真实浏览器断言首窗口展示 `partial server window 1-250 of 260` 和 `Window 1-250 of 260`，Next 后展示 `Window 251-260 of 260`。
5. 同一用例过滤 `riskScoreReview260`，证明末尾 target 可定位并展示服务端 schema hint；同时断言 Connectability panel 没有横向溢出。

验证：

```bash
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringBrowserDomTest#composerConnectabilityHandlesLargeTargetWindowInRealBrowser test
```

剩余风险：

这轮把 250+ target 的真实浏览器窗口、服务端分页成本和 canvas/server target 语义对齐补上了，但当时仍不是完整候选表产品：过滤仍是当前窗口内 UI 检索，不是服务端全局 query。后续已经把文本 query 与 ready/blocked/wired status 下沉到服务端全局候选过滤；剩余缺口转为更丰富 facet、列表虚拟化、多算子族性能矩阵，以及窄屏和移动端布局自动化证明。

### 2026-07-04：Connectability 过滤真实浏览器交互回归

触发问题：

上一轮已经补了 JS helper 级过滤验证，但这还不够。Connectability filter 每输入一次都会重绘 selected-node editor；如果真实浏览器里输入框在第一次重绘后丢焦，用户实际只能输入一个字符，后面的过滤能力就是假能力。工业级画布不能只证明 helper 函数会返回正确数组，还要证明真实 DOM 交互不会把用户困在破碎控件里。

本轮完成：

1. 扩展 `VisualAuthoringBrowserDomTest#composerShowsServerCandidateSchemaAndRuntimeDebtInConnectabilityPanelInRealBrowser`，在真实 Chrome/Selenium 环境中导入 `lowering.mode=design` 的 `risk:scoreReview` 算子、拖入画布并打开 `loanPolicy` 的 Connectability 面板。
2. 新增 `sendKeysThroughRerenderedFocusedInput` 与 `waitForFocusedValue`，逐字符通过当前 `activeElement` 输入 `runtime`，每次输入后都断言重新渲染后的 filter input 仍是焦点且 value 完整保留。
3. 真实浏览器用例证明 `runtime` 能命中可见的 `target runtime binding requirement`，`ready` 状态筛选仍保留 `Score review (riskScoreReview)` 候选。
4. 同一用例继续切到 `blocked` 状态，断言组合筛选进入 `No matching targets`，且 `riskScoreReview` 的 connect action 不再出现在 DOM 中。
5. Clear 后断言 query 清空、焦点回到 filter input，并恢复展示 `Score review (riskScoreReview)` 与 runtime-binding debt。

验证：

```bash
mvn -q -f resource-gateway-examples/pom.xml -Dtest=VisualAuthoringBrowserDomTest#composerShowsServerCandidateSchemaAndRuntimeDebtInConnectabilityPanelInRealBrowser test
mvn -q -f resource-gateway-examples/pom.xml clean verify
```

剩余风险：

这轮证明的是“真实浏览器里的当前窗口过滤交互可用”，不是“所有大画布候选检索问题已解决”。后一轮已经补了真实 260 target DOM、候选窗口翻页和候选接口分页成本收敛；剩余缺口是窄屏长文案布局、候选表虚拟化、全局服务端搜索和跨页 query 语义。

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

这轮当时仍是“当前窗口内检索”，不是服务端全局候选查询，也不是虚拟化候选表。后续已经补了 filter browser interaction、真实 260 target DOM 回归、服务端全局文本 query 和 ready/blocked/wired 服务端状态筛选；下一步应推进更丰富 facet、虚拟化候选列表和窄屏布局矩阵。

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

这轮解决的是窗口可达性，不是高效检索；后续已经补了当前窗口内 target/name/schema/reason 文本过滤、ready/blocked/wired 状态筛选、真实 260 target 大图 DOM 回归和后端分页成本收敛。但它仍不是完整候选表：还缺服务端全局候选查询、虚拟化列表和窄屏布局矩阵。

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

这轮只把窗口边界显性化；后续已经补了基本 Prev/Next 窗口翻页、候选过滤、真实 260 target DOM 视觉稳定性，以及大窗口服务端分页成本收敛。但全局候选检索、虚拟化和窄屏布局仍未完成。

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
| P1 | Browser regression matrix | required-only / contains-only typeless schema、Connectability 可见候选解释、design-only target runtime debt、候选窗口截断提示、基本 Prev/Next 窗口翻页、JS 层候选过滤、真实浏览器 filter 交互、260 target 大画布窗口、服务端全局 query 与 ready/blocked/wired status 已覆盖，但 UI 能力多，DOM smoke 仍需继续扩大 | 覆盖导入面板、窄屏/移动布局、多算子族大画布、虚拟化候选列表、更丰富服务端 facet 和大量 schema field rendering 的更多负路径/漂移路径 |
| P1 | 协议文档收敛 | 设计草案与当前 wire contract 名称仍有历史漂移 | 把 candidate/fit/readiness 当前字段写入 protocol v1 |
| P2 | 前端模块化 | `app.js` 已承载太多 authoring 逻辑 | 先抽 schema helper 或 readiness helper，保持测试覆盖 |

## 6. 评估报告维护规则

每轮迭代后必须更新本报告的第 4 章：

1. 写清楚本轮修复的是哪个工业化缺口。
2. 写清楚代码证据和测试证据。
3. 写清楚本轮以后离工业级还差什么。
4. 不允许只写“已完成”；必须说明仍未覆盖的风险面。

这份报告的作用不是给项目贴金，而是阻止示例项目在能力堆叠中失去边界感。
