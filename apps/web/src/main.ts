import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@anesop/ui/tokens.css'
import App from './App.vue'
import router from './router'
import { configureHttp } from './api/http'
import { pinia } from './stores'

/**
 * 刷新失败/刷新后仍 401：http 层已清会话，这里统一跳登录并保留来源页。
 * 放在 main.ts 而不是 http.ts，避免 api 层依赖 router。
 */
configureHttp({
  onSessionExpired: () => {
    const current = router.currentRoute.value
    if (current.name === 'login') return
    void router.replace({
      name: 'login',
      query: { redirect: current.fullPath, reason: 'session-expired' },
    })
  },
})

createApp(App).use(pinia).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
