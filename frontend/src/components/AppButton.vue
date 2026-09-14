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
  'primary-green':
    'btn-outline border font-medium border-gh-green/70 bg-gh-green/10 text-gh-green hover:border-gh-green hover:bg-gh-green/20',
  'primary-blue':
    'btn-outline border font-medium border-gh-blue/70 bg-gh-blue/10 text-gh-blue hover:border-gh-blue hover:bg-gh-blue/20',
  secondary:
    'btn-outline border border-gh-border text-gh-muted hover:text-gh-text hover:border-gh-cyan/50 hover:bg-gh-cyan/5',
  ghost: 'btn-ghost text-gh-muted hover:text-gh-text hover:bg-gh-cyan/5',
  danger:
    'btn-outline border font-medium border-gh-red/70 bg-gh-red/10 text-gh-red hover:border-gh-red hover:bg-gh-red/20',
  link: 'btn-link text-gh-cyan hover:underline'
}

const cls = computed(() => [
  'btn-ripple inline-flex items-center justify-center gap-1 rounded-md text-sm px-3 h-[30px]',
  'transition-all duration-150 select-none',
  'hover:-translate-y-px',
  'active:translate-y-0 active:scale-95 active:brightness-95',
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
  ink.style.background = 'currentColor'
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
