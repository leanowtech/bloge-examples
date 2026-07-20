# Stage 5 Serving Inventory 外部非等价锚验证

## 1. 根因与边界

数据库 sequence floor 能拒绝进程重启、跨副本竞争、回退、分叉、跳号和断链，但不能识别“数据库连同
floor 一起恢复到旧备份”。继续在同一个数据库里增加 append-only 表、触发器或 WORM 标记并不能根治：
攻击或运维回退的故障域仍然相同。

本增量把两个独立可变顺序流提交到 Resource Gateway 数据库之外：

1. serving-inventory publication 与 witness 的组合顺序；
2. managed serving-inventory trust-root publication 顺序。

Resource Gateway 负责协议客户端、签名验证、法定人数、external-first 组合、启动门禁和聚合观测。
独立 notary 服务的部署、持久化、灾备、密钥 ceremony、mTLS、KMS/HSM 和运行认证属于外部基础设施，
不在本仓库中伪装成已经交付。

## 2. 威胁模型

已防御：

- Resource Gateway 数据库完整备份回退，但外部 notary 集合未同时回退；
- 旧 `ACCEPTED` receipt 被重放给新的本地提交；
- 少数 notary 不可用、超时、返回坏 JSON、坏签名或错误绑定；
- 同一 sequence 的不同 head、旧 head rollback、gap 或错误 predecessor；
- publication floor 与 trust-root floor 只完成其中一个的局部外部化；
- 配置声称 Byzantine quorum，但不满足 `3f+1` authority 和 `2f+1` threshold；
- endpoint、authority 或 failure domain 重复导致的伪独立性。

明确假设：

- 最多 `f` 个配置 notary Byzantine，且 authority key、endpoint 与 failure domain 的独立性真实成立；
- Resource Gateway 的 notary public-key 配置、TLS trust、制品和 staging 配置未被同时攻破；
- 外部 notary 对 `(streamKind, scopeId, streamId)` 实现原子 compare-and-append，并持久保存 head；
- 运维不会把全部 notary 与 Resource Gateway 数据库恢复到同一个旧时间点。

明确不保证：

- 超过 `f` 个 notary 合谋或同一组织控制伪装为多个 failure domain；
- 被攻破的部署配置把攻击者 key 和 endpoint 作为合法 notary 集合；
- transparency log inclusion/consistency proof、公开 gossip 或任意第三方审计；
- 单个 authenticated conflict 下的持续可用性。该协议选择 safety over availability：一个拥有合法
  配置 key 的恶意 notary 可以阻断推进，但不能让冲突 head 被本地接受；
- 外部 notary 服务自身的容量、SLA、数据保留、跨地域灾备和合规认证。

## 3. Wire Contract

权威 Schema：
[`suite-stability-external-sequence-checkpoint-v1.schema.json`](schemas/resource-gateway-testing/suite-stability-external-sequence-checkpoint-v1.schema.json)。

### 3.1 Head

`bloge.testSuiteStabilityExternalSequenceHead.v1` 包含：

- closed `streamKind`；
- stable `scopeId` 与 `streamId`；
- contiguous one-based `sequence`；
- current 和 predecessor SHA-256 fingerprint；
- sequence 1 必须使用空 predecessor，后继必须携带 canonical predecessor。

publication floor 不只锚 publication fingerprint。它把 publication material 与独立 witness material
合成为一个 deterministic head，避免其中一条链单独回退。trust-root floor 直接锚 canonical root material
fingerprint。

### 3.2 Request

`bloge.testSuiteStabilityExternalSequenceCheckpointRequest.v1` 绑定完整 head、trust domain、anchor set、
fresh 256-bit unpadded base64url challenge、whole-second `requestedAt/expiresAt` 和 canonical request
fingerprint。最长生命周期为 60 秒。

相同 head 的第二次提交会产生新 challenge 和新 request fingerprint，因此旧 receipt 不能授权数据库回退
后的重试。

### 3.3 Receipt

`bloge.testSuiteStabilityExternalSequenceCheckpointReceipt.v1` 由一个 notary 的 Ed25519 key 签名，包含：

- exact request fingerprint；
- trust domain、anchor set、authority、failure domain 与 key identity；
- `ACCEPTED` 或 `CONFLICT`；
- candidate sequence/head 与 observed sequence/head；
- whole-second issue/expiry window；
- 64-byte detached Ed25519 signature。

`ACCEPTED` 必须观察到 exact candidate。`CONFLICT` 必须是有意义的 current/next contradiction，不能用
无关旧状态制造冲突。receipt 不得晚于 request deadline，验证时 request 与 receipt 都必须仍有效。

JSON Schema 负责字段闭集、类型、格式和 genesis/successor 形状；Java 语义验证负责 fingerprint 重算、
跨字段相等/不等、deadline 包含、quorum、key lifecycle、签名和 meaningful conflict。

## 4. 仲裁与提交顺序

所有 notary 请求并发发出，客户端不跟随 redirect，只接受精确 vendor media type、protocol header、
HTTP `200 ACCEPTED` 或 `409 CONFLICT`，并限制响应为 128 KiB。重复字段、未知字段、trailing token、
错误 Content-Type、错误 protocol、超时、坏签名和错误绑定都不能计入 quorum。

法定人数为：

```text
authorityCount >= 3f + 1
acceptedReceiptCount >= 2f + 1
independentFailureDomainCount == authorityCount
```

任何一个经过认证且有意义的 `CONFLICT` 都立即拒绝 candidate；其余无效/不可用响应按 availability
failure 处理。只有无 conflict 且 accepted receipt 达到 threshold 才成功。

