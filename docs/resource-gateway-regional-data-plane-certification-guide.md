# Resource Gateway 区域数据平面认证接入与运维指南

> 适用协议：`resourceGateway.regionalDataPlaneDeploymentContract.v1`、
> `resourceGateway.regionalDataPlaneCertification.v1`、
> `resourceGateway.mirrorDeploymentIsolationAttestationBundle.v2`
>
> 对应工作包：RG-BM-012
>
> 部署边界：Resource Gateway 验证并消费客户区域基础设施的认证事实。它不部署 KMS、Vault、
> 私有 PKI、状态存储或网络策略，也不替代客户 Security、SRE、数据 Owner 和 TEE 治理。

## 1. 这项能力解决什么问题

Mirror Runtime 原有的 deployment-isolation attestation 能证明一个工作负载禁止生产身份、生产凭据和
外部业务出口，但不能独立证明运行所依赖的 KMS、Payload Vault、Secret Authority、State Store、
Fixture Resolver、mTLS 和 Egress Isolation 同时位于正确区域、使用正确策略、处于当前代际并完成安全轮换。

区域数据平面认证把这些条件变成两个内容寻址对象，并把认证引用绑定到现有隔离决策：

1. `RegionalDataPlaneDeploymentContract` 冻结客户批准的目标状态。
2. `RegionalDataPlaneCertification` 记录外部 Authority 对当前部署的短期观察。
3. `MirrorDeploymentIsolationAttestationBundle.v2` 内容寻址到精确认证 revision 和 fingerprint。
4. Mirror 在 admission、execution confirmation 和 evidence commit permit 三个时点重新读取并验证同一闭包。
5. 缺失、篡改、过期、降级、代际漂移、轮换失败或 write escape 均失败关闭。

该机制证明「某次 Mirror 运行使用了已认证的区域数据平面」，不证明客户基础设施已经完成 HA、灾备、
升级或备份恢复认证。后者由 RG-BM-013 的 Runtime Certification Harness 负责。

## 2. 责任边界

| 责任方 | 拥有的事实或动作 | 不拥有的事项 |
|---|---|---|
| 客户 Security / SRE | Contract、区域部署身份、KMS/CA 生命周期、组件观察、外部签名和 proof refs | Mirror 运行结果、Package 治理和 ANEKE 发布裁决 |
| 客户区域数据面 | KMS、Vault、Secret、State、Resolver、mTLS、Egress 的实际实现和可用性 | 自行宣称 Resource Gateway 已验证 |
| Resource Gateway | strict protocol、内容地址、外部签名验证、隔离闭包、运行三次复核、持久化和能力探针 | 私钥、原始证书、Secret、业务 Payload、基础设施部署 |
| Resource Gateway Test Kit | 离线 Schema、fingerprint、Scope、窗口、组件、轮换和 write-escape 复验 | 替客户选择信任根或调用客户 KMS |
| ANEKE | Registry、Workbook、治理投影、发布门禁和证据保留 | 区域基础设施实时状态和 Mirror commit permit |

仓库不提供生产默认 `RegionalDataPlaneCertificationMaterialSource`，也不提供可被误用的 synthetic
ready Adapter。未接入客户材料源时，`mirrorRegionalDataPlaneCertificationReady=false`。若部署显式设置
`gateway.testing.mirror.regional-data-plane.required=true`，缺少 Adapter 会阻断 Mirror 运行。

## 3. 七类组件与强制事实

Contract 必须且只能包含以下七类组件。每类组件都必须绑定 `authorityId`、精确 `policyRef`、最小代际、
最大观察年龄、私有传输、失败关闭和区域驻留要求。

| 组件 | 必须证明的事实 | 典型失败处理 |
|---|---|---|
| `EVIDENCE_KMS` | Evidence signing key 来自区域 KMS/HSM；当前 generation 达标；私钥不可导出 | 停止 admission；撤销受影响认证和 Evidence key |
| `PAYLOAD_VAULT` | 脱敏 Payload 只在批准区域按 purpose 和 TTL 访问 | 停止读取；不得回退到本地明文或日志 |
| `SECRET_AUTHORITY` | 测试 Secret 与生产 Secret 隔离；短期授权；失联失败关闭 | 停止运行；不得使用缓存的过期 credential |
| `SESSION_STATE_STORE` | Session、lease、fence 和状态代际在批准区域持久化 | 停止有状态运行；不得回退到进程内状态 |
| `FIXTURE_RESOLVER` | Fixture exact ref、revision、fingerprint 和授权均在线复验 | 返回 resolver unavailable；不得猜测或使用旧 head |
| `MUTUAL_TLS` | 工作负载身份、服务端身份、私有 CA 和活动代际一致 | 断开旧连接；旧 session 未排空时拒绝认证 |
| `EGRESS_ISOLATION` | 默认拒绝出口；外部业务写入被物理阻断；允许目的地有内容地址 | 任一 write attempt 即拒绝认证并触发安全事件 |

