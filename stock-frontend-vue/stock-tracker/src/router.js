import { createRouter, createWebHistory } from 'vue-router'
import { findHashTarget, scrollMotion } from './utils/scroll.js'
import AboutView from './views/AboutView.vue'
import HomeView from './views/HomeView.vue'
import ScreenerView from './views/ScreenerView.vue'

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

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    // 例如 /screener?strategy=golden_cross&days=10；選擇記錄在網址上，可分享、可用上一頁返回
    { path: '/screener', name: 'screener', component: ScreenerView },
    // 例如 /stocks/2330.TW；props: true 讓 symbol 直接成為元件的 prop
    // 個股頁含 ECharts（體積較大），進入時才載入，清單頁不受影響
    {
      path: '/stocks/:symbol',
      name: 'stock-detail',
      component: () => import('./views/StockDetailView.vue'),
      props: true,
    },
    // 使用說明與免責聲明；各段落可用 #indicators 等錨點直接連結
    { path: '/about', name: 'about', component: AboutView },
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
