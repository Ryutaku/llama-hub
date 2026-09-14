<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { api } from '../api'
import { toast } from '../ui'
import AppButton from './AppButton.vue'

const keys = ref([])
const loading = ref(true)
const error = ref('')

const showCreate = ref(false)
const showEdit = ref(false)
const showPlain = ref(false)
const plainKey = ref('')

const form = ref({ name: '', number: 30, unit: 'day', tokenQuota: '', requestQuota: '' })
const editForm = ref({ id: null, number: '', unit: 'day', tokenQuota: '', requestQuota: '' })
const busy = ref(false)

const UNITS = [
  { value: 'minute', label: '分钟' },
  { value: 'hour', label: '小时' },
  { value: 'day', label: '天' },
  { value: 'month', label: '月' },
  { value: 'year', label: '年' },
  { value: 'permanent', label: '永久' }
]

async function load() {
  try {
    keys.value = await api.keys()
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
  loading.value = false
}

function fmtTime(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 16)
}

function fmtExpiry(expiresAt) {
  if (!expiresAt) return '永久'
  const exp = fmtTime(expiresAt)
  const diff = new Date(String(expiresAt)).getTime() - Date.now()
  if (diff <= 0) return `已过期 · ${exp}`
  if (diff >= 86400000) return `${Math.floor(diff / 86400000)} 天 · ${exp}`
  if (diff >= 3600000) return `${Math.floor(diff / 3600000)} 小时 · ${exp}`
  return `${Math.max(0, Math.floor(diff / 60000))} 分钟 · ${exp}`
}

const statusInfo = computed(() => ({
  active: { label: 'active', cls: 'text-gh-green border-gh-green/50' },
  disabled: { label: 'disabled', cls: 'text-gh-muted border-gh-border' },
  expired: { label: 'expired', cls: 'text-gh-red border-gh-red/50' },
  exceeded: { label: 'exceeded', cls: 'text-gh-orange border-gh-orange/50' }
}))

function openCreate() {
  form.value = { name: '', number: 30, unit: 'day', tokenQuota: '', requestQuota: '' }
  showCreate.value = true
}

function openEdit(k) {
  editForm.value = {
    id: k.id,
    number: '',
    unit: 'day',
    tokenQuota: k.tokenQuota,
    requestQuota: k.requestQuota
  }
  showEdit.value = true
}

async function doCreate() {
  busy.value = true
  try {
    const body = {
      name: form.value.name,
      number: Number(form.value.number),
      unit: form.value.unit
    }
    if (form.value.tokenQuota !== '') body.tokenQuota = Number(form.value.tokenQuota)
    if (form.value.requestQuota !== '') body.requestQuota = Number(form.value.requestQuota)
    const r = await api.createKey(body)
    plainKey.value = r.key
    showCreate.value = false
    showPlain.value = true
    toast('Key 创建成功', 'success')
    load()
  } catch (e) {
    toast(e.message, 'error')
  }
  busy.value = false
}

async function doEdit() {
  busy.value = true
  try {
    const body = {}
    if (editForm.value.number !== '') body.number = Number(editForm.value.number)
    if (body.number !== undefined) body.unit = editForm.value.unit
    let changed = false
    if (editForm.value.tokenQuota !== '' && Number(editForm.value.tokenQuota) >= 0) {
      body.tokenQuota = Number(editForm.value.tokenQuota)
      changed = true
    }
    if (editForm.value.requestQuota !== '' && Number(editForm.value.requestQuota) >= 0) {
      body.requestQuota = Number(editForm.value.requestQuota)
      changed = true
    }
    if (body.number === undefined && !changed) {
      toast('没有需要修改的内容', 'info')
      return
    }
    await api.updateKey(editForm.value.id, body)
    showEdit.value = false
    toast('更新成功', 'success')
    load()
  } catch (e) {
    toast(e.message, 'error')
  }
  busy.value = false
}

