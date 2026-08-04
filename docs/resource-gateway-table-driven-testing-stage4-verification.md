# Resource Gateway 表格驱动测试 Stage 4 验证记录

> 日期：2026-08-04
>
> 对应设计：[产品设计](resource-gateway-table-driven-testing-product-design.md)
>
> 关键决策：[ADR-005](adr/ADR-005-coverage-candidate-generation-boundary.md)
>
> 结论：Coverage-guided authoring 主链完成；Stage 5 企业协作与规模化 next。

## 1. 用户可见能力

进入 `Author -> Scenarios -> Coverage` 后，页面以六个独立分母展示：

| 维度 | 当前可定位事实 |
|---|---|
| Case intent | GOLDEN、NEGATIVE、BOUNDARY、REGRESSION、PROPERTY |
| Contract | input presence、required missing/null、enum、min/max、string length、union、output expectation、error variant |
| DAG | node、普通/条件 edge、fallback、retry |
| Dependency | 每个已知坐标的 RETURN、ERROR、TIMEOUT、MUST_NOT_CALL |
| Assertion | output path、node status、edge transfer、invocation |
| Evidence | 每个 case 的 current successful execution、passing business oracle、schema 以上 proof |

每个维度显示 `covered / total / gaps`，但没有 opaque overall score。点击维度只换 read projection；
不会改变 target、Scenario selection 或 canonical 数据。

Gap 表给出 label、精确 coordinate、原因和下一动作。支持生成的 gap 可点 **Target gap**。生成区
在执行前显示 seed、max cases 与 max work units；只有点击 **Generate candidates** 才运行。
`Pairwise: Not installed` 是真实 capability 状态，不是一个点击后失败的装饰按钮。

候选 review 行显示 case intent、rationale、generator id/version、named contribution 和
`Needs oracle`。**Reject** 只隐藏当前临时建议；**Accept** 通过 exact source command 追加 canonical
Scenario。接受一个后 source projection 变化，其余候选统一 stale；切到 **Case** 后补 Given、
Dependencies 和业务 assertion，再运行取得 Evidence。

## 2. 领域与协议实现

`coverageModel.ts` 提供三个纯边界：

1. `buildCoverageProjection(...)` 从 exact Graph、Contract、Scenario 和当前行级 Evidence 重建六维
   denominator；projection fingerprint 使用 canonical JSON + SHA-256。
2. `generateCoverageCandidates(...)` 只消费 uncovered fact，按 generator version + seed 生成稳定顺序，
   同时执行 max candidate 与 work-unit 预算。
3. `acceptCoverageCandidate(...)` 是临时 candidate 进入 canonical `ScenarioDraftSet` 的唯一 command，
   target/Contract/set revision/projection 任一不一致即拒绝。

严格 wire authority：

- [Coverage Projection v1](schemas/bloge-coverage-projection-v1.schema.json)
- [Coverage Candidate Set v1](schemas/bloge-coverage-candidate-set-v1.schema.json)
- Candidate proposal 复用 [Scenario Draft Set v1](schemas/bloge-scenario-draft-set-v1.schema.json#/$defs/scenario)

Projection 固定六个有序维度并禁止额外字段；Candidate Set 固定 source closure、版本、seed、预算、
贡献、readiness 和 proposal。`promotionEligible` 在 v1 schema 中固定为 `false`。

## 3. Generator 边界

内置 `bloge.schema-boundary@1.0.0` 支持 required missing、null、数值/长度上下界、enum 合法/非法、
union variant 和 error-contract intent。`bloge.dependency-behavior@1.0.0` 支持已知依赖的 RETURN、
ERROR、TIMEOUT、MUST_NOT_CALL。它们都会：

- 从稳定 base Scenario 或 Contract sample 开始；
- 清空继承 assertion；
- 写入 `GENERATED` provenance 与 coverage fact tag；
- 标记 `Needs oracle / promotionEligible=false`；
- 不在 log、session storage 或服务端持久层写入候选 payload。

`CoverageCandidateGenerator` 是组合与领域 generator 的独立 SPI。Pairwise 首期不实现；其约束支持、
稳定顺序、预算、取消、许可证和 golden vectors 门禁见 ADR-005。

## 4. 正确性与安全不变量

1. Authoring projection 不替代 testing control plane 的 signed semantic coverage。
2. 静态 DAG 可达推断只说明 planning path；真实 branch/node/edge coverage 仍需 Evidence。
3. 没有 business assertion 的成功执行也只覆盖 execution fact，不覆盖 oracle fact。
4. 相同 exact input、generator version、seed 和预算生成相同 candidate fingerprint 顺序。
5. 不同 seed 只改变确定性探索顺序，不改变 Contract 或 Scenario 真相。
6. 删除 generated Scenario 后重新打开 Coverage 不会自动重建它。
7. stale candidate、重复 scenario id、非法预算和 projection/source 不匹配均 fail closed。
8. 一次 Accept 后不能继续使用基于旧 denominator 的建议。

## 5. 测试证据

聚焦模型与组件测试覆盖：六维分母、required/null/enum/min/max/length/union/error、DAG
branch/fallback/retry、四种 dependency behavior、assertion/evidence 独立性、seed 稳定性、双预算、
500-case corpus、无自动生成、无自动 oracle、Accept/Reject 与 stale 拒绝。

`CoverageGenerationProtocolSchemaTest` 冻结严格 Schema、六维顺序、SHA-256 source closure、预算上界、
Scenario proposal ref 和 `promotionEligible=false`。`VisualAuthoringBrowserDomTest` 在打包后的真实
Chrome 中走完整 Loan 示例，检查桌面/手机无页面溢出、候选明确 Needs oracle、Accept 后旧候选失效，
并回到 Case 深编辑。

Stage 4 前端全量为 `60 files / 484 tests passed`，生产 build 通过；packaged Chrome Coverage
路径在 `1280 x 800` 与 `390 x 844` 通过。浏览器验收发现并修复了 mobile candidate action
24px 热区，复验稳定为 32px。Resource Gateway `clean verify` 最终为 `5882 tests, 0 failures,
0 errors, 13 skipped`，于 2026-08-05 完成，用时 10 分 18 秒；完整阶段状态见
[实施状态](resource-gateway-table-driven-testing-implementation-status.md)。
