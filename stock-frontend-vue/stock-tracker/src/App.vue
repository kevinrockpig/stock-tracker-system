<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import BackToTop from './components/BackToTop.vue'
import HoldingDialog from './components/HoldingDialog.vue'
import { auth, loadAuth, logout, startLogin } from './utils/auth.js'
import { loadPlan } from './utils/plan.js'

const route = useRoute()
const router = useRouter()

// 主選單 4 項；個股頁、策略選股屬於「選股」，回測比較、交易計畫、說明屬於「研究」（見 router.js 的 meta.section）
const SECTIONS = [
  { key: 'today', label: '今天', to: { name: 'today' } },
  { key: 'holdings', label: '庫存', to: { name: 'holdings' } },
  { key: 'stocks', label: '選股', to: { name: 'stocks' } },
  { key: 'research', label: '研究', to: { name: 'research' } },
]
const section = computed(() => route.meta.section)

// 登入狀態：導覽列、庫存頁與「加入庫存」按鈕共用；確定後載入我的計畫（登入時從帳號，否則從瀏覽器）
onMounted(loadAuth)
watch(
  () => [auth.loaded, auth.authenticated],
  ([loaded]) => loaded && loadPlan(),
)

// 帳號選單（<details>）：換頁、點選單以外的地方或按 Esc 時收起
const accountMenu = ref(null)
const closeMenu = () => accountMenu.value?.removeAttribute('open')
watch(() => route.fullPath, closeMenu)
function onDocumentClick(event) {
  if (accountMenu.value && !accountMenu.value.contains(event.target)) closeMenu()
}
function onKeydown(event) {
  if (event.key === 'Escape' && accountMenu.value?.open) {
    closeMenu()
    accountMenu.value.querySelector('summary')?.focus()
  }
}
onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
})

const loggingOut = ref(false)
async function doLogout() {
  loggingOut.value = true
  try {
    await logout()
    closeMenu()
    if (route.name === 'holdings') return // 庫存頁會改顯示登入說明
    router.push({ name: 'today' })
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
      <RouterLink
        v-for="item in SECTIONS"
        :key="item.key"
        :to="item.to"
        :class="{ current: section === item.key }"
        :aria-current="section === item.key ? 'page' : undefined"
        active-class=""
        exact-active-class=""
        >{{ item.label }}</RouterLink
      >
      <!-- 帳號：未登入顯示「登入」，登入後為選單（Email、登出）；手機也顯示 -->
      <span v-if="auth.loginEnabled" class="account">
        <details v-if="auth.authenticated" ref="accountMenu" class="account-menu">
          <summary :title="auth.email">帳號</summary>
          <div class="menu" role="menu">
            <p class="email">{{ auth.email }}</p>
            <RouterLink :to="{ name: 'setup' }" role="menuitem">我的計畫</RouterLink>
            <RouterLink :to="{ name: 'holdings', hash: '#holdings-notify' }" role="menuitem">通知設定</RouterLink>
            <button type="button" role="menuitem" :disabled="loggingOut" @click="doLogout">登出</button>
          </div>
        </details>
        <button v-else type="button" class="login" @click="startLogin()">登入</button>
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

.site-nav > a {
  flex-shrink: 0;
  padding: 6px 14px;
  border-radius: 6px;
  color: #444;
  font-size: 15px;
  white-space: nowrap;
}

.site-nav > a:hover {
  background: #f1f3f5;
  text-decoration: none;
}

/* 目前所在的區塊（同一區塊的子頁也標示，例如個股頁屬於「選股」） */
.site-nav > a.current {
  background: #1f2328;
  color: #fff;
}

/* 帳號靠右 */
.account {
  margin-left: auto;
}

.account .login,
.account summary {
  padding: 4px 12px;
  border: 1px solid #ced4da;
  border-radius: 6px;
  background: #fff;
  color: #444;
  font-size: 14px;
  cursor: pointer;
}

.account-menu {
  position: relative;
}

.account summary {
  list-style: none;
}

.account summary::-webkit-details-marker {
  display: none;
}

.account summary::after {
  content: ' ▾';
  font-size: 11px;
}

.account .menu {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  display: grid;
  gap: 2px;
  min-width: 200px;
  padding: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.12);
}

.account .email {
  margin: 0 0 4px;
  padding: 4px 8px 8px;
  border-bottom: 1px solid #e5e7eb;
  color: #666;
  font-size: 13px;
  overflow-wrap: anywhere;
}

.account .menu a,
.account .menu button {
  padding: 8px;
  border: 0;
  border-radius: 6px;
  background: none;
  color: #1f2328;
  font-size: 14px;
  text-align: left;
}

.account .menu a:hover,
.account .menu button:hover {
  background: #f1f3f5;
  text-decoration: none;
}

/* 手機：4 項加上帳號一行放得下，字級維持 15px，點擊範圍夠大 */
@media (max-width: 600px) {
  .site-nav {
    gap: 2px;
  }

  .site-nav > a {
    padding: 6px 10px;
  }
}

@media (max-width: 360px) {
  .site-nav > a {
    padding: 6px 7px;
  }
}

.site-footer {
  margin-top: 24px;
  padding-top: 12px;
  border-top: 1px solid #e5e7eb;
  color: #666;
  font-size: 13px;
}
</style>
