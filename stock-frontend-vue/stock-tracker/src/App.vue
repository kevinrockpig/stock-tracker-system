<script setup>
import BackToTop from './components/BackToTop.vue'
</script>

<template>
  <main class="container">
    <nav class="site-nav" aria-label="主選單">
      <RouterLink to="/">股票清單</RouterLink>
      <RouterLink :to="{ name: 'screener' }">策略選股</RouterLink>
      <RouterLink :to="{ name: 'about' }">使用說明</RouterLink>
    </nav>
    <RouterView />
    <footer class="site-footer">
      資料為日收盤價，僅供參考，不構成投資建議。
      <RouterLink :to="{ name: 'about', hash: '#disclaimer' }">免責聲明與使用說明</RouterLink>
    </footer>
    <BackToTop />
  </main>
</template>

<style scoped>
/* 導覽列固定在畫面頂端，捲到哪裡都能切換頁面；高度與 style.css 的 --nav-height 一致 */
.site-nav {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 4px;
  box-sizing: border-box;
  height: var(--nav-height);
  margin: -8px 0 16px;
  border-bottom: 1px solid #e5e7eb;
  background: #fff;
}

.site-nav a {
  padding: 4px 10px;
  border-radius: 6px;
  color: #444;
  font-size: 14px;
}

.site-nav a:hover {
  background: #f1f3f5;
  text-decoration: none;
}

/* vue-router 會為目前頁面的連結加上 router-link-active */
.site-nav a.router-link-active {
  background: #1f2328;
  color: #fff;
}
.site-footer {
  margin-top: 24px;
  padding-top: 12px;
  border-top: 1px solid #e5e7eb;
  color: #666;
  font-size: 13px;
}
</style>
