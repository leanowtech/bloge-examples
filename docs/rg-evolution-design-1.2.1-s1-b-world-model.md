# S1-B 世界模型实现说明

本文记录 [`rg-evolution-design-1.2.1.md`](./rg-evolution-design-1.2.1.md) 阶段一 `S1-B` 的实现边界。`S1-B1` 提供无状态世界模型登记与纯净准入；`S1-B2` 提供世界片段隔离试跑。两个切片均已完成开发验证。

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

### 隔离片段单测台

`WorldFragmentTestKit` 使用真实 BLOGE `GraphEngine` 执行已经准入的片段。每次 replay 都创建独立 engine、context 和只包含决策表、转换原语的 registry。业务算子、资源算子、网络和文件系统能力不会进入执行环境。

测试台接收深不可变复制后的 rendered request，并返回响应、响应指纹、空状态、replay 次数和耗时。多次 replay 的响应指纹必须一致；任何漂移均以 `NON_DETERMINISTIC_REPLAY` 失败关闭。

`Limits` 对以下维度设置硬上限：

- BLOGE 源码字节数；
- 纯原语数量；
- 表达式和输入、输出结构深度；
- 输入、输出序列化字节数；
- replay 次数；
- 单次执行超时。

超时会取消任务、关闭 executor 和 engine。测试通过活动线程计数证明连续超时不会积累执行线程。含外部算子的片段在 engine 创建前即被拒绝。

决策表直接使用 BLOGE 运行语义：`FIRST` 按 DSL 声明顺序选择，并以末行 `otherwise` 作为默认结果；`UNIQUE` 出现多行命中时，由真实 `DecisionTableOperator` 检测歧义，再收敛为净化错误 `RG.WORLD.FRAGMENT_AMBIGUOUS`。

## 当前限制

以下能力不属于当前切片，不得由本切片推断：

- 不支持世界状态；有状态演进属于阶段二。
- `WorldSlice.Registration` 仍是应用层登记输入。`S1-D` 持久化时必须从授权资产注册表解析绑定事实，不能信任跨系统传入的 `valid` 布尔值。
- BLOGE 当前 transform 类型没有已证明的 nullable 输出声明方式。测试台不把会触发 runtime schema violation 的 `null` 结果认定为合法能力。

授权绑定事实由 `S1-D` 的版本化资产库闭合；有状态世界由阶段二闭合。

## 验证方式

运行世界模型与逻辑契约聚焦集：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=LogicalResourceContractTest,LogicalResourceContractCompatibilityTest,ResourceWorldModelTest,WorldModelS1BTest \
  clean test
```

当前固定分母为 `41` 项测试，覆盖契约兼容、稳定指纹、顺序归一、防御拷贝、版本变化、切片重复、租户和绑定漂移、无状态约束、纯原语准入、不纯 AST 拒绝、真实引擎执行、FIRST/default、UNIQUE 歧义、20 次确定性重放、全部资源上限、超时线程终止、外部算子零执行及错误净化。
