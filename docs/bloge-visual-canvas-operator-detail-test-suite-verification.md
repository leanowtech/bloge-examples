# BLOGE Visual Canvas Operator Detail And Test Suite Verification

> Date: 2026-07-07  
> Scope: `/author/` 通用可视化编排画布，本轮聚焦 foreach 可理解性、算子双击详情、Test Suite 浮层表格，以及图文产品手册同步。

## 1. 本轮目标

| 要求 | 状态 | 证据 |
| --- | --- | --- |
| foreach 算子交互更直观 | Done | `ForeachLoopGuide` 把循环拆成 `Bind collection -> Run per item -> Collect result list` 三段，并在 foreach 节点详情浮层中展示 |
| 每个画布算子都能双击展开详情 | Done | `OperatorDetailDialog` 成为统一双击入口，普通 resource/design operator、decision table、transform、foreach 都走同一个浮层 |
| 详情浮层展示 input/output schema 与专有属性 | Done | `SchemaPortCards` 展示字段摘要并保留 Raw schema；详情区展示 operatorRef、source、lowering、readiness，并支持 label、resource/http config、Node Inputs、Input/Output samples 编辑 |
| decision table / transform 保留专属编辑能力 | Done | 规则矩阵和 transform assignment 表格嵌入详情浮层，不再是孤立弹窗 |
| 表格测试从右侧栏迁移到浮层 | Done | 右侧 inspector 只保留 Test Suite 摘要按钮；点击后打开 `test-suite-dialog` 表格编辑和批量运行 |
| 操作手册图文同步 | Done | 产品手册新增 Operator Detail 与 Test Suite 两张标注 SVG，并按实际页面入口说明 |
| 差距小于 3% | Done | 主路径 UX 与测试闭环已落地；残余差距约 2%，集中在治理增强而非当前 authoring 主路径 |

## 2. 设计决策

### 2.1 统一双击语义

过去不同算子的双击行为不一致：decision table / transform 有局部编辑器，resource / foreach / design operator 缺少可解释详情。新版把双击统一成 `Operator Detail`：

```text
canvas node double click
  -> Operator Detail
    -> contract summary
    -> input/output schema cards
    -> operator properties and config preview
    -> optional family-specific editor
```

这个入口让用户先理解“这个算子是什么、吃什么、吐什么”，再进入规则、映射或循环语义。decision table 和 transform 的编辑能力没有被削弱，只是被收进同一个上下文里。

### 2.2 foreach 不伪装成普通属性表

foreach 的难点不是缺一个 JSON 字段，而是用户不理解循环在图里如何发生。因此本轮不把 foreach 做成裸属性表，而是提供三段式循环向导：

1. **Bind collection**：把上游 array 绑定到集合输入。
2. **Run per item**：运行期对每个 item 形成单项上下文。
3. **Collect result list**：输出仍是 array，继续给下游 transform / decision / resource 使用。

这能解释 foreach 的执行模型，同时避免在画布里臆造还没有形式化表达的内部子图编辑器。

### 2.3 Test Suite 是浮层工具，不挤占 inspector

表格测试天然横向宽：case name、context、fixture overrides、expected output、actual output、status 都需要空间。把它塞进右侧栏会压缩节点配置和 schema 信息。新版采用：

```text
right inspector lightweight summary
  -> Test Suite button
    -> modal table editor
      -> row edit / run table / clear results
```

右栏负责提示“有多少 case、上次运行是否通过”，浮层负责实际编辑和批量运行。

## 3. 实现清单

| 文件 | 变更 |
| --- | --- |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.tsx` | 新增 `OperatorDetailDialog`、`SchemaPortCards`、`ForeachLoopGuide`；统一节点双击打开详情；详情浮层支持 key properties、Node Inputs、Input/Output samples；Test Suite 改为右侧摘要按钮 + 浮层表格 |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.test.tsx` | 覆盖普通节点双击详情、http resource 详情编辑、foreach 循环向导、decision table 嵌入详情、Test Suite 浮层入口和运行路径 |
| `resource-gateway-examples/src/main/frontend/src/styles.css` | 新增算子详情浮层、schema 卡、foreach guide、Test Suite 摘要卡和浮层表格样式 |
| `docs/assets/bloge-author-operator-detail-annotated.svg` | 产品手册中的算子详情标注图 |
| `docs/assets/bloge-author-test-suite-dialog-annotated.svg` | 产品手册中的 Test Suite 浮层表格标注图 |
| `docs/bloge-visual-canvas-product-and-system-guide.md` | 按实际页面更新双击详情、foreach、Test Suite 浮层使用说明 |
| `docs/bloge-visual-canvas-mock-table-testing-verification.md` | 把旧表格测试入口描述同步为 Test Suite 浮层模型 |
| `docs/bloge-resource-graph-schema-mock-table-testing.md` | 同步画布内 Test Suite authoring 入口和后端治理 suite 的边界 |

## 4. 自动化验证

| 命令 | 结果 |
| --- | --- |
| `npm test -- src/AuthorCanvas.test.tsx` | Passed，25 tests |
| `npm run build` | Passed，`tsc --noEmit && vite build` 成功 |
| `npm test` | Passed，111 tests |

关键断言：

- `test-suite-open` 点击后才出现 `test-suite-dialog`，右侧栏不再常驻大表格。
- 普通 `risk:score` 节点双击后出现 `operator-detail-dialog`、`Input schema`、`Output schema`。
- `httpResource` 节点双击后可以在详情浮层编辑 label、method、url、contextPath input binding 和 output fixture，并同步导出到 `GraphDraft`。
- foreach 节点双击后出现 `foreach-loop-guide`，包含 `Bind collection`、`Run per item`、`Collect result list`。
- decision table 双击后在 `operator-detail-dialog` 内出现规则表编辑器，并保留传入边条件列。

## 5. 差距评估

当前主路径已经成立：

```text
节点双击
  -> 统一 Operator Detail
  -> schema / property / config 可视化
  -> decision table / transform 专属编辑
  -> foreach loop guide
  -> Test Suite 浮层批量验证
  -> 图文产品手册可按页面操作
```

剩余差距估算：约 **2%**，低于 3%。剩余项都不是当前 authoring 主链路阻断：

1. Test Suite 还不能一键保存为后端 stored suite 或 publication golden case。
2. foreach 还没有子图级 body designer；当前先解释 collection/item/result list 语义。
3. Operator Detail 已有 schema 摘要与 raw schema，尚未提供 schema diff、sample payload 对照和端口级数据探针。

下一轮优先级建议：

1. `Save as Suite`：把当前浮层 case 保存为后端 schema-gated suite。
2. Foreach body designer：明确 foreach 子流程 DSL 形态后，再支持内部子图可视化。
3. Schema probe：在 Operator Detail 里展示最近一次 trace 的端口 payload 与 schema 校验结果。
