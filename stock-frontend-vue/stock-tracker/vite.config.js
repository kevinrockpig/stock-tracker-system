import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 開發時把 API 與登入相關的路徑轉給 Java，前端與 API 同一個來源：登入的 session cookie 才會隨請求送出，
// Google 登入後也會回到 http://localhost:5173/login/oauth2/code/google（需在 Google Cloud 登記這個網址）
const apiTarget = process.env.API_PROXY_TARGET || 'http://localhost:8081'
const proxy = Object.fromEntries(['/api', '/oauth2', '/login/oauth2'].map((path) => [path, { target: apiTarget }]))

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // Google 登入的回呼網址固定為 5173；port 被占用時直接報錯，而不是改用 5174
    strictPort: true,
    proxy,
  },
  build: {
    // 個股頁含 ECharts（已按需引入，壓縮後約 540 kB），只在進入個股頁時載入
    chunkSizeWarningLimit: 600,
  },
})