提交顺序固定为：

```text
verify candidate
  -> external compare-and-append quorum
  -> local database floor transaction
  -> publish immutable in-process snapshot
```

不能反过来。external success + local failure 可用同一 head 和新 challenge 幂等重试；local success +
external failure 会产生数据库中已推进但外部不可证明的代际，因此被结构性禁止。

| 故障 | 结果 | 原因 |
| --- | --- | --- |
| 4 个 notary 中 3 个 accepted、1 个 unavailable，`f=1` | 成功 | 达到 3-of-4 quorum |
| 3 个 accepted、1 个 authenticated conflict | 拒绝 | 冲突是 safety 事实，不被多数票覆盖 |
| 2 个 accepted、2 个 unavailable | 拒绝 | quorum 不足 |
| receipt 来自旧 challenge | 拒绝 | request fingerprint 不匹配 |
| receipt expiry 晚于 request expiry | 拒绝 | 不能扩张调用方签发的授权窗口 |
| external 成功、local DB 失败 | 本次失败，可重试 | external head 已安全存在 |
| external 失败 | local floor 不执行 | 不产生未锚定本地状态 |

## 5. Spring 与 Staging 门禁

配置根为：

```text
gateway.testing.stability-jobs.authority.http.jwks.cohort
  .signed-inventory.remote.external-anchor
```

staging 要求：

- dynamic witnessed inventory 与 managed dual trust roots 已启用；
- `external-anchor.enabled=true` 且 `required=true`；
- `minimum-faults=1`，因此至少 4 个独立 notary、threshold 至少 3；
- non-empty trust domain、anchor set 和 endpoint array；managed trust publication 提供完整 key set；
- authority、failure domain 和 URI 一一唯一，endpoint authority set 必须被当前 trust generation 覆盖；
- HTTPS only；insecure loopback 只允许显式本地测试；
- timeout 100 ms..30 s、clock skew 0..30 s、receipt lifetime 1..60 s，timeout 小于 lifetime。

`scripts/visual-canvas-demo.sh` 在启动前检查 required 开关、标识符、managed trust/bootstrap quorum、
非空 endpoint、`f>=1`、`threshold>=2f+1` 和时间边界；Java composition root 再执行严格 JSON、
trust publication、endpoint、`3f+1` 和完整
语义验证。两层检查目的不同：shell 提供即时可操作错误，Java 是不可绕过的权威门禁。

同一个 external anchor bean 同时装饰 publication floor 与 trust-root floor。任一 floor 缺少 external
装饰时，完整 inventory non-equivocation capability 为 false；staging 的 required 配置使缺 bean、多个
bean、不可用 descriptor 或 unsafe descriptor 在网络 bootstrap 前失败。

## 6. Capability、Health 与隐私

cohort descriptor v4、HTTP authorizer descriptor、authority Schema、Actuator health 和 capability probe
贯穿以下 aggregate facts：

- `externalInventoryNonEquivocation`；
- `byzantineQuorumInventoryNonEquivocation`；
- `externallyAnchoredSuiteStabilityServingInventoryOrdering`；
- `byzantineQuorumSuiteStabilityServingInventoryNonEquivocation`。
- `managedSuiteStabilityExternalNotaryTrust`；
- `restartFreeSuiteStabilityExternalNotaryKeyRotation`；
- `durableSuiteStabilityExternalNotaryTrustFloor`；
- `suiteStabilityExternalNotaryTrustReady`。

Byzantine capability 必须蕴含 external capability；managed mode 只有 publication 与 trust-root 两条流均满足
时才为 true。health 只暴露 status、last success、success/failure/conflict count、authority count、threshold、
maximum faults 和 failure-domain count。endpoint、domain identity、authority/key id、stream、challenge、
fingerprint、request/response body 都不会进入 health、capability 或稳定异常消息。

## 7. 验证证据

本增量的聚焦门禁执行 53 tests，0 failures、0 errors、0 skips，覆盖：

- 3-of-4 成功与 minority outage；
- authenticated conflict 优先于 accepted quorum；
- old-challenge replay、坏签名和 receipt 越过 request deadline；
- external-first/local-second 调用顺序与 external failure 零本地副作用；
- publication/witness composite head 和 root direct head；
- quorum/failure-domain/config minimum-fault policy；
- cohort/authorizer/health/capability/schema/profile 投影；
- strict wire Schema 与 payload/private-material 排除。

完整 Resource Gateway `clean verify` 执行 2754 tests，0 failures、0 errors、2 个既有条件浏览器跳过，
并成功重新打包 Spring Boot 可执行 JAR。`zsh -n scripts/visual-canvas-demo.sh`、两个变更 Schema 的
JSON 解析和 `git diff --check` 同时通过。

## 8. 仍需外部完成

Resource Gateway 侧的协议、客户端、双 floor 接线和门禁已经完成，但生产投用还必须由企业基础设施团队
交付并认证：

- 至少四个真正独立故障域的 compare-and-append notary 服务；
- notary 存储的备份恢复、WORM/tamper evidence、容量和跨地域 DR；
- key ceremony、KMS/HSM、mTLS、证书和 emergency revocation；
- quorum latency/error/conflict 告警、演练与 runbook；
- PostgreSQL 等目标数据库和目标 notary 实现的 backup/restore、故障注入与规模认证；
- 若合规要求公开可验证历史，再增加 transparency inclusion/consistency proof 与跨域 gossip。

因此 capability 证明“当前 Resource Gateway 进程按已配置外部 quorum 成功锚定”，不证明外部组织独立性
已经审计，也不等价于公开 transparency log certification。
