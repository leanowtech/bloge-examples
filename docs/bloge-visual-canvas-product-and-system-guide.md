# BLOGE 通用可视化编排画布产品与系统说明

> Scope: `resource-gateway-examples` 新版通用编排画布 · Primary UI: `/author/` · Companion UI: `/showcase/`

> 截图说明：本文后续页面图来自本地演示服务的真实 `/author/` 与 `/showcase/` 页面。蓝色框表示重点区域，橙色编号对应正文中的操作说明。

## 1. 产品定位

BLOGE 通用可视化编排画布是一套面向复杂业务编排的 schema-first 工作台。它以 resource gateway 的资源编排能力为基版，但不再把画布绑定到固定几个内置算子，而是允许用户导入自己的算子库定义，再在服务端 schema 约束下拖拽、连线、校验、模拟和导出业务流程。

一句话：用户先给系统一份 `bloge.visualOperatorLibrary.v1` 算子库，系统把它变成可拖拽的业务积木，并保证连线、输入、输出、模拟结果都由服务端合同校验。

它解决的问题不是“把图画出来”，而是把以下闭环产品化：

```text
导入/采用算子库
  -> 拖拽算子
    -> schema 约束连线
      -> 服务端校验
        -> mock/real 混合模拟
          -> 导出草稿或发布物
```

这次改进的重点，是把旧版 Custom Composer 中“能用但不够直观”的能力，收束成更清晰的 React Flow authoring workspace：有可搜索 palette、 typed handles、自动布局、下一步行动提示、连接候选高亮、节点级 fixture、模拟 trace 和明确的 real/mocked 标记。

## 2. 面向谁

**业务编排者**：把风控、营销、订单、资源聚合、AI 工具链等业务逻辑按 DAG 编排出来，先验证逻辑，再交给工程实现。

**平台工程师**：维护算子库、资源描述、schema 合同、执行绑定和发布治理，把业务编排从手写代码拆成可审阅的合同资产。

**解决方案/售前/演示人员**：用 `/showcase/` 讲清楚 resource gateway 的典型场景，用 `/author/` 展示“用户自带算子库也能编排”。

## 3. 系统入口

| 入口 | 用途 | 推荐人群 |
| --- | --- | --- |
| `/author/` | 新版通用可视化编排画布，支持导入算子库、拖拽、连线、校验、模拟、导出 | 主要使用入口 |
| `/showcase/` | React 版 resource gateway 场景目录，按后端场景顺序展示案例、图、请求执行和 SSE 流 | 演示与验证 |
| `/examples/gateway` | 旧版 Custom Composer/Showcase，保留兼容和功能回归价值 | 兼容入口 |

打开 `/author/` 后先按下面这张图定位页面：

![Author 工作台总览标注](assets/bloge-author-overview-annotated.svg)

图中 5 个区域分别承担不同任务：

1. **算子库导入**：粘贴 JSON/YAML，先 Validate，再 Import。导入成功后算子会出现在下方 palette。
2. **内置复杂示例**：直接加载可编辑的复杂业务 graph，适合第一次理解 fan-out、decision table、transform、fixture 的组合方式。
3. **编排动作条**：执行 Simulate、Auto Layout、Validate、Export Draft，并查看节点数、边数、输出节点和 fixture 数。
4. **Graph Contract**：显示当前 graph 的 input/output schema 摘要，告诉系统集成方这张图需要什么上下文、会产出什么结果。
5. **Runtime Context**：以图形化变量表维护本次模拟的 context；高级用户也可以展开 Advanced JSON。
6. **Mock Setup / Test Table**：右侧 inspector 会先列出节点级 mock fixture，再用 Test Table 组织多行 context、fixture overrides 和 expected output，用同一张画布批量跑回归。

### 3.1 演示脚本启动方式

推荐演示时直接使用仓库根目录下的专用脚本。它默认执行 `resource-gateway-examples` 的 `-Pfrontend package`，把 React UI 打进 Spring Boot 静态资源，然后在 `8080` 启动服务。为缩短演示准备时间，脚本默认给 Maven 打包加 `-DskipTests`；需要把测试也跑进去时使用 `--run-tests`。

```bash
./scripts/start-visual-canvas-demo.sh
```

启动成功后脚本会打印：

```text
Author canvas:   http://localhost:8080/author/
Showcase:        http://localhost:8080/showcase/
Legacy composer: http://localhost:8080/examples/gateway
```

查看状态和日志位置：

```bash
./scripts/visual-canvas-demo.sh status
```

停止演示服务：

```bash
./scripts/stop-visual-canvas-demo.sh
```

常用参数：

| 参数 | 用途 |
| --- | --- |
| `--open` | 启动后自动打开 `/author/` |
| `--port 18080` | 改用指定端口 |
| `--no-build` | 跳过打包，复用已有 jar |
| `--api-only` | 不启用 `-Pfrontend`，只打包后端 API |
| `--run-tests` | 打包时不跳过 Maven 测试 |
| `-- --gateway.base-url=http://localhost:9091` | `--` 后面的参数透传给 Spring Boot 应用 |

脚本使用 `target/example-pids/visual-canvas-demo.pid` 记录进程，使用 `target/example-logs/visual-canvas-demo.log` 记录日志；停止时会校验 PID/端口上的进程确实像 Resource Gateway demo，避免误停其它服务。

### 3.2 手动启动方式

如果只运行后端 API 或旧版静态资源：

```bash
mvn -f resource-gateway-examples/pom.xml spring-boot:run
```

