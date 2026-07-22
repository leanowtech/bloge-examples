# ADR-004: MirrorPlan 复用 FixtureBundle 控制协议

- 状态：Accepted
- 日期：2026-07-22
- 影响范围：Resource Gateway mirror planning、testing control plane、运行时拦截、证据和 Tool Studio 集成

## 背景

Resource Gateway 已有一套经过执行与证据链验证的 `FixtureBundle` / `FixtureRule`：它能描述结构化调用点、
输入匹配、attempt/occurrence、次数约束、RETURN、THROW、DELAY、TIMEOUT、REPLAY、DENY、schema check，
并由 `ExecutionControlCompiler` 在节点调度前解析成不可变 `CompiledExecutionControl`。Mirror runtime 同样需要
控制 external leaf。若再定义 `MirrorFixture`，两个模型会逐渐拥有不同的匹配优先级、故障语义、schema 行为、
replay 治理和证据字段，最终出现“测试通过但镜像失败”或反向漂移。

与此同时，`FixtureBundle` 含业务测试值，不能直接嵌入跨系统 `MirrorPlan`。ANEKE、审计存储和离线 verifier
需要的是 exact revision、fingerprint、调用点解析和来源类型，而不是 payload。

## 决策

`FixtureBundle` 继续作为 owner-specified 测试控制的唯一主模型；不新增平行 `MirrorFixture`。

1. `MirrorPlanCompiler` 先独立验证 sealed `CapabilityClosure`，再把每条 external dependency edge 映射到
   `InvocationInventory` 中唯一的 BLOGE 调用点。
2. 映射得到的调用点集合交给 `ExecutionControlCompiler.compileMirror`。该入口复用原 selector/preflight/runtime，
   并把 Closure 声明的 read-only external operator 也列为 mandatory interception site。
3. 没有 owner rule 的 external site 合成为 implicit deny；REAL、SPY、`ALLOW_REAL` 和
   `FALLBACK_TO_REAL` 在 mirror purpose 下全部拒绝。
4. `CompiledMirrorPlan` 的内部半部保留完整 `CompiledExecutionControl`、fixture value 和已治理 replay payload；
   对外 `MirrorPlan` 只携带 `FIXTURE_BUNDLE` exact ref、rule ids、resolver source、external edge identity 和
   `executionControlFingerprint`。
5. `executionControlFingerprint` 必须等于实际 `EffectiveExecutionPlan.planFingerprint`，从而同时固定 graph target、
   operator runtime inventory、fixture、replay dependency 和 deterministic service binding。
6. 后续 recorded corpus、session state、schema synthesis 由 `MirrorResolver` SPI 提供，不伪装成 FixtureRule；
   FixtureBundle 仍只表达 owner/test-author 明确给出的控制。

## 映射矩阵

| Fixture 行为 | MirrorPlan 来源 | Stage 1 行为 | 认证约束 |
|---|---|---|---|
| RETURN | `OWNER_SPECIFIED` | 复用 node/transport response lowering | STRICT 可认证，WAIVED 不可认证 |
| THROW | `OWNER_SPECIFIED` | 复用稳定 error code/type | 可认证 |
| DELAY / TIMEOUT | `OWNER_SPECIFIED` | 复用 logical clock | 必须声明 logical clock |
| REPLAY | `GOVERNED_REPLAY` | 只使用预先解析的 exact replay closure | 来源不可认证时不能产出认证证据 |
| DENY | `OWNER_SPECIFIED` | 显式拒绝并记录 rule | 可认证 |
| 无匹配 rule | `ABSTAINED` | implicit deny，绝不落到 REAL | 可认证为“拒答事实” |
| REAL / SPY | 无 | 编译拒绝 | 禁止 |
| STREAM | 无 | 现有 v1 preflight 拒绝 | 暂不支持 |
| schema WAIVED | `OWNER_SPECIFIED` | 可用于探索性计划 | certificationRequired 时拒绝 |

`FixtureRule.functionRef` 仍受现有 FunctionCallSite 支持边界约束。ScenarioPack、StateModel 和
`SCHEMA_SYNTHESIZED` 虽已在 MirrorPlan 协议预留，但 Stage 1 编译器在相应 runtime 上线前明确拒绝。

## 不变量

- Capability Closure、Graph artifact 和 FixtureBundle 的 target fingerprint 必须指向同一执行代。
- 每条 external dependency edge 恰好对应一个 runtime invocation site；一个 site 不得代表两条 edge。
- runtime 中可识别的额外 http/external-effect site 若不在 Closure 中，计划编译失败。
- public MirrorPlan 不含 fixture value、replay payload、credential 或业务输入输出。
- runtime 只消费 `CompiledMirrorPlan`，不根据 public plan 重新查找 mutable fixture 或 operator registry。
- `CompiledMirrorPlan` 必须内含 exact Graph、FixtureBundle、replay closure 和 execution control；运行准入重新计算
  fixture fingerprint，并重验 graph/control generation 后才能调度。
- FixtureBundle 只能控制 capability closure 声明的 external leaf；内部 decision、transform 和业务 operator 必须
  真实执行，不能被 mirror fixture 静默替换。
- Closure edge 对账与 `CompiledExecutionControl` 必须消费同一个已冻结 `InvocationInventory`；禁止在两者之间
  再查一次 mutable operator registry。
- 同一组 exact 输入、planId、编译时间和 expiry 必须得到相同 plan fingerprint。

## 后果

正向结果是测试、镜像和未来场景演练共享一个成熟控制语言，selector、重试、故障、schema 和证据语义不会
分叉；public plan 又保持 payload-free，适合跨系统治理。代价是 MirrorPlan compiler 必须承担严格的 Closure
到 runtime inventory 对账，并维护 Fixture 行为到 MirrorSource 的显式损失报告。

Stage 2 以后新增 corpus resolver 时，不能把统计推断硬塞进 FixtureBundle metadata。Resolver 选择、confidence、
abstention 和 provenance 属于 mirror serving 协议；owner 明确输入的固定规则仍归 FixtureBundle。

## 被否方案

- 新建 `MirrorFixture`：形成第二套 selector 和行为语义，拒绝。
- 把完整 FixtureBundle 嵌入 MirrorPlan：泄漏业务 payload 并放大跨系统数据面，拒绝。
- 运行时按 rule id 回查 fixture repository：破坏 immutable plan，允许运行期漂移，拒绝。
- 未匹配 external 回落真实调用：使模拟环境具备生产副作用逃逸路径，拒绝。
- 把 corpus 结果转换成 owner FixtureRule：混淆 owner 声明与统计推断 provenance，拒绝。

## 验证

`ExecutionControlCompilerTest` 验证 read-only mandatory external 也会被拦截、REAL fallback 和未知 site 失败关闭。
`MirrorPlanCompilerTest` 验证 direct/nested graph 对账、implicit abstention、payload-free plan、target drift、closure
漏报 external、deterministic services、classification、schema waiver 和未就绪能力拒绝。额外回归证明 registry
在 inventory freeze 后发生替换不会改变 runtime control，且空 mirror-site adapter 与普通 plan fingerprint 相同。
