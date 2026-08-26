# S1-C 剧情与编译下沉实现说明

本文记录 [`rg-evolution-design-1.2.1.md`](./rg-evolution-design-1.2.1.md) 阶段一 `S1-C` 的实现边界。当前完成 `S1-C1`「剧情资产模型与契约兼容绑定」、`S1-C2a`「无副作用编译下沉」和 `S1-C2b`「统一内核世界委托」；compiler 三重 oracle 尚未闭合。

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

## 编译下沉

`WorldScenarioCompiler` 将精确的 `Scenario + ResourceWorldModel + Graph + WorldSliceSelection` 确定性编译为现有 `FixtureBundle`。编译器本身不执行图、不访问网络、不读取资产库，并坚持以下约束：

- 每个剧情依赖必须显式选择唯一的 `provider + apiVersion + sliceFingerprint`，缺失、多余或漂移均失败关闭；
- 图节点通过规范化的 `bloge.logical-contract:<base64url(contractId)>@<fingerprint>` 标签绑定逻辑契约，避免依赖节点 id，也避免逗号、`@`、空格和 Unicode 契约标识破坏解析；
- 同一契约可被多个 `PRIMARY` / `RESOURCE` 调用点复用，编译结果只生成一条契约级规则；
- 规则选择必须再次通过现有 `SelectorResolver` 求解，并与编译器预期命中集合完全一致，防止产生第二套匹配语义；
- Fixture 规则默认携带 `WORLD_DELEGATE_UNBOUND` 的 `DENY` 行为。缺少服务端绑定桥时必然失败，不能静默退回真实外部调用；
- `WorldDelegateBinding` 只在 Java 控制面保存冻结片段引用，不进入 `FixtureBundle` wire value；
- metadata 只保存 scenario、world、fragment 的精确版本和指纹，不保存 DSL、context、expected 或其他业务负载。

编译结果带稳定指纹并在构造时重算校验。指纹覆盖装置包的无负载投影、片段引用和双向来源映射；任何结构篡改都会以固定错误码拒绝。

## 来源映射

`WorldScenarioSourceMap` 同时保存 source-to-output 和 output-to-source 两个方向，当前覆盖：

- 世界切片、世界片段到 fixture rule；
- 逻辑契约到所有实际 invocation site；
- Scenario 到 FixtureBundle；
- Scenario expectation 到 fixture assertion。

Expectation 坐标使用“规范序号 + 内容指纹”，相同断言仍可按序号区分，断言内容又不会以明文进入来源映射。该映射为后续失败归因、影响分析和可视化回跳提供稳定基础。

## 统一内核执行

`WorldScenarioRunService` 是当前 Scenario 世界委托的高层入口。它不会建立第二条模拟执行路径，而是：

1. 重算实际 Graph 指纹，并与 Scenario 编译目标和请求目标同时对拍；
2. 校验编译结果指纹及 rule、binding、source map 的一对一关系；
3. 复用 `InvocationInventoryBuilder + SelectorResolver` 重建真实调用点；
4. 只为精确的 `invocationSiteId + ruleId` 生成 Java-only `WORLD_DELEGATE` hint；
5. 通过现有 `ExecutionControlCompiler` 编译控制计划；
6. 通过现有 `TestRunService`、独立 GraphEngine 和证据管线执行。

`WorldDelegateRuntime` 是单次运行作用域的冻结 binding 表。`TestDoubleFactory` 仅当编译模式已经被服务端固定为 `WORLD_DELEGATE`，且规则仍是 C2a 生成的 `WORLD_DELEGATE_UNBOUND` DENY sentinel 时，才允许调用纯 BLOGE 片段。普通 `TestRunService` 看到同一 FixtureBundle 仍执行 DENY，不能由 fixture wire value 自行激活世界委托。

世界片段结果继续经过节点输出 schema 校验。Node trace 的 fidelity/control fact 标记为 `WORLD_DELEGATE`，证据上限固定为 `EXPLORATORY`；真实算子与资源网络不会执行。缺失 runtime、缺失 binding、规则漂移、错误 purpose 或 Graph 身份漂移均使用固定净化错误，并在真实算子执行前失败。

## 尚未实现

- 独立参考编译器与生产编译器的固定差分矩阵；
- FixtureBundle、binding 和 source map 的规范序列化往返证明；
- 对规则完备性、双向映射一致性和负载隔离的结构性质验证。

三类证据全部固定后，compiler 三重 oracle 才算闭合。

上述能力闭合前，`S1-C` 保持 `IN_PROGRESS`。

## 验证方式

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=LogicalResourceContractTest,LogicalResourceContractCompatibilityTest,ResourceWorldModelTest,WorldModelS1BTest,WorldFragmentTestKitTest,ScenarioTest,WorldScenarioCompilerTest \
  clean test
```

当前固定分母为 `63` 项测试。其中 Scenario 测试 `12` 项；编译器测试 `10` 项，覆盖复杂契约标识编解码、多节点和混合调用点复用、显式切片选择、双向来源映射、20 次编译确定性、target/world/contract 漂移、零命中和多标签拒绝、错误净化，以及 metadata、来源映射和编译指纹的负载隔离。

运行期受影响聚焦集为 `106` 项测试，包含 `WorldScenarioRunServiceTest` 的 `4` 项端到端证明：同一逻辑契约跨两个节点委托同一冻结片段、真实算子调用为零、20 次语义结果指纹一致、证据降级正确、缺 runtime/binding 和错误 purpose 失败关闭、变化 Graph 不能冒用旧指纹，以及普通运行入口不能激活 C2a DENY bundle。