如果要使用新版 `/author/` 和 `/showcase/` 的打包版 React UI，先启用 frontend profile：

```bash
mvn -f resource-gateway-examples/pom.xml -Pfrontend package
java --enable-preview -jar resource-gateway-examples/target/bloge-examples-resource-gateway-1.0.0.jar
```

然后访问：

```text
http://localhost:8080/author/
http://localhost:8080/showcase/
http://localhost:8080/examples/gateway
```

说明：默认 Maven 构建不会打包 React UI，目的是让 Java 验证保持快速、离线。`-Pfrontend` 会安装本地 Node、执行 `npm ci` 和 `npm run build`，再把同一份 Vite 产物复制到 `static/author` 与 `static/showcase`。

本地前端调试也可以进入 `resource-gateway-examples/src/main/frontend` 执行：

```bash
npm ci
npm run dev
```

当前 Vite dev proxy 只代理 `/api` 到 Spring Boot。算子库导入使用 `/admin/visual-operator-libraries/*`，所以完整体验建议优先使用 Maven 打包后的 `/author/`。

## 4. 核心概念

| 概念 | 含义 |
| --- | --- |
| Operator Library | 用户或系统提供的算子库，合同版本为 `bloge.visualOperatorLibrary.v1`；字段定义见 [BLOGE 可视化算子库 Schema 定义](./bloge-visual-operator-library-schema.md) |
| Operator | 单个可编排算子，至少有 `operatorRef`，通常包含展示信息、输入/输出端口、schema、lowering |
| Built-in Function | 算子库或系统默认目录提供的 BLOGE 表达式函数，用于 transform/branch 等表达式输入框的函数名补全和签名提示 |
| Port Schema | 输入/输出端口的 JSON Schema envelope，画布用它判断可连接性 |
| GraphDraft | 画布中的业务流程草稿，合同版本为 `bloge.visualGraphDraft.v1` |
| Connection Candidate | 服务端根据当前 draft 和 schema 枚举出的可连接目标 |
| Validate | 对当前 draft 做结构、schema、readiness、action readiness 校验 |
| Node Fixture | 节点级模拟样本，可 pin mock 输出，也可断言该节点收到的 expected input |
| Test Table | 画布内的表格测试入口；每行是一组 runtime context、节点 fixture override 和 expected terminal output |
| Simulate | 混合模拟运行。安全且已实现的内置算子可 real-run；design-only 或高风险算子会 mock-run |
| Export | 导出当前 draft、publication bundle 或内置 operator library bundle |

关键原则：浏览器负责交互体验，规则由服务端兜底。客户端可以做提示和高亮，但连接是否有效、草稿是否可运行、模拟是否可信，都以服务端结果为准。

### 4.1 Graph 级 input/output schema 在哪里看

Resource Gateway 内置 graph 的正式合同定义在：

- 代码：`resource-gateway-examples/src/main/java/com/leanowtech/bloge/gateway/gateway/GatewayGraphContractCatalog.java`
- API：`GET /api/gateway/graphs/contracts`
- 示例场景 API：`GET /api/gateway/examples/scenarios`，每个 scenario 会携带自己的 `inputSchema` 和 `outputSchema`

新版 `/author/` 和旧版 `/examples/gateway` 都把 graph 合同作为一等信息看待：

- `/author/`：画布工具栏下方有 **Graph Contract** 条，显示当前 draft 的 Input/Output 摘要。3 个内置复杂示例各自携带 `inputSchema` 和 `outputSchema`；加载示例时会同步设置当前 graph contract，并用 input schema 生成一份 runtime context 样本。
- `/examples/gateway`：右侧 Inspector 顶部有 **Graph Contract** 区块，会显示当前 showcase/composer 的 Input/Output 摘要。

Graph Contract 会同时显示：

- **Input / ctx**：这张 graph 执行前要求的上下文字段。
- **Output / public result**：这张 graph 对系统集成暴露的终态输出字段。

对于 Resource Gateway showcase 示例，Graph Contract 来自 `GatewayGraphContractCatalog`，所以 `User Dashboard`、`Loan Decision Policy`、`Product Detail` 等示例各自有独立的 input/output schema。对于 `/author/` 的 3 个可编辑复杂示例，Graph Contract 定义在 `resource-gateway-examples/src/main/frontend/src/canvasExamples.ts`，并会随 draft 一起导出 `inputSchema`。对于 `Custom Composer`，Input 来自当前画布的 `Graph Input Schema`，Output 来自当前 `Graph Output` 选中的输出节点和 path；修改 schema 或切换输出节点后，Graph Contract 摘要会同步刷新。

加载 `Loan policy fallback` 后，Graph Contract 与画布状态会像下图这样联动：

![Author 加载 Loan policy fallback 后的 Graph Contract 标注](assets/bloge-author-loan-example-annotated.svg)

1. **示例元数据**：每个内置示例直接显示节点数、边数、Input 字段数、Output 字段数；点击 Load 会把完整 draft 加载进画布。
2. **运行/导出工具栏**：加载后节点、边、输出节点和 fixture 数会同步刷新，确认当前不是空草稿。
3. **输入输出 schema**：这里就是 graph 级 input/output schema 的可视化入口；例子中输入需要 `applicantId`，输出暴露 `decision`、`tier`、`primaryScore` 等公共结果字段。
4. **节点 mock/real 状态**：右侧 Mock Setup 告诉你哪些节点有 fixture、哪些节点可真实执行、哪些还只是 server sample。
5. **可编辑 DAG**：图不是静态展示，节点、边、output node、fixture 和配置都仍然可以继续编辑。

