# Resource Gateway 1.2.1 S3-E 双层变异与验证器反证验证说明

本文记录阶段三切片 `S3-E` 的开发证据。目标是证明 Scenario 不仅能抓住错误业务图，也能抓住错误 World；同时用固定负面对照证明关键验证器仍会拒绝已知危险输入。该切片只处理 payload-free 的计划、指纹、状态和证明，不建立新的业务数据存储通道。

## 双层变异闭环

`WorldMutationPlanner` 从受治理 `WorldSlice` 的真实 BLOGE AST 定位变异点。每个候选绑定基线 fragment、World、Slice、变异种类、AST 坐标、生成源码、编译图和目标指纹；生成后必须重新 parse、compile，并再次通过 `WorldFragmentTestKit` admission。计划只保留指纹和稳定 gap，不保留业务源码或运行 payload。

规划器覆盖删除规则、反转决策条件、替换边界值、改变业务结果、丢弃精确状态写入，以及改变 default 规则优先级。前五类可生成并重建真实候选；BLOGE 的纯 World 准入要求 `otherwise` 必须位于末尾，因此 default 优先级变异会被真实编译/准入拒绝，并记录稳定 `MUTANT_COMPILATION_REJECTED`，不会用普通规则交换冒充该语义。

`WorldMutationEvaluator` 接受完整 Scenario x mutant 矩阵，并与 typed graph mutant closure 组合。图和 World 分别计算计划数、等价数、非等价分母、killed、survived、inconclusive、得分、存活 ID、等价来源和 N/A 原因。所有非等价 mutant，包括 inconclusive 和 unclassified，均进入分母。任一 Scenario 已产生 assertion kill 时，该 kill 不会被其他 Scenario 的 timeout 或执行失败抹除。认证级不达阈值时阻断，探索级只警告；策略允许无可执行 World mutant 时显式返回 `NOT_APPLICABLE`。

`WorldMutationMaterializer` 在运行受审 mutant 前重新核对 World、Slice、计划和 mutant 的精确版本及指纹，再调用 planner 确定性再生。公开报告构造器不信任调用方提供的聚合值，会重新校验 Graph/World 层方向、mutant 数量与唯一 ID、Scenario 父子绑定、killed 计数、aggregate 状态、survivor ID 和等价来源。reason、N/A、ID 和来源均限于机器可读安全字符或强类型枚举，不能借报告字段携带自由文本 payload。

等价 mutant 不能由 survivor 自动转化。收据必须精确绑定租户、用途、计划、基线、mutant、源码、编译图和目标指纹，并由外部 `WorldMutationEquivalenceAuthority` 验证。默认 authority 拒绝全部收据；独立语义证明或人工复核通过后，收据才进入原子 replay ledger。错绑、篡改、跨租户、伪造 target 和重复消费全部失败关闭。

## 验证器反证闭环

`ValidatorAdversarialCorpus` 固定以下六类 must-reject case：

- 脱敏器遗漏凭证或自由文本身份信息；
- 影响分析漏掉 `OBSERVED_ONLY`；
- 迁移器猜测无标签契约；
- 保真比较器放过破坏性 schema 差异；
- mutation evaluator 将 survivor 标记为 killed；
- 审批收据引用错误候选版本。

每个 case 精确绑定 validator、用途、source schema、算法版本、策略和 golden fingerprint。只有独立 `ValidatorReceiptAuthority` 验证的拒绝收据才能关闭 case；缺失、通过、重复、额外、错绑、篡改、未授权和跨租户收据均阻断。source schema、算法、策略或 golden 漂移时，整组结果进入 `STALE_GOLDEN`，不能沿用旧证明。

报告只输出稳定 case code、状态、安全诊断和 fingerprint。测试使用 payload canary 扫描计划、报告、异常和 `toString()`，证明默认输出不回显凭证、身份文本或业务值。

## 固定证明

聚焦测试覆盖：

- 真实 BLOGE AST 的确定性规划、生成、重编译、准入和再生；
- 五类可执行变异与 default 优先级稳定 compilation gap；
- 有状态 `stateWrites` 精确 entry 删除；
- 图与 World 的完整非等价分母、存活 ID、N/A 和独立得分；
- 多 Scenario kill 优先、inconclusive/unclassified 不隐藏；
- World/Slice 漂移、未知 mutant 和公开报告统计伪造失败关闭；
- 外部等价 authority、精确 target binding、跨租户、篡改和并发 replay；
- 六类 must-reject corpus 的闭包、外部收据 authority 和 20 次确定性；
- source schema、算法、策略和 golden staleness；
- payload-free 诊断、报告、异常和 canary 扫描。

验证命令及结果：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='com.leanowtech.bloge.gateway.testing.world.mutation.*Test,com.leanowtech.bloge.gateway.testing.verification.*Test' test
53 tests / 0 failures / 0 errors / 0 skipped

mvn -f resource-gateway-examples/pom.xml clean verify
7554 tests / 0 failures / 0 errors / 28 skipped
BUILD SUCCESS
```

## 边界

`S3-E` 关闭 `S3-EXIT-14..16` 的仓库内开发证明，不代表阶段三整体完成。capture、redact、review、draft、run 和 impact 的真实系统闭环，以及 Resource Gateway/Test Kit 双项目最终里程碑，由 `S3-F` 闭合。企业部署仍需对外部审批 authority、持久化 replay 防护、并发容量、审计保留、DLP 和生产隔离进行环境验收。
