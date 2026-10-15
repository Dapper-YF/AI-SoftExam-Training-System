import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { installElementPlus } from './app/element-plus'
import router from './app/router'
import { examUnauthorizedTarget, platformUnauthorizedTarget } from './app/router/api-redirects'
import { configureApiClientHandlers } from './api/client'
import { useExamSimulatorStore } from './features/exam-simulator/stores/exam-simulator'
import { useAuthStore } from './stores/auth'
import { useQualificationStore } from './stores/qualification'
import './styles/global.css'
import './styles/tokens.css'
import './styles/platform.css'
import './styles/practice.css'

const favicon = document.createElement('link')
favicon.rel = 'icon'
favicon.href = 'data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 viewBox=%220 0 32 32%22%3E%3Crect width=%2232%22 height=%2232%22 rx=%2216%22 fill=%22%231677d2%22/%3E%3Ctext x=%2216%22 y=%2222%22 text-anchor=%22middle%22 font-family=%22Arial%22 font-size=%2218%22 fill=%22white%22%3EA%3C/text%3E%3C/svg%3E'
document.head.appendChild(favicon)

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
installElementPlus(app)

const authStore = useAuthStore(pinia)
const qualificationStore = useQualificationStore(pinia)
const examStore = useExamSimulatorStore(pinia)

configureApiClientHandlers({
  onPlatformUnauthorized: () => {
    const wasAuthenticated = authStore.isAuthenticated
    const protectedRoute = router.currentRoute.value.matched.some((record) => record.meta.requiresAuth)
    authStore.clearSession()
    if (!wasAuthenticated && !protectedRoute) return
    qualificationStore.clear()
    const redirect = router.currentRoute.value.fullPath
    void router.replace(platformUnauthorizedTarget(redirect))
  },
  onExamUnauthorized: () => {
    examStore.clearClientSession()
    void router.replace(examUnauthorizedTarget())
  },
  onForbidden: () => {
    void router.replace('/403')
  },
  onServerError: () => {
    void router.replace('/500')
  },
})

app.mount('#app')