## 5. `/author/` 怎么用

### 5.1 第一步：准备算子库

最小可用算子库可以是 schema-only 的 design operator。它还没有运行时实现，也能进入画布参与设计、连接校验和模拟。算子库也可以声明 `builtInFunctions`，用于补充 transform/branch 表达式里的业务函数；导入后这些函数会和系统默认函数一起出现在表达式编辑器中。
完整字段合同、lowering 约束和机器校验 schema 见 [BLOGE 可视化算子库 Schema 定义](./bloge-visual-operator-library-schema.md) 与 [bloge-visual-operator-library.schema.json](./schemas/bloge-visual-operator-library.schema.json)。

```yaml
schemaVersion: bloge.visualOperatorLibrary.v1
libraryId: risk-policy
displayName: Risk Policy
version: 1.0.0
operators:
  - operatorRef: risk:eligibility
    display:
      name: Eligibility
      description: Decides whether an applicant is eligible.
      tags: [risk, policy]
    lowering:
      mode: design
    ports:
      inputs:
        - name: inputs
          required: true
          description: Applicant facts.
          schema:
            format: json-schema
            version: "2020-12"
            schema:
              type: object
              properties:
                score:
                  type: integer
                amount:
                  type: number
              required: [score, amount]
      outputs:
        - name: output
          description: Eligibility decision.
          schema:
            format: json-schema
            version: "2020-12"
            schema:
              type: object
              properties:
                eligible:
                  type: boolean
                reason:
                  type: string
              required: [eligible]
```

`lowering.mode: design` 表示它是设计期算子：可以拖拽、连接、保存、导出、模拟，但不能作为真实 request-response 运行时直接执行。未来接入 Java/native/remote worker/AI tool 等执行绑定后，readiness 会变化。

### 5.2 第二步：导入或采用算子

在 `/author/` 左侧的 operator library intake 中粘贴 JSON/YAML：

1. 点击 Validate Library，先让服务端解析和校验合同。
2. 校验通过后点击 Import Library。
3. 画布会刷新 `/api/visual/operators`，新算子进入 palette。
4. palette 可以按 library 分组，也可以通过 source、tag、runtime/design facet 过滤。
5. 使用 Cmd/Ctrl-K 可以快速聚焦搜索框并按关键字过滤。

校验不只是 JSON/YAML 语法检查。服务端还会做 namespace、operatorRef、端口、JSON Schema、lowering、远程 `$ref`、高风险 runtime capability 等检查。warning 需要显式确认时，服务端会在 validation/import response 中返回 readiness 和 diagnostics。

### 5.2.1 直接从内置复杂示例开始

新版 `/author/` 在画布上方内置了复杂编排示例入口。它不是只展示图片或说明文字，而是把一张可编辑的 `GraphDraft` 直接加载到当前画布，包括节点、连线、字段绑定、规则表/转换配置、输出节点和 mock fixtures。

当前内置示例：

| 示例 | 覆盖模式 | 典型学习点 |
| --- | --- | --- |
| Loan policy fallback | 风控 fan-out、双 provider、decision table、response transform | 多资源并行取数、字段级条件绑定、规则表输出进入最终响应 |
| Order fulfillment lane | 订单列表、foreach enrich、shipping quote、SLA decision | 列表 enrichment、资源参数从上游字段派生、履约 lane 规则 |
| Personalized dashboard | 用户画像 fan-out 到钱包、推荐、通知，再聚合成 dashboard | 多资源聚合、最终响应映射、mock resource + real transform 的混合模拟 |

这 3 个示例现在不再只是“看结构”。每个示例都内置了两类资产：

- **Built-in function transform**：最终 `bloge:transform` 使用 `coalesce(...)`、`toNumber(...)`、`round(...)` 这类 BLOGE 表达式函数，展示如何把空值兜底、类型转换和数值规整写进可视化映射。
- **Test Table cases**：每个示例提供 2 行表格测试。第一行通常是 happy path；第二行通过 fixture override 改变下游 mock 数据，覆盖 decline、standard lane、fallback default 等分支。

如果示例依赖的 operatorRef 不在当前 catalog 中，Load 按钮会禁用并提示缺失数量。此时先导入对应算子库，或确认 resource descriptor / built-in operator catalog 是否已经启动完成。示例加载后会替换当前画布；需要保留当前草稿时，先使用 Export Draft 导出。

### 5.3 第三步：把算子放到画布上

在 palette 中可以点击算子添加，也可以拖拽到 canvas。每个节点卡片会显示：

- 业务展示名和 `operatorRef`。
- 输入/输出端口数量。
- design-only/runtime-blocked/ready 等状态。
- typed handles：输出端口在一侧，输入端口在另一侧。

当画布变乱时，点击 Auto Layout。新版画布会用确定性布局把 DAG 拉开，让节点和边更容易读。

### 5.3.1 配置起始节点输入

起始节点通常没有上游边，但它仍然需要业务入参，例如 `userId`、`orderId`、`applicant.score` 或请求上下文里的租户信息。新版 `/author/` 在右侧 inspector 中提供图形化的 `Runtime Context -> Context Variables`：

![Author Context Variables 绑定标注](assets/bloge-author-context-binding-annotated.svg)

对着图操作：

