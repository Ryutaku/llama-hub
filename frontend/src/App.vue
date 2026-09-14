<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { api, setUnauthorizedHandler } from './api'
import { ui } from './ui'
import LoginView from './components/LoginView.vue'
import DashboardView from './components/DashboardView.vue'
import ApiKeysView from './components/ApiKeysView.vue'
import LogsView from './components/LogsView.vue'
import UsageStatsView from './components/UsageStatsView.vue'
import AuditLogsView from './components/AuditLogsView.vue'
import ChangePasswordModal from './components/ChangePasswordModal.vue'

const authed = ref(false)
const checking = ref(true)
const username = ref('')
const showChangePwd = ref(false)
const showUserMenu = ref(false)
const upstream = ref({ up: false, latencyMs: null })
let pollTimer = null

setUnauthorizedHandler(() => {
  authed.value = false
})

const tabs = [
  { key: 'dashboard', label: '仪表盘', icon: 'fa-solid fa-gauge-high' },
  { key: 'keys', label: 'API Keys', icon: 'fa-solid fa-key' },
  { key: 'logs', label: '调用日志', icon: 'fa-solid fa-table-list' },
  { key: 'usage', label: '用量统计', icon: 'fa-solid fa-chart-column' },
  { key: 'audit', label: '审计日志', icon: 'fa-solid fa-shield-halved' }
]

