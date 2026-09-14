<script setup>
import { computed } from 'vue'

const props = defineProps({
  variant: { type: String, default: 'primary-green' },
  loading: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  block: { type: Boolean, default: false },
  type: { type: String, default: 'button' }
})

const emit = defineEmits(['click'])

const VARIANTS = {
  'primary-green': 'btn-solid bg-gh-green text-white hover:bg-green-600 font-medium',
  'primary-blue': 'btn-solid bg-gh-blue text-white hover:bg-gh-blue/85 font-medium',
  secondary: 'btn-outline border border-gh-border text-gh-muted hover:text-gh-text hover:bg-gh-tag',
  ghost: 'btn-ghost text-gh-muted hover:text-gh-text hover:bg-gh-tag',
  danger: 'btn-solid bg-gh-red text-white hover:bg-gh-red/85 font-medium',
  link: 'btn-link text-gh-blue hover:underline'
}

const cls = computed(() => [
  'btn-ripple inline-flex items-center justify-center gap-1 rounded-md text-sm px-3 py-1.5',
  'transition-all duration-150 select-none',
  'hover:-translate-y-px hover:shadow-sm',
  'active:translate-y-0 active:scale-95 active:shadow-none active:brightness-95',
  'disabled:opacity-50 disabled:pointer-events-none disabled:hover:translate-y-0 disabled:hover:shadow-none',
  VARIANTS[props.variant] || VARIANTS['primary-green'],
  props.block ? 'w-full' : '',
  props.loading ? 'btn-busy pointer-events-none' : '',
  props.disabled || props.loading ? 'cursor-not-allowed' : 'cursor-pointer'
])

function spawnRipple(e) {
  const btn = e.currentTarget
  if (!btn) return
  const rect = btn.getBoundingClientRect()
  const size = Math.max(rect.width, rect.height)
  const ink = document.createElement('span')
  ink.className = 'ripple-ink'
  ink.style.width = ink.style.height = size + 'px'
  // 实心按钮用白色波纹，描边/文字按钮用当前色
  const solid = (props.variant || '').startsWith('primary') || props.variant === 'danger'
  ink.style.background = solid ? '#ffffff' : 'currentColor'
  const x = (e.clientX ?? rect.left + rect.width / 2) - rect.left - size / 2
  const y = (e.clientY ?? rect.top + rect.height / 2) - rect.top - size / 2
  ink.style.left = x + 'px'
  ink.style.top = y + 'px'
  btn.appendChild(ink)
  setTimeout(() => ink.remove(), 500)
}

function onClick(e) {
  if (props.disabled || props.loading) {
    e.preventDefault()
    return
  }
  spawnRipple(e)
  emit('click', e)
}
</script>

<template>
  <button :type="type" :class="cls" :disabled="disabled || loading" @click="onClick">
    <i v-if="loading" class="fa-solid fa-spinner fa-spin"></i>
    <slot />
  </button>
</template>