async function toggleActive(k) {
  try {
    await api.updateKey(k.id, { isActive: !k.isActive })
    toast(k.isActive ? 'Key 已禁用' : 'Key 已启用', 'success')
    load()
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function doDelete(k) {
  if (!confirm(`确认删除 Key "${k.name}"？该操作不可恢复。`)) return
  try {
    await api.deleteKey(k.id)
    toast('已删除', 'success')
    load()
  } catch (e) {
    toast(e.message, 'error')
  }
}

function copyText(text) {
  if (navigator.clipboard) {
    navigator.clipboard.writeText(text).then(() => toast('已复制', 'success'), () => fallbackCopy(text))
  } else {
    fallbackCopy(text)
  }
}
function fallbackCopy(text) {
  const ta = document.createElement('textarea')
  ta.value = text
  document.body.appendChild(ta)
  ta.select()
  document.execCommand('copy')
  ta.remove()
  toast('已复制', 'success')
}

async function doReveal(k) {
  try {
    const r = await api.revealKey(k.id)
    copyText(r.key)
  } catch (e) {
    toast(e.message, 'error')
  }
}

const unitOpen = ref(null)
function unitLabel(u) {
  return UNITS.find(x => x.value === u)?.label || u
}
function toggleUnit(which) {
  unitOpen.value = unitOpen.value === which ? null : which
}
function selectUnit(which, u) {
  const f = which === 'create' ? form.value : editForm.value
  f.unit = u.value
  unitOpen.value = null
}
function onDocMousedown() {
  unitOpen.value = null
}
onMounted(() => {
  document.addEventListener('mousedown', onDocMousedown)
  load()
})
onUnmounted(() => document.removeEventListener('mousedown', onDocMousedown))

function pctText(cur, quota) {
  if (quota == null) return '不限'
  return `${cur}/${quota}`
}
function pctBar(cur, quota) {
  if (quota == null || quota <= 0) return 0
  return Math.min(100, Math.round((cur / quota) * 100))
}
function barColor(bar) {
  if (bar >= 90) return 'bg-gh-red'
  if (bar >= 70) return 'bg-gh-orange'
  return 'bg-gh-green'
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-3">
      <h2 class="text-base font-semibold">API Keys</h2>
      <AppButton variant="primary-green" @click="openCreate"
      ><i class="fa-solid fa-plus mr-1"></i>新建 Key</AppButton>
    </div>

    <p v-if="error" class="text-gh-red text-sm mb-4">{{ error }}</p>

    <div class="bg-gh-panel border border-gh-border rounded-lg overflow-hidden">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-gh-border text-left text-xs text-gh-muted">
            <th class="px-3 py-2">名称</th>
            <th class="px-3 py-2">Key</th>
            <th class="px-3 py-2">有效期</th>
            <th class="px-3 py-2">Tokens 用量</th>
            <th class="px-3 py-2">请求用量</th>
            <th class="px-3 py-2">状态</th>
            <th class="px-3 py-2">创建时间</th>
            <th class="px-3 py-2">最后使用</th>
            <th class="px-3 py-2 text-right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!loading && keys.length === 0">
            <td colspan="9" class="px-4 py-10 text-center text-gh-muted">暂无 API Key，点击右上角“新建 Key”创建</td>
          </tr>
          <tr v-for="k in keys" :key="k.id" class="border-b border-gh-border/50 hover:bg-gh-tag/40">
            <td class="px-3 py-2 font-medium">{{ k.name }}</td>
            <td class="px-3 py-2 font-mono text-xs text-gh-muted">{{ k.keyPrefix }}…</td>
            <td class="px-3 py-2 text-xs text-gh-muted">{{ fmtExpiry(k.expiresAt) }}</td>
            <td class="px-3 py-2 w-44">
              <div class="text-xs text-gh-muted mb-1 font-mono">{{ pctText(k.tokensUsed, k.tokenQuota) }}</div>
              <div v-if="k.tokenQuota != null" class="h-1.5 bg-gh-tag rounded-full overflow-hidden">
                <div class="h-full transition-all" :class="barColor(pctBar(k.tokensUsed, k.tokenQuota))"
                  :style="{ width: pctBar(k.tokensUsed, k.tokenQuota) + '%' }" />
              </div>
            </td>
            <td class="px-3 py-2 w-40">
              <div class="text-xs text-gh-muted mb-1 font-mono">{{ pctText(k.requestsUsed, k.requestQuota) }}</div>
              <div v-if="k.requestQuota != null" class="h-1.5 bg-gh-tag rounded-full overflow-hidden">
                <div class="h-full transition-all" :class="barColor(pctBar(k.requestsUsed, k.requestQuota))"
                  :style="{ width: pctBar(k.requestsUsed, k.requestQuota) + '%' }" />
              </div>
            </td>
            <td class="px-3 py-2">
              <span
                class="inline-block px-2 py-0.5 rounded-full text-xs border"
                :class="statusInfo[k.status]?.cls || statusInfo.active.cls"
              >{{ statusInfo[k.status]?.label || k.status }}</span>
            </td>
            <td class="px-3 py-2 text-xs text-gh-muted">{{ fmtTime(k.createdAt) }}</td>
            <td class="px-3 py-2 text-xs text-gh-muted">{{ fmtTime(k.lastUsedAt) }}</td>
            <td class="px-3 py-2 text-right whitespace-nowrap text-xs">
              <button class="btn-link px-1 py-0.5 text-gh-blue hover:underline mr-3 transition-all duration-150 active:scale-90 active:bg-gh-tag rounded" @click="openEdit(k)"><i class="fa-solid fa-pen mr-0.5"></i>编辑</button>
              <button class="btn-link px-1 py-0.5 text-gh-blue hover:underline mr-3 transition-all duration-150 active:scale-90 active:bg-gh-tag rounded" @click="doReveal(k)"><i class="fa-solid fa-copy mr-0.5"></i>复制</button>
              <button
                class="btn-link px-1 py-0.5 mr-3 hover:underline transition-all duration-150 active:scale-90 active:bg-gh-tag rounded"
                :class="k.isActive ? 'text-gh-orange' : 'text-gh-green'"
                @click="toggleActive(k)"
              ><i :class="k.isActive ? 'fa-solid fa-ban' : 'fa-solid fa-circle-check'" class="mr-0.5"></i>{{ k.isActive ? '禁用' : '启用' }}</button>
              <button class="btn-link px-1 py-0.5 text-gh-red hover:underline transition-all duration-150 active:scale-90 active:bg-gh-red/10 rounded" @click="doDelete(k)"><i class="fa-solid fa-trash-can mr-0.5"></i>删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- create modal -->
    <div v-if="showCreate" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50 px-4"
      @click.self="showCreate = false">
      <div class="modal-pop bg-gh-bg border border-gh-border rounded-lg w-full max-w-md p-4 shadow-xl">
        <h3 class="font-semibold text-base mb-3">新建 API Key</h3>
        <label class="block text-xs text-gh-muted mb-1">名称</label>
        <input v-model="form.name" placeholder="如：vscode-claude" class="input mb-3" />
        <label class="block text-xs text-gh-muted mb-1">有效期</label>
        <div class="flex gap-2 mb-3">
          <input v-if="form.unit !== 'permanent'" v-model="form.number" type="number" min="1" class="input w-24" />
            <div class="relative flex-1">
            <button type="button" class="input w-full flex justify-between items-center gap-2 whitespace-nowrap transition-all duration-150 active:scale-[0.98] active:border-gh-blue"
              @mousedown.stop @click="toggleUnit('create')">
              <span>{{ unitLabel(form.unit) }}</span>
              <i class="fa-solid fa-chevron-down text-[10px] text-gh-muted"></i>
            </button>
            <div v-if="unitOpen === 'create'"
              class="absolute right-0 top-full mt-1 w-full bg-white border border-gh-border rounded-md shadow-lg z-30 py-1 max-h-48 overflow-auto">
              <button type="button" v-for="u in UNITS" :key="u.value"
                class="block w-full text-left px-3 py-1.5 text-xs hover:bg-gh-tag transition-colors active:bg-gh-border/60 active:scale-[0.98]"
                :class="form.unit === u.value ? 'bg-gh-tag font-medium' : ''"
                @mousedown.stop @click="selectUnit('create', u)">{{ u.label }}</button>
            </div>
          </div>
        </div>
        <label class="block text-xs text-gh-muted mb-1">Token 配额（留空 = 不限）</label>
        <input v-model="form.tokenQuota" type="number" min="0" placeholder="如 1000000" class="input mb-3" />
        <label class="block text-xs text-gh-muted mb-1">请求次数配额（留空 = 不限）</label>
        <input v-model="form.requestQuota" type="number" min="0" placeholder="如 10000" class="input mb-3" />
        <div class="flex justify-end gap-1.5">
          <AppButton variant="secondary" @click="showCreate = false">取消</AppButton>
          <AppButton
            variant="primary-green"
            :loading="busy" :disabled="busy || !form.name.trim()" @click="doCreate">创建</AppButton>
        </div>
      </div>
    </div>

    <!-- edit modal -->
    <div v-if="showEdit" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50 px-4"
      @click.self="showEdit = false">
      <div class="modal-pop bg-gh-bg border border-gh-border rounded-lg w-full max-w-md p-4 shadow-xl">
        <h3 class="font-semibold text-base mb-3">编辑 API Key</h3>
        <label class="block text-xs text-gh-muted mb-1">修改有效期（留空 = 不变）</label>
        <div class="flex gap-2 mb-3">
          <input v-if="editForm.unit !== 'permanent'" v-model="editForm.number" type="number" min="1" placeholder="数量" class="input w-24" />
          <div class="relative flex-1">
            <button type="button" class="input w-full flex justify-between items-center gap-2 whitespace-nowrap transition-all duration-150 active:scale-[0.98] active:border-gh-blue"
              @mousedown.stop @click="toggleUnit('edit')">
              <span>{{ unitLabel(editForm.unit) }}</span>
              <i class="fa-solid fa-chevron-down text-[10px] text-gh-muted"></i>
            </button>
            <div v-if="unitOpen === 'edit'"
              class="absolute right-0 top-full mt-1 w-full bg-white border border-gh-border rounded-md shadow-lg z-30 py-1 max-h-48 overflow-auto">
              <button type="button" v-for="u in UNITS" :key="u.value"
                class="block w-full text-left px-3 py-1.5 text-xs hover:bg-gh-tag transition-colors active:bg-gh-border/60 active:scale-[0.98]"
                :class="editForm.unit === u.value ? 'bg-gh-tag font-medium' : ''"
                @mousedown.stop @click="selectUnit('edit', u)">{{ u.label }}</button>
            </div>
          </div>
        </div>
        <label class="block text-xs text-gh-muted mb-1">Token 配额（留空 = 不变）</label>
        <input v-model="editForm.tokenQuota" type="number" min="0" placeholder="当前值见表格"
          class="input mb-3" />
        <label class="block text-xs text-gh-muted mb-1">请求次数配额（留空 = 不变）</label>
        <input v-model="editForm.requestQuota" type="number" min="0" placeholder="当前值见表格"
          class="input mb-3" />
        <div class="flex justify-end gap-1.5">
          <AppButton variant="secondary" @click="showEdit = false">取消</AppButton>
          <AppButton
            variant="primary-blue"
            :loading="busy" :disabled="busy" @click="doEdit">保存</AppButton>
        </div>
      </div>
    </div>

    <!-- plain key modal -->
    <div v-if="showPlain" class="fixed inset-0 bg-black/40 flex items-center justify-center z-50 px-4"
      @click.self="showPlain = false">
      <div class="modal-pop bg-gh-bg border border-gh-border rounded-lg w-full max-w-md p-4 shadow-xl">
        <h3 class="font-semibold text-base mb-2">Key 已创建</h3>
        <p class="text-xs text-gh-muted mb-3">请复制保存。该 Key 已加密保存在服务器，之后可在列表点击"复制"再次查看明文。</p>
        <div class="bg-gh-panel border border-gh-border rounded-md p-3 font-mono text-sm mb-3 break-all">
          {{ plainKey }}
        </div>
        <div class="flex justify-end gap-1.5">
          <AppButton variant="secondary" @click="showPlain = false">关闭</AppButton>
          <AppButton variant="primary-green" @click="copyText(plainKey)">复制 Key</AppButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.input {
  width: 100%;
  padding: 4px 8px;
  font-size: 12px;
  line-height: 20px;
  background: #ffffff;
  border: 1px solid #d0d7de;
  border-radius: 6px;
  color: #24292f;
  outline: none;
}
.input:focus {
  border-color: #0969da;
}
</style>
