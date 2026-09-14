<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { api } from '../api'
import { toast } from '../ui'

const status = ref(null)
const config = ref(null)
const argsText = ref('')
const busy = ref('')
const logs = ref([])
const autoScroll = ref(true)
const filterText = ref('')
const showDiff = ref(false)
const pendingDiff = ref([])
const logBox = ref(null)
const paramTab = ref('args')
const unreadLogs = ref(0)

let eventsEs = null
let logsEs = null
const MAX_LOGS = 2000

const STATE_META = {
  RUNNING: { label: '运行中', cls: 'bg-gh-green text-[#04121a] shadow-[0_0_16px_-2px_rgba(25,181,132,0.8)]', icon: 'fa-solid fa-play-circle' },
  STARTING: { label: '加载中', cls: 'bg-gh-blue text-white shadow-[0_0_16px_-2px_rgba(61,139,253,0.8)]', icon: 'fa-solid fa-spinner fa-spin' },
  STOPPING: { label: '停止中', cls: 'bg-gh-orange text-[#1a1204] shadow-[0_0_16px_-2px_rgba(245,166,35,0.7)]', icon: 'fa-solid fa-spinner fa-spin' },
  STOPPED: { label: '已停止', cls: 'bg-gh-tag text-gh-muted border border-gh-border', icon: 'fa-solid fa-stop-circle' },
  ERROR: { label: '错误', cls: 'bg-gh-red text-white shadow-[0_0_16px_-2px_rgba(240,69,92,0.8)]', icon: 'fa-solid fa-exclamation-circle' },
  UNKNOWN: { label: '未知', cls: 'bg-[#33415c] text-gh-text', icon: 'fa-solid fa-question-circle' }
}

const SOURCE_LABEL = { default: '内置默认', edited: '手工编辑', snapshot: '服务器快照' }
const SOURCE_ICON = { default: 'fa-solid fa-box', edited: 'fa-solid fa-pen', snapshot: 'fa-solid fa-camera-alt' }

const sourceLabel = computed(() => SOURCE_LABEL[config.value?.source] || config.value?.source || '—')

function fmtUptime(sec) {
  const s = Number(sec)
  if (s >= 86400) return (s / 86400).toFixed(1) + ' 天'
  if (s >= 3600) return (s / 3600).toFixed(1) + ' 小时'
  if (s >= 60) return (s / 60).toFixed(1) + ' 分钟'
  return Math.round(s) + ' 秒'
}

function lineClass(line) {
  if (/error|fatal|fail/i.test(line)) return 'text-red-400'
  if (/warn/i.test(line)) return 'text-yellow-400'
  return ''
}

async function loadStatus() {
  try {
    status.value = await api.modelStatus()
  } catch (e) {
    /* ignore */
  }
}

async function loadConfig() {
  try {
    const c = await api.modelConfig()
    config.value = c
    argsText.value = formatArgsMultiline(c.args)
  } catch (e) {
    toast(e.message || '加载参数失败', 'error')
  }
}

async function doStart() {
  busy.value = 'start'
  try {
    await api.modelStart()
    toast('启动任务已下发，模型加载中…', 'info')
    loadStatus()
  } catch (e) {
    toast(e.message || '启动失败', 'error')
  } finally {
    busy.value = ''
  }
}

async function doStop() {
  busy.value = 'stop'
  try {
    await api.modelStop()
    toast('停止任务已下发…', 'info')
    loadStatus()
  } catch (e) {
    toast(e.message || '停止失败', 'error')
  } finally {
    busy.value = ''
  }
}

async function saveParams() {
  busy.value = 'save'
  try {
    const r = await api.modelConfigSave(argsText.value)
    toast(`参数已保存（${r.diff.length} 项变更），重启后生效`, 'success')
    loadConfig()
  } catch (e) {
    toast(e.message || '保存失败', 'error')
  } finally {
    busy.value = ''
  }
}

function formatArgsMultiline(args) {
  const tokens = (args || '').trim().split(/\s+/).filter(Boolean)
  const lines = []
  for (let i = 0; i < tokens.length; i++) {
    if (tokens[i].startsWith('-')) {
      if (i + 1 < tokens.length && !tokens[i + 1].startsWith('-')) {
        lines.push(tokens[i] + ' ' + tokens[i + 1])
        i++
      } else {
        lines.push(tokens[i])
      }
    }
  }
  return lines.join('\n')
}

