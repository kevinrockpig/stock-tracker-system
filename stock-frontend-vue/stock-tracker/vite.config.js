import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // Java 端 CORS 只允許 http://localhost:5173；port 被占用時直接報錯，而不是改用 5174 導致 CORS 被拒
    strictPort: true,
  },
  build: {
    // 個股頁含 ECharts（已按需引入，壓縮後約 540 kB），只在進入個股頁時載入
    chunkSizeWarningLimit: 600,
  },
})