1. **Graph 输入字段**：先看 Graph Contract 的 Input 区，确认这张图需要哪些 ctx 字段。示例中 graph input 需要 `applicantId`。
2. **Context 变量表**：在 `Runtime Context -> Context Variables` 点击 Add Variable，新增一行变量，Path 填 `applicantId` 或 `applicant.score` 这类上下文路径。
3. **Bind 到节点输入**：选中需要配置输入的节点，再点击变量行上的 Bind；也可以把 `ctx.applicantId` chip 直接拖到节点的 `Node Inputs` 区域。
4. **Preview JSON**：Sample 值会即时汇总成最终模拟 context，图中生成的是 `{ "applicantId": "prime" }`。
5. **起始节点/字段来源**：画布中的起始节点可以从 ctx 字段获得输入，不必为了“没有上游边”再造一个假节点。

画布会自动创建 `contextPath` 输入绑定，并把 Target port 默认设为算子的第一个输入端口、Target path 默认设为上下文路径最后一段。如果需要常量或复杂目标字段，仍可在 `Node Inputs` 中手动调整 Source、Target port 和 Target path。

例如，一个风控起始节点要从运行上下文读取 `applicant.score`，导出的 draft 会包含：

```json
{
  "inputs": {
    "score": {
      "kind": "contextPath",
      "path": "applicant.score",
      "targetPort": "inputs",
      "targetPath": "score"
    }
  }
}
```

模拟时，`Context Variables` 会生成本次 run 的 JSON context，例如：

```json
{
  "applicant": {
    "score": 720
  }
}
```

`Runtime Context` 会进入 `POST /api/visual/graphs/simulate` 的 `context` 字段；它不会写进导出的 `GraphDraft`。导出的 draft 只保存 `contextPath` / `constant` 等输入绑定语义，方便后续在真实网关运行时由外部请求上下文提供变量。

`Advanced JSON` 仍然保留给专家模式。没有配置 Context Variables 时，模拟会使用 `Advanced JSON` 中的对象；一旦配置了变量，模拟优先使用变量表生成的 context。

### 5.4 第四步：连线

从一个节点的输出 handle 拖到另一个节点的输入 handle。拖拽过程中，画布会调用：

```text
POST /api/visual/connections/candidates
```

服务端返回哪些目标 ready、哪些 blocked、哪些 already wired。真正落线时再调用：

```text
POST /api/visual/connections/check
```

只有服务端 accepted 的连接会写入 draft。这样可以避免浏览器本地规则和后端 validator 分叉。

常见 blocked 原因：

- 输出 schema 不能赋给目标输入 schema。
- 目标 required input 已经被别的边占用。
- path/port 名称不是 DSL-safe。
- draft 里存在阻断级诊断，导致连线后图仍不可用。

### 5.4.1 双击配置可编辑算子

新版 `/author/` 不再只把复杂算子当成普通卡片。对于内置可配置算子，双击节点会打开对应的浮层编辑器：

| 算子族 | 双击行为 | 写入 draft 的配置 |
| --- | --- | --- |
| `bloge:decisionTable` | 打开规则矩阵。可编辑 hit policy、output type、条件列、输出列、规则行和 otherwise fallback | `config.hitPolicy`、`config.outputType`、`config.conditionColumns`、`config.outputColumns`、`config.rules[]` |
| `bloge:transform` | 打开字段映射表。可编辑输出字段名和 BLOGE 表达式，可新增/删除 assignment，并在 Expression 下方使用函数 chip、函数名补全和签名提示 | `config.assignments` |

Decision table 双击后的页面重点如下：

![Author Decision Table 浮层编辑器标注](assets/bloge-author-decision-table-editor-annotated.svg)

1. **双击浮层编辑器**：双击 `bloge:decisionTable` 节点后打开，不需要在右侧 inspector 里找隐藏 JSON。
2. **来自传入边的条件列**：`score`、`income`、`employmentYears` 这类列来自上游边绑定，会以锁定列展示，避免规则表和边上的数据合同脱节。
3. **输出列**：规则命中后产出的结构化字段，例如 `decision`、`tier`、`reason`。
4. **规则行/otherwise**：每一行是一条匹配规则；otherwise 行作为 fallback，条件单元格禁用，只保留输出编辑。
5. **Done 保存到节点**：点击 Done 后，表格配置写回当前节点的 `config`，画布节点上的 input/output 数量也会同步刷新。

Decision table 的规则矩阵支持“加行”和“加列”：

1. 先把上游节点输出连到 decision table 的输入字段，例如连到 `inputs.score`。
2. 双击 decision table 后，规则矩阵会把传入边暴露为锁定条件列，例如 `score`。锁定列可以填写规则表达式，但不能改名或删除，因为列名就是后端 DSL 使用的 input key。
3. 点击 Add Condition Column 增加手工条件列，例如 `segment`、`amount`。
4. 点击 Add Output Column 增加输出列，例如 `tier`、`reason`。
5. 在规则行中填写每个条件表达式，例如 `score >= 700`。
6. 在输出列中填写匹配后的结构化结果，例如 `decision=approve`、`tier=platinum`。
7. 勾选 Otherwise 的行会作为 fallback，条件列会禁用，只保留输出编辑。

导出的 draft 会保持 schema-friendly 结构，而不是把整张表压成一段不可解析字符串：

