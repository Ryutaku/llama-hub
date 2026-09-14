import { reactive } from 'vue'

export const ui = reactive({
  toastMsg: '',
  toastType: 'info',
  showToast: false
})

export function toast(msg, type = 'info') {
  ui.toastMsg = msg
  ui.toastType = type
  ui.showToast = true
  clearTimeout(toast._timer)
  toast._timer = setTimeout(() => {
    ui.showToast = false
  }, 3200)
}

export function fmtTok(n) {
  if (n == null) return '—'
  const v = Number(n)
  if (v >= 1000000) {
    const x = v / 1000000
    return (x >= 100 ? Math.round(x) : x.toFixed(1).replace(/\.0$/, '')) + 'M'
  }
  if (v >= 1000) {
    const x = v / 1000
    return (x >= 100 ? Math.round(x) : x.toFixed(1).replace(/\.0$/, '')) + 'k'
  }
  return String(v)
}

export function fmtDuration(v) {
  if (v == null) return '—'
  const n = Number(v)
  if (n >= 60000) return (n / 60000).toFixed(1).replace(/\.0$/, '') + ' 分钟'
  if (n >= 1000) return (n / 1000).toFixed(1).replace(/\.0$/, '') + 's'
  return Math.round(n) + 'ms'
}
