# ADR-005：Coverage Candidate 生成边界与组合算法 SPI

> 状态：Accepted
>
> 日期：2026-08-04
>
> 决策范围：Resource Gateway Scenario authoring 的 Coverage Lens 与候选生成

## 1. 背景

成熟测试产品会帮助作者发现边界、无效输入和组合缺口，但“能生成输入”不等于“知道业务正确
结果”。若 Resource Gateway 自动生成 expected output、打开页面就修改 suite，或用一个总百分数
掩盖分母，测试生成会把未知性包装成绿色资产。另一个风险是直接手写 pairwise：算法稳定顺序、
约束求解、预算上界和维护正确性都很难由普通产品回归证明。

Resource Gateway 同时已有两类 coverage：authoring 阶段需要回答“还缺什么”，testing control
plane 的 signed semantic coverage 回答“运行证据证明了什么”。二者必须相关，但不能互相冒充。

## 2. 决策

### 2.1 六维可解释投影

`bloge.coverageProjection.v1` 固定六个有序维度：`CASE / CONTRACT / DAG / DEPENDENCY /
ASSERTION / EVIDENCE`。每个 denominator fact 都必须有稳定 `factId`、业务坐标、说明、覆盖它的
case ids 和下一动作。产品不输出 opaque overall score。

该投影是只读、可重建的 planning read model。它不是 promotion verdict，也不替代运行时 node、
edge、invocation 和 assertion evidence。

### 2.2 候选是临时产物

`bloge.coverageCandidateSet.v1` 必须绑定 exact target fingerprint、Contract fingerprint、
Scenario set id/revision 和 Coverage projection fingerprint，并记录 generator id/version、seed、
case/work-unit budget、贡献 fact 与截断状态。打开 Coverage 或删除 Scenario 不触发自动生成。

内置 v1 generator 只处理可确定解释的模板：

- JSON Schema required/null/min/max/minLength/maxLength/enum/union boundary；
- invalid input 与 error-contract intent；
- 已知 dependency 的 `RETURN / ERROR / TIMEOUT / MUST_NOT_CALL`。

候选清空继承 assertion，固定 `Needs oracle` 和 `promotionEligible=false`。生成器发现输入，不发明
业务预期。

### 2.3 唯一写入路径

只有显式 **Accept** command 可以把候选追加到 canonical `ScenarioDraftSet`。写入前再次比较全部
source coordinate；任一 Contract、Graph、Scenario 或 coverage 变化都 fail closed。Reject/Ignore
只改变当前临时候选集，不修改业务 revision。一次 Accept 后，其余旧候选必须 stale，避免连续
套用基于旧分母的建议。

### 2.4 Pairwise 只走独立 SPI

核心模块只公开 `CoverageCandidateGenerator`，不实现 pairwise。候选 adapter 只有同时满足以下
条件才可安装：

1. 使用有维护者、许可证和 SBOM 可审计的成熟算法实现；
2. 支持显式 invalid combination constraints，不能先生成再静默丢弃；
3. 同 input/version/seed 得到相同 canonical order 与黄金向量；
4. 生成前能估计上界，并遵守 case、work unit、时间和取消预算；
5. 大枚举、递归 schema、不可满足约束和中途取消 fail closed；
6. 输出同样是 source-bound candidate，不能绕过 Accept 或生成 business oracle；
7. 通过 5/50/500 corpus、跨版本兼容与许可证安全评审。

在 adapter 通过上述门禁前，UI 明确显示 `Pairwise: Not installed`，不提供失效按钮。

## 3. 被拒绝方案

- **一个 Coverage 百分数**：无法定位病因，并会混淆静态计划与运行证据。
- **打开页面自动补 case**：删除后又出现，污染 canonical state 和 review history。
- **自动推断 expected output**：把模型或样例偏差升级成错误 oracle。
- **核心代码手写 pairwise**：维护和约束正确性不可接受。
- **接受 stale candidate**：可能把旧 Contract 或旧 DAG 下的建议写入新 revision。
- **候选直接满足 promotion**：没有 assertion 与 current execution evidence，不构成正确性证明。

## 4. 后果

正面后果是 Coverage 缺口可定位、生成可复现、写入可审计，浏览器、VS Code 插件和 sidecar
可以共享严格协议。代价是每次接受后要重新生成，且作者仍需补业务 oracle；这是用一次明确动作
换取正确性边界。若未来需要批量接受，必须设计一个对同一 source candidate set 的原子 multi-
accept command，而不是在客户端连续调用单条 Accept。
