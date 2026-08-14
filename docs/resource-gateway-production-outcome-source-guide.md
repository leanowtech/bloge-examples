# Resource Gateway 权威 Outcome Source 接入与运维指南

> 适用协议：`resourceGateway.authoritativeOutcomeSourcePage.v1`、
> `resourceGateway.authoritativeOutcomeConnectorControlCommand.v1`、
> `resourceGateway.authoritativeOutcomeSourceCheckpoint.v1`
>
> 对应工作包：RG-BM-011
>
> 部署边界：Resource Gateway 只在隔离的 `test` 或 `staging` Mirror 环境持续摄取客户生产事实。
> 它不在客户生产交易域运行，不回写业务源，也不替代客户的数据 Owner、Outcome Authority 或 ANEKE 治理。

## 1. 这项能力解决什么问题

Fixture、模拟和 Conformance 可以证明系统在已知输入下的行为，但不能证明真实客户业务最终发生了什么。
例如，取消费申诉流程运行成功，不代表乘客真的收到退款，也不代表后续没有冲正。生产 Outcome Source
把独立业务事实源中的结果持续转换为签名、无业务 Payload、可重放的
`AuthoritativeOutcomeObservation`，再复用现有 Outcome inbox、reconciliation、selected population 和
continuous assessment 内核完成业务保真度校准。

该链路必须同时回答五个问题：

1. 事实来自哪一个客户批准的 Connector generation。
2. 当前 cursor、watermark 和 page chain 是否连续且不可倒退。
3. 迟到数据如何通过独立 Backfill 流修复，而不改写 Live 历史。
4. Connector、密钥或数据授权失效后，如何不可逆地停止整个 generation。
5. 崩溃发生在「拉取、落页、写 Observation、推进 cursor」任一点时，是否能精确恢复。

## 2. 责任边界

| 责任方 | 拥有的事实或动作 | 不拥有的事项 |
|---|---|---|
| 客户业务事实源 | 原始 Outcome、source cursor、watermark、迟到与撤销语义 | Resource Gateway 的 Evidence、Fidelity 或发布结论 |
| 客户数据 Owner / Source Authority | Connector generation、page seal、Backfill 授权、generation revoke、密钥生命周期 | Resource Gateway 内部重试、lease 和 checkpoint |
| Resource Gateway | payload-free page 校验、durable checkpoint、stage/apply/commit、Outcome inbox 准入、运行审计 | 原始业务 Payload、客户 Registry、发布门禁 |
| ANEKE | Registry、Workbook、治理投影、发布裁决和证据保留策略 | Connector cursor、Resource Gateway worker 调度 |
| 平台运维 | 区域部署、数据库、身份、mTLS、证书、告警、迁移和事故处置 | 伪造数据 Owner 授权或改写 checkpoint |

仓库不提供任何客户生产 Connector 或默认信任根。部署方必须显式实现
`AuthoritativeOutcomeSource`、`AuthoritativeOutcomeSourceAuthorityVerifier` 和既有
`AuthoritativeOutcomeAuthorityVerifier`。缺少任意 Authority 时，运行组件不会装配，能力探针保持失败关闭。

## 3. 数据流与不变量

```text
customer outcome source
  -> AuthoritativeOutcomeSource.fetch(exact committed position)
  -> externally sealed AuthoritativeOutcomeSourcePage
  -> verify page fingerprint + source authority + contiguous chain
  -> durably stage the complete page
  -> sign and exactly append every observation to the existing Outcome inbox
  -> atomically commit page fingerprint + cursor ref + watermark ref
  -> reconciliation / selected population / continuous assessment
  -> Package Evidence and ANEKE governance consumption
```

核心不变量：

- `Scope + connectorId + generation + streamKind + streamId` 是完整流坐标，所有读写均按企业 Scope 隔离。
- Live 流固定使用 `streamId=live`；Backfill 必须引用一份外部授权的 exact control command。
- 同一 generation 的部署基线不可改变。需要换协议、密钥、数据授权或 cursor 基线时发布新 generation。
- Page 必须满足 `sequence + 1`、前页 fingerprint、前 cursor、Backfill command ref 和内部 ordinal 闭合。
- Source page 中的 Observation 尚未由 Resource Gateway 签名；worker 通过既有 Observation Authority
  校验后再签名和准入，不建立第二个 Outcome 真相源。
