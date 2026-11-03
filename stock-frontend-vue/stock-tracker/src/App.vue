<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BackToTop from './components/BackToTop.vue'
import HoldingDialog from './components/HoldingDialog.vue'
import { auth, loadAuth, logout } from './utils/auth.js'

// 個股頁屬於「股票清單」底下，導覽列也標示為目前位置
const route = useRoute()
const router = useRouter()

// 登入狀態：導覽列、庫存頁與「加入庫存」按鈕共用
onMounted(loadAuth)

const loggingOut = ref(false)
async function doLogout() {
  loggingOut.value = true
  try {
    await logout()
    if (route.name === 'holdings') return // 庫存頁會改顯示登入說明
    router.push({ name: 'holdings' })
  } catch {
    // 登出失敗（例如網路中斷）：維持登入狀態，使用者可再按一次
  } finally {
    loggingOut.value = false
  }
}
</script>

<template>
  <main class="container">
    <nav class="site-nav" aria-label="主選單">
      <RouterLink to="/" :class="{ 'router-link-active': route.name === 'stock-detail' }">股票清單</RouterLink>
      <RouterLink :to="{ name: 'screener' }">策略選股</RouterLink>
      <RouterLink :to="{ name: 'holdings' }"><span class="wide-only">我的</span>庫存</RouterLink>
      <RouterLink :to="{ name: 'paper' }">交易計畫</RouterLink>
      <RouterLink :to="{ name: 'portfolio' }">回測比較</RouterLink>
      <RouterLink :to="{ name: 'about' }">使用說明</RouterLink>
      <!-- 帳號：手機版導覽列空間不足，改在庫存頁登入、登出 -->
      <span v-if="auth.loginEnabled" class="account">
        <button v-if="auth.authenticated" type="button" :title="auth.email" :disabled="loggingOut" @click="doLogout">登出</button>
        <RouterLink v-else :to="{ name: 'holdings' }" class="login" active-class="" exact-active-class="">登入</RouterLink>
      </span>
    </nav>
    <RouterView />
    <footer class="site-footer">
      資料為日收盤價，僅供參考，不構成投資建議。
      <RouterLink :to="{ name: 'about', hash: '#disclaimer' }">免責聲明與使用說明</RouterLink>
    </footer>
    <BackToTop />
    <HoldingDialog />
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
  flex-shrink: 0;
  padding: 4px 10px;
  border-radius: 6px;
  color: #444;
  font-size: 14px;
  white-space: nowrap;
}

/* 帳號（登入 / 登出）靠右 */
.account {
  margin-left: auto;
}

.account button,
.account .login {
  padding: 3px 10px;
  border: 1px solid #ced4da;
  border-radius: 6px;
  background: #fff;
  color: #444;
  font-size: 13px;
}

/* 手機：縮小間距、「我的庫存」簡稱「庫存」，讓 6 個項目排成一行；更窄的螢幕可左右滑動，不會擠成兩行 */
@media (max-width: 600px) {
  .site-nav {
    gap: 1px;
    overflow-x: auto;
    scrollbar-width: none;
  }

  .site-nav a {
    padding: 4px 2px;
    font-size: 13px;
  }

  .wide-only,
  .account {
    display: none;
  }
}

/* 375px 以下的手機（例如 iPhone SE）再縮小字級 */
@media (max-width: 400px) {
  .site-nav a {
    font-size: 12px;
  }
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
