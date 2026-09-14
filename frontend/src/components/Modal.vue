<template>
  <div class="fixed inset-0 z-30 flex items-center justify-center">
    <div class="absolute inset-0 bg-black/60" @click="$emit('close')"></div>
    <div class="modal-pop relative panel-tech p-5 w-full max-w-md mx-4">
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
