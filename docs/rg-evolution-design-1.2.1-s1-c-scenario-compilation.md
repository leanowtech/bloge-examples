# S1-C 剧情与编译下沉实现说明

本文记录 [`rg-evolution-design-1.2.1.md`](./rg-evolution-design-1.2.1.md) 阶段一 `S1-C` 的实现边界。当前完成 `S1-C1`「剧情资产模型与契约兼容绑定」；FixtureBundle 下沉、逻辑契约寻址、双向来源映射和 compiler oracle 尚未实现。

## 剧情资产

`Scenario` 是不可变、带版本和内容指纹的业务剧情。它包含：

- 精确的 graph 或 operator `TargetRef`；
- 精确的 `WorldModelRef`，包含世界模型标识、版本和指纹；
- 深不可变的业务 context；
- 阶段一固定为空的 `WorldStateInit`；
- 可无损映射为现有 `FixtureBundle.Assertion` 的 expectations；
- 逻辑契约标识和 baseline fingerprint 依赖。

Scenario 构造时必须同时提供 `WorldModelRef` 和对应的 `ResourceWorldModel`，或直接提供世界模型并由系统生成精确引用。引用与模型的标识、版本、指纹或租户任一不一致，都会失败关闭。`latest`、`unknown` 和空指纹不能成为资产依赖。

Scenario 指纹包含完整 target 和 world 引用。更换 provider 或 API binding 会形成新的世界模型地址，因此会改变 Scenario 的完整依赖指纹；但 Scenario 的契约依赖不保存 provider 或 API 版本，契约兼容性不会因具体实现切换而误判失效。

## 正确性断言

`Scenario.Expectation` 复用现有 assertion 语义，不引入新的表达式语言。当前支持现有执行器已经实现的 scope 和 operator，并在构造时检查：

- scope 和 operator 必须来自固定支持集合；
- JSON Pointer 必须合法；
- 节点级 scope 必须提供 `nodeId`；
- 数值比较必须使用数值 expected；
- tolerance 只允许用于数值 `EQUALS`；
- schema 匹配必须提供对象 schema。

Expectations 按集合语义规范排序。相同业务断言的输入顺序不影响 Scenario 指纹。

## 契约兼容

`ContractDependency` 只保存 `contractId + baselineFingerprint`，不在 value object 内隐藏 JVM 对象。该约束保证资产序列化、跨进程传输和重启恢复前后的判断一致。

兼容验证有两种证据强度：

1. 只提供 candidate 时，系统只能证明 candidate 指纹与 baseline 完全一致。指纹不同一律返回 `REVIEW_REQUIRED`，不能自动使用。
2. 同时提供 baseline 和 candidate 时，系统先核验 baseline 与剧情依赖完全一致，再调用 `LogicalResourceContractCompatibility`。只有 `COMPATIBLE` 允许自动使用；`BREAKING` 和 `REVIEW_REQUIRED` 均失败关闭。

所有失败使用固定 `RG.WORLD.SCENARIO.*` 错误码，不包含 context、expected、密钥或堆栈。

## 尚未实现

- 将世界切片和 Scenario 编译为现有 `FixtureBundle` 与 assertions；
- 按逻辑契约而非节点标识生成 fixture selector；
- 同一逻辑契约被多个节点复用时的路由证明；
- world rule、Scenario expectation 与编译产物之间的双向来源映射；
- 结构性质、独立参考实现差分和往返三重 compiler oracle。

上述能力闭合前，`S1-C` 保持 `IN_PROGRESS`。

## 验证方式

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=LogicalResourceContractTest,LogicalResourceContractCompatibilityTest,ResourceWorldModelTest,WorldModelS1BTest,WorldFragmentTestKitTest,ScenarioTest \
  clean test
```

当前固定分母为 `53` 项测试，其中 Scenario 测试 `12` 项，覆盖指纹确定性、顺序归一、深防御拷贝、精确 target/world 引用、无状态约束、assertion 往返、兼容与 breaking/review 判定、provider binding 隔离、序列化后语义一致和错误净化。
