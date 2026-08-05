# BLOGE 可视化画布多语言设计与扩展指南

> 当前状态：第一批顶层工作区已覆盖；Contract、Compatibility、Evidence、Library Detail、
> Operator Test 和移动端 compact projection 仍存在英文 UI 回退。缺口与 strict locale gate
> 计划见 [UX 深度审阅与针对性演进计划](resource-gateway-ux-deep-audit-and-targeted-evolution-plan.md)。

## 1. 能力范围

Resource Gateway React 界面当前支持 `en` 与 `zh-CN`。全局语言选择器位于主导航右侧，
覆盖 Author、Libraries、Rehearsals 和 Showcase；旧版静态 Custom Composer 作为 legacy
入口，不纳入本轮 React 国际化边界。

首批翻译覆盖全局导航、Author 命令条与开始对话框、画布导航、Graph Run Input、
Contract/Scenario Matrix/Case/Coverage、Library 首页和创建入口、Rehearsal 主工作面与证据
抽屉、Showcase 运行器和决策表。业务资产内容和服务端自由文本按原文展示。

## 2. 用户操作

1. 启动演示服务，打开任一 React 工作区。
2. 点击顶部 `中文`，页面在原路由和原任务上下文中切换。
3. 进入其他工作区或刷新页面，语言偏好继续生效。
4. 点击 `EN` 可随时恢复英文。

可分享的确定性链接使用 `?lang=zh-CN` 或 `?lang=en`。解析顺序为：

```text
URL lang -> localStorage bloge.visual.locale -> navigator.languages -> en
```

不支持的值会被忽略；`zh`、`zh-SG`、`zh-Hans` 等中文区域标记规范化为 `zh-CN`，
`en-US` 等英文区域标记规范化为 `en`。

## 3. 技术结构

| 模块 | 职责 |
| --- | --- |
| `src/i18n/i18n.ts` | Locale 类型、中文目录、参数插值、语言解析和持久化 |
| `src/i18n/I18nProvider.tsx` | React Context、即时切换、`html lang` 与 URL 同步 |
| `src/i18n/LanguageSwitcher.tsx` | 全局可键盘操作的 `EN / 中文` 分段控件 |
| `src/App.tsx` | 在路由和工作区之外安装唯一 Provider |

目录采用“英文源文案即 key”的渐进模式。英文不需要重复维护资源文件；中文目录缺少 key
时显示英文源文案，不会渲染空白或内部 key。动态值使用命名参数：

```tsx
const { t } = useI18n();
t('Draft r{revision} · {nodes} nodes', { revision, nodes });
```

## 4. 翻译边界

以下内容必须翻译：命令、标题、字段说明、空状态、错误解释、状态标签、tooltip、
`aria-label`、面向用户的数量和日期。

以下内容不得翻译：

- graph、operator、function、resource 和 port 的业务标识；
- DSL、JSON、JSON Schema、fixture、request/response payload；
- JSON Pointer、JSONPath、context path 和 runtime binding；
- protocol/schema version、枚举 wire value、fingerprint、draftId、runId；
- 服务端返回且没有稳定 code 的自由诊断文本。

组件显示协议状态时可以翻译 label，但提交给 API 的 `value` 必须保持协议常量。例如
下拉框可显示“超时”，其值仍为 `TIMEOUT`。

## 5. 新增文案

1. 在组件中调用 `useI18n()`，将稳定界面文案写成 `t('English source')`。
2. 在 `ZH_CN_MESSAGES` 添加完全相同的 key；动态内容使用命名参数，不拼接句子。
3. 同步翻译可访问名称和 tooltip，避免视觉用户与读屏用户得到不同语言。
4. 日期使用当前 `locale` 创建 `Intl.DateTimeFormat`；数字优先使用结构化参数。
5. 保留业务数据原值，避免对整个 DOM 或 API response 做文本替换。
6. 增加中文模式测试，并确认英文既有断言不回归。

## 6. 验证清单

- `?lang=zh-CN` 首次打开即显示中文，`?lang=en` 显示英文；
- 切换不触发页面重载，不清空当前草稿、选择、筛选或运行结果；
- URL、浏览器存储与 `document.documentElement.lang` 一致；
- 中文长文案在桌面和窄视口不溢出、不遮挡按钮；
- 技术标识、JSON/DSL 内容和请求 payload 在切换前后逐字一致；
- 键盘可以聚焦并操作两个语言选项，选中态由 `aria-pressed` 表达；
- 无存储权限时仍可在当前会话切换，不因 `localStorage` 异常阻断页面。