- Cursor 只在整页所有 Observation 已 durable append 或 exact replay 后推进。
- 数据库保存完整的结构化 staged page。进程在准入中途崩溃时，下一个 lease owner 重放同一页，
  不能从 Source 重新猜测这一页。
- Lease 使用数据库时间、owner 和单调 epoch。失去 lease 的实例不能提交旧页。
- 错误只持久化稳定 reason code，不保存 provider body、异常文本、credential 或原始 cursor。

## 4. 部署方需要实现的两个 Source 接口

### 4.1 `AuthoritativeOutcomeSource`

```java
@Bean
AuthoritativeOutcomeSource customerOutcomeSource(CustomerLedgerClient client) {
    return new AuthoritativeOutcomeSource() {
        @Override
        public AuthoritativeOutcomeSourceCheckpointRepository.Registration
        liveRegistration() {
            return deploymentOwnedLiveBaseline();
        }

        @Override
        public FetchResult fetch(Position position) {
            // 只解析 position.committedCursorRef()，最多返回一个有界、已签名页面。
            return client.fetchPage(position);
        }

        @Override
        public Descriptor descriptor() {
            return new Descriptor(Descriptor.SCHEMA_VERSION,
                    true, true, true, true, true, true);
        }
    };
}
```

实现要求：

| 项目 | 要求 |
|---|---|
| I/O 边界 | `fetch` 每次最多返回一页；设置连接、读取和总 deadline；禁止无界分页或阻塞轮询 |
| Cursor | `MirrorArtifactRef` 只保存内容地址或 Vault 引用；不得保存 raw token、SQL offset 或业务 Payload |
| Transport | 私有信任库、mTLS、证书身份绑定和服务端 SPKI pin 必须全部成立；`descriptor()` 不允许降级为 `false` |
| Payload | Page 只能携带协议允许的 Outcome 结构化事实和引用；禁止 request/response、姓名、电话、订单明细和 credential |
| Status | 无新数据返回 `NO_CHANGE`；临时源故障返回 `SOURCE_UNAVAILABLE`；协议错误返回 `PROTOCOL_REJECTED`；仅 Backfill 可返回 `STREAM_COMPLETE` |
| Generation | 协议、身份、授权范围、密钥或基线变化时递增 generation；不得复用旧 generation 重置 cursor |

### 4.2 `AuthoritativeOutcomeSourceAuthorityVerifier`

```java
@Bean
AuthoritativeOutcomeSourceAuthorityVerifier sourceAuthority(
        CustomerSourceTrustStore trustStore) {
    return new AuthoritativeOutcomeSourceAuthorityVerifier() {
        @Override
        public boolean available() {
            return trustStore.currentGenerationUsable();
        }

        @Override
        public void verifyPage(AuthoritativeOutcomeSourcePage page) {
            trustStore.verifyPageIdentityGenerationRevocationAndSeal(page);
        }

        @Override
        public void verifyCommand(
                AuthoritativeOutcomeConnectorControlCommand command) {
            trustStore.verifyDataOwnerAuthorization(command);
        }
    };
}
```

`verifyPage` 必须校验 source identity、generation、签名、密钥有效期、撤销状态和允许的 Scope。
`verifyCommand` 必须校验数据 Owner 对 Backfill 时间窗或 generation revoke 的授权。HTTP bearer token
只能证明调用者身份，不能替代这两种业务 Authority。

既有 `AuthoritativeOutcomeAuthorityVerifier` 仍负责每条 Outcome Observation 的独立业务真实性。
Source Authority 和 Observation Authority 可以由同一客户系统托管，但必须是不同的策略判定，不得用
`return true` 合并成一个无语义信任开关。

## 5. PostgreSQL 与启动

### 5.1 迁移

在发布包含 Source worker 的实例前应用：

```text
resource-gateway-examples/src/main/resources/db/postgresql/
V20260815_002__authoritative_outcome_source_checkpoints.sql
```

迁移建立 command journal、generation lock、stream checkpoint、staged page、DB-time lease/epoch 和
区域领取索引。先在所有实例共享的数据库完成迁移，再发布应用。回滚应用版本时保留表和历史状态；
不得回滚 cursor、重用 generation 或手工删除 staged page。

