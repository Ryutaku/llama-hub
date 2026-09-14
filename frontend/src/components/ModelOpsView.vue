<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { api } from '../api'
import { toast } from '../ui'

const status = ref(null)
const config = ref(null)
const argsText = ref('')
const envText = ref('')
const runtimeFacts = ref([])
const busy = ref('')
const logs = ref([])
const autoScroll = ref(true)
const filterText = ref('')
const showDiff = ref(false)
const pendingDiff = ref([])
const logBox = ref(null)
const paramTab = ref('args')
const unreadLogs = ref(0)
const presets = ref([])
const presetModal = ref({ show: false, mode: '', id: null, name: '', note: '', diff: [], viewArgs: '', viewEnv: '', createdAt: '', isCurrent: false, isRunning: false })

const canStartModel = computed(() => !['RUNNING', 'STARTING'].includes(status.value?.state))

let eventsEs = null
let logsEs = null
let tickTimer = null
const MAX_LOGS = 2000

const nowTs = ref(Date.now())
const statusTs = ref(0)

const uptimeNow = computed(() => {
  const s = status.value
  if (!s || s.uptimeSec == null) return null
  return s.uptimeSec + Math.max(0, Math.floor((nowTs.value - statusTs.value) / 1000))
})

const STATE_META = {
  RUNNING: { label: '运行中', cls: 'bg-gh-green text-[#04121a] shadow-[0_0_16px_-2px_rgba(25,181,132,0.8)]', icon: 'fa-solid fa-play-circle' },
  STARTING: { label: '加载中', cls: 'bg-gh-blue text-white shadow-[0_0_16px_-2px_rgba(61,139,253,0.8)]', icon: 'fa-solid fa-spinner fa-spin' },
  STOPPING: { label: '停止中', cls: 'bg-gh-orange text-[#1a1204] shadow-[0_0_16px_-2px_rgba(245,166,35,0.7)]', icon: 'fa-solid fa-spinner fa-spin' },
  STOPPED: { label: '已停止', cls: 'bg-gh-tag text-gh-muted border border-gh-border', icon: 'fa-solid fa-stop-circle' },
  ERROR: { label: '错误', cls: 'bg-gh-red text-white shadow-[0_0_16px_-2px_rgba(240,69,92,0.8)]', icon: 'fa-solid fa-exclamation-circle' },
  UNKNOWN: { label: '未知', cls: 'bg-[#33415c] text-gh-text', icon: 'fa-solid fa-question-circle' }
}

const SOURCE_LABEL = { default: '内置默认', edited: '手工编辑', snapshot: '服务器快照', preset: '版本应用' }
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
    statusTs.value = Date.now()
  } catch (e) {
    /* ignore */
  }
}

