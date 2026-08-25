# S1-A 逻辑资源契约核心实现说明

本文记录 `rg-evolution-design-1.2.1` 阶段一 S1-A 已实现的代码边界和验收结果。本文不扩展主设计，仅用于后续开发、评审和集成时快速定位实现入口。

## 实现范围

实现位于：

```text
resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/testing/world/
```

逻辑资源契约独立于 `provider` 和 `apiVersion`。具体服务商及其 API 版本只出现在实现绑定中，不参与逻辑契约身份和 `contractFingerprint` 计算。

### 公开类型与入口

| 类型 | 入口 | 作用 |
|---|---|---|
| `LogicalResourceContract` | 构造函数 | 定义 `contractId`、输入结构、输出结构和响应语义，并生成 canonical `contractFingerprint`。构造过程执行规范化、防御拷贝和失败关闭校验。 |
| `ResponseSemantics` | `unknown()`、`confirmed(...)` | 表达成功条件、错误分类、幂等性和可重试性；`requiresReview()` 判断是否仍需人工确认。 |
| `LogicalResourceContractProjector` | `project(ResourceDesignContract, VisualResourceDescriptor)` | 从现有资源设计契约和描述符生成待确认的逻辑契约初稿。 |
| `LogicalResourceContractProjection` | 投影结果 | 携带初稿、确认状态、描述符指纹和待确认字段。 |
| `LogicalResourceBinding` | `bind(provider, apiVersion, providerDesign, descriptor, contract)` | 将具体资源实现绑定到逻辑契约。绑定前校验资源身份、人工确认状态和结构化输出兼容性。 |
| `LogicalResourceContractCompatibility` | `analyze(baseline, candidate)` | 分析逻辑契约修订的结构兼容性和语义确认状态，输出稳定、可审计的 findings。 |
| `LogicalResourceContractException` | `code()` | 返回稳定、无业务载荷的机器错误码；错误消息不输出 schema 内容、凭据或业务数据。 |

`LogicalResourceContractCanonicalizer` 是包内实现，不属于公开集成入口。

## 投影与人工确认边界

`LogicalResourceContractProjector.project(...)` 只做现有信息可以直接证明的投影，不推测业务语义：

| 响应语义 | 投影结果 | 原因 |
|---|---|---|
| 成功条件 | `PROJECTED` | 从 `VisualResourceDescriptor.responseProtocol` 直接转换，但仍需人工确认其业务含义。 |
| 错误分类 | `UNKNOWN` | 现有描述符无法证明业务错误分类。 |
| 幂等性 | `UNKNOWN` | 不根据 HTTP 方法或描述符字段猜测。 |
| 可重试性 | `UNKNOWN` | 不根据协议、错误码或超时设置猜测。 |

投影结果固定为 `REQUIRES_CONFIRMATION`。`PROJECTED` 表示“有技术来源的候选值”，不等于 `CONFIRMED`。

只要任一响应语义不是 `CONFIRMED`，或幂等性、可重试性仍为 `UNKNOWN`，`ResponseSemantics.requiresReview()` 就返回 `true`。此时 `LogicalResourceBinding.bind(...)` 失败关闭，并返回错误码：

```text
RG.LOGICAL_CONTRACT.CONFIRMATION_REQUIRED
```

当前 S1-A 只提供核心领域对象和确认边界，不包含确认工作流、持久化接口或公开 HTTP endpoint。

## 具体实现绑定

`LogicalResourceBinding.bind(...)` 绑定以下具体实现身份：

```text
provider + apiVersion + resourceId + descriptorFingerprint
```

绑定过程不接受调用方自报的描述符指纹、输出 schema 指纹或契约指纹。所有指纹均从 canonical 内容重新计算。

具体实现的 `responseSchema` 必须通过结构化兼容校验，证明其输出满足逻辑契约的 `outputShape`。以下情况拒绝绑定：

- 资源身份不一致，或无法证明兼容性。
- 响应语义尚未全部人工确认。
- schema 使用当前保守分析边界无法识别的结构。
- 具体实现输出与逻辑输出结构明确不兼容。

绑定校验复用 `SchemaEnvelope` 和 `VisualSchemaCompatibility`，不解析 schema 字符串，也不信任调用方提供的 fingerprint。

## 契约兼容分析方向

兼容分析以 `baseline` 为已有契约，以 `candidate` 为候选修订。输入和输出必须使用不同方向：

| 对象 | 校验方向 | 业务含义 |
|---|---|---|
| 输入结构 | `baseline input -> candidate input` | 已有调用方能够提供的输入，候选契约仍必须接受。 |
| 输出结构 | `candidate output -> baseline output` | 候选实现可能产生的输出，已有消费方仍必须能够处理。 |

当前保守结构规则至少覆盖 `required`、`properties`、`type` 和 `additionalProperties`。分析结果包括：

- `COMPATIBLE`：没有 breaking 或待确认 finding，允许自动使用。
- `BREAKING`：存在能够明确证明的不兼容结构变化，原绑定失效。
- `REVIEW_REQUIRED`：存在未知 schema 结构、未确认语义或已确认语义变化；不判定为 breaking，但禁止自动使用。

只有明确不兼容的结构变化会判定为 `BREAKING`。`UNKNOWN` 不会被误判为兼容；它通过 `REVIEW_REQUIRED` 失败关闭，`automaticUseAllowed()` 返回 `false`。findings 按路径和错误码稳定排序，且不包含原始 schema 值或业务载荷。

## Focused 测试与验收

测试文件：

```text
resource-gateway-examples/src/test/java/com/leanowtech/bloge/gateway/testing/world/LogicalResourceContractTest.java
resource-gateway-examples/src/test/java/com/leanowtech/bloge/gateway/testing/world/LogicalResourceContractCompatibilityTest.java
```

执行命令：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=LogicalResourceContractTest,LogicalResourceContractCompatibilityTest test
```

验收结果：

```text
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖范围包括投影、稳定指纹、Map 与集合语义列表顺序归一化、防御拷贝、兼容的增量变化、required 移除、输入与输出的类型扩大/收窄、breaking 输出、UNKNOWN 失败关闭和错误信息脱敏。测试断言使用独立的预期结果，没有调用生产兼容性方法作为测试 oracle。