async function doSnapshot() {
  busy.value = 'snap'
  try {
    const r = await api.modelSnapshot()
    if (!r.diff || r.diff.length === 0) {
      toast('快照完成：服务器参数与当前配置一致', 'success')
      await loadConfig()
    } else {
      pendingDiff.value = r.diff
      showDiff.value = true
    }
  } catch (e) {
    toast(e.message || '快照失败', 'error')
  } finally {
    busy.value = ''
  }
}

function closeDiff() {
  showDiff.value = false
  loadConfig()
}

watch(paramTab, (t) => {
  if (t === 'logs') {
    unreadLogs.value = 0
    if (autoScroll.value) {
      nextTick(() => {
        if (logBox.value) logBox.value.scrollTop = logBox.value.scrollHeight
      })
    }
  }
})

function pushLog(line) {
  logs.value.push(line)
  if (paramTab.value !== 'logs') unreadLogs.value++
  if (logs.value.length > MAX_LOGS) {
    logs.value.splice(0, logs.value.length - MAX_LOGS)
  }
  if (autoScroll.value) {
    nextTick(() => {
      if (logBox.value) logBox.value.scrollTop = logBox.value.scrollHeight
    })
  }
}

const filteredLogs = computed(() => {
  if (!filterText.value) return logs.value
  const f = filterText.value.toLowerCase()
  return logs.value.filter(l => l.toLowerCase().includes(f))
})

function connectEvents() {
  eventsEs = new EventSource(api.modelEventsUrl())
  eventsEs.addEventListener('state', e => {
    try {
      status.value = JSON.parse(e.data)
    } catch (err) {
      /* ignore */
    }
  })
}

function connectLogs() {
  logsEs = new EventSource(api.modelLogsUrl())
  logsEs.addEventListener('log', e => pushLog(e.data))
}

onMounted(() => {
  loadStatus()
  loadConfig()
  connectEvents()
  connectLogs()
})
onUnmounted(() => {
  if (eventsEs) eventsEs.close()
  if (logsEs) logsEs.close()
})
</script>