async function loadConfig() {
  try {
    const c = await api.modelConfig()
    config.value = c
    argsText.value = formatArgsMultiline(c.args)
    envText.value = formatEnvMultiline(c.env)
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

function formatEnvMultiline(env) {
  return (env || '').trim().split(/\s+/).filter(Boolean).join('\n')
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
    runtimeFacts.value = r.runtimeFacts || []
    if (!r.diff || r.diff.length === 0) {
      toast('快照完成：服务器参数与环境变量与下次启动配置一致', 'success')
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

async function loadPresets() {
  try {
    presets.value = await api.modelPresets()
  } catch (e) {
    /* ignore */
  }
}

function openPresetModal(mode, p) {
  presetModal.value = {
    show: true,
    mode,
    id: p ? p.id : null,
    name: p ? p.name : '',
    note: p ? (p.note || '') : '',
    diff: [],
    viewArgs: mode === 'view' && p ? formatArgsMultiline(p.args) : '',
    viewEnv: mode === 'view' && p ? formatEnvMultiline(p.env) : '',
    createdAt: p ? (p.createdAt || '') : '',
    isCurrent: !!(p && p.isCurrent),
    isRunning: !!(p && p.isRunning)
  }
  if (mode === 'apply' || mode === 'start') {
    api.modelPresetDiff(p.id).then(r => {
      presetModal.value.diff = r || []
    }).catch(e => toast(e.message || '读取差异失败', 'error'))
  }
}

async function submitPresetModal() {
  const m = presetModal.value
  if (!m.name.trim()) {
    toast('请填写版本名', 'error')
    return
  }
  busy.value = 'preset'
  try {
    if (m.mode === 'save') {
      await api.modelPresetSave(m.name.trim(), m.note.trim(), argsText.value, envText.value)
      toast(`版本「${m.name.trim()}」已保存`, 'success')
    } else if (m.mode === 'rename') {
      await api.modelPresetUpdate(m.id, m.name.trim(), m.note.trim())
      toast('版本信息已更新', 'success')
    } else if (m.mode === 'apply') {
      const r = await api.modelPresetApply(m.id)
      toast(`版本「${m.name}」已应用（${r.diff.length} 项变更），重启后生效`, 'success')
      await loadConfig()
    } else if (m.mode === 'start') {
      await api.modelPresetStart(m.id)
      toast(`已按版本「${m.name}」启动，模型加载中…`, 'info')
      loadStatus()
    } else if (m.mode === 'delete') {
      await api.modelPresetDelete(m.id)
      toast(`版本「${m.name}」已删除`, 'success')
    }
    m.show = false
    loadPresets()
  } catch (e) {
    toast(e.message || '操作失败', 'error')
  } finally {
    busy.value = ''
  }
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
      statusTs.value = Date.now()
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
  loadPresets()
  connectEvents()
  connectLogs()
  tickTimer = setInterval(() => { nowTs.value = Date.now() }, 1000)
})
onUnmounted(() => {
  if (eventsEs) eventsEs.close()
  if (logsEs) logsEs.close()
  if (tickTimer) clearInterval(tickTimer)
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
        <div class="text-sm text-gh-muted flex flex-wrap items-center gap-x-4 gap-y-1">
          <span v-if="status?.pid != null"><i class="fa-solid fa-hashtag mr-1"></i>PID {{ status.pid }}</span>
          <span v-if="uptimeNow != null"><i class="fa-solid fa-stopwatch mr-1"></i>运行 {{ fmtUptime(uptimeNow) }}</span>
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
          :class="paramTab === 'presets' ? 'border-gh-cyan text-gh-cyan font-semibold' : 'border-transparent text-gh-muted hover:text-gh-text'"
          @click="paramTab = 'presets'"
        ><i class="fa-solid fa-bookmark mr-1.5"></i>参数版本</button>
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
          title="服务器运行进程的参数/环境变量与下次启动配置不一致，可用「从服务器快照」覆盖"
        ><i class="fa-solid fa-exclamation mr-1"></i>运行参数/环境变量与配置不一致</span>
        <div class="ml-auto flex items-center gap-2">
          <button
            class="px-3 h-[30px] rounded-lg text-sm font-semibold border border-gh-border bg-[#0a111d] text-gh-text hover:border-gh-cyan/50 hover:text-gh-cyan transition-all duration-200 active:scale-95 disabled:opacity-40 disabled:cursor-not-allowed"
            :disabled="!!busy"
            @click="doSnapshot"
          ><i class="fa-solid fa-camera-alt mr-1.5"></i>从服务器快照</button>
          <button
            class="px-3 h-[30px] rounded-lg text-sm font-semibold border border-gh-green/70 bg-gh-green/10 text-gh-green hover:border-gh-green hover:bg-gh-green/20 transition-all duration-200 active:scale-95 disabled:opacity-40 disabled:cursor-not-allowed"
            :disabled="!!busy"
            title="把当前文本框中的启动参数与环境变量存为参数版本，留档备用"
            @click="openPresetModal('save')"
          ><i class="fa-solid fa-bookmark mr-1.5"></i>保存为版本</button>
        </div>
      </div>
      <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <div>
          <p class="text-xs text-gh-muted mb-2">
            <i class="fa-solid fa-sliders-h mr-1 text-gh-cyan"></i>启动参数行（不含二进制路径），任意参数可自由增删改（每行一个参数或连续书写均可）。
            状态探测的端口跟随 <code class="font-mono">--port</code>；保存为版本并应用后重启生效。
          </p>
          <textarea
            v-model="argsText"
            spellcheck="false"
            rows="18"
            class="glow-input w-full font-mono text-xs leading-5 p-3 rounded-md bg-[#161b22] text-[#e6edf3] resize-y"
          ></textarea>
        </div>
        <div>
          <p class="text-xs text-gh-muted mb-2">
            <i class="fa-solid fa-envelope-open-text mr-1 text-gh-cyan"></i>环境变量（每行一个 K=V）
            仅纳管白名单前缀：CUDA_VISIBLE_DEVICES / NVIDIA_* / GGML_* / LLAMA_* / OMP_* / MKL_*。
            网关启动时渲染为脚本 export 行；CUDA_VISIBLE_DEVICES 决定可用显卡，未配置时回退系统默认。
          </p>
          <textarea
            v-model="envText"
            spellcheck="false"
            rows="18"
            placeholder="CUDA_VISIBLE_DEVICES=0,1,2"
            class="glow-input w-full font-mono text-xs leading-5 p-3 rounded-md bg-[#161b22] text-[#e6edf3] resize-y"
          ></textarea>
        </div>
      </div>

      <div v-if="config?.runningArgs" class="mt-2 text-xs text-gh-muted">
        <span class="font-medium"><i class="fa-solid fa-bolt-lightning mr-1"></i>当前运行参数：</span>
        <code class="font-mono break-all whitespace-pre-wrap">{{ config.runningArgs }}</code>
      </div>
      <div v-if="config?.runningEnv" class="mt-1 text-xs text-gh-muted">
        <span class="font-medium"><i class="fa-solid fa-bolt-lightning mr-1"></i>当前运行环境变量：</span>
        <code class="font-mono break-all whitespace-pre-wrap">{{ config.runningEnv }}</code>
      </div>
      <div v-if="runtimeFacts.length" class="mt-3">
        <p class="text-xs font-medium text-gh-muted mb-1"><i class="fa-solid fa-magnifying-glass-chart mr-1"></i>运行事实（只读，取自最近一次启动日志，点「从服务器快照」刷新）</p>
        <div class="rounded-md border border-gh-border bg-[#060a11] p-2 font-mono text-xs text-[#c9d1d9] max-h-40 overflow-auto">
          <div v-for="(f, i) in runtimeFacts" :key="i" class="whitespace-pre-wrap break-all">{{ f }}</div>
        </div>
      </div>
      <p class="text-xs text-gh-muted mt-2"><i class="fa-solid fa-info-circle mr-1"></i>参数与环境变量需保存为版本、应用后重启模型才生效。</p>

    </div>

    <div v-show="paramTab === 'presets'" class="panel-tech p-4">
      <div class="flex items-center gap-3 mb-3 flex-wrap">
        <h2 class="font-semibold text-base"><i class="fa-solid fa-bookmark mr-1.5 text-gh-cyan"></i>参数版本</h2>
        <span class="text-xs text-gh-muted">满意的一组参数存为版本，随时应用或直接按版本启动。「下次启动配置」= 下次启动模型将使用的参数；「运行中」= 服务器当前进程正在使用的参数</span>
      </div>
      <div v-if="presets.length === 0" class="py-4 text-center text-xs text-gh-muted border border-dashed border-gh-border rounded-md">
        还没有版本。在「启动参数」页编辑满意后，点「保存为版本」留档。
      </div>
        <div v-else class="space-y-2">
          <div v-for="p in presets" :key="p.id" class="border border-gh-border rounded-md bg-[#0a111d] px-3 py-2">
            <div class="flex items-center gap-2 flex-wrap">
              <span class="font-semibold text-sm">{{ p.name }}</span>
              <span v-if="p.isCurrent" class="px-1.5 py-0.5 rounded-full text-[11px] font-semibold bg-gh-green/10 text-gh-green border border-gh-green/40 cursor-help" title="保存在 model_config 中：下次启动模型将使用这组参数与环境变量">下次启动配置</span>
              <span v-if="p.isRunning" class="px-1.5 py-0.5 rounded-full text-[11px] font-semibold bg-gh-cyan/10 text-gh-cyan border border-gh-cyan/40 cursor-help" title="服务器当前运行中的模型进程，其参数/环境变量与该版本一致">运行中</span>
              <span class="text-xs text-gh-muted font-mono ml-auto">{{ (p.createdAt || '').replace('T', ' ') }}</span>
              <div class="flex items-center gap-1.5">
              <button
                class="px-2.5 h-[26px] rounded-md text-xs font-semibold border border-gh-border bg-[#0e1624] text-gh-text hover:border-gh-cyan/50 hover:text-gh-cyan transition-all active:scale-95"
                title="查看该版本的完整参数行与环境变量"
                @click="openPresetModal('view', p)"
              ><i class="fa-solid fa-eye mr-1"></i>查看</button>
               <button
                 class="px-2.5 h-[26px] rounded-md text-xs font-semibold border border-gh-border bg-[#0e1624] text-gh-text hover:border-gh-cyan/50 hover:text-gh-cyan transition-all active:scale-95 disabled:opacity-40 disabled:cursor-not-allowed"
                 :disabled="!!busy || p.isCurrent"
                 :title="p.isCurrent ? '与下次启动配置相同，无需应用' : '将该版本的完整参数行与环境变量行设为下次启动配置，不影响运行中的进程，重启模型后生效'"
                 @click="openPresetModal('apply', p)"
               ><i class="fa-solid fa-paper-plane mr-1"></i>应用</button>
              <button
                class="px-2.5 h-[26px] rounded-md text-xs font-semibold border border-gh-green/70 bg-gh-green/10 text-gh-green hover:bg-gh-green/20 transition-all active:scale-95 disabled:opacity-40 disabled:cursor-not-allowed"
                :disabled="!!busy || !canStartModel"
                :title="canStartModel ? '' : '模型运行中，需先停止'"
                @click="openPresetModal('start', p)"
              ><i class="fa-solid fa-play mr-1"></i>按此启动</button>
              <button
                class="px-2.5 h-[26px] rounded-md text-xs font-semibold border border-gh-border bg-[#0e1624] text-gh-muted hover:text-gh-text hover:border-gh-cyan/50 transition-all active:scale-95 disabled:opacity-40 disabled:cursor-not-allowed"
                :disabled="!!busy"
                @click="openPresetModal('rename', p)"
              ><i class="fa-solid fa-pen mr-1"></i>改名</button>
              <button
                class="px-2.5 h-[26px] rounded-md text-xs font-semibold border border-gh-red/70 bg-gh-red/10 text-gh-red hover:bg-gh-red/20 transition-all active:scale-95 disabled:opacity-40 disabled:cursor-not-allowed"
                :disabled="!!busy"
                @click="openPresetModal('delete', p)"
               ><i class="fa-solid fa-trash mr-1"></i>删除</button>
              </div>
            </div>
            <p v-if="p.note" class="text-xs text-gh-muted mt-1.5 break-all">{{ p.note }}</p>
          </div>
        </div>
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
                <th class="px-3 py-1.5 font-medium">类型</th>
                <th class="px-3 py-1.5 font-medium">参数</th>
                <th class="px-3 py-1.5 font-medium">原值</th>
                <th class="px-3 py-1.5 font-medium">服务器值</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(d, i) in pendingDiff" :key="i" class="border-t border-gh-border">
                <td class="px-3 py-1.5 text-xs text-gh-muted">{{ d.kind === 'env' ? '环境变量' : '启动参数' }}</td>
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

    <!-- 参数版本弹窗（保存 / 查看 / 改名 / 应用 / 按此启动 / 删除） -->
    <div v-if="presetModal.show" class="fixed inset-0 z-[70] bg-black/60 flex items-center justify-center" @click.self="presetModal.show = false">
      <div class="modal-pop panel-tech max-w-[92vw] p-5" :class="presetModal.mode === 'view' ? 'w-[640px]' : 'w-[520px]'">
        <h3 class="font-semibold text-base mb-3">
          <i class="fa-solid fa-bookmark mr-1.5 text-gh-cyan"></i>
          {{ { save: '保存为版本', view: `查看版本「${presetModal.name}」`, rename: '修改版本', apply: `应用版本「${presetModal.name}」`, start: `按版本「${presetModal.name}」启动`, delete: `删除版本「${presetModal.name}」` }[presetModal.mode] }}
        </h3>

        <template v-if="presetModal.mode === 'save' || presetModal.mode === 'rename'">
          <label class="block text-xs text-gh-muted mb-1">版本名</label>
          <input
            v-model="presetModal.name"
            type="text"
            maxlength="64"
            placeholder="如：ubatch2048+spec 稳定版"
            class="glow-input w-full h-[30px] px-2.5 text-sm rounded-md bg-[#0a111d] text-gh-text mb-2"
          />
          <label class="block text-xs text-gh-muted mb-1">备注（可选）</label>
          <textarea
            v-model="presetModal.note"
            rows="2"
            maxlength="500"
            placeholder="为什么满意这版：吞吐、质量、稳定性…"
            class="glow-input w-full px-2.5 py-1.5 text-sm rounded-md bg-[#0a111d] text-gh-text resize-y"
          ></textarea>
          <p v-if="presetModal.mode === 'save'" class="text-xs text-gh-muted mt-2">
            <i class="fa-solid fa-info-circle mr-1"></i>保存的是「启动参数」页文本框当前内容（启动参数 + 环境变量，含未保存的修改），需应用后重启生效。
          </p>
        </template>

        <template v-if="presetModal.mode === 'view'">
          <div class="flex items-center gap-2 mb-2 flex-wrap">
            <span class="text-xs text-gh-muted font-mono"><i class="fa-solid fa-clock mr-1"></i>创建 {{ (presetModal.createdAt || '').replace('T', ' ') }}</span>
            <span v-if="presetModal.isCurrent" class="px-1.5 py-0.5 rounded-full text-[11px] font-semibold bg-gh-green/10 text-gh-green border border-gh-green/40 cursor-help" title="保存在 model_config 中：下次启动模型将使用这组参数与环境变量">下次启动配置</span>
            <span v-if="presetModal.isRunning" class="px-1.5 py-0.5 rounded-full text-[11px] font-semibold bg-gh-cyan/10 text-gh-cyan border border-gh-cyan/40 cursor-help" title="服务器当前运行中的模型进程，其参数/环境变量与该版本一致">运行中</span>
          </div>
          <p v-if="presetModal.note" class="text-xs text-gh-muted mb-3">{{ presetModal.note }}</p>
          <label class="block text-xs text-gh-muted mb-1">启动参数（完整参数行）</label>
          <pre class="m-0 rounded-md border border-gh-border bg-[#060a11] p-2.5 font-mono text-xs leading-5 text-[#c9d1d9] whitespace-pre-wrap break-all max-h-56 overflow-auto">{{ presetModal.viewArgs }}</pre>
          <label class="block text-xs text-gh-muted mt-3 mb-1">环境变量</label>
          <pre v-if="presetModal.viewEnv" class="m-0 rounded-md border border-gh-border bg-[#060a11] p-2.5 font-mono text-xs leading-5 text-[#c9d1d9] whitespace-pre-wrap break-all">{{ presetModal.viewEnv }}</pre>
          <p v-else class="text-xs text-gh-muted">（无）</p>
        </template>

        <template v-if="presetModal.mode === 'apply' || presetModal.mode === 'start'">
          <p class="text-xs text-gh-muted mb-2">
            相对下次启动配置的变更（{{ presetModal.diff.length }} 项）：
          </p>
          <div v-if="presetModal.diff.length === 0" class="text-sm text-gh-green py-3 text-center border border-gh-border rounded-md">
            与下次启动配置一致
          </div>
          <div v-else class="max-h-60 overflow-auto border border-gh-border rounded-md">
            <table class="w-full text-sm">
              <thead class="bg-gh-tag sticky top-0">
                <tr class="text-left text-gh-muted">
                  <th class="px-3 py-1.5 font-medium">类型</th>
                  <th class="px-3 py-1.5 font-medium">参数</th>
                  <th class="px-3 py-1.5 font-medium">当前值</th>
                  <th class="px-3 py-1.5 font-medium">版本值</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(d, i) in presetModal.diff" :key="i" class="border-t border-gh-border">
                  <td class="px-3 py-1.5 text-xs text-gh-muted">{{ d.kind === 'env' ? '环境变量' : '启动参数' }}</td>
                  <td class="px-3 py-1.5 font-mono text-xs">{{ d.flag }}</td>
                  <td class="px-3 py-1.5 font-mono text-xs text-gh-muted">{{ d.old ?? '—' }}</td>
                  <td class="px-3 py-1.5 font-mono text-xs">{{ d.new ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-if="presetModal.mode === 'start'" class="text-xs text-gh-muted mt-2">
            <i class="fa-solid fa-info-circle mr-1"></i>将覆盖下次启动配置并立即启动模型（约需数分钟加载）。
          </p>
        </template>

        <p v-if="presetModal.mode === 'delete'" class="text-sm text-gh-red">
          <i class="fa-solid fa-triangle-exclamation mr-1.5"></i>删除后不可恢复，确定要删除该版本吗？
        </p>

        <div class="mt-4 text-right flex items-center justify-end gap-2">
          <button
            class="px-4 h-[30px] rounded-lg text-sm font-semibold border border-gh-border text-gh-muted hover:text-gh-text transition-all active:scale-95"
            @click="presetModal.show = false"
          >{{ presetModal.mode === 'view' ? '关闭' : '取消' }}</button>
          <button
            v-if="presetModal.mode === 'delete'"
            class="px-4 h-[30px] rounded-lg text-sm font-semibold border border-gh-red/70 bg-gh-red/10 text-gh-red hover:bg-gh-red/20 active:scale-95 disabled:opacity-40"
            :disabled="!!busy"
            @click="submitPresetModal"
          ><i class="fa-solid fa-trash mr-1.5"></i>删除</button>
          <button
            v-else-if="presetModal.mode !== 'view'"
            class="px-4 h-[30px] rounded-lg text-sm font-semibold border border-gh-green/70 bg-gh-green/10 text-gh-green hover:border-gh-green hover:bg-gh-green/20 active:scale-95 disabled:opacity-40"
            :disabled="!!busy"
            @click="submitPresetModal"
          ><i class="fa-solid fa-check mr-1.5"></i>{{ { save: '保存', rename: '保存', apply: '应用', start: '应用并启动' }[presetModal.mode] }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
