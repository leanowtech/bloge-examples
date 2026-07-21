# Stage 4 Certificate Rotation Event Watcher Kernel 验收

## 1. 结论

本子步把已冻结的 event page-chain 和 durable cursor 接成可执行消费内核，但尚未声明 Spring 产品接线
完成。严格 HTTP source 通过独立 PKIX、hostname、SPKI pin、mTLS 与双端 workload identity transport
取页；watcher 只在本副本 serving admission 成立时执行 `fetch -> stage -> apply all -> commit`。

事件源传输身份只授权“交付页面”，不授权“轮换证书”。每个页面内事件仍逐一进入既有 M-of-N
Ed25519 trust、material fingerprint 和 durable floor；任一事件失败，页面保持 staged，committed cursor
不前进。下一次必须收到 exact page，已成功事件以 `REPLAYED` 收敛，失败事件才有机会修复。

## 2. Source 边界

`HttpControlPlaneCertificateRotationEventSource` 强制：

- endpoint 为 HTTPS；仅显式测试配置允许 HTTP loopback；
- transport descriptor 必须同时证明 private trust store、server SPKI pin、mTLS 与 certificate identity；
- request 携带 exact deployment scope、committed sequence 和 committed page fingerprint；
- redirect 由 transport contract 禁止；request/connect deadline 为 100ms..30s；
- `200` 只接受 v1 media type 和 exact protocol header；`204` 也必须携带 protocol header 且 body 为空；
- body 在 JSON 解析前限制为 1KiB..512KiB；duplicate/unknown/trailing JSON 全部拒绝；
- scope、`sequence=current+1`、predecessor、canonical page fingerprint、未来签发 skew、硬本地 expiry
  和最大 page lifetime 全部校验；
- 408/425/429/5xx 与网络中断归入 `SOURCE_UNAVAILABLE`，永久 HTTP/协议错误归入
  `PROTOCOL_REJECTED`；两者均不携带 response body 或 exception text。

## 3. Watcher 状态机

watcher 每轮最多处理 1..32 页，固定 delay 带 bounded startup jitter，单线程且零队列并发。每页顺序为：

1. 读取 cached runtime serving admission；fenced 时不发网络请求。
2. 读取 durable committed cursor，并向 source 请求 exact successor。
3. 在 apply 前 stage page identity；gap/fork/competing page 立即阻断。
4. 逐事件调用 `ControlPlaneCertificateRotationRuntime.apply`；只接受 `APPLIED/REPLAYED`。
5. 全部接受后 commit exact staged fingerprint。

`ALREADY_COMMITTED` 不是自动跳过本地 apply：滚动并发中另一个同 serving-slot 进程可能在 fetch 后先
提交，本进程仍需用 exact page 对本地 transport 做 reconcile。不同 page fingerprint 才是 conflict。

状态闭集区分 `RUNTIME_FENCED`、`RUNTIME_UNAVAILABLE`、`SOURCE_UNAVAILABLE`、
`PROTOCOL_REJECTED`、`CURSOR_UNAVAILABLE`、`CURSOR_CONFLICT`、`APPLY_BLOCKED`、
`WATCHER_UNAVAILABLE` 与 `CLOSED`。descriptor 使用 cached cursor，不执行远程或数据库 I/O；只含
sequence、staged boolean、饱和计数与固定 reason code，不含 scope、replica、event/page fingerprint、
TLS material、URI、credential 或 provider diagnostics。

## 4. 测试证据

```bash
mvn -f resource-gateway-examples/pom.xml \
  -Dtest=HttpControlPlaneCertificateRotationEventSourceTest,\
ControlPlaneCertificateRotationEventWatcherTest,\
ControlPlaneCertificateRotationEventWatcherProtocolSchemaTest test
```

当前内核门禁 21 项，覆盖真实 loopback HTTP request/response protocol、cursor query/header、204、media
type/header downgrade、page body tamper、gap/fork、hard expiry、future issue、lifetime、body bound、
malformed JSON、HTTP transient/permanent 分类、弱 transport 拒绝、serving fence 零 I/O、page limit、
partial apply/replay repair、同 serving-slot 并发提交后的本地 reconcile、source/cursor/runtime/apply
故障隔离、cached descriptor、close 和配置边界。

## 5. 尚未完成

- typed Spring properties、真实 mTLS source 产品组装和 stable instance cursor bean；
- Actuator health、Tool Studio capability 和 test/staging profile；
- demo preflight、运维 freshness/backlog SLO、外部 alert routing；
- CA source retention/compaction 契约、跨区域灾备与 production transport rotation；
- certificate status publication watcher 与逐请求 revocation admission。

因此本子步降低了事件分发内核风险，但不改变 `productionReady=false`。