<template>
  <div class="space-y-4">
    <!-- 状态卡 -->
    <div class="panel-tech p-4">
      <div class="flex items-center gap-4 flex-wrap">
        <span class="px-3 py-1 rounded-full text-sm font-semibold flex items-center gap-1.5"
              :class="STATE_META[status?.state]?.cls || 'bg-gh-border text-gh-text'">
          <i :class="STATE_META[status?.state]?.icon || 'fa-solid fa-question-circle'"></i>
          {{ STATE_META[status?.state]?.label || '未知' }}
        </span>
        <div class="text-sm text-gh-muted space-x-4">
          <span v-if="status?.pid != null"><i class="fa-solid fa-hashtag mr-1"></i>PID {{ status.pid }}</span>
          <span v-if="status?.uptimeSec != null"><i class="fa-solid fa-stopwatch mr-1"></i>运行 {{ fmtUptime(status.uptimeSec) }}</span>
          <span :class="status?.healthOk ? 'text-gh-green' : ''"><i class="fa-solid fa-heartbeat mr-1"></i>health {{ status?.healthOk ? '正常' : '不可达' }}</span>
          <span><i class="fa-solid fa-plug mr-1"></i>端口 {{ status?.portListening ? '监听中' : '未监听' }}</span>
          <span v-if="status && !status.sshAvailable" class="text-gh-red"><i class="fa-solid fa-plug-circle-xmark mr-1"></i>SSH 断开</span>
        </div>
        <div class="ml-auto flex items-center gap-2">
          <button
            class="px-4 h-[30px] rounded-lg text-sm font-semibold border border-gh-green/70 bg-gh-green/10 text-gh-green hover:border-gh-green hover:bg-gh-green/20 transition-all duration-200 active:scale-95 disabled:opacity-40 disabled:cursor-not-allowed"
            :disabled="!!busy || ['RUNNING', 'STARTING'].includes(status?.state)"
            @click="doStart"
          ><i class="fa-solid fa-play mr-1.5"></i>启动</button>
          <button
            class="px-4 h-[30px] rounded-lg text-sm font-semibold border border-gh-red/70 bg-gh-red/10 text-gh-red hover:border-gh-red hover:bg-gh-red/20 transition-all duration-200 active:scale-95 disabled:opacity-40 disabled:cursor-not-allowed"
            :disabled="!!busy || ['STOPPED', 'STOPPING', 'UNKNOWN'].includes(status?.state)"
            @click="doStop"
          ><i class="fa-solid fa-stop mr-1.5"></i>停止</button>
        </div>
      </div>
      <div v-if="status?.message" class="mt-2 text-sm text-gh-red">{{ status.message }}</div>
    </div>

    <!-- 启动参数 / 实时日志（Tab 布局） -->
    <div>
      <div class="flex items-center gap-1 border-b border-gh-border mb-3">
        <button
          class="px-3 py-1.5 text-sm border-b-2 -mb-px transition-colors"
          :class="paramTab === 'args' ? 'border-gh-cyan text-gh-cyan font-semibold' : 'border-transparent text-gh-muted hover:text-gh-text'"
          @click="paramTab = 'args'"
        ><i class="fa-solid fa-sliders-h mr-1.5"></i>启动参数</button>
        <button
          class="px-3 py-1.5 text-sm border-b-2 -mb-px transition-colors"
          :class="paramTab === 'logs' ? 'border-gh-cyan text-gh-cyan font-semibold' : 'border-transparent text-gh-muted hover:text-gh-text'"
          @click="paramTab = 'logs'"
        ><i class="fa-solid fa-terminal mr-1.5"></i>实时日志<span
          v-if="paramTab !== 'logs' && unreadLogs > 0"
          class="ml-1.5 px-1.5 py-0.5 rounded-full text-[10px] font-semibold bg-gh-green text-white"
        >{{ unreadLogs > 99 ? '99+' : unreadLogs }}</span></button>
      </div>

    <div v-show="paramTab === 'args'" class="panel-tech p-4">
      <div class="flex items-center gap-3 mb-2 flex-wrap">
        <h2 class="font-semibold text-base"><i class="fa-solid fa-sliders-h mr-1.5 text-gh-cyan"></i>启动参数</h2>
        <span class="text-xs px-1.5 py-0.5 rounded bg-[#0a111d] border border-gh-border text-gh-muted"><i :class="(SOURCE_ICON[config?.source] || 'fa-solid fa-box') + ' mr-1'"></i>来源: {{ sourceLabel }}</span>
        <span v-if="config?.updatedAt" class="text-xs text-gh-muted font-mono"><i class="fa-solid fa-clock-four mr-1"></i>更新于 {{ config.updatedAt.replace('T', ' ') }}</span>
        <span
          v-if="config?.drift"
          class="px-2 py-0.5 rounded-full text-xs font-semibold bg-gh-orange/10 text-gh-orange border border-gh-orange/40"
          title="服务器运行进程的参数段与当前配置不一致，可用「从服务器快照」覆盖"
        ><i class="fa-solid fa-exclamation mr-1"></i>运行参数与配置不一致</span>
        <div class="ml-auto flex items-center gap-2">
          <button
            class="px-3 h-[30px] rounded-lg text-sm font-semibold border border-gh-border bg-[#0a111d] text-gh-text hover:border-gh-cyan/50 hover:text-gh-cyan transition-all duration-200 active:scale-95 disabled:opacity-40 disabled:cursor-not-allowed"
            :disabled="!!busy"
            @click="doSnapshot"
          ><i class="fa-solid fa-camera-alt mr-1.5"></i>从服务器快照</button>
          <button
            class="px-3 h-[30px] rounded-lg text-sm font-semibold border border-gh-green/70 bg-gh-green/10 text-gh-green hover:border-gh-green hover:bg-gh-green/20 transition-all duration-200 active:scale-95 disabled:opacity-40 disabled:cursor-not-allowed"
            :disabled="!!busy"
            @click="saveParams"
          ><i class="fa-solid fa-save mr-1.5"></i>保存参数</button>
        </div>
      </div>
      <p class="text-xs text-gh-muted mb-2">
        llama-server 完整启动参数行（不含二进制路径），任意参数可自由增删改（每行一个参数或连续书写均可）。
        状态探测的端口跟随 <code class="font-mono">--port</code>；保存后重启生效。
      </p>
      <textarea
        v-model="argsText"
        spellcheck="false"
        rows="20"
        class="w-full font-mono text-xs leading-5 p-3 rounded-md border border-gh-border bg-[#161b22] text-[#e6edf3] focus:outline-none focus:ring-1 focus:ring-gh-green resize-y"
      ></textarea>
      <div v-if="config?.runningArgs" class="mt-2 text-xs text-gh-muted">
        <span class="font-medium"><i class="fa-solid fa-bolt-lightning mr-1"></i>当前运行参数：</span>
        <code class="font-mono break-all whitespace-pre-wrap">{{ config.runningArgs }}</code>
      </div>
      <p class="text-xs text-gh-muted mt-2"><i class="fa-solid fa-info-circle mr-1"></i>参数修改保存后需重启模型才生效。</p>
    </div>

    <div v-show="paramTab === 'logs'" class="panel-tech p-4">
      <div class="flex items-center gap-3 mb-3">
        <h2 class="font-semibold text-base"><i class="fa-solid fa-terminal mr-1.5 text-gh-cyan"></i>实时日志</h2>
        <label class="flex items-center gap-1.5 text-sm text-gh-muted">
          <input v-model="autoScroll" type="checkbox" class="accent-[#22d3ee]" /><i class="fa-solid fa-arrows-down-to-line mr-0.5"></i>自动滚动
        </label>
        <div class="relative ml-auto">
          <i class="fa-solid fa-search absolute left-2.5 top-1/2 -translate-y-1/2 text-xs text-gh-muted pointer-events-none"></i>
          <input
            v-model="filterText"
            type="text"
            placeholder="过滤（如 error / warn）"
            class="w-56 pl-8 pr-2 py-1 text-sm border border-gh-border rounded-md bg-[#0a111d] text-gh-text focus:outline-none focus:border-gh-cyan focus:ring-1 focus:ring-gh-cyan"
          />
        </div>
      </div>
      <div ref="logBox" class="h-96 overflow-auto rounded-md p-3 font-mono text-xs leading-5 bg-[#060a11] text-[#c9d1d9] border border-gh-border shadow-[inset_0_0_24px_rgba(0,0,0,0.5)]">
        <div v-if="filteredLogs.length === 0" class="text-[#8b949e]"><i class="fa-solid fa-satellite-dish mr-1.5"></i>等待日志输出…</div>
        <div v-for="(line, i) in filteredLogs" :key="i" :class="lineClass(line)">{{ line }}</div>
      </div>
    </div>
    </div>

    <!-- 快照 diff 弹窗 -->
    <div v-if="showDiff" class="fixed inset-0 z-[70] bg-black/60 flex items-center justify-center" @click.self="showDiff = false">
      <div class="modal-pop panel-tech w-[520px] max-w-[92vw] p-5">
        <h3 class="font-semibold text-base mb-3"><i class="fa-solid fa-camera-alt mr-1.5 text-gh-cyan"></i>服务器参数快照（已应用，{{ pendingDiff.length }} 项变更）</h3>
        <div class="max-h-80 overflow-auto border border-gh-border rounded-md">
          <table class="w-full text-sm">
            <thead class="bg-gh-tag sticky top-0">
              <tr class="text-left text-gh-muted">
                <th class="px-3 py-1.5 font-medium">参数</th>
                <th class="px-3 py-1.5 font-medium">原值</th>
                <th class="px-3 py-1.5 font-medium">服务器值</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="d in pendingDiff" :key="d.flag" class="border-t border-gh-border">
                <td class="px-3 py-1.5 font-mono text-xs">{{ d.flag }}</td>
                <td class="px-3 py-1.5 font-mono text-xs text-gh-muted">{{ d.old ?? '—' }}</td>
                <td class="px-3 py-1.5 font-mono text-xs">{{ d.new ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="mt-4 text-right">
          <button class="px-4 h-[30px] rounded-lg text-sm font-semibold border border-gh-green/70 bg-gh-green/10 text-gh-green hover:border-gh-green hover:bg-gh-green/20 active:scale-95" @click="closeDiff()">
            知道了
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
