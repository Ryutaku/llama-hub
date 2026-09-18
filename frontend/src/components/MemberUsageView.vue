<script setup>
import { ref, onMounted, computed } from 'vue'
import { api } from '../api'
import { fmtTok } from '../ui'
import DatePicker from './DatePicker.vue'
import AppButton from './AppButton.vue'

function isoDaysAgo(n) {
  const d = new Date()
  d.setDate(d.getDate() - n)
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

const startAt = ref(isoDaysAgo(6))
const endAt = ref(isoDaysAgo(0))
const result = ref(null)
const loading = ref(false)
const error = ref('')

const total = computed(() => result.value?.total || null)
const grandTokens = computed(() => Number(result.value?.total?.totalTokens) || 0)

// 成员 = API Key（Key 名称即成员名），按总 Token 降序生成排名
const ranked = computed(() => {
  const items = [...(result.value?.items || [])]
  items.sort((a, b) => Number(b.totalTokens) - Number(a.totalTokens))
  return items.map((i, idx) => ({
    ...i,
    rank: idx + 1,
    share: grandTokens.value > 0 ? Number(i.totalTokens) * 100 / grandTokens.value : 0
  }))
})

async function search() {
  loading.value = true
  error.value = ''
  try {
    result.value = await api.usageStats({
      startAt: startAt.value,
      endAt: endAt.value
    })
  } catch (e) {
    error.value = e.message
  }
  loading.value = false
}

onMounted(search)

function fmtInt(n) {
  return n == null ? '—' : Number(n).toLocaleString()
}
function fmtPct(v) {
  return v == null ? '—' : `${v}%`
}
function fmtShare(v) {
  return `${Number(v).toFixed(1)}%`
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-3">
      <h2 class="text-base font-semibold"><i class="fa-solid fa-users mr-1.5 text-gh-blue"></i>成员用量统计</h2>
      <span class="text-xs text-gh-muted">成员即 API Key，按所选区间内总 Token 消耗排名</span>
    </div>

    <!-- filters -->
    <div class="flex flex-wrap items-end gap-2.5 mb-3">
      <div>
        <label class="block text-[11px] text-gh-muted mb-0.5">开始日期</label>
        <DatePicker v-model="startAt" />
      </div>
      <div>
        <label class="block text-[11px] text-gh-muted mb-0.5">结束日期</label>
        <DatePicker v-model="endAt" />
      </div>
      <AppButton
        variant="primary-green"
        :loading="loading" @click="search"
      ><i v-if="!loading" class="fa-solid fa-search mr-1"></i>查询</AppButton>
    </div>

    <p v-if="error" class="text-gh-red text-sm mb-3">{{ error }}</p>

    <div v-if="result" class="panel-tech overflow-x-auto">
      <table class="w-full min-w-[960px] text-sm">
        <thead>
          <tr class="border-b border-gh-border bg-gh-tag/60 text-left text-xs text-gh-muted">
            <th class="px-3 py-2">排名</th>
            <th class="px-3 py-2">成员名</th>
            <th class="px-3 py-2 text-right">请求次数</th>
            <th class="px-3 py-2 text-right">总 Token</th>
            <th class="px-3 py-2 text-right">输入 Token</th>
            <th class="px-3 py-2 text-right">输出 Token</th>
            <th class="px-3 py-2 text-right">缓存 Token</th>
            <th class="px-3 py-2 text-right">命中率</th>
            <th class="px-3 py-2 w-48">占比</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="ranked.length === 0">
            <td colspan="9" class="px-4 py-10 text-center text-gh-muted">暂无数据</td>
          </tr>
          <tr v-for="i in ranked" :key="i.keyId" class="border-b border-gh-border/40 hover:bg-gh-cyan/5 transition-colors">
            <td class="px-3 py-1.5 font-mono text-xs" :class="i.rank <= 3 ? 'text-gh-orange font-bold' : 'text-gh-muted'">{{ i.rank }}</td>
            <td class="px-3 py-1.5 text-xs font-medium">{{ i.name }}</td>
            <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtInt(i.count) }}</td>
            <td class="px-3 py-1.5 text-right font-mono text-xs font-semibold">{{ fmtTok(i.totalTokens) }}</td>
            <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtTok(i.promptTokens) }}</td>
            <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtTok(i.completionTokens) }}</td>
            <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtTok(i.cachedTokens) }}</td>
            <td class="px-3 py-1.5 text-right font-mono text-xs">{{ fmtPct(i.cacheHitRate) }}</td>
            <td class="px-3 py-1.5">
              <div class="flex items-center gap-2">
                <div class="flex-1 h-2 rounded-full bg-gh-border/40 overflow-hidden">
                  <div
                    class="h-full rounded-full bg-gradient-to-r from-gh-blue to-gh-cyan transition-all duration-300"
                    :style="{ width: `${Math.max(1.5, i.share)}%` }"
                  />
                </div>
                <span class="w-12 text-right font-mono text-[11px] text-gh-muted">{{ fmtShare(i.share) }}</span>
              </div>
            </td>
          </tr>
          <!-- 合计 -->
          <tr v-if="ranked.length > 0" class="bg-gh-cyan/[0.06] font-medium border-t border-gh-cyan/20">
            <td class="px-3 py-2 text-xs" />
            <td class="px-3 py-2 text-xs">合计</td>
            <td class="px-3 py-2 text-right font-mono text-xs">{{ fmtInt(total.count) }}</td>
            <td class="px-3 py-2 text-right font-mono text-xs font-semibold">{{ fmtTok(total.totalTokens) }}</td>
            <td class="px-3 py-2 text-right font-mono text-xs">{{ fmtTok(total.promptTokens) }}</td>
            <td class="px-3 py-2 text-right font-mono text-xs">{{ fmtTok(total.completionTokens) }}</td>
            <td class="px-3 py-2 text-right font-mono text-xs">{{ fmtTok(total.cachedTokens) }}</td>
            <td class="px-3 py-2 text-right font-mono text-xs">{{ fmtPct(total.cacheHitRate) }}</td>
            <td class="px-3 py-2 text-right font-mono text-[11px] text-gh-muted">100%</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>