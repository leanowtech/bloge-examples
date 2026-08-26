# Resource Gateway 1.2.1 S2-E1 控制证据协议验证说明

本文记录 `S2-E1` 的实现边界和可复现证据。目标是把状态会话与函数控制的运行事实固化为稳定、可持久化、可防篡改且不携带业务 payload 的外部协议。本文不声明真实 HTTP、Test Kit 或生产双层隔离已经完成。

## 1. 外部协议

`bloge.testRunControlEvidence.v1` 通过 `TestRunEvidence.metadata.controlEvidenceProjection` 承载：

- 完整运行绑定：run、Scenario、World、target、execution plan 和 function plan fingerprint；
- 状态事实：state spec、revision、事务坐标、read/write key 和读写/结果 fingerprint；
- 函数事实：调用点、原函数与运行事实 fingerprint、控制模式、证据等级、消费摘要和 invocation observation；
- projection fingerprint：覆盖完整运行绑定和全部控制事实。

协议不包含状态值、状态快照 payload、函数参数、返回值、错误文本或 schema。内部 `WorldStateSnapshot` 与 `FunctionControlRunEvidence` 只作为运行期输入，不直接成为外部 wire contract。

## 2. 两层指纹

完整 projection fingerprint 包含 `runId`，用于防止证据跨运行串用。semantic result fingerprint 使用稳定语义材料，排除 `runId`、projection 自指纹和包含状态 payload 的内部 snapshot fingerprint。

固定测试证明：

- 不同 runId 的同一受控运行得到不同完整 projection fingerprint；
- 连续 20 次计算得到相同语义指纹；
- 状态事务、函数计划或证据等级变化会改变对应稳定语义材料；
- 无控制的历史 evidence 不增加 metadata，也不改变原有指纹路径。

## 3. 失败关闭

严格 codec 与持久化完整性边界拒绝：

- projection 与 evidence 的 run、target 或 execution plan 错绑；
- state 与外层 Scenario、World、Graph 或 run 错绑；
- function plan 与函数投影错绑；
- 未知字段、重复 JSON key、缺失字段、未知枚举和非法 fingerprint；
- 重复事务坐标、重复 read/write key、重复 binding/consumption/observation；
- 超过条目、文本或整体字节上限的协议；
- 调用方预占 `controlEvidenceProjection` 保留 metadata key；
- projection fingerprint 或 semantic result fingerprint 被篡改。

终结状态或函数 evidence 失败时只记录固定错误码，不拼接可能携带 payload 的异常文本。

## 4. 运行时事实兼容

投影使用真实 `FunctionControlRuntime` 证据验证 RETURN、THROW、DELAY、TIMEOUT、参数未命中、消费耗尽和 DELAY 时钟失败。成功 RETURN/DELAY 保存结果 fingerprint；THROW/TIMEOUT 和受控失败保存错误 fingerprint，结果与错误互斥。

`CERTIFIABLE`、`EXPLORATORY`、`PREVIEW` 三类函数 evidence ceiling 均通过外部 JSON 往返。纯函数强制替换仍由服务端把最终运行证据降为 `EXPLORATORY`。

## 5. 验证结果

聚焦命令：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=TestRunEvidenceProtocolCodecTest,TestEvidenceSanitizerTest,TestRunServiceTest,FunctionControlRuntimeTest,TestRunRecordIntegrityTest,TestingDomainProtocolTest \
  test
```

结果：83 tests，0 failures，0 errors，0 skipped。

全量命令：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

结果：7383 tests，0 failures，0 errors，28 skipped；`BUILD SUCCESS`。`git diff --check` 通过。

## 6. 尚未闭合

以下内容属于 `S2-E2`：

- 通过现有测试控制信封传递 Scenario、World 和 function control 精确引用；
- 真实 Spring HTTP 正向整链与 production profile 入口拒绝；
- 绕过入口后的服务端独立拒绝及零编译、零执行、零证据写入证明；
- capability probe 与公开协议 Schema；
- Test Kit 客户端解析、兼容性 fixture 和双项目 `clean verify`。
