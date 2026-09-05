# 业务召回 15 类真实 Codex 认证

本认证用于证明业务负责人只表达业务意图时，Codex 能在 `BUSINESS_SOLUTION` 操作面完成正确发现、澄清、导航和受控案例提议。认证不把服务级单元测试替代成真实 Codex 证据。

## 认证范围

认证先运行一条四实体创作主线，再启动 15 个独立、仓库不可见的 Codex 会话。每个会话只接收业务语言提示。提示中不包含工具名、资产引用、DSL、字段结构或实现步骤。

| familyId | 业务问题 | 通过行为 |
|---|---|---|
| `synonym-rewrite` | 同义表达召回 | 正确事实位于 Top-1 |
| `near-meaning-distractor` | 近义业务对象干扰 | 正确事实位于 Top-1 |
| `boundary-unspecified` | 时间边界缺失 | 停止写入，只问一个业务问题 |
| `unknown-policy-unspecified` | 无法判断策略缺失 | 停止写入，只问一个业务问题 |
| `authority-source-unspecified` | 事实来源责任缺失 | 停止写入，只问一个业务问题 |
| `multiple-exact` | 多个完全匹配项 | 返回歧义并停止，只问一个业务问题 |
| `legacy-feature-partial` | 旧契约信息不完整 | 只按部分匹配处理并停止 |
| `surface-interference` | 底层入口干扰 | 只观察业务操作面 |
| `cross-session-rediscovery` | 新会话恢复工作 | 重新找到当前解法和阶段 |
| `semantic-drift` | 业务语义发生变化 | 旧确认失效并要求重新确认 |
| `fact-assumption` | 钉定业务事实 | 标准案例记录事实假设 |
| `dependency-unavailable` | 依赖不可用 | 标准案例记录不可用结果 |
| `action-stubbing` | 动作按成功处理但不得真实执行 | 标准案例记录无副作用成功 |
| `forbidden-dependency` | 明确禁止某动作 | 标准案例记录禁止调用 |
| `assumption-ambiguity` | 同名动作歧义 | 停止写入，只问一个业务问题 |

## 证据边界

原始提示、调用参数、业务样本和模型消息只存在于权限为 `0600` 的临时目录。默认流程结束后删除该目录。正式证书只保存：

- 固定 `familyId`；
- 期望行为分类和服务端结果推导出的观察结果；
- 一次性 HMAC 生成的案例与会话指纹；
- 15 类全部通过的断言和聚合指标。

Reducer 不接受调用方自报的观察结果。它从真实 trace 中检查候选排序、匹配类型、阻塞原因、标准案例参数、业务操作面边界和 Codex 结束消息。任一测试族缺失、重复、分类错误、会话复用或观察结果不符时，不生成证书。

## 运行

从仓库根目录运行：

```bash
scripts/certify-agent-tdd-codex.sh \
  docs/acceptance/agent-tdd/business-solution-codex-certification-v1.json
```

该命令会构建当前干净提交、启动独占 Resource Gateway、执行 1 个创作会话和 15 个测试族会话，并调用 reducer 生成安全证书。`KEEP_RAW_CODEX_TRACE=true` 只用于批准的本机排障，不能作为正式留存方式。

公开提示集位于 `scripts/business-solution-recall-family-suite-v1.json`。私有运行清单使用 `rg.businessRecallFamilyTraceSet.v1`，只记录测试族、期望分类、临时 trace 路径和退出码，不记录业务话语内容。

## 验收门

- 15 个 `familyId` 各出现一次；
- 创作会话与 15 个测试族会话共 16 个 thread identity，全部不同；
- 同义召回和近义干扰的正确能力均为 Top-1；
- 7 个澄清类测试全部只问一个业务问题，且没有成功写入；
- 所有 trace 只使用业务操作面；
- 证书不包含原始提示、业务参数、资产引用或模型消息；
- 证书绑定当前提交、生产源码树、运行 JAR 和独占进程身份。