### 5.2 启动条件

运行面仅在以下条件同时满足时装配：

1. Spring profile 包含 `test` 或 `staging`，且不包含 `production`。
2. `gateway.testing.mirror.enabled=true`。
3. 部署方提供 Source Authority、Observation Authority、Evidence signer 和 Source adapter。
4. PostgreSQL checkpoint 与既有 Outcome inbox 可用。
5. 需要连续运行时，显式开启 Scheduler。

```bash
mvn -f resource-gateway-examples/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments='\
--spring.profiles.active=staging \
--gateway.testing.mirror.enabled=true \
--gateway.testing.mirror.outcome-source.scheduler.enabled=true \
--gateway.testing.mirror.outcome-source.scheduler.instance-id=rg-outcome-sg-a \
--gateway.testing.mirror.outcome-source.scheduler.region=sg \
--gateway.testing.mirror.outcome-source.scheduler.environment-id=staging \
--gateway.testing.mirror.outcome-source.scheduler.maximum-pollers=2 \
--gateway.testing.mirror.outcome-source.scheduler.poll-interval-millis=1000'
```

该命令只是装配示例。若没有部署方提供的三个 Authority/Source bean，能力仍然不可用，这是正确行为。
`prod`、`production` 和 `live` 不能作为 Scheduler 的 `environment-id`，`production` profile 下相关
Bean 与 HTTP 路由物理不存在。

优雅停止会先取消新领取，再在 `drain-timeout-millis` 内等待本地在途 turn。超时后进程可退出；
数据库 lease 到期后由其他实例接管 staged page。

## 6. 能力探针

客户端必须先读取动态能力，不得根据版本号或 classpath 猜测连接器可用：

```bash
curl -s http://localhost:8080/api/integration/capabilities \
  | jq '.payload | {
      objects: {
        page: .supportedObjects.authoritativeOutcomeSourcePage,
        command: .supportedObjects.authoritativeOutcomeConnectorControlCommand,
        checkpoint: .supportedObjects.authoritativeOutcomeSourceCheckpoint,
        descriptor: .supportedObjects.authoritativeOutcomeSourceDescriptor
      },
      runtime: {
        protocol: .features.mirrorAuthoritativeOutcomeSourceProtocol,
        controlApi: .features.mirrorAuthoritativeOutcomeSourceControlApi,
        durableCheckpoint: .features.mirrorAuthoritativeOutcomeSourceDurableCheckpoint,
        source: .features.mirrorAuthoritativeOutcomeSourceReady,
        authority: .features.mirrorAuthoritativeOutcomeSourceAuthorityReady,
        worker: .features.mirrorAuthoritativeOutcomeSourceWorkerReady,
        scheduling: .features.mirrorAuthoritativeOutcomeSourceScheduling,
        continuous: .features.mirrorAuthoritativeOutcomeSourceContinuousReady
      }
    }'
```

`protocol=true` 只表示当前软件理解协议。只有 `continuous=true` 才表示 control API、durable checkpoint、
Source、Authority、worker 和 Scheduler 在当前时刻全部可用。任何一项为 `false` 时，治理侧必须把
freshness 视为未知或陈旧，不能继续沿用历史 Outcome 冒充实时结论。

## 7. Backfill、Revoke 与 checkpoint 操作

先设置认证信息。实际 Scope 来自服务端 trusted identity registry，不能通过请求头自行扩权。

```bash
export RG_URL=http://localhost:8080
export RG_TOKEN=replace-with-staging-identity-token
export RG_HEADERS=(-H "Authorization: Bearer $RG_TOKEN")
```

### 7.1 注册独立 Backfill 流

从固定样例复制结构并替换 exact Scope、generation、时间窗、baseline refs、fingerprint 和外部签名：

```bash
curl -s -X POST "${RG_HEADERS[@]}" \
  -H 'X-Purpose: MIRROR_OUTCOME_SOURCE_ADMIN' \
  -H 'Content-Type: application/json' \
  "$RG_URL/api/mirror/outcome-sources/backfills" \
  --data-binary @signed-backfill-command.json
```

