<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { api, setUnauthorizedHandler } from './api'
import { ui } from './ui'
import LoginView from './components/LoginView.vue'
import DashboardView from './components/DashboardView.vue'
import ApiKeysView from './components/ApiKeysView.vue'
import LogsView from './components/LogsView.vue'
import UsageStatsView from './components/UsageStatsView.vue'
import AuditLogsView from './components/AuditLogsView.vue';
import ModelOpsView from './components/ModelOpsView.vue'
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
  { key: 'audit', label: '审计日志', icon: 'fa-solid fa-shield-halved' },
  { key: 'ops', label: '模型运维', icon: 'fa-solid fa-server' }
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
          ? 'bg-gh-red/15 border-gh-red/60 text-[#ffb3bd] shadow-[0_0_18px_-6px_rgba(240,69,92,0.6)]'
          : ui.toastType === 'success'
            ? 'bg-gh-green/15 border-gh-green/60 text-gh-green shadow-[0_0_18px_-6px_rgba(25,181,132,0.6)]'
            : 'bg-gh-panel/90 border-gh-border text-gh-text'"
      >{{ ui.toastMsg }}</div>
    </div>

    <!-- Login -->
    <LoginView v-if="!checking && !authed" @success="onLogined" />

    <!-- Main -->
    <div v-else-if="authed" class="min-h-screen">
      <header class="app-header sticky top-0 z-40">
        <div class="max-w-7xl mx-auto px-4 h-12 flex items-center gap-5">
          <div class="font-mono font-bold text-base whitespace-nowrap flex items-center gap-2.5 tracking-[0.08em] select-none">
            <span class="brand-badge">
              <i class="fa-solid fa-bolt-lightning"></i>
            </span>
            <span class="brand-text">llama-hub</span>
            <span class="brand-tag">GW·CONSOLE</span>
          </div>
          <nav class="flex items-center gap-0.5 text-sm flex-1">
            <button
              v-for="t in tabs"
              :key="t.key"
              class="btn-ripple px-3 py-1.5 rounded-lg transition-all duration-200 active:scale-95"
              :class="tab === t.key
                ? 'nav-active'
                : 'text-gh-muted hover:text-gh-text hover:bg-gh-cyan/5 active:bg-gh-cyan/10'"
              @click="tab = t.key"
            ><i :class="t.icon" class="mr-1.5"></i>{{ t.label }}</button>
          </nav>

          <!-- upstream health indicator -->
          <div class="flex items-center gap-1.5 text-xs text-gh-muted" title="upstream 健康状态">
            <span
              class="w-2.5 h-2.5 rounded-full"
              :class="upstream.up ? 'dot-live bg-gh-green' : 'bg-gh-red'"
              :style="{ color: upstream.up ? '#19b584' : '#f0455c' }"
            />
            <span class="hidden sm:inline">{{ upstream.up ? 'upstream 正常' : 'upstream 不可达' }}</span>
          </div>

          <!-- user menu -->
          <div class="relative">
            <button
              class="text-sm text-gh-muted hover:text-gh-text px-3 py-1.5 rounded-md hover:bg-gh-cyan/5 transition-all duration-150 active:scale-95 active:bg-gh-cyan/10"
              @click="showUserMenu = !showUserMenu"
            ><i class="fa-solid fa-user-large mr-1"></i>{{ username }} ▾</button>
            <div
              v-if="showUserMenu"
              class="modal-pop absolute right-0 mt-1 w-40 bg-gh-panel border border-gh-border rounded-md shadow-xl py-0.5 text-sm"
            >
              <button
                class="w-full text-left px-3 py-1.5 hover:bg-gh-cyan/5 transition-colors active:bg-gh-border/60 active:scale-[0.98]"
                @click="showUserMenu = false; showChangePwd = true"
              ><i class="fa-solid fa-lock mr-1.5"></i>修改密码</button>
              <button
                class="w-full text-left px-3 py-1.5 hover:bg-gh-red/10 text-gh-red transition-colors active:bg-gh-border/60 active:scale-[0.98]"
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
        <ModelOpsView v-else-if="tab === 'ops'" />
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
  background: rgba(9, 14, 23, 0.78);
  backdrop-filter: blur(16px) saturate(1.4);
  -webkit-backdrop-filter: blur(16px) saturate(1.4);
  border-bottom: 1px solid rgba(34, 211, 238, 0.14);
  box-shadow:
    inset 0 1px 0 rgba(140, 180, 240, 0.06),
    0 12px 32px -24px rgba(0, 0, 0, 0.9);
}

.brand-badge {
  width: 26px;
  height: 26px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: linear-gradient(135deg, #22d3ee 0%, #19b584 100%);
  color: #04121a;
  font-size: 12px;
  box-shadow:
    0 0 14px -2px rgba(34, 211, 238, 0.65),
    inset 0 1px 0 rgba(255, 255, 255, 0.45);
}

.brand-text {
  color: #eaf4ff;
  text-shadow: 0 0 14px rgba(34, 211, 238, 0.4);
}

.brand-tag {
  font-size: 9px;
  letter-spacing: 0.18em;
  color: #22d3ee;
  border: 1px solid rgba(34, 211, 238, 0.35);
  background: rgba(34, 211, 238, 0.08);
  border-radius: 4px;
  padding: 2px 5px 1px;
  font-weight: 500;
}

.nav-active {
  background: rgba(34, 211, 238, 0.1);
  color: #7deffc;
  font-weight: 600;
  box-shadow:
    inset 0 0 0 1px rgba(34, 211, 238, 0.3),
    0 0 14px -4px rgba(34, 211, 238, 0.45);
}
</style>
