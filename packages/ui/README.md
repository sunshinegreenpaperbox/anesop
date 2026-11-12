# @anesop/ui — 设计令牌与共享展示层

> 状态：S0-08 建立。包名 `@anesop/ui`，`private`，只服务于本 monorepo。
> 当前只交付设计令牌（`tokens.css` + `tokens.ts`）；共享展示组件随 M1 起按需新增，
> 新增组件前先确认是否已有 Element Plus 组件可满足，避免重复造轮子。

## 1. 职责与目录

**职责**：作为全前端唯一的设计令牌事实源，提供 CSS 变量、TS 投影与 Element Plus 主题映射；
当前不提供业务组件，共享展示组件随 M1 起按需新增。

| 文件 | 用途 |
|---|---|
| `tokens.css` | CSS 变量（品牌色板、语义色、字号、间距、圆角、阴影、z-index、字体族）与 Element Plus 主题变量映射 |
| `tokens.ts` | 同一套令牌的 TypeScript 投影，供 TS 逻辑、图表主题、导出模板引用 |
| `README.md` | 使用方式与约束（本文件） |

`package.json` 的导出：

```ts
import tokens, { colors, spacing, fontSize } from '@anesop/ui'
import '@anesop/ui/tokens.css'
```

## 2. 使用方式

### 2.1 在 `apps/web` 中加载

```ts
// apps/web/src/main.ts
import 'element-plus/dist/index.css'
import '@anesop/ui/tokens.css' // 必须在 Element Plus 之后，才能覆盖 --el-* 变量
```

顺序不能颠倒：Element Plus 2.9 在 `:root` 定义默认主题，后加载的 `tokens.css`
用同名 `--el-*` 变量覆盖；反过来会被组件库默认值盖掉。

### 2.2 在页面样式中引用

```css
.panel {
  padding: var(--anesop-space-4);
  color: var(--anesop-color-text-primary);
  background: var(--anesop-color-bg-card);
  border: 1px solid var(--anesop-color-border-light);
  border-radius: var(--anesop-radius-lg);
  box-shadow: var(--anesop-shadow-sm);
  font-size: var(--anesop-font-size-base);
}

.panel__title {
  font-size: var(--anesop-font-size-lg);
  font-weight: var(--anesop-font-weight-semibold);
}
```

### 2.3 在 TS 中引用

```ts
import { colors, spacing } from '@anesop/ui'

const chartTheme = {
  color: [colors.brand[500], colors.semantic.success, colors.semantic.warning],
  grid: { top: Number.parseInt(spacing[4], 10) },
}
```

## 3. 令牌清单（摘要）

| 类别 | 令牌 | 值 / 规则 |
|---|---|---|
| 品牌色 | `--anesop-color-brand-50…900` | 医疗蓝主色 `#1f74b3`，50–900 十档 |
| 语义色 | `--anesop-color-{success,warning,danger,info}` | 另有 `*-soft` 浅底，用于标签/提示背景 |
| 文本 | `--anesop-color-text-{primary,regular,secondary,placeholder,disabled,inverse}` | 正文对比度满足 WCAG AA |
| 背景 | `--anesop-color-bg-{page,card,elevated,hover,active,inverse}` | 页面底色 `#f4f7fa` |
| 字号 | `--anesop-font-size-{xs,sm,base,md,lg,xl,2xl,3xl,4xl}` | 12/13/14/16/18/20/24/28/32 px |
| 间距 | `--anesop-space-{1,2,3,4,6,8}` | 4/8/12/16/24/32 px，不允许 5/10/15 等游离值 |
| 圆角 | `--anesop-radius-{sm,md,lg,xl,full}` | 4/6/8/12/999 px |
| 阴影 | `--anesop-shadow-{sm,md,lg,focus}` | 低饱和蓝黑阴影，避免纯黑 |
| 层级 | `--anesop-z-index-{base,sticky,header,drawer,dropdown,modal,popover,toast}` | 0/100/200/1000/1500/2000/2100/3000 |
| 字体 | `--anesop-font-family-{sans,mono,number}` | 中文回退：PingFang SC → Microsoft YaHei → Noto Sans SC → 思源黑体 |