仓库 fixture 只用于查看字段结构和做兼容性测试，其固定授权时间窗不代表当前有效，不能原样提交。
同一个 `commandId + revision + fingerprint` 可精确重放。相同身份携带不同材料会返回冲突。
Backfill 使用独立 `streamId` 和 cursor chain；它不能修改 Live cursor，也不能覆盖既有 Observation 历史。

### 7.2 不可逆撤销 generation

```bash
curl -s -X POST "${RG_HEADERS[@]}" \
  -H 'X-Purpose: MIRROR_OUTCOME_SOURCE_ADMIN' \
  -H 'Content-Type: application/json' \
  "$RG_URL/api/mirror/outcome-sources/revocations" \
  --data-binary @signed-revoke-generation-command.json
```

撤销会 fence 该 Scope、Connector 和 generation 下的 Live/Backfill 流。恢复服务必须发布新 generation
和新部署基线，不能把已撤销状态改回 `ACTIVE`。

### 7.3 读取 payload-free checkpoint

```bash
curl -s "${RG_HEADERS[@]}" \
  -H 'X-Purpose: GOVERNANCE_EVIDENCE_INGESTION' \
  "$RG_URL/api/mirror/outcome-sources/settlement-ledger/generations/7/streams/LIVE/live" \
  | jq '.payload | {
      key, committedSequence, committedPageFingerprint,
      committedCursorRef, committedWatermarkRef, eventTimeThrough,
      status, stagedPageFingerprint, consecutiveFailures,
      nextEligibleAt, leaseEpoch, failureCode, updatedAt
    }'
```

Checkpoint 不含原始 cursor 或 Payload。`RUNNING` 且有 `stagedPageFingerprint` 表示一页正在处理或等待
崩溃恢复；`QUARANTINED`、`REVOKED` 和陈旧 `eventTimeThrough` 都必须触发运维或治理阻断。

## 8. 状态、重试与事故处置

| 状态或现象 | 含义 | 运维动作 |
|---|---|---|
| `ACTIVE` + `NO_CHANGE` | Source 当前无新页，worker 已释放 lease | 比较 `eventTimeThrough` 与业务 freshness SLO；不要把无新页自动解释为新鲜 |
| `RUNNING` | 某 owner/epoch 已领取，可能有 staged page | 等待 lease；实例消失后由其他 owner 接管，不手工改 owner/epoch |
| `COMPLETE` | Backfill 明确返回终点 | 固化 checkpoint 与对应 Outcome 证据；Live 流出现该语义应视为协议错误 |
| `REVOKED` | 外部 Authority 已不可逆撤销 generation | 停止消费；完成密钥、授权、基线审查后发布新 generation |
| `QUARANTINED` | 非重试错误或连续失败达到上限 | 检查稳定 `failureCode`、Source/Authority 日志和签名材料；修复后通过新授权流程处理，不直接改表 |
| `SOURCE_UNAVAILABLE` | 上游临时不可达 | 观察退避与 SLO；连续故障时切换到陈旧治理状态，不降低 mTLS 或 trust policy |
| `AUTHORITY_UNAVAILABLE` | 信任根、撤销状态或 signer 暂不可用 | 先恢复 Authority；禁止跳过校验推进 cursor |
| `PROTOCOL_REJECTED` | Page chain、Scope、cursor、watermark 或字段不合法 | 隔离 Connector generation，比较 Schema、canonical fingerprint 和前一 checkpoint |
| checkpoint 长期不推进 | Source 无页、cursor stall、Scheduler 停止或 worker 无法领取 | 依次检查 capability、Scheduler、DB lease、Source status、`nextEligibleAt` 和 watermark SLO |

默认 policy：lease `30s`、最大连续失败 `8`、基础失败退避 `5s`、最大失败退避 `5min`、空闲退避 `5s`。
调整这些值前，必须在目标 PostgreSQL 拓扑执行 kill、partition、长页、Authority 中断和优雅停止认证。

## 9. Test Kit 离线验证

Test Kit 不依赖 Spring 或 Resource Gateway server artifact。它验证 strict Schema、producer content
address、流闭合和 checkpoint 语义；客户 Source seal 仍由调用方自己的 trust callback 判定。

