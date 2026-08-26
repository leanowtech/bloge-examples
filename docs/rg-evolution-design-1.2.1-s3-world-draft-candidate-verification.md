# Resource Gateway 1.2.1 S3-A World 草稿候选验证说明

本文记录阶段三首个切片 `S3-A` 的开发证据。目标是让受治理的真实观测只能生成可审阅的 World 草稿，并在明确审批和发布授权后成为可跨重启执行、可撤销且默认不暴露业务 payload 的治理资产。

## 实现闭环

受支持来源固定为 `GOLDEN_CAPTURE`、`REPLAY_PAYLOAD`、`RUN_EVIDENCE` 和 `CAPABILITY_CORPUS_TRAJECTORY`。每类来源通过独立 adapter 接入统一 router；授权、租户、过期、schema 和来源指纹在 payload 读取前完成校验。任意 inline payload 不能伪装为沉淀来源。

候选采用 `CAPTURED -> REDACTED -> REVIEW_READY -> APPROVED -> MATERIALIZED_DRAFT -> PUBLISHED` 的 CAS 生命周期。schema 引导脱敏先处理已知字段，未知字段默认删除，最终树 DLP 对残留敏感值失败关闭。审批和发布 receipt 由外部 authority 签发，精确绑定候选 revision、来源、schema、脱敏策略和物化指纹。

物化器生成纯 BLOGE World Fragment，并使用真实 `WorldFragmentTestKit` 编译和执行。草稿资产、来源证明和规则投影持久化时不包含请求或响应 payload。响应值存入 AES-GCM 保护的 vault，AAD 和 commitment 绑定租户、候选、artifact revision 及请求/响应指纹。

发布事务把 receipt、治理目录发布、草稿资产状态、候选 CAS 与 vault pin 放在同一事务边界。发布后的规则只保存 `WorldDraftRedactedPayloadRef`；`WorldDraftPublishedBehaviorRuntime` 在执行时校验精确请求指纹和发布绑定，再授权读取响应并注入服务端保留 context。普通过期清理不会删除 pinned 行；显式撤销后运行失败关闭。

## 固定证明

聚焦测试覆盖：

- 四类来源路由、metadata-first admission 和零 payload read 负向路径；
- schema 引导脱敏、未知字段删除、最终树 DLP、数值身份与确定性 token；
- 候选状态机、并发 CAS、伪造状态、审批和发布绑定漂移；
- PostgreSQL/H2 DDL、租户复合主键、加密 payload、commitment 和 retention；
- 真实 BLOGE 物化、草稿资产持久化和治理目录发布；
- 内存与数据库发布事务的故障注入、回滚和可重试性；
- 新 repository/runtime 实例从持久化资产重载，精确请求 A 返回响应 A，请求 B 不匹配；
- pinned 数据不被普通 purge 删除，撤销、篡改和跨租户访问失败；
- 资产 JSON、目录、审计、错误与对象字符串不含业务 payload。

验证命令及结果：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='com.leanowtech.bloge.gateway.testing.world.draft.*Test' test
36 tests / 0 failures / 0 errors / 0 skipped

mvn -f resource-gateway-examples/pom.xml clean verify
7439 tests / 0 failures / 0 errors / 28 skipped
BUILD SUCCESS
```

新增未跟踪文件逐项执行 `git diff --no-index --check`，未发现空白错误。

## 边界

`S3-A` 只证明 World 草稿从受治理观测到安全发布及发布后可执行的闭环，不代表阶段三整体完成。静态依赖与运行消费投影、存量测试迁移、真实 API/World 保真校准、双层变异和双项目最终里程碑分别由 `S3-B..F` 闭合。仓库内开发验证也不替代企业生产环境的 DLP、审批系统、KMS、备份恢复、容量与数据保留认证。