```json
{
  "inputs": {
    "score": {
      "kind": "nodePath",
      "nodeId": "riskScore",
      "sourcePort": "decision",
      "path": "score",
      "targetPort": "inputs",
      "targetPath": "score"
    }
  },
  "config": {
    "hitPolicy": "unique",
    "outputType": "{ decision: String, ruleId: String, tier: String }",
    "conditionColumns": ["score"],
    "outputColumns": ["decision", "ruleId", "tier"],
    "rules": [
      {
        "conditions": {
          "score": "score >= 700"
        },
        "output": {
          "decision": "approve",
          "ruleId": "prime",
          "tier": "platinum"
        }
      },
      {
        "otherwise": true,
        "output": {
          "decision": "fallback",
          "ruleId": "otherwise",
          "tier": ""
        }
      }
    ]
  }
}
```

Transform 映射表则会导出为：

```json
{
  "config": {
    "assignments": {
      "tier": "inputs.score >= 700 ? \"prime\" : \"standard\"",
      "reason": "coalesce(inputs.reason, \"score policy\")"
    }
  }
}
```

Transform 浮层中的 Expression 输入框来自 `GET /api/visual/operators` 下发的 `builtInFunctions`：

1. 双击 `bloge:transform` 节点，打开 `Transform mapping` 浮层。
2. 在某一行 assignment 的 Expression 输入框下方，点击 `coalesce`、`jsonPath`、`round` 等函数 chip，系统会把调用片段插入当前表达式。
3. 当表达式为空时，签名提示会显示常用函数；当输入里出现 `coalesce(` 这类函数调用时，提示区会聚焦对应 signature。
4. 点击 Done 后，函数调用文本会作为普通 BLOGE 表达式写入 `config.assignments`，后续 validate、simulate、export 都读取同一份配置。

当前系统默认函数包括：

| 函数 | 典型用途 |
| --- | --- |
| `coalesce(value, fallback)` | 空值兜底，例如主评分缺失时使用备用评分 |
| `defaultIfBlank(text, fallback)` | 文本为空或 blank 时兜底 |
| `toNumber(value)` / `toString(value)` | 标量类型转换 |
| `jsonPath(object, path, fallback?)` | 从 object 中按路径读取字段 |
| `contains(collection, candidate)` | 判断字符串或集合是否包含某值 |
| `round(value, scale?)` | 数值四舍五入 |
| `formatDate(value, pattern)` | 日期/时间格式化 |

`foreach` 和 resource-backed operator 目前不提供双击本地编辑器：前者主要由 Java/operator contract 定义集合、item 与结果列表语义，后者由 resource descriptor / OpenAPI contract 管理参数和响应合同。它们的下一步操作仍在 selected-node inspector、connection guide、fixture 和服务端校验中完成。

### 5.5 第五步：选择输出节点

选中节点后，在 inspector 中使用 Set Output。GraphDraft 的 `output.nodeId` 决定 validate、simulate、export 时哪个节点代表整张图的业务结果。

如果不选输出节点，系统无法判断哪些节点是有效业务链路、哪些只是旁路草稿，因此 Validate 会给出缺失 output 的诊断。

### 5.6 第六步：配置 fixture

对 design-only 或尚未实现的算子，模拟时需要 mock output。新版画布把 fixture 放在 selected-node inspector 的 Simulation 区域：

- Output fixture：指定该节点模拟时产出的值。
- Expected input：断言该节点模拟时应该收到的输入。
- Use Sample：根据输出 JSON Schema 生成一个确定性样本。

样本生成顺序是：

```text
用户 fixture
  -> schema examples/default/const/enum[0]
    -> 确定性 canonical sample
```

fixture 会写入 `GraphDraft.nodeFixtures`，属于 authoring/test evidence，不改变 DSL、fingerprint 或生产执行语义。

### 5.6.1 用 Test Table 做批量 mock 回归

单次 Simulate 适合调试一条路径，但复杂业务编排不能只靠一条样例证明正确。新版 `/author/` 在 `Mock Setup` 下方新增 **Test Table**，把大规模系统化验证前移到画布内。

每一行 Test Table 都包含：

| 字段 | 作用 |
| --- | --- |
| Case name | 业务路径名称，例如 `Prime approval path` 或 `Fallback defaults` |
| Context | 本行传给 `POST /api/visual/graphs/simulate` 的 runtime context |
| Fixture Overrides | 本行覆盖节点级 Mock Setup 的 fixture，格式是 `{ "nodeId": { "output": ..., "expectedInput": ... } }` |
| Expected Output | 本行断言的 graph terminal output；留空时只要求 simulate 成功 |

资源节点的 fixture 要模拟完整资源输出，而不是只写 payload 本体。例如资源边和 transform 表达式通常读取 `n1.output.payload.score`，因此覆盖行应写成 `{ "n1": { "output": { "payload": { "score": 650 } } } }`。普通 primitive、transform 或 decision 节点则按节点真实输出结构填写。

使用方式：

1. 加载任意内置复杂示例，例如 `Loan policy fallback`。
2. 在右侧 `Test Table` 查看系统预置的 2 行 case。
3. 需要新增路径时点击 `Add Case`，填写新的 context、fixture overrides 和 expected output。
4. 点击 `Run Table`。画布会逐行调用 transient simulate endpoint，并把每行状态标成 `pending/running/passed/failed`。
5. 如果某行失败，结果区会显示实际 output 和 expected output，便于判断是 mock 数据、decision table 规则、transform 函数还是预期断言错了。
6. 批量运行后，最后一行的 run trace 会同步到画布节点 badge 和 Result 面板，因此仍可沿 DAG 排查 real/mocked 节点。

Fixture 合并顺序是：

```text
Mock Setup 基础 nodeFixtures
  -> Test Table 当前行 fixtureOverrides
    -> 本行 simulate request
```