`ComponentStatus` 只有 `READY` 可放行。`DEGRADED`、`UNAVAILABLE` 和 `REVOKED` 不参与加权，也不能由其他
健康组件抵消。

## 4. Contract 与 Certification 如何闭合

### 4.1 Deployment Contract

Contract 由 Security/SRE Owner 审批并内容寻址。关键字段如下：

| 字段 | 约束 |
|---|---|
| `scope` | 完整 `tenantId / organizationId / projectId / environmentId / region` |
| `region` | 精确基础设施区域标识，不使用模糊的全球或默认区域 |
| `deployment` | `deploymentScopeId / clusterId / namespace / workloadName / serviceAccount / imageDigest` 全部固定 |
| `requiredComponents` | 七类组件各一个，按枚举顺序；不得缺失、重复或附加未知组件 |
| `rotationPolicy` | KMS key 最大年龄、CA 最大年龄、最小双信任重叠、无重启和旧会话排空 |
| `validFrom / expiresAt` | Contract 的批准有效窗；运行必须完整落在窗口内 |

生产者使用 `RegionalDataPlaneCertificationIntegrity.address(...)` 生成 canonical fingerprint。消费者必须重新
计算 fingerprint，不能信任请求中的声明值。

### 4.2 Short-lived Certification

Certification 最长有效期为 15 分钟，并包含：

- 七个 `ComponentObservation`，每个都记录 Authority、Policy、generation、状态、观察时间和控制事实；
- 两个 `RotationObservation`，分别对应 Evidence KMS key 和 mTLS CA；
- 活动代际启用时间与实际双信任重叠秒数；
- 旧代际撤销、全副本收敛、旧会话排空和无重启事实；
- `externalBusinessWriteAttemptCount` 与 `writeEscapeCount`，两者都必须为 `0`；
- 只含内容地址的 `proofRefs`；
- 外部 Ed25519 Authority seal。

验证器按运行开始时间计算活动 key/CA 年龄，并与 Contract 的最大年龄比较。它同时校验实际重叠时间不小于
Contract 的 `minimumOverlapSeconds`。历史上曾完成一次轮换，不能替代当前活动代际仍然有效的证明。

### 4.3 Isolation Bundle v2

v2 bundle 保留既有 authority publication、isolation attestation 和 irreversible status，并新增
`regionalDataPlaneCertificationRef`。该引用参与 bundle fingerprint。数据库保存 bundle 版本与认证引用，
因此进程重启、精确重放和 attestation revoke 不会把 v2 降级为 v1。

同一 attestation revision 如果携带不同 Certification ref，会被视为 `REVISION_FORK`，不能按幂等请求吞掉。

## 5. 客户 Adapter

部署方实现一个原子材料源。一次 `current(scope)` 必须来自同一控制面快照，不得分别读取四个 mutable head
再拼接成可能混代的结果。

```java
@Bean
RegionalDataPlaneCertificationMaterialSource regionalCertificationMaterialSource(
        RegionalCertificationSidecar sidecar) {
    return new RegionalDataPlaneCertificationMaterialSource() {
        @Override
        public boolean available() {
            return sidecar.privateMtlsReady();
        }

        @Override
        public Current current(CapabilitySnapshot.Scope scope) {
            RegionalCertificationSnapshot snapshot = sidecar.current(scope);
            return new Current(
                    snapshot.isolationDecision(),
                    snapshot.deploymentContract(),
                    snapshot.certification(),
                    snapshot.pinnedAuthorityKey(),
                    snapshot.localDeploymentIdentity());
        }
    };
}
```

Adapter 必须满足以下要求：

1. 使用私有 mTLS 或等价的本地信任通道。
2. 按完整 Scope 精确读取，不允许 tenant、organization、project、environment 或 region 通配。
3. 原子返回 isolation decision、Contract、Certification、Authority key 和本地 deployment identity。
4. Authority key 来自客户固定的信任通道，不能取自待验证 Certification。
5. 设置连接、读取和总 deadline；异常时抛出失败，不返回上次成功材料冒充当前状态。
6. 不返回私钥、raw certificate、credential、endpoint URI、业务 request/response 或客户 Payload。
7. `available()` 只表示当前可尝试精确读取，不缓存永久 ready 结论。

Spring 在发现该 Bean 后自动装配 `VerifiedRegionalDataPlaneCertificationAuthority`，并把它组合进既有
`MirrorDeploymentIsolationRunTrustAuthority`。部署方不需要实现第二套运行生命周期。

## 6. 启动、停止与能力探针

