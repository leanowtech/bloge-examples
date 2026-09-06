# 业务召回 15 类真实 Codex 认证

本认证用于证明业务负责人只表达业务意图时，Codex 能在 `BUSINESS_SOLUTION` 操作面完成正确发现、澄清、导航和受控案例提议。认证不把服务级单元测试替代成真实 Codex 证据。

## 认证范围

认证先在无认证 seed 的环境中运行四实体创作主线和同义召回会话。随后使用独立 setup 凭据通过正常 HTTP MCP 只预置近义干扰项，完成服务端即时召回预检，再运行近义干扰会话。该会话通过后，setup 凭据才预置多精确项、旧能力和语义漂移资产；前三类干扰验收完成后，setup 凭据再预置同名动作，最后一个 Codex 会话在第四个运行实例中执行。每个会话只接收业务语言提示。提示中不包含工具名、资产引用、DSL、字段结构或实现步骤。

预置数据只使用 `recall-certification-test` 测试域。干扰 Feature、重复精确 Feature 和同名 Instruction 都在服务器导航的正常 journey 中创建。旧 Feature 经过服务端 DSL reference、编译门和 `feature.compose` 创建。语义漂移先建立独立解法并批准 GOLDEN，再通过独立修订 journey 改变 Feature 业务结果范围。原 journey 必须返回 `GOLDEN_CASE_STALE`，否则预置失败。近义预检使用主线 trace 私下关联的 Feature 坐标读取当前完整业务契约，再检查主 Feature 为 `EXACT` Top-1 且干扰项不是 `EXACT`。预检不通过资产命名或列表顺序制造 Top-1，也不能替代近义会话自身的 Top-1 证据。认证没有专用写入后门。

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
- 实际 seed 资产关系生成的 `setupFingerprint`；
- 私有 seed manifest 生成的一次性 HMAC；
- 15 类全部通过的断言和聚合指标。

Reducer 不接受调用方自报的观察结果。它从真实 trace 中检查首个业务工具是否符合该话语的只读发现集合、候选排序、匹配类型、阻塞原因、标准案例参数、业务操作面边界和 Codex 结束消息。受控案例会话不能把 `golden.propose` 本身当作工具召回成功。近义干扰、多精确项、旧 Feature、语义漂移和同名动作还必须在各自独立 trace 中观察到本轮 setup manifest 记录的实际资产。认证分别用 READ 和 AUTHORING purpose 从实际服务读取 `tools/list`，并在两个 purpose 下各直接调用一个被隐藏工具，均要求服务端返回 `TOOL_NOT_VISIBLE_IN_SURFACE`。任一测试族缺失、重复、分类错误、会话复用、setup/workload 凭据复用、setup 关系被篡改或观察结果不符时，不生成证书。

受控案例会话先读取目标实体的当前业务卡片，再把 `display.businessName` 原样写入事实名或能力名。`RETURNS` 必须携带受契约校验的 `value`；`UNAVAILABLE`、`SUCCEEDS_WITHOUT_EFFECT`、`FAILS_WITHOUT_EFFECT` 和 `MUST_NOT_BE_USED` 不得携带 `value`。这些约束由 MCP Schema 和服务端共同执行，不能依靠 Codex 猜测。

## 运行

从仓库根目录运行：

```bash
scripts/certify-agent-tdd-codex.sh \
  docs/acceptance/agent-tdd/business-solution-codex-certification-v1.json
```

该命令会构建当前干净提交，并使用同一私有数据库依次运行四个 Codex 实例：主线加同义召回、近义干扰与受控案例表达、多精确项与旧能力及语义漂移、同名动作歧义。脚本只接受 macOS 验签通过、Team ID 为 `2DC432GLL2` 的 OpenAI Codex 可执行文件，并把二进制 SHA-256 和完整 Code Directory hash 写入证书。每次写入测试资产前都切换到独立随机 setup token，写完再切回随机 Codex token。最终 setup manifest 合并三个阶段的全部角色、关系和预检结果，并保存 setup/workload 随机凭据不相同的私有指纹证明。该证明只说明 Codex 不持有 setup 凭据，不把两者描述成不同业务 actor。Reducer 要求四个 Codex 实例身份互不相同。`KEEP_RAW_CODEX_TRACE=true` 只用于批准的本机排障，不能作为正式留存方式。

公开提示集位于 `scripts/business-solution-recall-family-suite-v1.json`，固定测试域预置位于 `scripts/business-recall-platform-fixture-v1.json`。私有运行清单使用 `rg.businessRecallFamilyTraceSet.v1`，只记录测试族、期望分类、临时 trace 路径、退出码、运行实例关联和私有 setup manifest 路径，不记录业务话语内容。

## 过程截图

机器证书通过后，脚本从同一真实 Codex trace 生成 6 张脱敏过程图：

1. [先读业务积木](business-solution-codex-process-01.png)
2. [定义业务事实](business-solution-codex-process-02.png)
3. [定义业务规则](business-solution-codex-process-03.png)
4. [定义业务动作](business-solution-codex-process-04.png)
5. [组合业务解法](business-solution-codex-process-05.png)
6. [提交标准案例](business-solution-codex-process-06.png)

[过程图 manifest](business-solution-codex-process-v1.json) 绑定证书指纹、认证基准提交、真实 trace 序号、文件名和 SHA-256。每张图为 1440×900，只展示工具名、调用序号和完成状态。过程图不是 Codex 原生界面截图，不包含参数、结果、内部引用、模型消息或业务样本。

## 验收门

- 15 个 `familyId` 各出现一次；
- 创作会话与 15 个测试族会话共 16 个 thread identity，全部不同；
- 15 个测试族的首个业务工具均属于该业务话语的固定允许集合；
- 同义召回和近义干扰的正确能力均为 Top-1；
- 主线和同义召回无 seed，近义会话只观察到近义 seed，其余 seed 在近义会话通过后创建；
- 7 个澄清类测试全部只问一个业务问题，且没有成功写入；
- Codex 连接不使用客户端 `enabled_tools`；服务端目录隐藏底层工具，直接调用同一工具也被拒绝；
- 所有 trace 只使用业务操作面；
- setup 使用独立凭据和既有受治理入口，私有 manifest 证明 setup/workload 凭据不同，证书绑定实际 seed 资产关系；
- 证书不包含原始提示、业务参数、资产引用或模型消息；
- 证书绑定可解析的认证基准提交、生产源码树、认证脚本与 Schema、运行 JAR、OpenAI 签名 Codex 二进制和四个隔离的 Codex 进程身份。受控假设族在本证书中只证明业务表达被正确捕获；编译、零外呼执行和过期批准拒绝由服务端测试证据独立证明，不在本证书中伪报为已观察。
