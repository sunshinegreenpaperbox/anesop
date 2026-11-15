import type { RouteRecordRaw } from 'vue-router'
import MainLayout from '../layout/MainLayout.vue'
import LoginView from '../modules/m0/views/LoginView.vue'
import ChangePasswordView from '../modules/m0/views/ChangePasswordView.vue'
import SessionsView from '../modules/m0/views/SessionsView.vue'
import ApplicationListView from '../modules/m1/views/ApplicationListView.vue'
import ApplicationDetailView from '../modules/m1/views/ApplicationDetailView.vue'
import ScheduleBoardView from '../modules/m1/views/ScheduleBoardView.vue'
import AnalyticsView from '../modules/m1/views/AnalyticsView.vue'
import PreopEntryView from '../modules/m2/views/PreopEntryView.vue'
import PreopAssessmentView from '../modules/m2/views/PreopAssessmentView.vue'
import ConsentEntryView from '../modules/m2/views/ConsentEntryView.vue'
import ConsentView from '../modules/m2/views/ConsentView.vue'
import ThreeWayCheckEntryView from '../modules/m2/views/ThreeWayCheckEntryView.vue'
import ThreeWayCheckView from '../modules/m2/views/ThreeWayCheckView.vue'
import RecordEntryView from '../modules/m2/views/RecordEntryView.vue'
import RecordView from '../modules/m2/views/RecordView.vue'
import AlertRuleConfigView from '../modules/m2/views/AlertRuleConfigView.vue'
import PacuView from '../modules/m2/views/PacuView.vue'
import FollowupView from '../modules/m2/views/FollowupView.vue'
import DrugCatalogView from '../modules/m4/views/DrugCatalogView.vue'
import NarcoticView from '../modules/m4/views/NarcoticView.vue'
import StaffListView from '../modules/m4/views/StaffListView.vue'
import StaffAuthorizationView from '../modules/m4/views/StaffAuthorizationView.vue'
import StaffScheduleView from '../modules/m4/views/StaffScheduleView.vue'
import StaffTrainingView from '../modules/m4/views/StaffTrainingView.vue'
import MaterialCatalogView from '../modules/m4/views/MaterialCatalogView.vue'
import MaterialStockView from '../modules/m4/views/MaterialStockView.vue'
import MaterialTraceView from '../modules/m4/views/MaterialTraceView.vue'
import ReportView from '../modules/m4/views/ReportView.vue'
import QcIndicatorView from '../modules/m4/views/QcIndicatorView.vue'
import AdverseEventView from '../modules/m4/views/AdverseEventView.vue'
import ChartReviewView from '../modules/m4/views/ChartReviewView.vue'
import DashboardView from '../modules/m5/views/DashboardView.vue'
import RiskDashboardView from '../modules/m5/views/RiskDashboardView.vue'
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
        path: 'm1/applications',
        name: 'm1-applications',
        component: ApplicationListView,
        meta: menuMeta('/m1/applications'),
      },
      {
        path: 'm1/applications/:id',
        name: 'm1-application-detail',
        component: ApplicationDetailView,
        meta: {
          title: '手术申请详情',
          icon: 'sessions',
          permissions: ['m1:application:read'],
          menu: false,
        },
      },
      {
        path: 'm1/schedule',
        name: 'm1-schedule',
        component: ScheduleBoardView,
        meta: menuMeta('/m1/schedule'),
      },
      {
        path: 'm1/analytics',
        name: 'm1-analytics',
        component: AnalyticsView,
        meta: menuMeta('/m1/analytics'),
      },
      {
        path: 'm2/preop',
        name: 'm2-preop-entry',
        component: PreopEntryView,
        meta: menuMeta('/m2/preop'),
      },
      {
        path: 'm2/preop/:applicationId',
        name: 'm2-preop',
        component: PreopAssessmentView,
        meta: {
          title: '术前访视与评估',
          icon: 'sessions',
          permissions: ['m2:preop:read'],
          menu: false,
        },
      },
      {
        path: 'm2/consent',
        name: 'm2-consent-entry',
        component: ConsentEntryView,
        meta: menuMeta('/m2/consent'),
      },
      {
        path: 'm2/consent/:applicationId',
        name: 'm2-consent',
        component: ConsentView,
        meta: {
          title: '知情同意',
          icon: 'lock',
          permissions: ['m2:consent:read'],
          menu: false,
        },
      },
      {
        path: 'm2/three-way',
        name: 'm2-three-way-entry',
        component: ThreeWayCheckEntryView,
        meta: menuMeta('/m2/three-way'),
      },
      {
        path: 'm2/three-way/:applicationId',
        name: 'm2-three-way',
        component: ThreeWayCheckView,
        meta: {
          title: '三方核查',
          icon: 'sessions',
          permissions: ['m2:threeway:read'],
          menu: false,
        },
      },
      {
        path: 'm2/records',
        name: 'm2-records-entry',
        component: RecordEntryView,
        meta: menuMeta('/m2/records'),
      },
      {
        path: 'm2/records/:scheduleId',
        name: 'm2-record',
        component: RecordView,
        meta: {
          title: '麻醉记录单',
          icon: 'sessions',
          permissions: ['m2:record:read'],
          menu: false,
        },
      },
      {
        path: 'm2/pacu/:recordId',
        name: 'm2-pacu',
        component: PacuView,
        meta: {
          title: '复苏记录（PACU）',
          icon: 'sessions',
          permissions: ['m2:pacu:read'],
          menu: false,
        },
      },
      {
        path: 'm2/followups/:recordId',
        name: 'm2-followup',
        component: FollowupView,
        meta: {
          title: '术后随访与镇痛',
          icon: 'sessions',
          permissions: ['m2:followup:read'],
          menu: false,
        },
      },
      {
        path: 'm2/alert-rules',
        name: 'm2-alert-rules',
        component: AlertRuleConfigView,
        meta: menuMeta('/m2/alert-rules'),
      },
      {
        path: 'm4/drugs',
        name: 'm4-drugs',
        component: DrugCatalogView,
        meta: menuMeta('/m4/drugs'),
      },
      {
        path: 'm4/narcotic',
        name: 'm4-narcotic',
        component: NarcoticView,
        meta: menuMeta('/m4/narcotic'),
      },
      {
        path: 'm4/staff',
        name: 'm4-staff',
        component: StaffListView,
        meta: menuMeta('/m4/staff'),
      },
      {
        path: 'm4/staff/authorizations',
        name: 'm4-staff-authorizations',
        component: StaffAuthorizationView,
        meta: menuMeta('/m4/staff/authorizations'),
      },
      {
        path: 'm4/staff/schedules',
        name: 'm4-staff-schedules',
        component: StaffScheduleView,
        meta: menuMeta('/m4/staff/schedules'),
      },
      {
        path: 'm4/staff/trainings',
        name: 'm4-staff-trainings',
        component: StaffTrainingView,
        meta: menuMeta('/m4/staff/trainings'),
      },
      {
        path: 'm4/materials',
        name: 'm4-materials',
        component: MaterialCatalogView,
        meta: menuMeta('/m4/materials'),
      },
      {
        path: 'm4/materials/stock',
        name: 'm4-materials-stock',
        component: MaterialStockView,
        meta: menuMeta('/m4/materials/stock'),
      },
      {
        path: 'm4/materials/trace',
        name: 'm4-materials-trace',
        component: MaterialTraceView,
        meta: menuMeta('/m4/materials/trace'),
      },
      {
        path: 'm4/reports',
        name: 'm4-reports',
        component: ReportView,
        meta: menuMeta('/m4/reports'),
      },
      {
        path: 'm4/qc/indicators',
        name: 'm4-qc-indicators',
        component: QcIndicatorView,
        meta: menuMeta('/m4/qc/indicators'),
      },
      {
        path: 'm4/qc/adverse-events',
        name: 'm4-qc-adverse-events',
        component: AdverseEventView,
        meta: menuMeta('/m4/qc/adverse-events'),
      },
      {
        path: 'm4/qc/chart-reviews',
        name: 'm4-qc-chart-reviews',
        component: ChartReviewView,
        meta: menuMeta('/m4/qc/chart-reviews'),
      },
      {
        path: 'm5/dashboard',
        name: 'm5-dashboard',
        component: DashboardView,
        meta: menuMeta('/m5/dashboard'),
      },
      {
        path: 'm5/dashboard/risk',
        name: 'm5-dashboard-risk',
        component: RiskDashboardView,
        meta: menuMeta('/m5/dashboard/risk'),
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
