# S1-B 世界模型实现说明

本文记录 [`rg-evolution-design-1.2.1.md`](./rg-evolution-design-1.2.1.md) 阶段一 `S1-B` 的实现边界。当前只完成 `S1-B1`「无状态世界模型登记与纯净准入」；世界片段隔离试跑属于 `S1-B2`，尚未交付。

## 已实现能力

### 无状态世界模型

`ResourceWorldModel` 表示一个租户下不可变、带版本和内容指纹的世界模型。`WorldSlice` 按 `provider + apiVersion + logicalContractId` 定位一个实现切片。

登记时固定校验以下不变量：

- `revision` 必须为正数。
- 同一世界模型内不得出现重复切片坐标。
- 世界模型与切片的 `tenantId` 必须一致。
- 切片声明的逻辑契约标识和指纹必须与 `LogicalResourceContract`、`LogicalResourceBinding` 一致。
- `provider`、`apiVersion` 和 binding 指纹必须与已证明的具体实现绑定一致。
- 阶段一只允许 `StateSpec.empty()`；任何状态键或默认值均失败关闭。
- 切片排序不影响世界模型指纹；调用方传入的集合不会被模型反向修改。

### 冻结 BLOGE 世界片段

`BlogeFragmentRef` 保存 `.bloge` 工件标识、工件版本、规范化源码、输出节点和内容指纹。工件版本和源码均参与指纹计算。

`PureBlogeFragmentValidator` 是公开的纯净准入边界。它依次执行：

1. 使用 BLOGE `Lexer` 和 `DslCompiler` 解析结构，不使用正则表达式或子串扫描推断 DSL 语义。
2. 仅允许决策表、转换和分支等已证明为纯确定的 BLOGE AST 结构。
3. 编译片段并建立 invocation inventory，确认实际冻结算子仅包含 BLOGE 决策表和转换原语。
4. 拒绝资源、网络、文件系统、自定义算子、未解析函数、非确定函数、脚本、import 和世界委托。
5. 将 parser、compiler 和 admission 异常收敛为固定 `RG.WORLD.*` 错误码，不返回 DSL、业务负载、密钥或堆栈。

`WorldModelAdmissionService` 在世界模型登记边界重新检查跨对象身份，并通过 `PureBlogeFragmentValidator` 验证每个切片的行为工件。构造对象本身不等于登记成功；调用方必须以 admission 结果作为后续持久化和编译的输入。

## 当前限制

以下能力尚未实现，不得由本切片推断：

- 不执行世界片段，也不生成模拟响应。
- 不提供请求匹配、默认规则运行和同优先级歧义检测。
- 不提供节点数、执行深度、运行时间和输出大小限制。
- 不支持世界状态；有状态演进属于阶段二。
- `WorldSlice.Registration` 仍是应用层登记输入。`S1-D` 持久化时必须从授权资产注册表解析绑定事实，不能信任跨系统传入的 `valid` 布尔值。

上述缺口由 `S1-B2` 的隔离片段单测台和 `S1-D` 的授权版本化资产库闭合。在此之前，`S1-B` 整体状态保持 `IN_PROGRESS`。

## 验证方式

运行世界模型与逻辑契约聚焦集：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=LogicalResourceContractTest,LogicalResourceContractCompatibilityTest,ResourceWorldModelTest,WorldModelS1BTest \
  clean test
```

当前固定分母为 `32` 项测试，覆盖契约兼容、稳定指纹、顺序归一、防御拷贝、版本变化、切片重复、租户和绑定漂移、无状态约束、纯原语准入、不纯 AST 拒绝及错误净化。
