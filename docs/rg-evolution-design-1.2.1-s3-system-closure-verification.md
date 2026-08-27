# Resource Gateway 1.2.1 S3-F 系统闭环验证说明

本文记录阶段三切片 `S3-F` 的仓库内开发证据。目标是证明阶段三各领域能力已通过真实系统对象形成一条可执行、可审计、可失败关闭的闭环，而不是只由相互隔离的单元测试分别成立。

## 真实闭环

`WorldStageThreeSystemClosureTest` 使用真实候选服务、受治理 `GOLDEN_CAPTURE` 路由、schema 引导脱敏器、服务端审批与发布 authority、服务端 World 物化器、原子发布事务、BLOGE 场景编译器、发布态运行时、证据完整性服务和影响索引服务，执行以下链路：

```text
capture -> redact -> review-ready -> approve -> materialize draft
        -> publish -> zero-egress run -> integrity-sealed evidence
        -> static/runtime impact index -> reconcile -> impact analysis
```

物化结果首先保持未发布；发布后，运行时只从已发布资产和绑定的脱敏 payload 读取行为。场景编译保留逻辑契约到精确调用点的来源映射。运行结果来自已发布行为的真实执行，并进入语义指纹和完整性封印；只有完整性验证通过的 `TestRunRecord` 才能建立运行时消费索引。静态依赖与运行消费重建后，协调结果为 `DECLARED_AND_OBSERVED`，影响报告保留完整 Scenario 分母。

## 失败关闭矩阵

固定测试覆盖以下边界：

| 风险 | 固定证明 |
|---|---|
| inline 来源伪装 | 领域枚举不提供 `INLINE` 来源类型 |
| 未授权或跨租户 | 在 payload `read` 前拒绝，读取计数保持为零 |
| 过期或完整性篡改 | 在 payload `read` 前拒绝，读取计数保持为零 |
| production purpose | `Access` 构造阶段拒绝，不进入来源服务 |
| DLP 残留 | 脱敏失败，候选保持 `CAPTURED`，不能进入评审 |
| 来源、schema 或策略漂移 | 审批失效，不能物化或发布 |
| vault 或物化器故障 | 不生成资产，候选保持原合法状态 |
| 发布事务故障 | 候选、资产、回执和已发布 payload binding 一并回滚 |
| payload 泄漏 | 候选、草稿、资产、证据、索引、报告和异常均通过 canary 扫描 |

## 阶段退出条件

`S3-A` 已闭合 `S3-EXIT-01..05`，`S3-B` 闭合 `06..08`，`S3-C` 闭合 `09..11`，`S3-D` 闭合 `12..13`，`S3-E` 闭合 `14..16`。本切片通过真实跨域链路复核这些不变量，并以双项目全量门禁闭合 `S3-EXIT-17`。因此，`S3-EXIT-01..17` 在仓库内开发验证层面全部为 `MET`。

## 固定证明

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='WorldStageThreeSystemClosureTest' test
5 tests / 0 failures / 0 errors / 0 skipped

WorldStageThreeSystemClosureTest and affected security/runtime/transaction/impact tests
30 tests / 0 failures / 0 errors / 0 skipped

mvn -f resource-gateway-examples/pom.xml clean verify
7559 tests / 0 failures / 0 errors / 28 skipped
BUILD SUCCESS

mvn -f resource-gateway-test-kit/pom.xml clean verify
1920 unit tests / 0 failures / 0 errors / 0 skipped
2 integration tests / 0 failures / 0 errors / 0 skipped
A1 ARCHIVE BOUNDARY PASSED
BUILD SUCCESS
```

## 验收边界

`DEVELOPMENT_VERIFIED` 只表示仓库实现和固定自动化分母已闭合。企业部署仍需针对外部审批 authority、企业 DLP、密钥和回执持久化、生产网络隔离、并发容量、故障恢复、审计保留周期和责任人签署执行环境验收；这些外部事实不能由进程内测试代替。
