# Resource Gateway 1.2.1 S3-C 存量测试迁移验证说明

本文记录阶段三切片 `S3-C` 的开发证据。目标是在不删除、不改写、不隐式发布旧测试资产的前提下，将精确版本的 FixtureBundle 与 TestSuite 单向抬升为可继续编辑和物化的未发布草稿。

## 实现闭环

迁移源是只读 authority，读取请求必须精确绑定租户、FixtureBundle id/revision/fingerprint、TestSuite id/revision/fingerprint 和目标图 fingerprint。服务重新执行存储信封完整性校验，并校验 FixtureBundle、TestSuite、WorldScenarioCompilation、InvocationInventory 和双向 source map 属于同一目标；错租户、篡改、歧义或额外映射均失败关闭。

Scenario 产物直接复用现有 `ScenarioDraftSet`，保留测试输入、依赖行为、消费策略、schema check 和断言值，来源标记为 `MIGRATED`。迁移阶段不伪造 ContractDraft fingerprint；未绑定权威契约时保留空值并给出前置条件诊断，精确 rebind 后通过既有 `ScenarioValidationService` 和 `ScenarioSimulationCompiler`。

World 产物是 `WorldDraftMaterializationPlan`，不是第二套 World 模型，也不提前宣称已经 materialized。每条规则只保存 FixtureBundle 和 rule 的精确 legacy 引用、逻辑契约指纹、调用点和 payload 指纹。计划使用独立的 `NEEDS_PREREQUISITES` / `READY_TO_MATERIALIZE` 状态；server-owned materializer 再从只读来源解析实际规则并产出现有 `WorldDraftRule`。legacy fixture 不会伪装成 `GOLDEN_CAPTURE` 或其他受治理来源。

RETURN、冻结 REPLAY、THROW 和 TIMEOUT 可形成物化计划；schema stand-in 只能形成探索级草稿。无逻辑契约标签的 node fixture 保持未映射；SPY、ALLOW_REAL、模糊 selector 和未冻结 replay 进入诊断与人工补全清单。执行服务配置进入 Scenario 控制元数据，不被转换为 World 外部资源规则。

迁移包携带旧资产到草稿及草稿到旧资产的双向来源映射，并绑定算法、全部源 fingerprint、图编译 fingerprint 和调用清单 fingerprint。原子 sink 在故障时零写入，重复提交同一迁移包幂等；迁移接口没有 publish 或 legacy mutation 操作。

## 固定证明

聚焦测试覆盖：

- RETURN、冻结 REPLAY、THROW、TIMEOUT 和 schema stand-in 映射；
- 无标签不猜测，以及 SPY、ALLOW_REAL、模糊 selector、未冻结 replay 诊断；
- 多 case TestSuite 到现有 ScenarioDraftSet，输入与断言值保持；
- 未绑定 ContractDraft 时验证阻断，精确 rebind 后通过现有 validation/compiler；
- 精确 legacy rule 解析到现有 WorldDraftRule，缺少输入前置条件时阻断；
- 双向来源映射、未发布状态、旧资产字节不变和旧 API 回归；
- sink 故障零部分写入、幂等重复提交、跨租户和指纹篡改拒绝；
- 断言 canonical fingerprint id、重排稳定、重复内容失败关闭和 20 次确定性；
- payload-free projection、错误与 `toString()` 的 canary 扫描。

验证命令及结果：

```text
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='com.leanowtech.bloge.gateway.testing.world.migration.*Test' test
18 tests / 0 failures / 0 errors / 0 skipped

mvn -f resource-gateway-examples/pom.xml clean verify
7480 tests / 0 failures / 0 errors / 28 skipped
BUILD SUCCESS
```

`git diff --check` 通过。

## 边界

`S3-C` 证明单向迁移协议、可用 authoring 草稿、World 物化计划和旧协议兼容，不代表阶段三整体完成。计划完成后的 World 目录持久化、真实 API/World 保真校准、双层变异和双项目系统里程碑由后续切片闭合。生产部署仍需对 legacy source 授权、草稿 repository 事务接线、容量、保留和审计策略进行环境验收。
