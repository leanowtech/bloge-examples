# Resource Gateway 1.2.1 S2-E2a 治理函数 HTTP 链验证说明

本文记录 `S2-E2a` 的可复现开发证据。它证明治理函数控制已经贯通权威编译产物、受控资产目录、真实 HTTP、状态会话、证据持久化和生产双层隔离；Test Kit 协议消费与双项目最终里程碑随后由 [S2-E2b](./rg-evolution-design-1.2.1-s2-test-kit-protocol-verification.md) 闭合。

## 1. 协议与资产边界

- `bloge.functionControlAsset.v1` 是不可变、带稳定指纹的治理资产，保存函数声明和控制规则，仅存在于服务端治理目录。
- `bloge.testControlEnvelope.v1` 保持 Scenario 或 World 二选一的主引用，并增加可选 `functionControl` 精确引用；引用仅含 `id`、`revision` 和 `fingerprint`，拒绝 inline rules、函数声明或调用方编译计划。
- 授权顺序固定为读权限、元数据读取、精确引用与治理指纹校验、过期判断、元数据策略授权、payload 读取。未授权、跨租户、缺失、篡改、过期和策略拒绝均不会读取 payload。
- PostgreSQL 迁移和 H2 约束均登记 `FUNCTION_CONTROL`，旧治理资产种类保持兼容。

## 2. 权威编译与执行

- BLOGE Spring 启动时只编译一次 `.bloge` 文件，`CompiledGraphCatalog` 同时保存 `CompiledGraph`、原 `Graph` 对象身份和编译器函数注册快照。
- Resource Gateway 只接受目录中的治理资产；函数调用清单由服务端从请求目标对应的同一 `CompiledGraph` 构造，不重编译，不接受调用方 inventory 或 plan。
- 函数控制先绑定目标 fingerprint，再以当前调用清单、运行时函数事实和冻结规则编译。无函数控制引用时严格走原有三参数 World 路径，避免改变旧 profile 和旧调用行为。
- 状态会话和函数控制可在同一 run 中组合。运行结果、函数消费和状态事务进入 `bloge.testRunControlEvidence.v1`；参数、返回值、错误文本和状态值不进入公开证据。

## 3. 能力与隔离

- `CompiledFunctionInventoryProvider` 只在 `CompiledGraphCatalog` Bean 实际存在时装配。
- capability probe 仅在 provider 可用时宣告函数治理资产、精确引用、状态组合和 payload-free evidence；所有上限来自 `FunctionControlLimits.CURRENT` 单一描述符。
- production Filter 在反序列化前拒绝带函数控制引用的测试请求；即使绕过 Filter，服务层仍在解析资产、规划、目录读取、运行和证据写入前独立拒绝。
- 旧部署没有编译产物目录时仍可启动并运行旧 Scenario/World 路径；携带函数控制引用时固定失败关闭，且 capability 不得误报支持。

## 4. 固定验证矩阵

真实 Spring HTTP 系统测试覆盖：

- World 委托节点输出被后继内置函数消费，并按治理规则返回受控结果；
- 函数与状态控制同 run 组合，状态事务和函数观察同时进入外部证据；
- 同一输入连续运行 20 次，语义结果与控制证据稳定；
- 4 路并发运行互不串扰，并处于既有 admission 容量边界内；
- 引用篡改、inline 规则、跨租户、过期、危险控制和未授权访问失败关闭；
- POST 后 GET 读回的证据不含业务 payload。

两阶段生产隔离测试覆盖：

- 真实 Filter 链拒绝后，body 反序列化和下游服务调用均为零；
- 服务层旁路测试中，Graph、Operator、Resource、Fixture、Run、Replay、Evidence、World、Function、Catalog 和 Admission 依赖调用均为零，只记录安全事件。

## 5. 验证结果

BLOGE 权威编译产物链：

```bash
mvn -f tmp/bloge/pom.xml -pl bloge-core,bloge-spring -am \
  -Dtest='CompiledGraphCatalogTest,BlogeAutoConfigurationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：20 个 Reactor 模块全部成功；目标测试 50 项，0 failures，0 errors，0 skipped。

Resource Gateway 独立聚焦回归：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest='TestRuntimeGovernedHttpSystemTest,TestExecutionProductionIngressBypassTest,AuthorizedFunctionControlAssetResolverTest,FunctionControlAssetCodecTest,TestControlEnvelopeCompatibilityTest,Stage0Exit04ProductionBypassMatrixTest,TestabilityCapabilitiesTest,TestExecutionApiServiceTest' test
```

结果：92 tests，0 failures，0 errors，0 skipped。

Resource Gateway 全量里程碑：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

结果：7403 tests，0 failures，0 errors，28 skipped；`BUILD SUCCESS`。两仓库 `git diff --check` 均通过。

## 6. 后续闭合

`S2-E2b` 已让 `resource-gateway-test-kit` 成为独立协议消费者：可选函数控制精确引用、capability limits、控制证据严格校验、兼容 fixture 和负向矩阵均已固定；双项目里程碑全绿后，阶段二已标记为 `DEVELOPMENT_VERIFIED`。