function tabFromHash() {
  const h = location.hash.replace(/^#\/?/, '')
  return tabs.some(t => t.key === h) ? h : 'dashboard'
}

const tab = ref(tabFromHash())
watch(tab, v => {
  const target = '#/' + v
  if (location.hash !== target) location.hash = target
})
function onHashChange() {
  const t = tabFromHash()
  if (t !== tab.value) tab.value = t
}

async function pollUpstream() {
  try {
    upstream.value = await api.upstreamStatus()
  } catch (e) {
    upstream.value = { up: false, latencyMs: null }
  }
}

async function checkAuth() {
  try {
    const me = await api.me()
    if (me.ok) {
      authed.value = true
      username.value = me.username
      pollUpstream()
    }
  } catch (e) {
    /* not logged in */
  }
  checking.value = false
}

function onLogined(u) {
  username.value = u
  authed.value = true
  pollUpstream()
}

async function doLogout() {
  try {
    await api.logout()
  } catch (e) {
    /* ignore */
  }
  authed.value = false
  username.value = ''
  tab.value = 'dashboard'
  upstream.value = { up: false, latencyMs: null }
}

function onPasswordChanged() {
  showChangePwd.value = false
  toastLoginAgain()
  doLogout()
}

function toastLoginAgain() {
  // 改密成功后 session 失效，强制重新登录
}

onMounted(() => {
  checkAuth()
  window.addEventListener('hashchange', onHashChange)
  pollTimer = setInterval(() => {
    if (authed.value) pollUpstream()
  }, 30000)
})
onUnmounted(() => {
  clearInterval(pollTimer)
  window.removeEventListener('hashchange', onHashChange)
})
</script>

<template>
  <div class="min-h-screen bg-gh-bg">
    <!-- Toast -->
    <div v-if="ui.showToast" class="fixed top-4 left-1/2 -translate-x-1/2 z-[80]">
      <div
        class="toast-enter px-4 py-2 rounded-md text-sm border shadow-lg"
        :class="ui.toastType === 'error'
          ? 'bg-gh-red/20 border-gh-red text-gh-text'
          : ui.toastType === 'success'
            ? 'bg-gh-green text-white border-gh-green'
            : 'bg-gh-panel border-gh-border text-gh-text'"
      >{{ ui.toastMsg }}</div>
    </div>

    <!-- Login -->
    <LoginView v-if="!checking && !authed" @success="onLogined" />

    <!-- Main -->
    <div v-else-if="authed" class="min-h-screen">
      <header class="app-header sticky top-0 z-40">
        <div class="max-w-7xl mx-auto px-4 h-12 flex items-center gap-5">
          <div class="font-xh font-bold text-base whitespace-nowrap flex items-center gap-2 tracking-wide select-none">
            <span class="brand-badge">
              <i class="fa-solid fa-bolt"></i>
            </span>
            LLM网关服务
          </div>
          <nav class="flex items-center gap-0.5 text-sm flex-1">
            <button
              v-for="t in tabs"
              :key="t.key"
              class="btn-ripple px-3 py-1.5 rounded-lg transition-all duration-200 active:scale-95"
              :class="tab === t.key
                ? 'nav-active'
                : 'text-gh-muted hover:text-gh-text hover:bg-gh-tag active:bg-gh-tag'"
              @click="tab = t.key"
            ><i :class="t.icon" class="mr-1.5"></i>{{ t.label }}</button>
          </nav>

          <!-- upstream health indicator -->
          <div class="flex items-center gap-1.5 text-xs text-gh-muted" title="upstream 健康状态">
            <span
              class="w-2.5 h-2.5 rounded-full"
              :class="upstream.up ? 'bg-gh-green shadow-[0_0_6px_#1f883d]' : 'bg-gh-red shadow-[0_0_6px_#cf222e]'"
            />
            <span class="hidden sm:inline">{{ upstream.up ? 'upstream 正常' : 'upstream 不可达' }}</span>
          </div>

          <!-- user menu -->
          <div class="relative">
            <button
              class="text-sm text-gh-muted hover:text-gh-text px-3 py-1.5 rounded-md hover:bg-gh-tag transition-all duration-150 active:scale-95 active:bg-gh-tag"
              @click="showUserMenu = !showUserMenu"
            ><i class="fa-solid fa-user mr-1"></i>{{ username }} ▾</button>
            <div
              v-if="showUserMenu"
              class="modal-pop absolute right-0 mt-1 w-40 bg-gh-panel border border-gh-border rounded-md shadow-xl py-0.5 text-sm"
            >
              <button
                class="w-full text-left px-3 py-1.5 hover:bg-gh-tag transition-colors active:bg-gh-border/60 active:scale-[0.98]"
                @click="showUserMenu = false; showChangePwd = true"
              ><i class="fa-solid fa-lock mr-1.5"></i>修改密码</button>
              <button
                class="w-full text-left px-3 py-1.5 hover:bg-gh-tag text-gh-red transition-colors active:bg-gh-border/60 active:scale-[0.98]"
                @click="showUserMenu = false; doLogout()"
              ><i class="fa-solid fa-right-from-bracket mr-1.5"></i>退出登录</button>
            </div>
          </div>
        </div>
      </header>

      <main class="max-w-7xl mx-auto px-4 py-5">
        <DashboardView v-if="tab === 'dashboard'" :upstream="upstream" :refresh="tab === 'dashboard'" />
        <ApiKeysView v-else-if="tab === 'keys'" />
        <LogsView v-else-if="tab === 'logs'" />
        <UsageStatsView v-else-if="tab === 'usage'" />
        <AuditLogsView v-else-if="tab === 'audit'" />
      </main>
    </div>

    <!-- loading -->
    <div v-else class="min-h-screen flex items-center justify-center text-gh-muted text-sm">
      加载中…
    </div>

    <ChangePasswordModal v-if="showChangePwd" @close="showChangePwd = false" @changed="onPasswordChanged" />
  </div>
</template>

<style scoped>
.app-header {
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(14px) saturate(1.5);
  -webkit-backdrop-filter: blur(14px) saturate(1.5);
  border-bottom: 1px solid rgba(208, 215, 222, 0.75);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.6) inset,
    0 10px 30px -22px rgba(31, 35, 40, 0.35);
}

.brand-badge {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 7px;
  background: linear-gradient(135deg, #2da44e 0%, #1f883d 55%, #116329 100%);
  color: #ffffff;
  font-size: 11px;
  box-shadow:
    0 3px 8px -2px rgba(31, 136, 61, 0.5),
    inset 0 1px 0 rgba(255, 255, 255, 0.3);
}

.nav-active {
  background: rgba(31, 136, 61, 0.1);
  color: #1a7f37;
  font-weight: 600;
  box-shadow: inset 0 0 0 1px rgba(31, 136, 61, 0.22);
}
</style>
