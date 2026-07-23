# Mirror Serving Generation 生产代际围栏

## 1. 交付状态

Resource Gateway 已实现 `RG-MIR-PROD-003` 的进程内协议与执行内核：

- `resourceGateway.mirrorServingGenerationToken.v1` 签名协议；
- `resourceGateway.mirrorPlan.v2` 对完整 token 的不可变绑定；
- payload-free dependency closure fingerprint；
- Ed25519 domain separation、内容地址复验和本地独立验签；
- 每次新 run 强制读取 shared current floor；
- 每次 operator occurrence 在签名 `maximumStaleness` 到期后重读 floor；
- generation、predecessor、revocation cursor、scope、purpose、dependency、TTL 与 key lifecycle 校验；
- authority outage、stale、rollback、expired、unknown/revoked key 全部 fail closed；
- 固定基数 telemetry、稳定错误码和 evidence node error code；
- v1 无录制语料计划的读取兼容。

仓库**没有**把进程内自签名器当生产 authority。默认
`MirrorServingGenerationAuthority` 与 `MirrorServingGenerationTrustProvider` 都是 unavailable。
部署方必须接入共享线性化 floor 与独立分发的公钥信任策略，录制语料 readiness 才会变为 true。

## 2. 病根与安全不变量

仅在计划编译时检查 corpus publication 不能解决多副本撤销问题。副本 A 可能刚完成物化，副本 B
已经观察到 policy、grant、validation 或 source tombstone 的变化；如果两个副本继续各自使用内存快照，
业务表现会在撤销窗口内分叉。

本实现固定以下不变量：

1. authority response 是数据，不是 trust root；公钥来自 operator-owned trust provider。
2. 非空 `ResolvedCorpusPayloads` 没有合法 token 时不能进入 compiler。
3. token 精确绑定 scope、purpose 与物化结果的完整 payload-free dependency closure。
4. 新 run 不使用缓存，必须读取 current floor。
5. occurrence 只可在 token 签名的 `maximumStaleness` 内复用最近一次 verified floor。
6. floor 前进后，已通过 occurrence admission 的调用可以完成；任何后续 occurrence 都会被拒绝。
7. floor 回退或 revocation cursor 回退被视为 authority rollback，不得当成临时 stale。
8. authority outage 只能在尚未超过已签名缓存窗口时允许已存在 run 的 occurrence；新 run 永远失败关闭。
9. token、plan、evidence、audit、metrics 与日志不携带 request/response payload。

## 3. 协议

`MirrorServingGenerationToken.Material` 包含：

| 字段 | 约束 |
|---|---|
| `streamId` | authority 稳定流标识 |
| `generation` | 从 1 开始单调递增 |
| `previousTokenFingerprint` | generation 1 必须为空；后继必须指向前一 token |
| `scope` | tenant / organization / project / environment / region 完整坐标 |
| `authorizedPurpose` | 精确非生产 purpose |
| `dependencyClosureFingerprint` | 当前物化 publication、revision、payload ref、trajectory/cluster/rule refs 的规范指纹 |
| `revocationCursor` | authority 单调撤销游标 |
| `issuedAt` / `expiresAt` | 最长 24 小时，expiry 为排他边界 |
| `maximumStaleness` | 正数且最多 5 分钟，小于 token lifetime |

`Seal` 使用 Ed25519 签署经过
`RESOURCE_GATEWAY_MIRROR_SERVING_GENERATION_V1` domain separation 的 material fingerprint。
Resource Gateway 重新计算 material 与完整 token 两层 fingerprint，再使用本地 trust provider 返回的
authority/key/algorithm/window/state 校验签名。`REVOKED` key 无条件拒绝；`RETIRED` key只在其有效窗口内用于验签。

严格 Schema：

- `docs/schemas/resource-gateway-mirror/mirror-serving-generation-token-v1.schema.json`
- `docs/schemas/resource-gateway-mirror/mirror-plan-v2.schema.json`

## 4. 物化、编译与运行顺序

录制语料主链按以下顺序执行：

1. `CapabilityCorpusServingService` 重验 corpus/trajectory/cluster current head、policy、validation、
   source lifecycle、grant、retention、region 与 payload content address。
2. `ResolvedCorpusPayloads.generationDependencies()` 生成不含 payload 的规范闭包。
3. `MirrorServingGenerationService` 向 authority 提交 scope、purpose、dependency fingerprint 与 plan horizon。
4. 本地独立验签通过后，`MirrorServingGenerationFence` 与 payload owner 绑定。
5. `MirrorPlanCompiler` 只接受带 token 的非空 corpus，并输出 `mirrorPlan.v2`。
6. `ExecutionControlCompiler` 把 token identity、cursor、expiry 与 staleness 纳入 effective control fingerprint。
7. `MirrorRunService` 获取 generation lease 后强制调用 `admitRun()`。
8. `CompiledTestRuntimeOptions` 在 frozen invocation-site 校验之后、fixture/resolver/operator 之前调用
   `admitOccurrence()`。
9. 终态 evidence/commit 完成后释放 run lease；owner close 继续遵守
   `OPEN -> DRAINING -> CLOSED` 与 payload zeroization。

无录制语料的计划继续使用 `resourceGateway.mirrorPlan.v1`。v1 必须省略 `servingGeneration`；v2 必须携带
token 并至少有一个 `RECORDED_EXACT`、`RECORDED_TRAJECTORY` 或 `RECORDED_CLUSTER` resolver。Plan API envelope
返回计划自身的 schema version，不会把 v1 误标为 v2。

