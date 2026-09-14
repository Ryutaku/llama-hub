<template>
  <div class="fixed inset-0 z-30 flex items-center justify-center">
    <div class="absolute inset-0 bg-slate-900/30" @click="$emit('close')"></div>
    <div class="relative bg-panel border border-edge rounded-lg p-5 w-full max-w-md mx-4 shadow-xl">
      <h4 class="text-base font-semibold mb-4">{{ title }}</h4>
      <slot></slot>
    </div>
  </div>
</template>

<script>
export default {
  name: 'Modal',
  props: {
    title: { type: String, required: true }
  },
  emits: ['close']
}
</script>
