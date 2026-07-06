# BLOGE 可视化表达式内置函数能力验证记录

日期：2026-07-06
范围：`resource-gateway-examples` / `/author/` 通用编排画布

## 目标

本次补强要解决两个缺口：

1. `bloge.visualOperatorLibrary.v1` 只能描述算子，不能描述 BLOGE engine/表达式内置函数。
2. `bloge:transform` 这类 DAG 表达式编辑只提供原始输入框，缺少函数名补全和签名提示，复杂数据转换不够可发现。

## 落地内容

| 要求 | 证据 |
| --- | --- |
| 算子库 schema 支持函数定义 | `docs/schemas/bloge-visual-operator-library.schema.json` 增加 `builtInFunctions`、`functionSignature`、`functionParameter`、`functionReturn` 等定义 |
| Java wire contract 支持函数定义 | `OperatorLibrary` 增加 `BuiltInFunction`、`Signature`、`Parameter`、`ReturnValue` record，并保持旧 constructor 兼容 |
| 系统默认函数目录 | `BuiltInFunctionCatalog.defaults()` 暴露 `coalesce`、`defaultIfBlank`、`toNumber`、`toString`、`jsonPath`、`contains`、`round`、`formatDate` |
| Catalog API 下发函数目录 | `VisualOperatorCatalog.builtInFunctions(...)`、`DefaultVisualOperatorCatalog.builtInFunctions(...)`、`OperatorCatalogResponse.builtInFunctions`、`VisualOperatorCatalogController` 已接入 |
| 用户 library 可追加函数 | `DefaultVisualOperatorCatalogTest` 覆盖 active/deprecated/owner filter 下的函数合并逻辑 |
| 导入前语义校验 | `OperatorLibraryValidatorTest` 覆盖合法函数、非法函数名、缺失 signature label、unsupported type、variadic 位置错误 |
| 前端保留函数目录 | `fetchOperatorCatalog()` 读取 `builtInFunctions`，`api.test.ts` 覆盖 envelope 解析 |
| Transform 表达式补全/提示 | `AuthorCanvas.tsx` 在 transform 浮层 Expression 下方提供 datalist、函数 chip、signature hint；`AuthorCanvas.test.tsx` 覆盖插入 `coalesce(value, fallback)` 并导出到 `config.assignments` |
| 用户文档可查 | `docs/bloge-visual-operator-library-schema.md` 和 `docs/bloge-visual-canvas-product-and-system-guide.md` 已补充函数 schema 与页面操作说明 |

## 验证命令

```bash
python3 -m json.tool docs/schemas/bloge-visual-operator-library.schema.json >/dev/null
npm test -- src/api.test.ts src/AuthorCanvas.test.tsx
npm run build
mvn -f resource-gateway-examples/pom.xml -Dtest=OperatorLibraryValidatorTest,BuiltinOperatorLibraryExporterTest,DefaultVisualOperatorCatalogTest,VisualOperatorCatalogControllerTest test
mvn -f resource-gateway-examples/pom.xml clean verify
```

## 结果

| 命令 | 结果 |
| --- | --- |
| JSON schema 语法检查 | 通过 |
| 前端 API/画布单测 | 30 tests passed |
| 前端 production build | 通过，`tsc --noEmit && vite build` 成功 |
| 后端定向测试 | 171 tests passed |
| Resource Gateway 全量验证 | BUILD SUCCESS，1407 tests run，0 failures，0 errors，2 skipped |

## 差距评估

当前需求覆盖度约 97% 以上，剩余差距小于 3%：

- 已解决：函数 schema 合同、默认函数目录、用户 library 函数合并、服务端校验、catalog API 下发、transform 表达式函数插入/补全/签名提示、测试与文档。
- 剩余演进：表达式运行时尚未做 AST 级函数签名类型检查；当前能力定位为 authoring hint 和 schema contract，执行期仍由既有 BLOGE 表达式语义承担。
- 剩余演进：branch/config 等更多表达式编辑器可以复用同一套函数提示组件；本次先在 `bloge:transform` 落地，因为它是当前最直接的数据转换入口。