这使作者可以把“共用的下游 mock 数据”放在节点 Simulation 区，把“某条业务路径特殊的 mock 变化”放在表格行里。工业化测试的关键就在这里：大部分复杂场景不需要真实下游 API，也能稳定跑大量路径验证，避免测试环境被外部系统状态、限流、网络和脏数据拖垮。

Test Table 是画布内的 authoring-side transient runner。需要把测试资产治理起来时，使用后端已经落地的 schema-gated suite/golden 能力：

| 层级 | 入口 | 用途 |
| --- | --- | --- |
| 画布内调试 | `/author/` Test Table | 作者快速构造路径、调试 mock、验证 transform/decision 逻辑 |
| Resource graph suite | `/api/gateway/graphs/contracts/tests/*` | 对正式 resource graph 按 input/output schema、resource mock 和 coverage policy 批量验证 |
| Operator suite | `/api/visual/operators/tests/*` | 对单个 operator 的 input/config/output schema 和 mock output 断言做表格验证 |
| Published golden | `/api/visual/golden-cases/*` | 对不可变 publication 做发布级回归和认证 |

更完整的后端表格测试模型见 [Resource Graph Schema Mock Table Testing](./bloge-resource-graph-schema-mock-table-testing.md)。

### 5.7 第七步：Validate

点击 Validate 后，前端调用：

```text
POST /api/visual/drafts/validate
```

结果中最重要的是三类信息：

- `valid`：合同和图结构是否通过。
- `readiness`：当前图整体是 executable、design-only、runtime-blocked 还是 catalog-repair required。
- `actionReadiness`：compile/run/publish design/publish executable 当前能不能做。

理解方式：

| 状态 | 说明 |
| --- | --- |
| Ready/valid | 图结构和 schema 约束通过，可以继续模拟或发布路径 |
| Design-only | schema 正确，但包含未绑定 runtime 的 design operator，只能作为设计资产或通过 simulate 验证 |
| Runtime-blocked | 存在 remote worker、AI tool、event source、message handler、webhook、streaming/durable 等当前 request-response runtime 不支持的边界 |
| Catalog repair required | 算子库或 operator projection 本身存在阻断问题，需要先修 catalog |

### 5.8 第八步：Simulate

点击 Simulate 后，前端调用：

```text
POST /api/visual/graphs/simulate
```

模拟不是生产运行。它的目标是验证编排逻辑、schema 形状、mock 输出传播、节点 trace 和终端输出是否符合预期。

系统采用 hybrid strategy：

- 安全、确定性、已实现的内置 DSL primitive 可以真实执行。
- 用户导入的 design-only operator、未绑定 runtime 的 operator、高风险副作用 operator 会用 `SimulationOperator` mock。
- 每个节点 trace 都会标记 `REAL` 或 `MOCKED`。
- 输出节点会额外标记 `OUTPUT`。

这能避免两个极端：一边是“所有东西都 mock 导致 transform/branch 逻辑没验证”，另一边是“设计期模拟误触真实外部副作用”。

模拟完成后，页面重点看这几个位置：

![Author 模拟结果标注](assets/bloge-author-simulation-result-annotated.svg)

1. **Simulate 成功**：顶部状态卡和工具栏会从 `not run` 变成 `success`。
2. **Run/Trust 检查**：Checklist 会显示 Run 是否成功，以及当前结果里有多少 real-run / mocked 节点。
3. **Mocked/Real 节点状态**：Mock Setup 区会按节点列出 `MOCKED` 或 `REAL`，方便判断哪些结果来自 fixture，哪些来自真实 transform/decision 执行。
4. **Graph ready 卡片**：画布左下角给出下一步行动提示；成功时会提示 graph ready，但仍标明 mocked 节点是否存在。
5. **节点徽标同步**：画布节点上的 badge 会同步显示 real/mock 状态，便于沿着 DAG 追踪模拟路径。

### 5.9 第九步：Export

当前 `/author/` 支持本地导出 draft JSON，包含：

```json
{
  "schemaVersion": "bloge.visualGraphDraft.v1",
  "graphName": "customGraph",
  "nodes": [],
  "edges": [],
  "nodeFixtures": {},
  "output": {
    "nodeId": "selectedNode"
  }
}
```

更完整的服务端资产流还包括：

| 资产 | API |
| --- | --- |
| 内置算子库导出 | `GET /api/visual/builtin-library/export` |
| 指定用户算子库导出 | `GET /admin/visual-operator-libraries/{libraryId}/export` |
| 用户算子库 bundle 导入 | `POST /admin/visual-operator-libraries/import-bundle` |
| Draft export/import | `GET /api/visual/drafts/{draftId}/export`, `POST /api/visual/drafts/import` |
| Publication export/import | `GET /api/visual/publications/{publicationId}/export`, `POST /api/visual/publications/import-bundle` |
| Golden case | `/api/visual/golden-cases/*` |

## 6. `/showcase/` 怎么用

`/showcase/` 是面向 resource gateway 示例的 React 场景目录，不是通用 authoring 工作台。它用于证明后端 resource gateway 场景、图、请求和 SSE 行为仍然可用。

页面重点如下：

![Showcase Loan Decision Policy 标注](assets/bloge-showcase-loan-policy-annotated.svg)

