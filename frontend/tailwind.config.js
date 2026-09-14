/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,js}'],
  theme: {
    extend: {
      colors: {
        canvas: '#ffffff',
        panel: '#f6f8fa',
        edge: '#d0d7de',
        fg: '#1f2328',
        muted: '#59636e',
        accent: '#1f883d',
        link: '#0969da',
        pill: '#eaeef2',
        danger: '#cf222e',
        warn: '#9a6700'
      },
      fontFamily: {
        sans: ['-apple-system', 'BlinkMacSystemFont', '"Segoe UI"', 'Helvetica', 'Arial', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', '"SF Mono"', 'Menlo', 'Consolas', 'monospace']
      }
    }
  },
  plugins: []
}
