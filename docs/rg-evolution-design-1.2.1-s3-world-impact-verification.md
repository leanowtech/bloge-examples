# Resource Gateway 1.2.1 S3-B World 影响索引验证说明

本文记录阶段三切片 `S3-B` 的开发证据。目标是把剧情的静态声明和真实运行消费分别投影为可重建、可审计、不携带业务 payload 的事实索引，并在契约变化时给发布门禁提供不会错误缩小的回归范围。

## 实现闭环

静态索引以 `Scenario`、`ResourceWorldModel` 和 `WorldScenarioCompilation` 为唯一输入，校验剧情、World、逻辑契约、World slice、BLOGE fragment、目标图和调用点的精确指纹链。编译器拥有的双向 source map 必须闭合且无歧义；额外链接、重复 binding、缺失 slice 或目标漂移均失败关闭，不从 DSL 文本、业务 payload 或松散 `Map` 猜测依赖。

运行索引只消费经 `TestRunRecordIntegrity` 和 `TestEvidenceIntegrityService` 验证的终态 evidence。fixture 消费、node trace、调用点、逻辑契约、World rule、slice 和 fragment 必须能回到同一权威编译产物。未知 fixture、异常调用点或 fixture bundle 指纹漂移不会被静默忽略。

静态与运行快照采用独立水位和不可变版本，内存实现用于隔离测试，JDBC 实现支持 PostgreSQL `JSONB` 和 H2 测试迁移。读取时重新构造强类型对象并验证指纹、租户和资源身份；同一身份写入不同内容触发冲突。

`WorldImpactReconciliation` 区分 `DECLARED_AND_OBSERVED`、`DECLARED_ONLY` 和 `OBSERVED_ONLY`，后者直接阻断发布。契约影响报告绑定算法版本、新旧契约指纹、静态/运行水位及 evidence 时间窗。breaking 变更使用全部静态依赖剧情作为分母；缺少静态或运行分母、索引陈旧时返回 `UNKNOWN` 和显式 scope 状态，`gateBlocked()` 必为 `true`，即使 affected 集为空也不能被解释为安全。

## 固定证明

聚焦测试覆盖：

- 精确编译 source map 链、歧义 binding、额外链接、目标和 fixture bundle 漂移；
- 完整性已验证 evidence 的消费投影，以及未知 fixture 和异常调用点失败关闭；
- declared-only、observed-only 和完整依赖链一致性校验；
- compatible 选择性范围、breaking 全静态分母及错契约拒绝；
- 缺失静态分母、缺失运行分母、陈旧水位、跨租户污染和重复身份；
- 内存并发水位、数据库跨实例恢复、PostgreSQL/H2 DDL 差异；
- 快照、reconciliation 和报告连续 20 次确定性重建；
- 对象、JSON、数据库 canonical JSON、异常和字符串输出的 payload canary 扫描。

验证命令及结果：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='com.leanowtech.bloge.gateway.testing.world.impact.*Test' test
23 tests / 0 failures / 0 errors / 0 skipped

mvn -f resource-gateway-examples/pom.xml clean verify
7462 tests / 0 failures / 0 errors / 28 skipped
BUILD SUCCESS
```

`git diff --check` 通过。

## 边界

`S3-B` 证明仓库内影响事实、持久化和失败关闭语义，不代表阶段三整体完成。存量测试迁移、真实 API 与 World 保真校准、双层变异和双项目系统里程碑由 `S3-C..F` 闭合。当前 PostgreSQL DDL 已受固定测试约束，但仍需在企业部署验收中执行独立 PostgreSQL 实库迁移、备份恢复、容量和并发压力演练。
