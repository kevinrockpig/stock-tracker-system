// 登入狀態（全站共用一份）：App 載入時呼叫 loadAuth()，導覽列、庫存頁與「加入庫存」按鈕都讀這裡
import { reactive } from 'vue'
import { fetchMe, loginUrl, logout as apiLogout } from '../api/stocks.js'

export const auth = reactive({
  loaded: false, // 已取得 /api/me 的結果（失敗也算，避免一直顯示載入中）
  error: '',
  loginEnabled: false, // 伺服器沒有設定 Google 登入時為 false：隱藏登入與「加入庫存」
  authenticated: false,
  email: null,
  displayName: null,
  loginUrl: null,
})

export async function loadAuth() {
  try {
    const me = await fetchMe()
    Object.assign(auth, me, { error: '' })
  } catch (err) {
    auth.error = err.message
  } finally {
    auth.loaded = true
  }
}

// 登入後 Java 一律導回 /holdings；從股票清單或個股頁按「加入庫存」時，先記住要加入哪一檔，回來後直接開啟表單
const PENDING_ADD_KEY = 'stock-tracker:pending-add'

export function startLogin(pendingSymbol) {
  if (!auth.loginUrl) return
  try {
    if (pendingSymbol) sessionStorage.setItem(PENDING_ADD_KEY, pendingSymbol)
    else sessionStorage.removeItem(PENDING_ADD_KEY)
  } catch {
    // 瀏覽器停用儲存空間時只是回來後不會自動開啟表單
  }
  window.location.assign(loginUrl(auth.loginUrl))
}

// 取出並清除登入前記住的股票（只用一次）
export function takePendingAdd() {
  try {
    const symbol = sessionStorage.getItem(PENDING_ADD_KEY)
    sessionStorage.removeItem(PENDING_ADD_KEY)
    return symbol
  } catch {
    return null
  }
}

export async function logout() {
  await apiLogout()
  markLoggedOut()
}

// API 回 401（登入逾時）時也呼叫，讓畫面改顯示登入按鈕
export function markLoggedOut() {
  Object.assign(auth, { authenticated: false, email: null, displayName: null })
}
