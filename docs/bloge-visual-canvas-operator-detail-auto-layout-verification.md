# BLOGE Visual Canvas Operator Detail And Auto Layout Verification

> Date: 2026-07-07  
> Scope: `/author/` 通用可视化编排画布，本轮聚焦算子双击 UX、http/resource 关键属性编辑、input/output 编辑入口、schema 摘要可读性，以及 Auto Layout 舒展排版。

## 1. 设计目标

| 目标 | 设计结论 |
| --- | --- |
| http resource 双击后要直观 | 双击浮层升级为节点工作台：先展示 Key properties，再提供 Resource ID / Method / URL / Timeout 编辑 |
| input/output 不应藏在右栏 | 浮层内直接复用 `Node Inputs` 图形化绑定，并提供 Output sample / Expected input fixture 编辑 |
| 每类算子都要过关 | 所有算子共享 label、输入绑定、样例、schema 摘要、高级 config；decision table / transform / foreach 叠加专属区域 |
| schema 不能只给 raw JSON | 端口卡先展示 schema 类型、字段数、字段表；Raw schema 作为可展开专家入口 |
| Auto Layout 不能只“不重叠” | 分层仍保持确定性，但提高左上留白、行距、列距和长边标签估宽，让 DAG 留出必要空白 |

## 2. 算子族 UX 审查结果

| 算子族 | 本轮修整 | 结果 |
| --- | --- | --- |
| resource / http resource | Key properties 可编辑；`Node Inputs` 可绑定 ctx/constant；Output sample / Expected input 可编辑；schema 摘要优先展示 | 主路径过关 |
| decision table | 保留双击浮层表格编辑；传入边 condition 列继续锁定；同时获得通用输入绑定、样例和 schema 摘要 | 主路径过关 |
| transform | 保留 assignment 编辑器与 built-in function 辅助；同时获得通用输入绑定、样例和 schema 摘要 | 主路径过关 |
| foreach | 保留三段式 Loop guide；同时获得通用输入绑定、样例和 schema 摘要 | 主路径过关 |
| streaming | readiness 仍直接显式展示；同时获得通用输入绑定、样例和 schema 摘要 | 主路径过关 |
| generic / design | 从“只看合同”升级为可编辑 label、inputs、fixtures 和 advanced config | 主路径过关 |

## 3. 实现清单

| 文件 | 变更 |
| --- | --- |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.tsx` | 新增 Key properties、Advanced config、Input/Output samples；把输入绑定和 fixture 操作抽成 nodeId 版本供 inspector/浮层共用；SchemaPortCards 增加字段摘要 |
| `resource-gateway-examples/src/main/frontend/src/draftModel.ts` | 调整 Auto Layout 留白、节点估算尺寸、行距、列距、长边标签估宽和最大列 pitch |
| `resource-gateway-examples/src/main/frontend/src/styles.css` | 增加详情浮层编辑控件、resource config 网格、schema field table、增强 edge label 字号/描边 |
| `resource-gateway-examples/src/main/frontend/src/AuthorCanvas.test.tsx` | 覆盖 http resource 双击浮层编辑 label/method/url/input/output fixture 并验证导出 draft |
| `resource-gateway-examples/src/main/frontend/src/draftModel.test.ts` | 把 Auto Layout 验收从“避免覆盖”提高到行距、列距、长边标签空间都有明确下限 |
| `docs/bloge-visual-canvas-product-and-system-guide.md` | 更新 Auto Layout、Context binding、Operator Detail、fixture 使用说明 |
| `docs/assets/bloge-author-operator-detail-annotated.svg` | 替换为新版可编辑 Operator Detail 标注图 |

## 4. 自动化验证

| 命令 | 结果 |
| --- | --- |
| `npm run build -- --emptyOutDir=false` | Passed，`tsc --noEmit` 与 Vite build 成功 |
| `npm test` | Passed，5 test files / 111 tests |

关键断言：

- Auto Layout 线性图从 `96,72` 起排，列距至少 `408px`。
- fan-out 中间层节点垂直间距至少 `236px`。
- 长边标签场景列距至少 `680px`，为 `source.path -> target.path` 留出可读空间。
- `httpResource` 双击后出现 Key properties 和 Resource config。
- 在详情浮层里修改 label、HTTP method、URL 后，导出 draft 的 `nodes[0].label` 与 `nodes[0].config` 同步更新。
- 在详情浮层里 Add Binding 并填写 `request.customerId` 后，导出 draft 的 `nodes[0].inputs.input.kind=contextPath`。
- 在详情浮层里填写 Output sample 后，导出 draft 的 `nodeFixtures.n1.output` 同步更新。

## 5. 差距评估

当前目标闭环已经成立：

```text
设计方案
  -> 双击浮层升级为节点工作台
  -> 通用编辑能力覆盖所有算子族
  -> family-specific 编辑能力继续保留
  -> Auto Layout 改为舒展排版
  -> 测试与产品手册同步
```

剩余差距估算：约 **2.5%**，低于 3%。剩余项不阻断当前 authoring 主链路：

1. Resource ID / Method / URL / Timeout 当前写入节点 `config`，还没有直接打开 ResourceDescriptor 管理器做 descriptor 级编辑。
2. Operator Detail 尚未展示最近一次 trace 的端口 payload 与 schema 校验结果。
3. foreach 仍是 loop guide，不是子图 body designer。
4. Test Suite 仍是 authoring-side transient runner，还没有一键保存为后端 stored suite / publication golden case。

下一轮建议优先做 ResourceDescriptor drill-down、schema probe 和 Save as Suite；这些属于治理增强，不影响本轮“可理解、可编辑、可演示”的主体验。