### 6.1 启动条件

区域认证运行面只允许在隔离的 `test` 或 `staging` Mirror profile 中使用：

1. 启用 `gateway.testing.mirror.enabled=true`。
2. 部署客户 `RegionalDataPlaneCertificationMaterialSource` Bean。
3. 配置既有 deployment-isolation trust agent、Authority、admission policy 和 Evidence signer。
4. 对需要强制区域认证的环境设置
   `gateway.testing.mirror.regional-data-plane.required=true`。
5. 确保共享数据库已允许新增 `bundle_schema_version` 和
   `regional_certification_ref_json` 字段。Repository 会执行向后兼容的加法初始化；外部 Schema 管理环境应先审批并应用等价 DDL。

```bash
mvn -f resource-gateway-examples/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments='\
--spring.profiles.active=staging \
--gateway.testing.mirror.enabled=true \
--gateway.testing.mirror.regional-data-plane.required=true'
```

仓库演示启动脚本可以启动 UI 和基础 API，但不会伪造客户 KMS、Vault、PKI 或认证 Authority：

```bash
bash scripts/start-visual-canvas-demo.sh
bash scripts/stop-visual-canvas-demo.sh
```

停止生产化 staging 实例时，先停止新的 Mirror admission，再等待在途运行完成或取消，最后停止进程。
不要在运行中撤销数据库列、删除当前认证引用或回滚 attestation head。

### 6.2 能力探针

```bash
curl -s http://localhost:8080/api/integration/capabilities \
  | jq '.payload | {
      objects: {
        contract: .supportedObjects.regionalDataPlaneDeploymentContract,
        certification: .supportedObjects.regionalDataPlaneCertification,
        authority: .supportedObjects.regionalDataPlaneCertificationAuthorityDescriptor,
        isolationBundle: .supportedObjects.mirrorDeploymentIsolationAttestationBundle
      },
      runtime: {
        isolationRunTrust: .features.mirrorIsolationRunTrustReady,
        regionalCertification: .features.mirrorRegionalDataPlaneCertificationReady,
        certifiableEvidence: .features.mirrorCertifiableEvidenceServingReady
      }
    }'
```

解释规则：

- `supportedObjects` 表示软件理解该协议，不表示客户基础设施已连接。
- `mirrorIsolationRunTrustReady` 表示既有 deployment-isolation trust 当前可用。
- `mirrorRegionalDataPlaneCertificationReady` 独立读取客户区域认证 Authority 的动态状态。
- 部署要求区域认证时，只有前两项都为 `true` 才能运行。
- capability probe 发生 Adapter 异常时返回 `false`，不能传播 provider body 或异常 message。

## 7. 运行时检查顺序

每次 Mirror 运行执行以下检查：

1. 既有 isolation authority 在 admission 时返回稳定 decision ref 和 attestation ref。
2. 区域 Authority 读取当前原子材料，并验证 exact ref、Scope、deployment、Contract 和 Certification。
3. 运行完成后，系统对完整 execution window 重新验证同一 decision 和 Certification。
4. 写入 terminal Evidence 前，系统先取得既有 isolation commit permit，再第三次验证区域材料。
5. 第三次验证通过后，Evidence 事务提交；验证失败时释放 permit 并拒绝提交。

这三个时点阻断以下 TOCTOU：运行前证书有效但运行中撤销、执行后组件降级、提交前 decision head 漂移、
KMS/CA generation 变化或出口策略失效。

## 8. 离线 Test Kit 验证

`resource-gateway-test-kit` 提供不依赖 Spring 和服务端类的
`RegionalDataPlaneCertificationVerifier`：

```java
RegionalDataPlaneCertificationVerifier.VerifiedCoordinates verified =
        new RegionalDataPlaneCertificationVerifier().require(
                contractJson,
                certificationJson,
                isolationBundleV2Json,
                executionStartedAt,
                executionCompletedAt,
                (seal, certification) -> customerTrustStore.verify(seal, certification));
```

外部 callback 必须验证 Authority identity、key lifecycle、revocation 和 Ed25519 signature。除有界单元测试外，
不得实现为 `(seal, certification) -> true`。

固定 fixtures 位于 `docs/schemas/resource-gateway-mirror/`：

- `regional-data-plane-deployment-contract-v1.fixture.json`
- `regional-data-plane-certification-v1.fixture.json`
- `mirror-deployment-isolation-attestation-bundle-v2.fixture.json`

这些 fixtures 使用公开占位签名，只证明 Schema、内容地址和跨版本兼容性。它们不是当前有效认证，不能进入
客户发布门禁或作为目标环境上线证据。

## 9. 轮换操作

### 9.1 KMS key 或 mTLS CA 轮换

