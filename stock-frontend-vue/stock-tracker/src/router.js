import { createRouter, createWebHistory } from 'vue-router'
import { findHashTarget, scrollMotion } from './utils/scroll.js'
import { setTitle } from './utils/title.js'
import AboutView from './views/AboutView.vue'
import HomeView from './views/HomeView.vue'
import ResearchView from './views/ResearchView.vue'
import ScreenerView from './views/ScreenerView.vue'
import TodayView from './views/TodayView.vue'

// 上一頁回到需要重新載入資料的頁面（例如個股頁）時，載入中的頁面較短，直接捲動會被壓回頂端；
// 等頁面長到足以捲回原位置再捲動，最多等 2 秒（逾時就捲到目前能到的位置）
function whenTallEnough(position, timeout = 2000) {
  return new Promise((resolve) => {
    const start = performance.now()
    const check = () => {
      const tallEnough = document.documentElement.scrollHeight - window.innerHeight >= position.top
      if (tallEnough || performance.now() - start > timeout) resolve(position)
      else requestAnimationFrame(check)
    }
    check()
  })
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    // 今天：大盤狀態、庫存待處理、R1 規則的候選（2026-11-05 起的首頁）
    { path: '/', name: 'today', component: TodayView, meta: { title: '今天', section: 'today' } },
    // 選股：觀察名單與搜尋（U1-3 會與策略選股、我的自選合併）
    { path: '/stocks', name: 'stocks', component: HomeView, meta: { title: '選股', section: 'stocks' } },
    // 例如 /screener?strategy=golden_cross&days=10；選擇記錄在網址上，可分享、可用上一頁返回
    { path: '/screener', name: 'screener', component: ScreenerView, meta: { title: '策略選股', section: 'stocks' } },
    // 例如 /stocks/2330.TW；props: true 讓 symbol 直接成為元件的 prop
    // 個股頁含 ECharts（體積較大），進入時才載入，清單頁不受影響；標題在頁面載入股票名稱後設定
    {
      path: '/stocks/:symbol',
      name: 'stock-detail',
      component: () => import('./views/StockDetailView.vue'),
      props: true,
      meta: { section: 'stocks' },
    },
    // 研究：研究結論、模擬帳戶、舊回測（U1-6 改版）
    { path: '/research', name: 'research', component: ResearchView, meta: { title: '研究', section: 'research' } },
    // 投資組合回測比較，例如 /portfolio?scope=TW50&sort=cagr&strategy=high_120（strategy 為要標示的策略）
    { path: '/portfolio', name: 'portfolio', component: () => import('./views/PortfolioView.vue'), meta: { title: '回測比較', section: 'research' } },
    // 交易計畫（往後實測的模擬帳戶），例如 /plan?account=high_120/trailing_nocap
    { path: '/plan', name: 'paper', component: () => import('./views/PaperView.vue'), meta: { title: '交易計畫', section: 'research' } },
    // 我的庫存（需要登入）；?add=代號 登入後開啟這一檔的新增表單，#holding-代號 捲到那一檔
    { path: '/holdings', name: 'holdings', component: () => import('./views/HoldingsView.vue'), meta: { title: '我的庫存', section: 'holdings' } },
    // 使用說明與免責聲明；各段落可用 #indicators 等錨點直接連結
    { path: '/about', name: 'about', component: AboutView, meta: { title: '使用說明', section: 'research' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return whenTallEnough(savedPosition) // 上一頁 / 下一頁：回到原本的位置
    const target = findHashTarget(to.hash)
    if (target) {
      // scrollIntoView 會套用 CSS 的 scroll-margin-top，標題不會被固定的導覽列蓋住；同一頁內跳轉使用平滑捲動
      target.scrollIntoView({ behavior: to.path === from.path ? scrollMotion() : 'auto' })
      return false
    }
    if (to.path !== from.path) return { top: 0 } // 換頁：回到頂端
    return false // 同一頁只改網址參數（例如策略選股切換條件）：維持目前位置
  },
})

// 分頁標題：每一頁不同，瀏覽紀錄與多個分頁才分得出來（個股頁在載入名稱後另外設定）
router.afterEach((to) => {
  if (to.meta.title) setTitle(to.meta.title)
})

export default router
