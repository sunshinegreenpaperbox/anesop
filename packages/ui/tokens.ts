/**
 * @anesop/ui — 设计令牌的 TypeScript 投影（S0-08）。
 *
 * 与 `tokens.css` 同源同值：CSS 变量供样式使用，本文件供 TS/TSX、
 * 图表与运行时逻辑引用（例如导出报表主题、拼装 ECharts option）。
 * 修改令牌时两处必须同步，并在 `packages/ui/README.md` 记录变更。
 */

export const colors = {
  brand: {
    50: '#eaf4fb',
    100: '#d3e8f7',
    200: '#a9d2ef',
    300: '#74b4e2',
    400: '#3d92d1',
    500: '#1f74b3',
    600: '#17608f',
    700: '#124c72',
    800: '#0d3a57',
    900: '#08273b',
  },
  semantic: {
    success: '#2e9e5b',
    warning: '#d98b0b',
    danger: '#c93b3b',
    info: '#607d8b',
  },
  semanticSoft: {
    success: '#eaf5ef',
    warning: '#fbf3e7',
    danger: '#faebeb',
    info: '#eff2f3',
  },
  text: {
    primary: '#1f2d3d',
    regular: '#4a5a6a',
    secondary: '#6b7a8a',
    placeholder: '#94a3b2',
    disabled: '#a3adb8',
    inverse: '#ffffff',
  },
  border: {
    base: '#d8e0e8',
    light: '#e8eef3',
    strong: '#b8c4cf',
  },
  background: {
    page: '#f4f7fa',
    card: '#ffffff',
    elevated: '#ffffff',
    hover: '#eef4f9',
    active: '#dcebf7',
    inverse: '#0d2a40',
    inverseHover: '#16405f',
  },
} as const

export const fontFamily = {
  sans: [
    'Inter',
    'PingFang SC',
    'Hiragino Sans GB',
    'Microsoft YaHei',
    'Noto Sans SC',
    'Source Han Sans SC',
    'WenQuanYi Micro Hei',
    'system-ui',
    '-apple-system',
    'Segoe UI',
    'sans-serif',
  ].join(', '),
  mono: [
    'JetBrains Mono',
    'SFMono-Regular',
    'Consolas',
    'Liberation Mono',
    'Courier New',
    'monospace',
  ].join(', '),
} as const

export const fontSize = {
  xs: '12px',
  sm: '13px',
  base: '14px',
  md: '16px',
  lg: '18px',
  xl: '20px',
  '2xl': '24px',
  '3xl': '28px',
  '4xl': '32px',
} as const

export const lineHeight = {
  tight: 1.25,
  base: 1.5,
  relaxed: 1.75,
} as const

export const fontWeight = {
  regular: 400,
  medium: 500,
  semibold: 600,
  bold: 700,
} as const

/** 间距阶梯固定为 4 的倍数：4/8/12/16/24/32。 */
export const spacing = {
  0: '0',
  1: '4px',
  2: '8px',
  3: '12px',
  4: '16px',
  6: '24px',
  8: '32px',
} as const

export const radius = {
  sm: '4px',
  md: '6px',
  lg: '8px',
  xl: '12px',
  full: '999px',
} as const

export const gradient = {
  brand:
    'radial-gradient(circle at 88% 12%, rgba(255, 255, 255, 0.16), transparent 42%), ' +
    'linear-gradient(135deg, #124c72, #1f74b3)',
} as const

export const shadow = {
  sm: '0 1px 2px rgba(13, 42, 64, 0.06)',
  md: '0 4px 12px rgba(13, 42, 64, 0.08)',
  lg: '0 12px 32px rgba(13, 42, 64, 0.12)',
  focus: '0 0 0 3px rgba(31, 116, 179, 0.28)',
} as const

export const zIndex = {
  base: 0,
  sticky: 100,
  header: 200,
  drawer: 1000,
  dropdown: 1500,
  modal: 2000,
  popover: 2100,
  toast: 3000,
} as const

export const layout = {
  headerHeight: '56px',
  sidebarWidth: '220px',
  sidebarCollapsedWidth: '64px',
  contentMaxWidth: '1600px',
} as const

export const tokens = {
  colors,
  gradient,
  fontFamily,
  fontSize,
  lineHeight,
  fontWeight,
  spacing,
  radius,
  shadow,
  zIndex,
  layout,
} as const

export type DesignTokens = typeof tokens
export type BrandColor = keyof typeof colors.brand
export type SemanticColor = keyof typeof colors.semantic
export type FontSizeToken = keyof typeof fontSize
export type SpacingToken = keyof typeof spacing

export default tokens
