# Resource Gateway 1.2.1 S3-D 世界保真校准验证说明

本文记录阶段三切片 `S3-D` 的开发证据。目标是在严格隔离的非生产环境内，用固定样本同时运行真实资源实现与已发布 World，检测破坏性 schema 和业务语义漂移，并将当前漂移状态投影为新证据等级与发布门禁。校准结果不能成为新的业务 payload 存储通道，也不能改写历史 evidence。

## 实现闭环

`WorldFidelityRequest` 精确绑定租户、作用域、固定用途、环境、逻辑契约、实现 fingerprint、World Slice fingerprint、策略 fingerprint、完整且新鲜的样本集，以及完整且新鲜的比较器。服务先调用 server-owned authority 重新解析实际目标，再核对所有绑定；用途不匹配、生产环境、未授权来源、未发布 World、真实实现不可用、样本或策略漂移均在任一 runner 调用前失败关闭。

`WorldFidelityCalibrationService` 将每个样本规范化一次，再向真实实现 runner 和 World runner 发送互不共享的副本。比较器覆盖 required/type、结构和值、数值容差、错误类别、HTTP 状态、重试标记和状态迁移；延迟差只保留为诊断。任一 runner 执行失败时结论为 `UNKNOWN` 或 `DIFFERENT`，双方同时失败时仍为 `UNKNOWN`，不会把共同失败误判为等价。

`WorldFidelityReport` 只包含样本、响应、状态迁移和版本的 fingerprint，以及安全错误类别、状态码、差异路径和延迟差。报告不保存请求或响应原文。数据库进一步使用显式 `PayloadFreeReportProjection`，即使运行时报告未来扩展字段，也不会默认把新增 payload 写入持久层。

`WorldFidelityDriftService` 管理 `CURRENT`、`SUSPECTED`、`CONFIRMED`、`REMEDIATING` 和 `ACCEPTED_DIVERGENCE`。观测只能初始化状态，或将 `CURRENT` 降为 `SUSPECTED`；不能自动跨越人工确认、修复或接受偏差流程。接受偏差需要外部 authority 验证精确绑定的审批收据，状态 CAS 与收据消费在同一原子操作中完成。

PostgreSQL/H2 repository 保存 append-only 报告历史、租户和目标隔离的当前 head，以及一次性收据。历史报告可跨 repository 实例恢复，篡改投影会失败关闭。`WorldFidelityPolicyService` 根据当前 drift head 生成独立的 payload-free 决策；历史 `TestRunEvidence` 在决策前后保持字节一致。缺失 drift head、`SUSPECTED`、`CONFIRMED` 或 `REMEDIATING` 均不能形成认证发布结论。

## 固定证明

聚焦测试覆盖：

- 延迟差不影响等价判断，required/type、值与容差差异可检测；
- 错误类别、状态码、重试和状态迁移差异可检测；
- production、错误用途、未授权、跨租户、目标和策略漂移在 runner 前拒绝；
- 单侧失败、超时和双方失败全部失败关闭；
- 双 runner 使用独立规范请求副本，并发校准不共享运行状态；
- 报告 round-trip、20 次重放、错误和 `toString()` 的 payload canary 扫描；
- 完整 drift 生命周期、非法跳转、并发 CAS 和新观测不得自动清除受治理状态；
- 原子收据消费、失败不烧毁收据、并发复用只允许一个胜者；
- 内存与数据库历史按租户和目标索引，数据库跨实例恢复；
- PostgreSQL/H2 migration、持久化篡改与跨租户边界；
- 当前 drift 策略注释历史 evidence，原 evidence 字节不变，缺失状态失败关闭。

验证命令及结果：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='com.leanowtech.bloge.gateway.testing.world.fidelity.*Test' test
21 tests / 0 failures / 0 errors / 0 skipped

mvn -f resource-gateway-examples/pom.xml clean verify
7501 tests / 0 failures / 0 errors / 28 skipped
BUILD SUCCESS
```

## 边界

`S3-D` 证明受控校准、结构化差异、漂移治理和证据门禁，不代表阶段三整体完成。图与 World 双层变异、验证器反证和完整 capture-to-impact 系统闭环由 `S3-E..F` 闭合。生产部署仍需对非生产环境隔离、真实实现 authority、调度容量、校准频率、审批系统、数据库备份恢复和审计保留进行环境验收。
