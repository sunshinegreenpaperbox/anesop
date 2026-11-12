/// <reference types="vite/client" />

import type { MenuIconName } from './navigation'

declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题，用于浏览器标题与面包屑。 */
    title?: string
    /** 侧边菜单图标；menu=false 的路由不需要。 */
    icon?: MenuIconName
    /** 是否出现在侧边菜单，缺省视为 false（仅 appMenu 登记的路由为 true）。 */
    menu?: boolean
    /** 访问所需权限点；声明多个时满足任一即可（OR）。 */
    permissions?: readonly string[]
    /** 公开路由（当前仅登录页）。 */
    public?: boolean
  }
}

interface ImportMetaEnv {
  /** API 基址；缺省 `/api`（Vite 代理 / Nginx 反代）。 */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
