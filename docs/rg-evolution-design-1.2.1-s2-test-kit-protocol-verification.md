# Resource Gateway 1.2.1 S2-E2b Test Kit 协议验证说明

本文记录阶段二最终切片 `S2-E2b` 的开发证据。目标不是在 Test Kit 中复制服务端领域模型，而是让独立客户端以机器可读 Schema 为权威，严格消费函数控制请求、能力探针和 payload-free 控制证据。

## 1. 独立协议模型

- `TestControlEnvelope` 保持 Scenario/World exactly-one 主引用，并增加可选 `functionControl` 精确引用；旧四参数构造方式继续可用。
- `TestControlHeaderCodec` 只接受 canonical Base64URL、严格 UTF-8 和有界 JSON；重复键、未知字段、inline rules/declarations/compiled plan、错误类型和超限输入均失败关闭。
- `ResourceGatewayTestClient.execute(request, envelope)` 只把控制信封写入 `X-BLOGE-Test-Envelope`，业务 Body 不变；信封 purpose 与请求 purpose 不一致时在本地拒绝。
- Test Kit 不依赖 Resource Gateway server artifact 或 Spring Boot。

## 2. 能力降级

`ResourceGatewayCapabilities` 只有同时观察到以下事实才报告函数控制可用：

- `functionControlAssetReference`、`functionControlGovernedCatalog`、`functionControlStateComposition` 和 `functionControlPayloadFreeEvidence` 四个 feature flag 全部为真；
- `functionControlAsset` 对象包含 `bloge.functionControlAsset.v1`；
- envelope、声明、规则、时长、消费、JSON 和 Schema 上限完整存在且为正数。

缺少 provider、任一旗标、对象版本或权威上限时不得误报完整能力；对象与 feature 广告矛盾时失败关闭。

## 3. 控制证据验证

`TestRunControlEvidenceProjection` 和 `TestRunControlEvidenceVerifier` 形成三层独立校验：

1. packaged `testing-control-plane-v1.schema.json` 拒绝未知字段、缺失字段、错误类型和超限集合；
2. 领域不变量校验枚举、消费边界、结果/错误互斥、唯一项、调用点派生 `siteKey`、Observation 必须属于 Binding，以及 Base64URL 分段的无碰撞动态坐标；
3. 按服务端公开 canonical material 分别重算 state、function 和顶层 projection fingerprint，并绑定 TestRun 的 run、target 和 execution plan；调用方还可校验 Scenario、World 与 function plan 精确绑定。

模型不含状态值、函数参数、返回值、错误文本或 schema。异常固定净化，不保留可能包含 payload 的原始 cause；历史无控制证据仍返回 `null`，保持兼容。

## 4. 固定负向矩阵

- 非 canonical Base64URL、非法 UTF-8、重复键、尾随 JSON 和超限 Header；
- inline 规则、未知字段、错误引用类型和 Scenario/World 二选一破坏；
- capability provider 缺失、四旗标不完整、对象版本或 limits 不一致；
- projection/state/function 指纹篡改，run/target/plan/Scenario/World 错绑；
- 未知枚举、伪造 siteKey、重复 binding/consumption/observation；
- payload 字段和保留 metadata 伪造；
- 包含分隔符的动态坐标碰撞。

## 5. 验证结果

最终收口聚焦回归：

```bash
mvn -f resource-gateway-test-kit/pom.xml \
  -Dtest=TestControlHeaderCodecTest,ResourceGatewayCapabilitiesTest,TestRunControlEvidenceProjectionTest,ResourceGatewayTestClientTest \
  test
```

结果：64 tests，0 failures，0 errors，0 skipped。

Test Kit 最终里程碑：

```bash
mvn -f resource-gateway-test-kit/pom.xml clean verify
```

结果：Surefire 1920 tests、Failsafe 2 tests，0 failures，0 errors，0 skipped；Schema 打包、A1 archive boundary 和 public API/Javadoc 门禁通过。

Resource Gateway 最终里程碑：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

结果：7403 tests，0 failures，0 errors，28 skipped。`git diff --check` 通过。

阶段二全部切片和双项目固定分母已经闭合，可标记为 `DEVELOPMENT_VERIFIED`。这仍是仓库内开发验证，不替代企业部署环境的安全、容量与治理责任人签署。
