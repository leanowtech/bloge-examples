# Stage 4 bootstrap-root recovery fleet publication floor kernel verification

## 1. 结论与边界

本子步冻结 recovery fleet dynamic inventory 的治理内核：

- strict `ACTIVE/REVOKED` publication；
- 独立 witness checkpoint；
- publication/witness 双前驱链；
- nested inventory generation、identity 与撤销状态的单调 floor；
- deployment scope + fleet 双重稳定作用域；
- 数据库时钟、跨副本线性化、跨重启 durable floor；
- strict machine JSON Schema。

它还没有接入 HTTPS/ETag refresh authority。当前 static authority 不会自动消费 publication，worker 也不会
仅因为 floor 表出现新行就感知撤销。这个边界刻意保留：先把治理事实和单调状态机冻结，再让动态消费者只
能沿同一协议前进，避免远端 transport、密码学验证、运行时发布与数据库 mutation 同时设计而留下旁路。

## 2. 根因

static signed inventory 只能证明“这份 lane 清单在有效期内曾被授权”。它不能证明：

1. 有效期内没有被治理侧撤销；
2. 当前副本没有回退到仍未过期的旧清单；
3. 两个副本没有接受同一 sequence 的不同事实；
4. 数据库恢复后没有忘记曾见过的更高 publication head；
5. publication authority 没有独自重写历史。
6. 一个签名合法且双链连续的 successor 没有包裹更旧或同代不同身份的 inventory。

因此本步不把 generation 继续塞进 replica-local 配置，而是建立两条独立签名链，并以持久 floor 记录系统
已经接受的最高双链 head，并同时冻结该 head 对应的 inventory 代际、身份和治理状态。

## 3. Publication 协议

`bloge.externalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.v1` 包含：

- 已有的完整 signed inventory attestation；
- deployment authority 签名的 publication material；
- witness authority 独立签名的 checkpoint。

publication material 绑定 trust domain、publication id、deployment scope、fleet id、单调 sequence、
inventory material fingerprint、`ACTIVE/REVOKED`、policy、publication predecessor 和 hard validity
window。`ACTIVE` 必须使用空 reason；`REVOKED` 必须给出有界稳定 reason code。

witness material 独立绑定 witness domain、checkpoint id、相同 scope/fleet/sequence、精确 publication
fingerprint、witness predecessor 和自己的 hard validity window。envelope 构造阶段即拒绝 scope、fleet、
sequence、publication fingerprint 的交叉链接歧义；签名必须按 authority/key canonical 排序，且 authority
不可重复。

机器合同位于
[`external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-publication-v1.schema.json`](schemas/resource-gateway-testing/external-sequence-anchor-bootstrap-root-recovery-fleet-inventory-publication-v1.schema.json)，
并通过相对 `$ref` 复用已有 exact inventory schema，不复制第二份 lane 定义。

## 4. Durable floor

`ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor` 只接受已经完成 inventory、
publication、witness、binding 与 freshness 验证的 private generation。candidate 同时携带 scope、fleet、
sequence、nested inventory generation/fingerprint、`ACTIVE/REVOKED`、当前 publication/witness fingerprint
和两个 predecessor。

`DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor` 使用
`(deployment_scope_id, fleet_id)` 复合锁线性化所有副本：

- 缺失 floor 只允许 sequence 1 建立；
- 当前 exact generation 可幂等 replay；
- successor 必须恰好 `current + 1`；
- 两个 predecessor 必须分别等于当前双链 head；
- nested inventory generation 不得回退，同 generation 不得替换 fingerprint；
- `REVOKED` inventory 只有以更高 generation、不同 fingerprint 发布后才能重新激活；
- rollback、同 sequence fork、gap、断链和跨 scope/fleet 输入全部拒绝；
- 整行以 canonical fingerprint 绑定 scope、fleet、sequence、inventory generation/identity/state、双 head 与数据库 observed time；
- 腐化行不可读取，也不可被新 candidate 覆盖修复。

存储记录升级为 v2。已存在的 v1 行先增补 nullable 列，但不能直接接纳 successor；调用方必须提供与旧
sequence、publication head、witness head 完全相同且已经完成全链验签的当前 publication，事务才会用其
nested inventory 事实水合 v2 行。旧 head 不精确、旧 whole-record fingerprint 腐化或直接跳 successor
全部 fail closed，避免 schema migration 变成回滚旁路。

表名控制在常见数据库 63 字符 identifier 限制内，但本步 SQL 仍只在仓内 H2 test-runtime 验证，不宣称
PostgreSQL/MySQL 已认证。

## 5. 威胁与失败语义

| 失败 | 处理 |
| --- | --- |
| 旧 sequence | `rollback`，事务回滚 |
| 同 sequence 不同双 head | `fork`，事务回滚 |
| 跳过 sequence | `sequence gap`，事务回滚 |
| 任一 predecessor 不匹配 | `predecessor mismatch`，事务回滚 |
| successor 包裹更低 inventory generation | `inventory rollback`，事务回滚 |
| 同 inventory generation 替换 identity | `inventory fork`，事务回滚 |
| 撤销后用同一 inventory 重新激活 | `reactivation`，事务回滚 |
| v1 floor 未先精确回放当前 head | 拒绝迁移与 successor，不猜测旧 inventory 状态 |
| scope/fleet 替换 | mutation 前拒绝 |
| floor record 被部分改写 | whole-record 校验失败，禁止覆盖 |
| 两副本竞争不同 successor | 数据库锁下仅一方成功，另一方稳定 fork |
| 数据库不可用 | 调用失败，不伪造本地 advance |

本地数据库 floor 只能防止普通进程回退和多副本分叉；能同时回滚数据库与应用备份的权威仍可重写历史。
`externallyAnchored=false` 与 `byzantineQuorumAnchored=false` 因此保持真实。

## 6. 验证

聚焦门禁：

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationTest,ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationProtocolSchemaTest,DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloorTest \
  test
```

该命令执行 18 tests，0 failures、0 errors、0 skips，覆盖 protocol cross-link、canonical signatures、
state/reason、fingerprint tamper、Schema/DTO parity、敏感字段排除、durable rebuild、rollback/fork/gap、
predecessor、nested inventory rollback/fork、撤销后重激活、v1→v2 精确水合、scope/fleet isolation、
row corruption、双副本竞争和数据库关闭。三个公共类型通过
`javadoc --release 25 -Werror -Xdoclint:all`，0 warnings、0 errors。

## 7. 下一闭环

下一子步必须实现同一协议的 bounded HTTPS/ETag dynamic authority：严格 media/protocol negotiation、
M-of-N publication 签名、独立 witness quorum、nested inventory 复验、ACTIVE-only runtime resolution、
atomic last-known-good replacement、refresh failure hard fence、signed revocation、maximum snapshot age、
floor-before-publish 和 worker in-flight fence。不得另建 unsigned revocation flag 或把 ETag 当作治理代际。
