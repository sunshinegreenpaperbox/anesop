/** 侧边菜单图标名；由 layout/MenuIcon.vue 映射为内联 SVG，避免引入图标依赖。 */
export type MenuIconName = 'home' | 'sessions' | 'lock'

export interface MenuEntry {
  path: string
  title: string
  icon: MenuIconName
  /**
   * 所需权限点（三段式 `<模块>:<资源>:<动作>`，见 docs/modules/m0/README.md）。
   * 缺省表示登录即可见；声明多个时满足任一即可。
   */
  permissions?: readonly string[]
}

/**
 * 导航是“路由 meta + 侧边菜单”的同一事实源：
 * - router/routes.ts 用 menuMeta() 生成 meta；
 * - stores/menu.ts 用 filterMenuByPermission() 生成可见菜单。
 * 新增页面时只改这里 + 路由表，避免菜单与路由漂移。
 */
export const appMenu: readonly MenuEntry[] = [
  { path: '/home', title: '首页', icon: 'home' },
  {
    path: '/m1/applications',
    title: '手术申请',
    icon: 'sessions',
    permissions: ['m1:application:read'],
  },
  {
    path: '/m1/schedule',
    title: '排程工作台',
    icon: 'sessions',
    permissions: ['m1:schedule:read'],
  },
  {
    path: '/m1/analytics',
    title: '排程分析',
    icon: 'sessions',
    permissions: ['m1:analytics:read'],
  },
  {
    path: '/m2/preop',
    title: '术前访视',
    icon: 'sessions',
    permissions: ['m2:preop:read'],
  },
  {
    path: '/m2/consent',
    title: '知情同意',
    icon: 'lock',
    permissions: ['m2:consent:read'],
  },
  {
    path: '/m2/three-way',
    title: '三方核查',
    icon: 'sessions',
    permissions: ['m2:threeway:read'],
  },
  {
    path: '/m2/records',
    title: '麻醉记录',
    icon: 'sessions',
    permissions: ['m2:record:read'],
  },
  {
    path: '/m2/alert-rules',
    title: '预警规则',
    icon: 'lock',
    permissions: ['m2:alert:configure'],
  },
  {
    path: '/m4/drugs',
    title: '药品目录',
    icon: 'sessions',
    permissions: ['m4:drug:read'],
  },
  {
    path: '/m4/narcotic',
    title: '麻精五专',
    icon: 'lock',
    permissions: ['m4:narcotic:manage'],
  },
  {
    path: '/m4/staff',
    title: '人员档案',
    icon: 'sessions',
    permissions: ['m4:staff:read'],
  },
  {
    path: '/m4/staff/authorizations',
    title: '分级授权',
    icon: 'lock',
    permissions: ['m4:staff:read'],
  },
  {
    path: '/m4/staff/schedules',
    title: '人员排班',
    icon: 'sessions',
    permissions: ['m4:staff:read'],
  },
  {
    path: '/m4/staff/trainings',
    title: '培训提醒',
    icon: 'lock',
    permissions: ['m4:staff:read'],
  },
  {
    path: '/m4/materials',
    title: '耗材目录',
    icon: 'sessions',
    permissions: ['m4:material:read'],
  },
  {
    path: '/m4/materials/stock',
    title: '耗材出入库',
    icon: 'lock',
    permissions: ['m4:material:read'],
  },
  {
    path: '/m4/materials/trace',
    title: 'UDI 追溯',
    icon: 'sessions',
    permissions: ['m4:material:read'],
  },
  {
    path: '/m4/reports',
    title: '工作量报表',
    icon: 'sessions',
    permissions: ['m4:report:read'],
  },
  {
    path: '/m4/qc/indicators',
    title: '质控指标',
    icon: 'sessions',
    permissions: ['m4:qc:read'],
  },
  {
    path: '/m4/qc/adverse-events',
    title: '不良事件',
    icon: 'lock',
    permissions: ['m4:qc:read'],
  },
  {
    path: '/m4/qc/chart-reviews',
    title: '病历抽查',
    icon: 'sessions',
    permissions: ['m4:qc:read'],
  },
  {
    path: '/m0/sessions',
    title: '会话管理',
    icon: 'sessions',
    permissions: ['m0:auth:read'],
  },
  { path: '/m0/password', title: '修改口令', icon: 'lock' },
]

export function findMenuEntry(path: string): MenuEntry | undefined {
  return appMenu.find((entry) => entry.path === path)
}

/** 按权限过滤菜单；纯函数，便于单测。 */
export function filterMenuByPermission(
  entries: readonly MenuEntry[],
  can: (permissions?: readonly string[]) => boolean,
): MenuEntry[] {
  return entries.filter((entry) => can(entry.permissions))
}

/** 角色码 → 中文标签；未登记的角色码原样展示，避免隐藏未知角色。 */
export const roleLabels: Record<string, string> = {
  SYS_ADMIN: '信息科管理员（R9）',
  SYS_ANESTHESIOLOGIST: '麻醉医师（R2）',
  SYS_NURSE_ANESTHETIST: '麻醉护士（R3）',
  SYS_SURGEON: '手术科室医师（R10）',
  SYS_OR_MANAGER: '手术室管理员（R4）',
  SYS_OR_NURSE: '手术室护士（R4）',
  SYS_QC_MANAGER: '质控专员（R8）',
  SYS_ANESTHESIA_DIRECTOR: '麻醉科主任/质控负责人（R1）',
  SYS_PACU_NURSE: 'PACU 护士（R5）',
  SYS_MATERIAL_MANAGER: '耗材/库房管理员（R6）',
  SYS_NARCOTIC_MANAGER: '麻精药品管理员（R7）',
  SYS_PHARMACIST: '药房药师（R6/R7）',
  SYS_HOSPITAL_LEADER: '院领导/医务处（R11）',
}

export function labelForRole(role: string): string {
  return roleLabels[role] ?? role
}

export function labelForRoles(roles: readonly string[]): string[] {
  return roles.map(labelForRole)
}
