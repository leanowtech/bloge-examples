# BLOGE 可视化画布多语言设计与扩展指南

> 当前状态：Stage 4 已完成。核心深层工作区、动态任务状态、协议诊断与第三方画布控件已纳入
> strict locale gate。实现证据见
> [Stage 4 双语完整性与术语治理](resource-gateway-ux-stage4-localization-governance.md)，推荐译法见
> [双语术语与界面文案规范](resource-gateway-localization-glossary.md)。

## 1. 能力范围

Resource Gateway React 界面当前支持 `en` 与 `zh-CN`。全局语言选择器位于主导航右侧，
覆盖 Author、Libraries、Rehearsals 和 Showcase；旧版静态 Custom Composer 作为 legacy
入口，不纳入本轮 React 国际化边界。

覆盖范围包括全局导航、Author 命令与开始对话框、画布节点/边/控件、Graph Run Input、
Contract/Scenario Matrix/Case/Coverage、Library 创建与深层编辑、Rehearsal、Showcase、Decision
Table 和诊断抽屉。业务资产、字段路径、代码和技术坐标按原文展示。

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
| `src/i18n/messageCatalog.ts` | typed command、blocker 与 lifecycle message ID |
| `src/i18n/diagnosticCatalog.ts` | diagnostic code 到标题、解释和修复动作的投影 |
| `src/i18n/I18nProvider.tsx` | React Context、即时切换、`html lang` 与 URL 同步 |
| `src/i18n/LanguageSwitcher.tsx` | 全局可键盘操作的 `EN / 中文` 分段控件 |
| `src/App.tsx` | 在路由和工作区之外安装唯一 Provider |

新命令、状态和 blocker 使用 typed ID；迁移中的稳定界面文案仍可使用“英文源文案即 key”。
运行时保留英文 fallback 以避免空白，但 strict inventory 会在 audited surface 缺少中文时让 CI
失败，因此 fallback 不能再静默进入发布。动态值使用命名参数：

```tsx
const { t } = useI18n();
t('Draft r{revision} · {nodes} nodes', { revision, nodes });

const { m } = useI18n();
m('author.command.run');
```

## 4. 翻译边界

以下内容必须翻译：命令、标题、字段说明、空状态、错误解释、状态标签、tooltip、
`aria-label`、面向用户的数量和日期。

以下内容不得翻译：

- graph、operator、function、resource 和 port 的业务标识；
- DSL、JSON、JSON Schema、fixture、request/response payload；
- JSON Pointer、JSONPath、context path 和 runtime binding；
- protocol/schema version、枚举 wire value、fingerprint、draftId、runId；
- 服务端自由诊断文本；它只进入折叠的技术详情，不作为中文主错误。

组件显示协议状态时可以翻译 label，但提交给 API 的 `value` 必须保持协议常量。例如
下拉框可显示“超时”，其值仍为 `TIMEOUT`。

## 5. 新增文案

1. 新命令、状态、blocker 或 diagnostic 优先增加 typed message ID/code catalog。
2. 迁移期稳定文案可写成 `t('English source')`，并在 `ZH_CN_MESSAGES` 添加同 key 中文。
3. 同步翻译可访问名称和 tooltip，避免视觉用户与读屏用户得到不同语言。
4. 日期使用当前 `locale` 创建 `Intl.DateTimeFormat`；数字优先使用结构化参数。
5. 保留业务数据原值，避免对整个 DOM 或 API response 做文本替换。
6. 动态 registry、状态机和 wire union 增加显式 inventory，不能依赖 JSX 扫描发现。
7. 运行 `npm run check:i18n`，并确认中英文命令状态和任务结构不回归。

## 6. 验证清单

- `?lang=zh-CN` 首次打开即显示中文，`?lang=en` 显示英文；
- 切换不触发页面重载，不清空当前草稿、选择、筛选或运行结果；
- URL、浏览器存储与 `document.documentElement.lang` 一致；
- 中文长文案在桌面和窄视口不溢出、不遮挡按钮；
- 技术标识、JSON/DSL 内容和请求 payload 在切换前后逐字一致；
- 键盘可以聚焦并操作两个语言选项，选中态由 `aria-pressed` 表达；
- 无存储权限时仍可在当前会话切换，不因 `localStorage` 异常阻断页面。
- `npm run check:i18n` 通过，浏览器固定任务不存在非业务资产英文 UI。