```java
AuthoritativeOutcomeSourceProtocolVerifier verifier =
        new AuthoritativeOutcomeSourceProtocolVerifier();

JsonNode page = CapabilityMirrorProtocol.authoritativeOutcomeSourcePageFixture();
JsonNode command = CapabilityMirrorProtocol.authoritativeOutcomeSourceCommandFixture();
JsonNode checkpoint =
        CapabilityMirrorProtocol.authoritativeOutcomeSourceCheckpointFixture();

JsonNode verifiedPage = verifier.requirePage(
        page,
        (seal, artifact) -> customerTrust.verifySourceSeal(seal, artifact));
JsonNode verifiedCommand = verifier.requireCommand(
        command,
        (seal, artifact) -> customerTrust.verifyOwnerAuthorization(seal, artifact));
JsonNode verifiedCheckpoint = verifier.requireCheckpoint(checkpoint);
```

固定 fixture 中的签名仅用于协议兼容测试，不是可信客户证据。生产消费者必须 pin 自己的 trust root、
key policy 和 revocation state；不能使用 `(seal, artifact) -> true`。

协议资源：

- [Source Page Schema](schemas/resource-gateway-mirror/authoritative-outcome-source-page-v1.schema.json)
- [Control Command Schema](schemas/resource-gateway-mirror/authoritative-outcome-connector-control-command-v1.schema.json)
- [Checkpoint Schema](schemas/resource-gateway-mirror/authoritative-outcome-source-checkpoint-v1.schema.json)
- [Live Page fixture](schemas/resource-gateway-mirror/authoritative-outcome-source-page-live-v1.fixture.json)
- [Backfill Command fixture](schemas/resource-gateway-mirror/authoritative-outcome-source-command-backfill-v1.fixture.json)
- [Live Checkpoint fixture](schemas/resource-gateway-mirror/authoritative-outcome-source-checkpoint-live-v1.fixture.json)

## 10. 上线与验收清单

### 10.1 Connector 合约

- [ ] Live baseline 由部署配置冻结，重启 exact replay，不允许 cursor rewind。
- [ ] generation 变更规则覆盖协议、身份、权限、密钥、Source schema 和基线变化。
- [ ] 一页大小、条数、I/O deadline 和 provider rate limit 有明确上限。
- [ ] `NO_CHANGE`、临时不可用、永久协议错误、Backfill 完成和 generation 撤销可区分。
- [ ] raw cursor、业务 Payload、credential 和 provider exception 不进入数据库、日志、审计或协议。

### 10.2 信任与隔离

- [ ] Source page 与控制命令分别由客户批准的 trust policy 验证。
- [ ] Observation 仍通过独立业务 Authority，不把 Connector 签名当成 Outcome 正确性。
- [ ] 私有 CA、mTLS、SPKI pin、certificate identity binding 和 rotation/revocation 演练通过。
- [ ] `production` profile、保留环境名和真实 write-capable egress 均失败关闭。
- [ ] 身份 Scope、purpose 和所有成功/失败命令均有 payload-free audit。

### 10.3 恢复与可运营性

- [ ] 两个实例竞争同一流时只有一个 owner/epoch 获胜。
- [ ] 在 stage 前、stage 后、每条 inbox append 后和 commit 前 kill，重启均精确恢复。
- [ ] cursor stall、watermark regression、迟到、冲突、Backfill 和 revoke 演练有可回放证据。
- [ ] Authority 不可用、数据库抖动和 Source 超时不会推进 cursor。
- [ ] `continuousReady=false`、checkpoint stale、quarantine 和 revoke 已接入告警与治理阻断。
- [ ] 备份恢复后验证 command journal、generation fence、checkpoint、staged page 和 inbox closure。

### 10.4 诚实的成熟度边界

仓库测试已证明：协议闭合、H2 restart、PostgreSQL 14 双连接唯一 claim、staged page 崩溃恢复、
generation revoke、Spring profile 隔离、strict Schema 和 Test Kit 独立校验。它尚未证明某一客户的
真实 Connector、私有 PKI/KMS、跨区域网络、PostgreSQL HA、容量、升级或业务 Outcome 语义。
这些证据分别属于 RG-BM-012、RG-BM-013 和客户试点 RG-BM-015，不得由本地绿色测试替代。
