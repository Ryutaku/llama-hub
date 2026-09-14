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
    color: { type: String, default: '#0969da' }
  },
  setup(props) {
    const W = 320
    const H = 90
    const PAD = 6
    const points = () => {
      const arr = props.data || []
      const max = Math.max(1, ...arr.map(d => Number(d.value) || 0))
      if (arr.length === 0) return ''
      return arr
        .map((d, i) => {
          const x = PAD + (i * (W - PAD * 2)) / Math.max(arr.length - 1, 1)
          const y = H - PAD - ((Number(d.value) || 0) / max) * (H - PAD * 2)
          return `${x.toFixed(1)},${y.toFixed(1)}`
        })
        .join(' ')
    }
    return () =>
      h('svg', { viewBox: `0 0 ${W} ${H}`, preserveAspectRatio: 'none', class: 'w-full h-20' }, [
        h('polyline', {
          points: points(),
          fill: 'none',
          stroke: props.color,
          'stroke-width': 2
        })
      ])
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
      <div class="bg-gh-panel border border-gh-border rounded-lg p-3">
        <div class="text-xs text-gh-muted mb-1 flex items-center justify-between">
          今日请求数
          <i class="fa-solid fa-arrow-right-left text-gh-blue"></i>
        </div>
        <div class="text-xl font-mono font-bold">{{ fmtInt(data?.todayRequests) }}</div>
      </div>
      <div class="bg-gh-panel border border-gh-border rounded-lg p-3">
        <div class="text-xs text-gh-muted mb-1 flex items-center justify-between">
          今日 Tokens 消耗
          <i class="fa-solid fa-coins text-gh-green"></i>
        </div>
        <div class="text-xl font-mono font-bold">{{ fmtTok(data?.todayTokens) }}</div>
      </div>
      <div class="bg-gh-panel border border-gh-border rounded-lg p-3">
        <div class="text-xs text-gh-muted mb-1 flex items-center justify-between">
          今日缓存命中率
          <i class="fa-solid fa-bolt text-gh-orange"></i>
        </div>
        <div class="text-xl font-mono font-bold">{{ fmtPct(data?.todayCacheHitRate) }}</div>
      </div>
      <div class="bg-gh-panel border border-gh-border rounded-lg p-3">
        <div class="text-xs text-gh-muted mb-1 flex items-center justify-between">
          活跃 Key 数
          <i class="fa-solid fa-key text-gh-muted"></i>
        </div>
        <div class="text-xl font-mono font-bold">{{ fmtInt(data?.activeKeys) }}</div>
      </div>
    </div>

    <!-- trend charts -->
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-3 mb-4">
      <div class="bg-gh-panel border border-gh-border rounded-lg p-3">
        <div class="text-sm text-gh-muted mb-2">近 7 天请求量</div>
        <LineChart :data="(data?.trend || []).map(d => ({ value: d.count }))" color="#0969da" />
        <div class="flex justify-between text-[10px] text-gh-muted mt-1 font-mono">
          <span v-for="d in data?.trend || []" :key="d.date">{{ d.date }}</span>
        </div>
      </div>
      <div class="bg-gh-panel border border-gh-border rounded-lg p-3">
        <div class="text-sm text-gh-muted mb-2">近 7 天 Tokens 消耗</div>
        <LineChart :data="(data?.trend || []).map(d => ({ value: d.tokens }))" color="#1f883d" />
        <div class="flex justify-between text-[10px] text-gh-muted mt-1 font-mono">
          <span v-for="d in data?.trend || []" :key="d.date">{{ d.date }}</span>
        </div>
      </div>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-2 gap-3">
      <!-- top keys -->
      <div class="bg-gh-panel border border-gh-border rounded-lg p-3">
        <div class="text-sm text-gh-muted mb-2">今日 Top 5 活跃 Key</div>
        <div v-if="!data?.topKeys || data.topKeys.length === 0" class="text-gh-muted text-sm py-6 text-center">
          今日暂无调用
        </div>
        <div v-else class="space-y-2.5">
          <div v-for="(k, i) in data.topKeys" :key="k.keyId">
            <div class="flex justify-between text-sm mb-1">
              <div class="flex items-center gap-2">
                <span class="font-mono text-gh-muted">{{ i + 1 }}</span>
                <span class="truncate">{{ k.name }}</span>
              </div>
              <span class="text-xs text-gh-muted font-mono whitespace-nowrap">
                {{ k.count }} 次 · {{ fmtTok(k.tokens) }} tok
              </span>
            </div>
            <div class="h-1.5 bg-gh-tag rounded-full overflow-hidden">
              <div class="h-full bg-gh-blue rounded-full" :style="{ width: barWidth(k.count / topMax) + '%' }" />
            </div>
          </div>
        </div>
      </div>

      <!-- upstream status -->
      <div class="bg-gh-panel border border-gh-border rounded-lg p-3">
        <div class="text-sm text-gh-muted mb-2">上游状态（llama-server）</div>
        <div class="flex items-center gap-3">
          <span
            class="w-3.5 h-3.5 rounded-full"
            :class="props.upstream?.up ? 'bg-gh-green' : 'bg-gh-red'"
          />
          <div>
            <div class="text-sm">
              {{ props.upstream?.up ? '正常' : '不可达' }}
            </div>
            <div class="text-xs text-gh-muted font-mono">
              {{ props.upstream?.latencyMs != null && props.upstream.latencyMs >= 0
                ? `延迟 ${props.upstream.latencyMs} ms`
                : '最近探测失败' }}
            </div>
          </div>
          <div v-if="data?.upstream?.lastCheckedAt" class="ml-auto text-xs text-gh-muted">
            每 30s 自动探测
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