1. 发布 successor generation，并保留 Contract 要求的双信任重叠。
2. 等待全部服务副本确认 successor generation。
3. 排空旧 TLS/key session。
4. 撤销 previous generation。
5. 记录 `activeGenerationActivatedAt`、`overlapAchievedSeconds` 和四项收敛事实。
6. 由外部 Authority 签发新的短期 Certification。
7. 发布引用新 Certification 的 isolation bundle v2。
8. 检查 capability probe 和受控 Mirror smoke run。

任一步未完成时，不要把旧 Certification 延长。若活动代际年龄超过 Contract 上限，即使最近一次轮换演练
曾通过，验证器仍返回 `ACTIVE_KEY_OR_CA_AGE_REJECTED`。

### 9.2 Contract 变更

Region、deployment identity、Authority、Policy、最小代际或 rotation policy 变化时，发布新 Contract
revision 和 fingerprint，再签发引用该 Contract 的新 Certification。禁止修改已发布对象或复用旧 fingerprint。

## 10. 故障排查与事故处置

| reason code 或现象 | 判断 | 处置 |
|---|---|---|
| `REGIONAL_CERTIFICATION_SOURCE_UNAVAILABLE` | Adapter 当前不可用 | 检查私有 mTLS、sidecar 和 deadline；不要切换到历史缓存 |
| `REGIONAL_CERTIFICATION_SOURCE_READ_FAILED` | Adapter 抛出读取异常 | 保存 payload-free request/trace 坐标；检查原子读取，不记录 provider body |
| `REGIONAL_ISOLATION_DECISION_DRIFTED` | 运行绑定与当前 v2 decision 不同 | 停止新运行；读取最新 isolation head；重新 admission |
| `REGIONAL_CERTIFICATION_SIGNATURE_INVALID` | 内容、签名或公钥不一致 | 隔离 artifact；检查 Authority key channel；不得重签旧材料掩盖篡改 |
| `REGIONAL_COMPONENT_OBSERVATION_STALE` | 组件观察超过 Contract 上限 | 刷新全部七组件原子观察并签发新 Certification |
| `REGIONAL_COMPONENT_CONTROL_NOT_READY` | 组件降级或控制事实为 false | 恢复对应基础设施；禁止用其他组件健康抵消 |
| `REGIONAL_KEY_OR_CA_ROTATION_NOT_CONVERGED` | 重叠、撤销、副本、session 或无重启要求失败 | 保持运行阻断；完成轮换或回滚到安全新 generation，不复活已撤销 generation |
| `REGIONAL_ACTIVE_KEY_OR_CA_AGE_REJECTED` | 活动 KMS key 或 CA 超龄 | 执行受控轮换并签发新 Certification |
| `REGIONAL_EXTERNAL_BUSINESS_WRITE_OBSERVED` | 出现 write attempt 或 write escape | 立即停止 Mirror admission；撤销认证；按安全事件调查出口策略和凭据边界 |

发生 write attempt 时，即使 `writeEscapeCount=0` 也必须拒绝认证。Attempt 说明某条调用路径已触达外部业务写
边界，不能把网络最终拒绝当成设计正确。

恢复必须生成新观察、新 Certification 和新 v2 decision。不要修改历史 Certification、清零计数或删除
失败 Evidence。

## 11. 上线验收清单

- [ ] Security/SRE Owner 已批准 Contract revision、Scope、region 和 deployment identity。
- [ ] 七类组件各有独立 Authority、Policy、代际、观察年龄和 payload-free proof。
- [ ] KMS key 与 mTLS CA 的年龄、重叠、撤销、副本收敛和旧会话排空均通过。
- [ ] 客户信任根通过独立通道固定；待验证 Certification 不能自带信任根。
- [ ] Material Source 使用原子精确读取，并为所有 I/O 设置 deadline。
- [ ] `regional-data-plane.required=true` 在缺失 Adapter、过期证书和 provider 异常时均失败关闭。
- [ ] 数据库重启后 v2 bundle 保持 Certification ref；revoke 后仍保留相同 ref。
- [ ] admission、confirm 和 commit permit 三个时点均执行区域复核。
- [ ] `externalBusinessWriteAttemptCount=0` 且 `writeEscapeCount=0`。
- [ ] Test Kit 在独立进程中通过 Schema、fingerprint、窗口、轮换和 external seal 验证。
- [ ] 能力探针区分 protocol support、isolation trust 和 regional certification readiness。
- [ ] 固定 fixture 被标记为兼容性样例，没有进入客户证据库。
- [ ] 真实 KMS/Vault/PKI/State/Resolver/egress 环境的认证报告已由客户 Owner 签字。

最后一项不能由仓库自动化测试替代。仓库实现证明协议和失败关闭语义；客户环境认证证明实际基础设施满足
这些语义。