1. **场景目录**：左侧按后端返回顺序列出 resource gateway 示例，适合演示时快速切换 `User Dashboard`、`Loan Decision Policy`、`Product Detail` 等场景。
2. **场景说明/标签**：顶部展示业务模式、标签和解释文案，用于讲清这个 graph 证明了什么能力。
3. **后端 graph 图**：中间 Diagram 是后端示例 graph 的可视化，不是可编辑 canvas；它用于解释运行路径和节点关系。
4. **节点 Inspector**：点击图中的节点后，右侧显示 node kind、operator、payload、resourceId 等后端合同信息。
5. **运行输入**：下方 Sample Input/Run 区用于选择 preset、编辑请求参数、执行真实 gateway endpoint，并查看 expectation matched/missing。

使用方式：

1. 打开 `/showcase/`。
2. 从左侧场景列表选择一个示例。
3. 查看场景说明、运行参数、示意图和节点摘要。
4. 编辑 sample input。
5. 对普通请求点击 Run，系统会调用对应 public gateway endpoint。
6. 对 streaming 场景使用 SSE lane，必要时点击 Stop。
7. 查看 preset expectation matched/missing 反馈，判断演示输出是否符合预期。

它消费的核心 API：

```text
GET /api/gateway/examples/scenarios
GET /api/gateway/examples/scenarios/{graphName}
GET /api/gateway/examples/scenarios/{graphName}/diagram
```

## 7. 系统架构说明

![BLOGE 通用可视化编排系统架构](assets/bloge-visual-canvas-architecture.svg)

图源文件：[`assets/drawio/bloge-visual-canvas-architecture.drawio`](assets/drawio/bloge-visual-canvas-architecture.drawio)

### 7.1 前端职责

`resource-gateway-examples/src/main/frontend` 提供同一套 Vite/React bundle，并在 Spring Boot 打包时复制到 `/author/` 和 `/showcase/` 两个静态入口。

前端负责：

- React Flow 渲染和拖拽体验。
- Palette 搜索、分组、filter 和 Cmd/Ctrl-K。
- Node inspector、fixture 编辑、output 选择。
- Test Table 行编辑、fixture override 合并和逐行 transient simulate 调度。
- 调用服务端候选连接、连线确认、validate、simulate。
- 展示 readiness、diagnostics、trace、real/mocked badge。
- 导出本地 draft JSON。

前端不负责：

- 私自判定连接一定有效。
- 私自决定 draft 是否可运行。
- 私自相信用户导入的 runtime readiness。
- 在浏览器里直接执行 graph 或替代服务端模拟语义。
- 真实执行 design-only 或高风险 operator。

### 7.2 后端职责

后端 `visual/*` 包是核心：

| 后端模块 | 职责 |
| --- | --- |
| `visual/catalog` | operator catalog、算子库导入/导出、builtin library projection、profile、impact、revision |
| `visual/connection` | 服务端连接候选和连接预检 |
| `visual/validation` | GraphDraft 合同、schema、runtime/design readiness、action readiness |
| `visual/simulation` | mock/real 混合模拟、fixture、trace、sample generator |
| `visual/publication` | publication 冻结、导入导出、依赖报告 |
| `visual/golden` | golden case 保存、运行、认证 |
| `visual/runtime` | run history 和 trace replay |
| `visual/resource` | OpenAPI/resource contract 投影到 visual resource/operator surface |

resource gateway 自身继续保留：

- `HttpResourceOperator`：通用 HTTP resource 集成点。
- `ResourceDescriptor`：资源声明和参数映射。
- gateway example controllers：对外演示接口。
- DSL graphs：业务示例编排。

### 7.3 数据不变量

系统里有几个必须坚持的不变量：

1. `GraphDraft.visualLayout` 和 `nodeFixtures` 都是 authoring/test evidence，不定义生产业务语义。
2. `GraphDraft.output` 是图级结果选择，不能让前端隐式猜。
3. 连接写入前必须经过服务端 preflight。
4. `lowering.mode=design` 的 operator 可以设计和模拟，但不能冒充 executable operator。
5. 模拟 trace 必须明确标记 real/mocked，不能让 mock 输出看起来像真实生产结果。
6. 用户导入的 operator runtime readiness 不能被直接信任，服务端会重新派生。
7. 远程 `$ref`、不安全 schema、秘密字段、外部副作用和高风险 runtime capability 必须被 warning-gate 或 blocking-gate。

## 8. 典型业务流程

### 8.1 先设计、后实现

适合业务还没完全落地，但 schema 已经比较清楚的场景。

1. 平台或业务团队定义 `bloge.visualOperatorLibrary.v1`。
2. operator 使用 `lowering.mode=design`。
3. 在 `/author/` 导入 library。
4. 拖拽形成业务 DAG。
5. 设置 output node。
6. 为关键节点补 fixture。
7. Validate + Simulate。
8. 导出 draft，作为后续 runtime binding/工程实现输入。

这种模式的价值是：业务流程和数据合同可以先稳定下来，不必等所有 Java/operator/runtime 实现完。

### 8.2 资源网关场景演示

适合讲 resource gateway 能力。

1. 打开 `/showcase/`。
2. 选择 dashboard、product、order、credit、streaming 等场景。
3. 查看图和节点。
4. 调整 sample input。
5. 运行请求或 SSE stream。
6. 用 expectation 反馈说明结果。

### 8.3 把内置 registry 变成可移植 library

适合做环境迁移或示例复制。

1. 调用 `GET /api/visual/builtin-library/export`。
2. 拿到 portable bundle。
3. 在另一个环境通过 import bundle 导入。
4. 刷新 catalog，在画布中使用这些 operator。

### 8.4 从 OpenAPI/AsyncAPI 投影设计面

