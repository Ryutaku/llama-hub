<script setup>
import { ref, computed, onMounted, defineComponent, h } from 'vue'
import { api } from '../api'
import { fmtTok } from '../ui'
import AppButton from './AppButton.vue'

const props = defineProps({
  upstream: { type: Object, default: () => ({ up: false, latencyMs: null }) },
  refresh: { type: Boolean, default: false }
})

const loading = ref(true)
const error = ref('')
const data = ref(null)

const topMax = computed(() => {
  const keys = data.value?.topKeys || []
  return Math.max(1, ...keys.map(k => Number(k.count) || 0))
})

async function load() {
  try {
    data.value = await api.dashboard()
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
  loading.value = false
}

onMounted(load)

function fmtInt(n) {
  return (n == null)
    ? '—'
    : Number(n).toLocaleString()
}

function fmtPct(v) {
  return v == null ? '—' : `${v}%`
}

function barWidth(v) {
  if (v == null || v <= 0) return 0
  return Math.min(100, Math.round(v * 100))
}

const LineChart = defineComponent({
  props: {
    data: { type: Array, default: () => [] },
    color: { type: String, default: '#22d3ee' },
    id: { type: String, default: 'chart' }
  },
  setup(props) {
    const W = 320
    const H = 90
    const PAD = 6
    function geom() {
      const arr = props.data || []
      if (arr.length === 0) return null
      const max = Math.max(1, ...arr.map(d => Number(d.value) || 0))
      const pts = arr.map((d, i) => {
        const x = PAD + (i * (W - PAD * 2)) / Math.max(arr.length - 1, 1)
        const y = H - PAD - ((Number(d.value) || 0) / max) * (H - PAD * 2)
        return [x, y]
      })
      const line = pts.map(p => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(' ')
      const area = `${PAD},${H - PAD} ${line} ${W - PAD},${H - PAD}`
      return { line, area, last: pts[pts.length - 1] }
    }
    return () => {
      const g = geom()
      if (!g) return h('div', { class: 'h-20' })
      const gid = 'lg-' + props.id
      return h('svg', { viewBox: `0 0 ${W} ${H}`, preserveAspectRatio: 'none', class: 'w-full h-20' }, [
        h('defs', {}, [
          h('linearGradient', { id: gid, x1: '0', y1: '0', x2: '0', y2: '1' }, [
            h('stop', { offset: '0%', 'stop-color': props.color, 'stop-opacity': '0.3' }),
            h('stop', { offset: '100%', 'stop-color': props.color, 'stop-opacity': '0' })
          ])
        ]),
        [0.25, 0.5, 0.75].map(f =>
          h('line', {
            x1: PAD,
            x2: W - PAD,
            y1: H * f,
            y2: H * f,
            stroke: 'rgba(126,144,169,0.16)',
            'stroke-width': '1',
            'stroke-dasharray': '3 5'
          })
        ),
        h('polygon', { points: g.area, fill: `url(#${gid})` }),
        h('polyline', {
          points: g.line,
          fill: 'none',
          stroke: props.color,
          'stroke-width': '2',
          'stroke-linejoin': 'round',
          'stroke-linecap': 'round',
          style: `filter: drop-shadow(0 0 5px ${props.color}88)`
        }),
        h('circle', {
          cx: g.last[0],
          cy: g.last[1],
          r: '2.5',
          fill: props.color,
          style: `filter: drop-shadow(0 0 6px ${props.color})`
        })
      ])
    }
  }
})
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-3">
      <h2 class="text-base font-semibold">用量仪表盘</h2>
      <AppButton variant="secondary" :loading="loading" @click="loading = true; load()"
      ><i class="fa-solid fa-rotate-right mr-1" :class="{ 'fa-spin': loading }"></i>刷新</AppButton>
    </div>

    <p v-if="error" class="text-gh-red text-sm mb-3">{{ error }}</p>

    <!-- stat cards -->
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-3 mb-4">
      <div class="panel-tech p-3.5">
        <div class="text-xs text-gh-muted mb-2 flex items-center justify-between">
          今日请求数
          <span class="chip chip-cyan"><i class="fa-solid fa-left-right"></i></span>
        </div>
        <div class="text-2xl font-mono font-bold num-glow">{{ fmtInt(data?.todayRequests) }}</div>
      </div>
      <div class="panel-tech p-3.5">
        <div class="text-xs text-gh-muted mb-2 flex items-center justify-between">
          今日 Tokens 消耗
          <span class="chip chip-green"><i class="fa-solid fa-coins"></i></span>
        </div>
        <div class="text-2xl font-mono font-bold num-glow">{{ fmtTok(data?.todayTokens) }}</div>
      </div>
      <div class="panel-tech p-3.5">
        <div class="text-xs text-gh-muted mb-2 flex items-center justify-between">
          今日缓存命中率
          <span class="chip chip-amber"><i class="fa-solid fa-bolt-lightning"></i></span>
        </div>
        <div class="text-2xl font-mono font-bold num-glow">{{ fmtPct(data?.todayCacheHitRate) }}</div>
      </div>
      <div class="panel-tech p-3.5">
        <div class="text-xs text-gh-muted mb-2 flex items-center justify-between">
          活跃 Key 数
          <span class="chip chip-slate"><i class="fa-solid fa-key"></i></span>
        </div>
        <div class="text-2xl font-mono font-bold num-glow">{{ fmtInt(data?.activeKeys) }}</div>
      </div>
    </div>

    <!-- trend charts -->
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-3 mb-4">
      <div class="panel-tech p-3.5">
        <div class="flex items-center gap-2 text-sm text-gh-muted mb-2">
          <span class="w-1 h-3.5 rounded-full bg-gh-cyan shadow-[0_0_8px_rgba(34,211,238,0.8)]"></span>
          近 7 天请求量
        </div>
        <LineChart id="req" :data="(data?.trend || []).map(d => ({ value: d.count }))" color="#22d3ee" />
        <div class="flex justify-between text-[10px] text-gh-muted mt-1.5 font-mono">
          <span v-for="d in data?.trend || []" :key="d.date">{{ d.date }}</span>
        </div>
      </div>
      <div class="panel-tech p-3.5">
        <div class="flex items-center gap-2 text-sm text-gh-muted mb-2">
          <span class="w-1 h-3.5 rounded-full bg-gh-green shadow-[0_0_8px_rgba(25,181,132,0.8)]"></span>
          近 7 天 Tokens 消耗
        </div>
        <LineChart id="tok" :data="(data?.trend || []).map(d => ({ value: d.tokens }))" color="#19b584" />
        <div class="flex justify-between text-[10px] text-gh-muted mt-1.5 font-mono">
          <span v-for="d in data?.trend || []" :key="d.date">{{ d.date }}</span>
        </div>
      </div>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-2 gap-3">
      <!-- top keys -->
      <div class="panel-tech p-3.5">
        <div class="text-sm text-gh-muted mb-3">今日 Top 5 活跃 Key</div>
        <div v-if="!data?.topKeys || data.topKeys.length === 0" class="text-gh-muted text-sm py-6 text-center">
          今日暂无调用
        </div>
        <div v-else class="space-y-3">
          <div v-for="(k, i) in data.topKeys" :key="k.keyId">
            <div class="flex justify-between text-sm mb-1.5">
              <div class="flex items-center gap-2">
                <span class="font-mono text-xs w-4 text-gh-cyan">{{ String(i + 1).padStart(2, '0') }}</span>
                <span class="truncate">{{ k.name }}</span>
              </div>
              <span class="text-xs text-gh-muted font-mono whitespace-nowrap">
                {{ k.count }} 次 · {{ fmtTok(k.tokens) }} tok
              </span>
            </div>
            <div class="h-1.5 bg-gh-tag rounded-full overflow-hidden">
              <div
                class="h-full rounded-full bg-gradient-to-r from-gh-cyan to-gh-green"
                :style="{ width: barWidth(k.count / topMax) + '%', boxShadow: '0 0 8px rgba(34,211,238,0.5)' }"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- upstream status -->
      <div class="panel-tech p-3.5">
        <div class="text-sm text-gh-muted mb-3">上游状态（llama-server）</div>
        <div class="flex items-center gap-3.5">
          <span
            class="w-3.5 h-3.5 rounded-full"
            :class="props.upstream?.up ? 'dot-live bg-gh-green' : 'bg-gh-red'"
            :style="{ color: props.upstream?.up ? '#19b584' : '#f0455c' }"
          />
          <div>
            <div class="text-sm font-semibold" :class="props.upstream?.up ? 'text-gh-green' : 'text-gh-red'">
              {{ props.upstream?.up ? '正常' : '不可达' }}
            </div>
            <div class="text-xs text-gh-muted font-mono mt-0.5">
              {{ props.upstream?.latencyMs != null && props.upstream.latencyMs >= 0
                ? `延迟 ${props.upstream.latencyMs} ms`
                : '最近探测失败' }}
            </div>
          </div>
          <div v-if="data?.upstream?.lastCheckedAt" class="ml-auto text-xs text-gh-muted font-mono">
            每 30s 自动探测
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