## 5. 部署接入

测试或 staging profile 启用 Mirror 后，组合根提供 fail-closed 默认 bean。生产化 embedding 必须替换：

```java
@Bean
MirrorServingGenerationAuthority mirrorServingGenerationAuthority(
        RegionalGenerationClient client) {
    return new ClientBackedMirrorServingGenerationAuthority(client);
}

@Bean
MirrorServingGenerationTrustProvider mirrorServingGenerationTrustProvider(
        GovernedAuthorityKeyStore keys) {
    return new KeyStoreBackedMirrorServingGenerationTrustProvider(keys);
}
```

实现必须满足：

- 使用 workload identity、private PKI/mTLS 与明确的 server identity；
- `admit` 与 `currentFloor` 不接受调用方上传公钥或 trust root；
- 同一 `streamId` 的 floor 至少提供线性化读，或提供更强、可证明的 bounded consistency；
- generation/cursor floor durable，进程重启不能回退；
- key rotation 与 revocation 通过独立认证渠道分发；
- client timeout 小于 run admission budget，禁止无界重试；
- provider reason 只映射到封闭 outcome，不进入 HTTP、metrics 或业务日志；
- scope/purpose/stream 必须在服务端重新绑定，不能只信 token body。

能力探针：

- `supportedObjects.mirrorPlan` 同时列出 v1 与 v2；
- `supportedObjects.mirrorServingGenerationToken` 列出 token v1；
- `mirrorServingGenerationFencing=true` 表示录制语料完整 serving chain 当前可用；
- `mirrorServingGenerationAuthorityReady=true` 表示相同动态 readiness；
- authority 或 trust 不可用时，三个 corpus resolver readiness 均为 false。

## 6. 稳定失败语义

| 阶段 | 错误码 | 含义 |
|---|---|---|
| compile | `RG.MIRROR.SERVING_GENERATION_REQUIRED` | 非空 corpus 没有签名代际 |
| compile | `RG.MIRROR.SERVING_GENERATION_DEPENDENCY_MISMATCH` | token 与实际物化 dependency closure 不一致 |
| materialization | `RG.MIRROR.SERVING_GENERATION_REJECTED` | authority 拒绝 dependency closure |
| materialization/run | `RG.MIRROR.SERVING_GENERATION_AUTHORITY_UNAVAILABLE` | authority 或 trust 无法完成正向决策 |
| materialization/run | `RG.MIRROR.SERVING_GENERATION_TOKEN_INVALID` | 内容地址、签名、scope、purpose、dependency、key 或 horizon 无效 |
| run/occurrence | `RG.MIRROR.SERVING_GENERATION_STALE` / `MIRROR_SERVING_GENERATION_STALE` | current floor 已前进 |
| run/occurrence | `RG.MIRROR.SERVING_GENERATION_ROLLBACK` | generation 或 cursor 低于计划 floor |
| run/occurrence | `RG.MIRROR.SERVING_GENERATION_EXPIRED` | token 到达排他 expiry |

Run admission 失败是受保护 API rejection。Occurrence 阶段失败是已经开始的业务 run 的受控执行失败：
node trace 与 diagnostics 保留稳定 `MIRROR_SERVING_GENERATION_*` code，业务 operator 不会执行，也不会降级到真实调用。

## 7. 指标与告警

唯一新增指标为：

`resource.gateway.mirror.serving_generation.checks`

标签只有：

- `check=materialization|run|occurrence`
- `outcome=current|cached|rejected|unavailable|invalid|stale|rollback|expired`

禁止增加 tenant、stream、generation、token、fingerprint、authority、key、plan、run 或异常文案标签。

建议告警：

- 任意 `rollback > 0`：安全事件，立即停止该 authority stream；
- `invalid > 0`：检查 key distribution、canonicalization、clock 与 provider compromise；
- `run/unavailable` 持续超过一个 admission SLO：停止新录制语料 run；
- `occurrence/stale` 突增：确认撤销/发布是否预期，并检查业务 run 的受控失败率；
- `cached/current` 比例异常：检查 `maximumStaleness`、时钟与 authority latency。

## 8. 上线与撤销演练

1. 先部署只读 trust provider，验证 authority/key window 与签名 fixture。
2. shadow 调用 `admit`，只比较 dependency fingerprint，不向 compiler 交付 token。
3. 单副本开启 v2，验证 v1 无 corpus 计划仍可读。
4. 两副本使用同一 floor：推进 generation，确认两个副本的新 run 都在一个 SLO 内拒绝旧代。
5. 在 run admission 后推进 floor，确认下一个 occurrence 失败且业务 operator invocation count 为 0。
6. 关闭 authority：新 run 立即失败；已存在 run 仅在 signed staleness 窗口内继续。
7. 模拟 floor rollback、unknown key、revoked key、signature tamper、expiry boundary 与 clock skew。
8. 检查 evidence、audit、HTTP、logs、heap sampling 与 metrics 均无业务 payload。

当前剩余工业门禁是部署侧共享 authority 的 durable/linearizable 实现、跨 region convergence 证明、私钥
KMS/HSM 托管、真实网络故障与容量压测、非 Java canonicalization fixture，以及撤销 SLO 的环境认证。
