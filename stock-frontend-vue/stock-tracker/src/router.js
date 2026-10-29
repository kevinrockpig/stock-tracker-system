import { createRouter, createWebHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import ScreenerView from './views/ScreenerView.vue'

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
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
