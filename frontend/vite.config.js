import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  base: '/llm-gateway/',
  plugins: [vue(), tailwindcss()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true
  },
  server: {
    proxy: {
      '/llm-gateway': {
        target: 'http://127.0.0.1:18443',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/llm-gateway/, '')
      }
    }
  }
})