Element Plus 映射覆盖：`--el-color-primary` 及 `light-3/5/7/8/9`、`dark-2`；
success/warning/danger/error/info 同构；`--el-font-family`、`--el-font-size-*`、
`--el-text-color-*`、`--el-border-color-*`、`--el-bg-color*`、`--el-border-radius-*`、
`--el-box-shadow*`、`--el-mask-color`。

## 4. 约束（评审检查项）

1. **禁止页面内硬编码颜色/字号**：不写 `#1f74b3`、`rgb(...)`、`font-size: 15px`；
   一律 `var(--anesop-*)`。临时插画/图表色如确需新增，先扩展本包令牌。
2. **禁止绕过语义层直接用品牌色**：业务页面优先 `--anesop-color-primary` /
   `--anesop-color-success` 等语义别名；只有品牌展示区才用 `brand-*`。
3. **间距只用 4 的倍数**：布局间距取 `--anesop-space-*`；组件内部 padding 同样遵守。
4. **Element Plus 变量只在 `tokens.css` 覆盖**：页面不得再写 `--el-color-primary`。
5. **令牌只增不删**：删除或改语义需评审并同步 `tokens.ts`、`docs/ui/README.md`
   与本文件；已在页面使用的令牌改名按破坏性变更处理。
6. **不引入 ECharts**：图表在 M8 引入 `echarts` 5.5.x，届时图表主题从这里取色，
   不在页面里另建色板。
7. **无障碍**：正文文本与背景对比度 ≥ 4.5:1，大字号 ≥ 3:1；焦点环统一使用
   `--anesop-shadow-focus` / `:focus-visible`，不得移除 outline。

## 5. 如何运行

本包不单独运行，作为工作区依赖被 `apps/web` 引入：

```ts
// apps/web/src/main.ts
import 'element-plus/dist/index.css'
import '@anesop/ui/tokens.css'   // 必须在 Element Plus 之后
```

`package.json` 的 `exports` 同时暴露 `tokens.css` 与 `tokens.ts`；`apps/web` 通过
`workspace:*` 依赖，执行 `pnpm install --frozen-lockfile` 后即可使用。

## 6. 如何测试

- 本包当前无独立单测；令牌正确性由 `apps/web` 的 Vitest/构建与 E2E 间接覆盖：
  ```bash
  pnpm -C apps/web exec vue-tsc --noEmit
  pnpm test:unit
  pnpm -C apps/web build
  ```
- 修改令牌后必须人工检查登录页/首页/表单/表格的对比度与 Element Plus 覆盖是否生效
  （加载顺序：Element Plus CSS 在前，`tokens.css` 在后）。
- 新增/改令牌需同步 `tokens.css`、`tokens.ts`、本文件与 `docs/ui/README.md`。

## 7. 关键契约

- **令牌是跨模块契约**：页面只消费 `var(--anesop-*)`，禁止硬编码颜色/字号/间距；
  Element Plus 的 `--el-*` 覆盖只允许写在 `tokens.css`。
- **只增不删**：删除或改语义按破坏性变更处理（评审 + 同步文档）；页面已使用的令牌改名
  必须先加新名、再迁移、最后删旧名。
- 语义别名优先：业务页面用 `--anesop-color-primary` 等，品牌展示区才用 `brand-*`。
- 图表主题（M8 ECharts）从 `tokens.ts` 取色，不在页面另建色板。

## 8. 负责人（占位）

- Owner：`@anesop/web-core`（CODEOWNERS 占位团队；远端组织未建，审批暂不生效）
- 备份：`@anesop/maintainers`
- 状态：S0-08 建立令牌；共享展示组件随 M1 起按需新增（先确认 Element Plus 是否已满足）。

## 9. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-12 | S0-08 建立：品牌色板、语义色、字号/间距/圆角/阴影/z-index/字体族、Element Plus 映射 |
