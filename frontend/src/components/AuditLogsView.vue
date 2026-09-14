<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import DatePicker from './DatePicker.vue'
import AppButton from './AppButton.vue'
import Pagination from './Pagination.vue'

function today() {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

const startAt = ref(today())
const endAt = ref(today())
const logs = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(50)
const loading = ref(false)
const error = ref('')

const ACTIONS = {
  LOGIN_OK: { label: '登录成功', cls: 'text-gh-green border-gh-green/50' },
  LOGIN_FAIL: { label: '登录失败', cls: 'text-gh-red border-gh-red/50' },
  LOGOUT: { label: '登出', cls: 'text-gh-muted border-gh-border' },
  PASSWORD_CHANGE: { label: '修改密码', cls: 'text-gh-orange border-gh-orange/50' },
  KEY_CREATE: { label: '创建 Key', cls: 'text-gh-blue border-gh-blue/50' },
  KEY_UPDATE: { label: '更新 Key', cls: 'text-gh-blue border-gh-blue/50' },
  KEY_DELETE: { label: '删除 Key', cls: 'text-gh-red border-gh-red/50' },
  KEY_REVEAL: { label: '查看 Key', cls: 'text-gh-blue border-gh-blue/50' },
  CONFIG_CHANGE: { label: '配置变更', cls: 'text-gh-orange border-gh-orange/50' }
}

async function load() {
  loading.value = true
  try {
    const r = await api.auditLogs({
      page: page.value,
      size: size.value,
      startAt: startAt.value,
      endAt: endAt.value
    })
    logs.value = r.content
    total.value = r.total
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
  loading.value = false
}
onMounted(load)

function search() {
  page.value = 1
  load()
}

function fmtTime(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 19)
}

function actionInfo(a) {
  return ACTIONS[a] || { label: a, cls: 'text-gh-muted border-gh-border' }
}
</script>

<template>
  <div>
    <h2 class="text-base font-semibold mb-3"><i class="fa-solid fa-shield-halved mr-1.5 text-gh-blue"></i>审计日志</h2>

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
      ><i v-if="!loading" class="fa-solid fa-magnifying-glass mr-1"></i>查询</AppButton>
    </div>

    <p v-if="error" class="text-gh-red text-sm mb-3">{{ error }}</p>

    <div class="bg-gh-panel border border-gh-border rounded-lg overflow-hidden">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-gh-border text-left text-xs text-gh-muted">
            <th class="px-3 py-2">时间</th>
            <th class="px-3 py-2">用户</th>
            <th class="px-3 py-2">动作</th>
            <th class="px-3 py-2">目标</th>
            <th class="px-3 py-2">详情</th>
            <th class="px-3 py-2">来源 IP</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!loading && logs.length === 0">
            <td colspan="6" class="px-4 py-10 text-center text-gh-muted">暂无记录</td>
          </tr>
          <tr v-for="l in logs" :key="l.id" class="border-b border-gh-border/50 hover:bg-gh-tag/40">
            <td class="px-3 py-1.5 text-xs text-gh-muted font-mono whitespace-nowrap">{{ fmtTime(l.createdAt) }}</td>
            <td class="px-3 py-1.5 text-xs">{{ l.username }}</td>
            <td class="px-3 py-1.5">
              <span
                class="inline-block px-2 py-0.5 rounded-full text-xs border"
                :class="actionInfo(l.action).cls"
              >{{ actionInfo(l.action).label }}</span>
            </td>
            <td class="px-3 py-1.5 text-xs text-gh-muted">{{ l.target || '—' }}</td>
            <td class="px-3 py-1.5 text-xs text-gh-muted break-all">{{ l.detail || '—' }}</td>
            <td class="px-3 py-1.5 text-xs font-mono text-gh-muted">{{ l.ip || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <Pagination v-model:page="page" v-model:size="size" :total="total" @change="load" />
  </div>
</template>
