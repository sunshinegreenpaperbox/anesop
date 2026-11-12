import type { RouteRecordRaw } from 'vue-router'
import MainLayout from '../layout/MainLayout.vue'
import LoginView from '../modules/m0/views/LoginView.vue'
import ChangePasswordView from '../modules/m0/views/ChangePasswordView.vue'
import SessionsView from '../modules/m0/views/SessionsView.vue'
import ForbiddenView from '../views/ForbiddenView.vue'
import HomeView from '../views/HomeView.vue'
import NotFoundView from '../views/NotFoundView.vue'
import { findMenuEntry } from '../navigation'

/** 菜单入口 → 路由 meta，保证菜单标题/图标/权限与路由声明同源。 */
function menuMeta(path: string) {
  const entry = findMenuEntry(path)
  if (!entry) throw new Error(`导航未登记路由：${path}`)
  return {
    title: entry.title,
    icon: entry.icon,
    permissions: entry.permissions,
    menu: true,
  }
}

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { title: '登录', public: true, menu: false },
  },
  {
    path: '/',
    component: MainLayout,
    redirect: '/home',
    children: [
      {
        path: 'home',
        name: 'home',
        component: HomeView,
        meta: menuMeta('/home'),
      },
      {
        path: 'm0/sessions',
        name: 'm0-sessions',
        component: SessionsView,
        meta: menuMeta('/m0/sessions'),
      },
      {
        path: 'm0/password',
        name: 'm0-password',
        component: ChangePasswordView,
        meta: menuMeta('/m0/password'),
      },
    ],
  },
  {
    path: '/403',
    name: 'forbidden',
    component: ForbiddenView,
    meta: { title: '无权访问', menu: false },
  },
  {
    path: '/404',
    name: 'not-found',
    component: NotFoundView,
    meta: { title: '页面不存在', menu: false },
  },
  {
    // 未匹配路径保留原 URL 并渲染 404；未登录时仍先被守卫送到登录页。
    path: '/:pathMatch(.*)*',
    name: 'catch-all',
    component: NotFoundView,
    meta: { title: '页面不存在', menu: false },
  },
]
