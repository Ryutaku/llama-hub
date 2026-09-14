let onUnauthorized = null

const BASE = '/llama-hub'

export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn
}

function qs(params = {}) {
  const parts = []
  for (const [k, v] of Object.entries(params)) {
    if (v !== null && v !== undefined && v !== '') {
      parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    }
  }
  return parts.join('&')
}

async function request(path, options = {}) {
  const res = await fetch(BASE + path, {
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options
  })
  if (res.status === 401) {
    if (onUnauthorized) onUnauthorized()
    throw new Error('未登录或会话已过期')
  }
  let data = null
  const ct = res.headers.get('content-type') || ''
  if (ct.includes('application/json')) {
    data = await res.json()
  } else {
    data = await res.text()
  }
  if (!res.ok) {
    let msg = '请求失败'
    if (data && typeof data === 'object') {
      msg = data.error
        ? (typeof data.error === 'object' ? (data.error.message || 'error') : data.error)
        : (data.message || msg)
    } else if (data) {
      msg = String(data).slice(0, 200)
    }
    throw new Error(msg)
  }
  return data
}

async function downloadCsv(path) {
  const res = await fetch(BASE + path, { credentials: 'same-origin' })
  if (res.status === 401) {
    if (onUnauthorized) onUnauthorized()
    throw new Error('未登录或会话已过期')
  }
  if (!res.ok) {
    let msg = '导出失败'
    try {
      const data = await res.json()
      msg = data.error || msg
    } catch (e) { /* ignore */ }
    throw new Error(msg)
  }
  const blob = await res.blob()
  const disposition = res.headers.get('content-disposition') || ''
  const match = disposition.match(/filename\*?=(?:UTF-8'')?"?([^\";]+)/i)
  const filename = match ? decodeURIComponent(match[1]) : 'call_logs.csv'
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

export const api = {
  login: (username, password) =>
    request('/api/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  logout: () => request('/api/logout', { method: 'POST' }),
  me: () => request('/api/me'),
  changePassword: (oldPassword, newPassword) =>
    request('/api/admin/password', {
      method: 'PUT',
      body: JSON.stringify({ oldPassword, newPassword })
    }),
  dashboard: () => request('/api/admin/dashboard'),
  usageStats: (params) => request(`/api/admin/stats/usage?${qs(params)}`),
  upstreamStatus: () => request('/api/admin/upstream/status'),
  keys: () => request('/api/admin/keys'),
  createKey: (data) => request('/api/admin/keys', { method: 'POST', body: JSON.stringify(data) }),
  updateKey: (id, data) =>
    request(`/api/admin/keys/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  deleteKey: (id) => request(`/api/admin/keys/${id}`, { method: 'DELETE' }),
  revealKey: (id) => request(`/api/admin/keys/${id}/reveal`, { method: 'POST' }),
  logs: (params) => request(`/api/admin/logs?${qs(params)}`),
  exportLogs: (params) => downloadCsv(`/api/admin/logs/export?${qs(params)}`),
  auditLogs: (params) => request(`/api/admin/audit-logs?${qs(params)}`)
}
