<template>
  <main class="container">
    <nav class="site-nav" aria-label="主選單">
      <RouterLink to="/">股票清單</RouterLink>
      <RouterLink :to="{ name: 'screener' }">策略選股</RouterLink>
    </nav>
    <RouterView />
  </main>
</template>

<style scoped>
.site-nav {
  display: flex;
  gap: 4px;
  margin: -8px 0 16px;
  padding-bottom: 8px;
  border-bottom: 1px solid #e5e7eb;
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
</style>