已有后端支持从协议文档生成 visual contract：

- OpenAPI resource contract：`POST /admin/resource-design-contracts/from-openapi`
- AsyncAPI operator library：`POST /admin/visual-operator-libraries/from-asyncapi`
- AsyncAPI operation discovery：`POST /admin/visual-operator-libraries/from-asyncapi/operations`

这些入口不会绕过 validator。未解析本地 `$ref`、远程 `$ref`、selector 未命中、blocked operation 都会被服务端诊断拦截。

## 9. 主要 API 速查

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/api/visual/operators` | 加载 operator catalog |
| `GET` | `/api/visual/operators/{operatorRef}` | 查看单个 operator detail |
| `POST` | `/api/visual/operators/fit-candidates` | 根据当前输出找可添加的候选 operator |
| `POST` | `/admin/visual-operator-libraries/validate-text` | 校验粘贴的算子库 JSON/YAML |
| `POST` | `/admin/visual-operator-libraries/import-text` | 导入粘贴的算子库 JSON/YAML |
| `GET` | `/admin/visual-operator-libraries/{libraryId}/export` | 导出指定用户算子库 |
| `POST` | `/admin/visual-operator-libraries/import-bundle` | 导入算子库 bundle |
| `GET` | `/api/visual/builtin-library/export` | 导出内置 operator registry 为 portable library |
| `POST` | `/api/visual/connections/candidates` | 枚举连接候选 |
| `POST` | `/api/visual/connections/check` | 预检单条连接 |
| `POST` | `/api/visual/drafts/validate` | 校验 transient draft |
| `POST` | `/api/visual/graphs/simulate` | 模拟 transient draft |
| `GET` | `/api/gateway/examples/scenarios` | showcase 场景列表 |
| `GET` | `/api/gateway/examples/scenarios/{graphName}/diagram` | showcase 场景图 |

## 10. 常见问题

**打开 `/author/` 是 404。**

大概率没有执行 `-Pfrontend package`，React 产物没有复制到 Spring Boot static resources。使用打包命令后再用 jar 启动。

**palette 为空。**

先确认 `/api/visual/operators` 是否有返回。若只有用户自定义算子，先在 library intake 导入算子库。若使用 deprecated library，默认 palette 会隐藏，需通过相应 catalog 参数或存量 draft 解析路径查看。

**算子库 Validate 失败。**

看第一条 blocking diagnostic。常见原因是 `schemaVersion` 不对、`libraryId` 缺失、`operatorRef` 冲突、port schema 格式不符合、远程 `$ref` 被拒绝、local `$ref` 无法解析、lowering mode 和字段不安全。

**拖线时目标是 blocked。**

看连接候选或 check response 的 message。多数是 schema 不兼容、目标 input 已被占用、path 不安全或目标 operator 当前不可用于该 scope。

**Validate 通过但 Run 不允许。**

这通常是 design-only 或 runtime-blocked。它说明 schema 编排成立，但当前 request-response runtime 没有真实执行绑定。此时使用 Simulate 验证逻辑，或补 runtime binding 后再发布 executable artifact。

**Simulate 结果都是 MOCKED。**

如果图里都是用户导入的 design-only operator，这是预期行为。mock 结果来自 fixture 或 schema sample。要看到 REAL，需要图中包含 allowlist 内、安全、确定性且已实现的内置 operator。

**Simulate 报 fixture JSON 无效。**

修正 selected-node Simulation 区域里的 Output fixture 或 Expected input。无效 fixture 不会发送到服务端，避免生成误导性的模拟证据。

**用 `npm run dev` 时导入算子库失败。**

当前 Vite dev proxy 只覆盖 `/api`，而导入算子库走 `/admin`。完整体验使用 Maven 打包后的 `/author/`，或在本地调试时补充 `/admin` proxy。

## 11. 验证与回归命令

前端核心回归：

```bash
cd resource-gateway-examples/src/main/frontend
npm test
```

resource gateway 后端完整验证：

```bash
mvn -f resource-gateway-examples/pom.xml clean verify
```

带 React 打包和浏览器 smoke 的关键验证：

```bash
mvn -f resource-gateway-examples/pom.xml -Pfrontend \
  -Dtest=VisualAuthoringBrowserDomTest#reactAuthorCanvasLoadsPackagedBundleInRealBrowser,VisualAuthoringBrowserDomTest#reactShowcaseLoadsPackagedScenarioParityInRealBrowser \
  verify
```

## 12. 当前边界与后续方向

当前系统已经覆盖通用画布核心闭环，但它仍是 `resource-gateway-examples` 内的 example-grade 实现，不等于完整控制面产品。

当前不覆盖：

- 多人实时协作。
- 生产级 IAM/RBAC。
- 持久化远程 worker runtime。
- 完整 AI tool/event/message/webhook 执行平面。
- 把 visual core 物理拆成独立 Maven artifact。

后续可以继续推进：

- 把 `visual/*` 抽出更干净的可复用 core + adapter SPI。
- 给 `/author/` 增加 stored draft 打开/保存/发布完整工作流。
- 把 runtime binding handoff 做成更直接的控制面。
- 增强复杂 schema 的表单化 fixture 编辑。
- 对大型 operator library 做更强的分页、分面和团队治理体验。

## 13. 一句话使用心法

先让 schema 成为真实边界，再让画布成为业务推理空间。不要急着追求所有算子都真实可执行；先用 design operator、fixture 和 simulate 把业务逻辑走通，再把稳定下来的图逐步绑定到 runtime。
