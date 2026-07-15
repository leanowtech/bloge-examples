# ADR-001: Resource Gateway 测试运行时隔离

- 状态：Accepted
- 日期：2026-07-15
- 影响范围：Resource Gateway execution data control plane、部署、IAM、网络和证据存储

## 背景

调用方注入 fixture、故障和 replay 数据后，可以改变 DAG 的运行行为。若这类控制能力与生产数据面共享入口、身份、网络权限、拦截器状态或证据存储，任何一次路由、配置或字段过滤失误都可能把测试响应写入生产缓存、污染限流/熔断统计、触发真实外部写，甚至让 MOCKED 结果被误认成生产证据。

仅增加 `testMode=true`、请求头或管理员权限不能根治问题：这些方案仍把危险能力装配在生产入口上，并把完整性押在每个未来调用者和过滤条件都正确。

## 决策

终态采用**独立 test-runtime 部署**。它与生产 runtime 使用同一版本化 artifact 和执行语义，但拥有独立的：

1. 服务入口与 workload identity；
2. 网络策略、凭据和 sandbox resource binding；
3. engine 实例与 interceptor/metrics/cache/circuit-breaker 状态；
4. test-run evidence store、保留策略和容量配额；
5. 发布节奏中的兼容性探针和协议版本。

在独立部署完成前，允许同进程过渡，但必须同时满足四重隔离：

| 控制面 | 强制条件 | 失败行为 |
| --- | --- | --- |
| Endpoint | 测试 API 只在 `/api/testing/**` | 生产路径携带 control 字段立即拒绝并审计 |
| Profile | controller bean 仅在 `test` / `staging` profile 装配 | `production` profile 不存在路由和 bean |
| Identity | 只接受专用 testing workload identity，purpose 由服务端铸造 | 调用方声明 `TEST` 不产生授权 |
| Network | 测试身份无生产下游凭据；外部写默认 DENY | 未匹配外部调用失败，不回退 REAL |

执行内核可以常驻所有环境以复用类型和编译逻辑，但生产 purpose 的 `EffectiveExecutionPlan` 必须为空。独立 test engine 不注册生产响应缓存、租户限流、生产熔断器和生产 side-effect commit adapter。

## 证据与告警

- 每次运行记录 authorized purpose、target/fixture/plan fingerprint 和 evidence class。
- test purpose 触达生产 endpoint、credential 或网络段时产生高优先级安全事件。
- production purpose 携带非空 control plan 时在任何节点调度前拒绝。
- 测试 evidence 与生产 evidence 分库存储；跨域投影只能复制签名摘要和引用。

## 过渡态退出条件

任一条件满足时，不得继续扩大同进程测试入口，必须完成独立部署：

1. 测试运行进入共享企业环境，或可被两个以上业务租户使用；
2. 测试身份需要访问真实凭据、生产相邻网络或写型资源；
3. 并发、保留数据或批量回归负载可能影响生产 SLO；
4. correctness gate 开始消费 CERTIFIABLE evidence；
5. 出现一次生产触碰告警、缓存污染或 MOCKED/REAL 证据混淆；
6. 需要独立扩缩容、升级、数据驻留或区域故障域。

## 后果

正向结果是生产数据面没有 caller-driven fixture 后门，测试容量和保留策略可独立治理。代价是目标态增加一套部署、身份、网络和存储运维面；过渡态必须维护明确的退出指标，不能把“四重隔离”误当成永久完成态。

## 被否方案

- 生产运行 API 增加 `testMode`：危险能力仍在生产路由中，拒绝。
- 共享 engine，仅调整 interceptor 顺序：无法隔离缓存、熔断和统计状态，拒绝。
- 只靠 RBAC：不能防配置漂移、身份误发和出站网络误配，拒绝。
- 永久同进程、独立数据表：只隔离存储，没有隔离执行与出站副作用，拒绝。
