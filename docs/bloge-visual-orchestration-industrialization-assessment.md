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
| Schema 约束与拖线裁决 | 7.8 | `VisualSchemaCompatibility`、`GraphDraftValidator`、connection check/candidates、fit candidates | JSON Schema 语义仍是受限子集，多个服务曾存在 type 推断不一致 | 持续收敛 shared schema helper，补更多 schema 子集回归 |
| 画布产品化体验 | 7.0 | Browser Composer palette、schema-aware picker、hover preflight、readiness panel、diagnostic queue、impact inspector | 单文件前端复杂度高，交互矩阵仍未完全自动化 | 抽更小 UI 模块或增加更强 browser regression matrix |
| Design-only artifact 生命周期 | 8.0 | `DESIGN` publication、action-readiness gate、run/golden 禁用、runtime-binding requirements | DESIGN 到 external runtime bound 的组织流程仍依赖外部协作 | handoff bundle 与外部工单/事件系统对接 |
| Runtime binding 闭环 | 6.5 | requirement index、handoff bundle、implementation proposal、bind/supersede/unbind、activation、rollout observation、lowering integration、readiness recompute | 跨 repository partial-failure、异步 workflow idempotency、指标消费闭环仍未全覆盖 | 继续硬化 runtime evidence lifecycle 和 replay/compensation |
| 发布、可迁移性与版本治理 | 7.5 | draft/publication bundles、fingerprint gate、immutable publication、revision guard、operator/resource impact | 还有协议命名与当前 wire contract 的历史漂移 | 协议草案按现状收敛，保留平台化 ADR |
| 观测、回归和认证 | 6.8 | run history、SLO stats、golden case、suite run、certification status | 事件流回放、趋势分析、长运行实例观测不足 | run trace/golden trend 与 durable runtime 对齐 |
| 安全与治理 | 5.0 | tenant/namespace/environment policy、secret capability、actor/reason evidence gate | 不是完整 IAM/RBAC/secret/egress/admin audit 后台 | 平台化阶段引入权限模型和安全边界 |
| Runtime 扩展族 | 5.8 | remote-worker、AI-tool、event-source、message-handler、webhook、streaming/durable contract 已可设计态编排 | 真正 dispatcher、ingress runtime、AI tool invocation、durable instance 尚未落地 | 从 runtime-binding handoff 开始逐类接 executor |
| 工程可维护性 | 6.5 | 服务端测试丰富，完整 `clean verify` 可跑通 | schema path/type 逻辑仍有重复，前端 `app.js` 过大 | 抽 shared schema path utility，逐步拆分前端 authoring helpers |

综合分：**68/100**。

这个分数不是贬低当前成果。相反，它说明项目已经跨过“画布玩具”阶段，但离完整工业平台还差治理、runtime、观测和维护性闭环。

## 3. 当前事实边界

### 已经成立

1. 用户可以导入 schema-only operator library。
2. operator input/output/config schema 会进入 catalog、画布、校验和发布流程。
3. 服务端能用 schema 约束连接，而不是只靠前端提示。
4. 没有 runtime 实现的 operator 可以保存、导出和发布为 DESIGN artifact。
5. compile/run/default EXECUTABLE publish 会被 action-readiness 明确阻断，而不是伪运行。
6. runtime-binding gap 可以被导出为 handoff material，给外部 runtime-plane 团队处理。

### 尚未成立

1. 不是所有 JSON Schema 语义都被完整证明；当前是受限且保守的 schema compatibility subset。
2. runtime-plane 从 handoff 到 executor bridge 的跨系统生命周期仍未完整产品化。
3. IAM/RBAC/secret/egress/审计查询还不是生产后台级别。
4. durable、event、message、webhook、AI tool 的真实运行时还没有完整闭环。
5. 前端仍是示例项目形态，复杂度已经接近需要模块化拆分的边界。

## 4. 本轮迭代复盘

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
| P0 | Shared schema path/type utility | 当前 schema 推断在多个服务重复，越补越容易漂移 | 抽 Java 侧 shared helper，迁移 connection/fit/publication projector/validator 路径解析 |
| P0 | Runtime binding partial-failure 硬化 | 这是 DESIGN artifact 走向可执行 runtime 的主干 | 选一个尚未补偿的跨 repository mutation，补 replay/compensation/诊断 |
| P1 | Browser regression matrix | 当前 UI 能力多，单测/DOM smoke 需要继续扩大 | 覆盖 fit candidates typeless array 的 browser-facing 行为 |
| P1 | 协议文档收敛 | 设计草案与当前 wire contract 名称仍有历史漂移 | 把 candidate/fit/readiness 当前字段写入 protocol v1 |
| P2 | 前端模块化 | `app.js` 已承载太多 authoring 逻辑 | 先抽 schema helper 或 readiness helper，保持测试覆盖 |

## 6. 评估报告维护规则

每轮迭代后必须更新本报告的第 4 章：

1. 写清楚本轮修复的是哪个工业化缺口。
2. 写清楚代码证据和测试证据。
3. 写清楚本轮以后离工业级还差什么。
4. 不允许只写“已完成”；必须说明仍未覆盖的风险面。

这份报告的作用不是给项目贴金，而是阻止示例项目在能力堆叠中失去边界感。
