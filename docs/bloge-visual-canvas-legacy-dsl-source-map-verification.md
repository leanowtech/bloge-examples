# Legacy DSL Source Map UI 验证记录

日期：2026-07-07
范围：`resource-gateway-examples` `/author/` 通用画布

## 1. 验证目标

用户补充的边界是：通用画布不关心 schema 怎么生成，只要进入画布的是合法 operator/function schema，画布就应该能用同一套 DSL preview/render 合同渲染对应 `.bloge`。

本轮验证聚焦两件事：

1. 合法 `bloge.visualOperatorLibrary.v1` 可以作为 preview-only inline schema，与 `.bloge` 一起渲染成 `GraphDraft`。
2. `DslVisualProjection.sourceMap` 不只停留在后端响应里，浏览器 Legacy DSL 面板能展示 source map 行，点击行能定位画布节点，导出 draft 保留源码映射。

## 2. 已落地能力

| 能力 | 状态 | 证据 |
| --- | --- | --- |
| schema provenance agnostic preview | 已落地 | `DslImportPreviewRequest` 明确只要求合法 visual catalog view；`DslImportServiceTest.projectsDslWithInlineOperatorLibraryWithoutDependingOnSchemaOrigin` 覆盖 inline visual library |
| `/author/` Legacy DSL render | 已落地 | 点击 `Render DSL` 后加载当前画布，真实浏览器返回 `Rendered 2 nodes / 2 edges.` |
| source map 面板 | 已落地 | 面板展示 6 refs：node、binding、edge 都带 DSL 行列和源码片段 |
| source map 行定位 | 已落地 | 点击 `legacy-dsl-source-map-row:node:eligibility` 后，行 class 变为 `dsl-source-map-row selected` |
| source map 导出 | 已落地 | `AuthorCanvas.test.tsx` 断言导出 draft 含 `visualLayout.import.sourceMap.nodes.eligibility.startLine/startColumn` |

![Legacy DSL source map 标注](assets/bloge-author-legacy-dsl-source-map-annotated.svg)

## 3. 验证命令

```bash
npm test -- AuthorCanvas.test.tsx
mvn -f resource-gateway-examples/pom.xml -Dtest=DslImportServiceTest,DslImportControllerTest test
npm run build
```

真实浏览器验证：

```text
http://127.0.0.1:18080/author/
Library -> Risk policy example
Legacy DSL -> Render DSL
Source map -> click node:eligibility
```

浏览器观测结果：

```json
{
  "notice": "Rendered 2 nodes / 2 edges.",
  "sourceMapRefs": 6,
  "selectedRowClass": "dsl-source-map-row selected"
}
```

## 4. 剩余差距

| 缺口 | 影响 | 下一步 |
| --- | --- | --- |
| preview commit 未落地 | Render 后还不能一键保存为 stored draft/revision | 增加 `/api/visual/dsl-imports/commit`，复用 draft repository 和 revision guard |
| `bloge.capabilityCatalog.v1` adapter 未落地 | 存量业务的 Maven export 产物还不能直接导入画布 | 增加 capability catalog -> visual operator library adapter；但不把它做成画布唯一入口 |
| opaque snippet 修复向导未落地 | 复杂 DSL primitive 只能 diagnostics，不够可操作 | 增加 `bloge:opaqueDslNode` / source snippet UI / unresolved mapping wizard |
| semantic round-trip 未落地 | 还不能安全自动回写 `.bloge` | 增加 original AST vs generated AST equivalence API |

结论：本轮把“合法 schema + DSL 可以在通用画布渲染并审阅源码映射”的核心路径闭合到浏览器层。剩余差距集中在迁移资产持久化、复杂语义保留和回写安全。
