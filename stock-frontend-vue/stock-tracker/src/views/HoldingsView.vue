<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { deleteHolding, deleteSale, fetchHoldings } from '../api/stocks.js'
import HoldingsHistory from '../components/HoldingsHistory.vue'
import NotificationSettings from '../components/NotificationSettings.vue'
import SaleDialog from '../components/SaleDialog.vue'
import { auth, logout, markLoggedOut, startLogin, takePendingAdd } from '../utils/auth.js'
import { formatRate, trendClass } from '../utils/format.js'
import { groupShortName } from '../utils/groups.js'
import { FEE_MODES, exTypeText, formatShares, holdingAlerts, openHoldingForm, ratioToDiscount } from '../utils/holdings.js'
import { findHashTarget, scrollMotion } from '../utils/scroll.js'

// 登入失敗時 Java 導回 /holdings?login=原因（AllowedUserOidcService）
const LOGIN_ERRORS = {
  not_allowed: '這個 Google 帳號沒有使用權限（目前只開放特定帳號）。',
  failed: '登入失敗或已取消，請再試一次。',
}

// 「距停利線」小於這個比例時以橘色提醒（與交易計畫頁相同）
const NEAR_EXIT = 0.05

const SORTS = {
  value: { label: '市值', compare: (a, b) => b.marketValue - a.marketValue },
  exit: { label: '距停利線', compare: (a, b) => nullsLast(a.distanceToExit, b.distanceToExit) },
  return: { label: '報酬', compare: (a, b) => nullsLast(b.unrealizedReturn, a.unrealizedReturn) },
}
function nullsLast(x, y) {
  if (x == null || y == null) return x == null ? (y == null ? 0 : 1) : -1
  return x - y
}

const SECTIONS = [
  { id: 'holdings-positions', label: '持股' },
  { id: 'holdings-realized', label: '已實現' },
  { id: 'holdings-benchmark', label: '同期比較與走勢' },
  { id: 'holdings-notify', label: '每日通知' },
  { id: 'holdings-notes', label: '計算方式' },
]

const route = useRoute()
const router = useRouter()

const data = ref(null)
const loading = ref(false)
const error = ref('')
const message = ref('') // 新增、修改、刪除後的結果
const messageIsError = ref(false) // 被擋下的修改（例如賣出會超過買進）以紅色顯示，不取代整頁
const loginError = ref('')
const expanded = ref(new Set())
const expandedSales = ref(new Set())

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await fetchHoldings()
  } catch (err) {
    if (err.status === 401) markLoggedOut()
    else error.value = err.message
  } finally {
    loading.value = false
  }
}

// 登入失敗的原因只顯示一次，並從網址移除（重新整理不再出現）
if (typeof route.query.login === 'string') {
  loginError.value = LOGIN_ERRORS[route.query.login] ?? LOGIN_ERRORS.failed
  const { login, ...rest } = route.query
  router.replace({ query: rest, hash: route.hash })
}

// 從股票清單或個股頁按「加入庫存」：?add=代號（尚未登入時帶過來），或登入前記住的代號
const pendingSymbol = ref(typeof route.query.add === 'string' ? route.query.add : null)

watch(
  () => [auth.loaded, auth.authenticated],
  async ([loaded, authenticated]) => {
    if (!loaded) return
    if (!authenticated) {
      data.value = null
      return
    }
    const symbol = pendingSymbol.value ?? takePendingAdd()
    pendingSymbol.value = null
    if (route.query.add) {
      const { add, ...rest } = route.query
      router.replace({ query: rest })
    }
    await load()
    if (symbol) openHoldingForm({ stock: { symbol, name: symbol }, onSaved: afterSave('已加入') })
  },
  { immediate: true },
)

// 網址帶 #holding-代號（例如加入後按「查看我的庫存」）：第一次載入後捲到那一列並展開明細
let hashHandled = false
watch(data, async () => {
  const symbol = route.hash.startsWith('#holding-') ? decodeURIComponent(route.hash.slice('#holding-'.length)) : null
  if (hashHandled || !symbol || !data.value) return
  hashHandled = true
  expanded.value = new Set([...expanded.value, symbol])
  await nextTick()
  findHashTarget(route.hash)?.scrollIntoView({ behavior: scrollMotion(), block: 'center' })
})

const sortKey = computed(() => (route.query.sort in SORTS ? route.query.sort : 'value'))
function setSort(key) {
  router.replace({ query: { ...route.query, sort: key === 'value' ? undefined : key } })
}
const positions = computed(() => [...(data.value?.positions ?? [])].sort(SORTS[sortKey.value].compare))

// 需要留意：跌破移動停利線、即將除權息
const attention = computed(() => holdingAlerts(data.value?.positions))

const hasNotes = (p) => p.belowExitLine || p.belowMa60 || p.nextExDate || p.signals.length > 0

function toggleIn(set, key) {
  const next = new Set(set.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  set.value = next
}
const toggle = (symbol) => toggleIn(expanded, symbol)
const toggleSale = (id) => toggleIn(expandedSales, id)

// 目錄：有賣出紀錄才顯示「已實現」
const sections = computed(() => SECTIONS.filter((s) => s.id !== 'holdings-realized' || data.value?.realized.count))
const hasSold = (p) => p.lots.some((lot) => lot.soldShares > 0)

function showMessage(text, isError = false) {
  message.value = text
  messageIsError.value = isError
}

function afterSave(verb) {
  return async (lot) => {
    await load()
    const name = data.value?.positions.find((p) => p.symbol === lot.symbol)?.name ?? lot.symbol
    showMessage(`${verb}：${name} ${formatShares(lot.shares)} @ ${price(lot.price)}，手續費 ${money(lot.fee)} 元。`)
    expanded.value = new Set([...expanded.value, lot.symbol])
  }
}

function addLot() {
  message.value = ''
  openHoldingForm({ onSaved: afterSave('已加入') })
}

function editLot(position, lot) {
  message.value = ''
  openHoldingForm({ stock: { symbol: position.symbol, name: position.name }, lot, onSaved: afterSave('已修改') })
}

async function removeLot(position, lot) {
  const when = lot.buyDate ? `${lot.buyDate} 買進的` : ''
  const sold = lot.soldShares > 0 ? '這筆有一部分已賣出，刪除後賣出會改從其他買進扣（不夠扣時會被擋下）。' : ''
  if (!window.confirm(`刪除 ${position.name} ${when}${formatShares(lot.shares)} @ ${price(lot.price)}？${sold}刪除後無法復原。`)) return
  message.value = ''
  try {
    await deleteHolding(lot.id)
    showMessage(`已刪除：${position.name} ${formatShares(lot.shares)} @ ${price(lot.price)}。`)
    await load()
  } catch (err) {
    handleWriteError(err)
  }
}

// 寫入失敗：輸入或先進先出的問題（400）顯示在訊息列，其他錯誤顯示在頁面上方
function handleWriteError(err) {
  if (err.status === 401) markLoggedOut()
  else if (err.status === 400) showMessage(err.message, true)
  else error.value = err.message
}

// ---- 賣出 ----

const saleDialog = ref({ open: false, position: null, sale: null })
// 修改賣出時，找出那檔的買進（持股或已全部賣出）
const positionOf = (symbol) =>
  data.value?.positions.find((p) => p.symbol === symbol) ?? data.value?.realized.closedPositions.find((p) => p.symbol === symbol) ?? null

function openSale(position) {
  message.value = ''
  saleDialog.value = { open: true, position, sale: null }
}

function editSale(sale) {
  message.value = ''
  saleDialog.value = { open: true, position: positionOf(sale.symbol), sale }
}

function closeSale() {
  if (saleDialog.value.open) saleDialog.value = { ...saleDialog.value, open: false }
}

async function saleSaved(sale) {
  const verb = saleDialog.value.sale ? '已修改賣出' : '已記錄賣出'
  closeSale()
  await load()
  showMessage(`${verb}：${sale.name} ${formatShares(sale.shares)} @ ${price(sale.price)}，拿回 ${money(sale.proceeds)} 元，已實現損益 ${signedMoney(sale.realizedPnl)} 元。`)
}

async function removeSale(sale) {
  if (!window.confirm(`刪除 ${sale.sellDate} 賣出的 ${sale.name} ${formatShares(sale.shares)} @ ${price(sale.price)}？刪除後這些股數會回到持股。`)) return
  message.value = ''
  try {
    await deleteSale(sale.id)
    showMessage(`已刪除賣出：${sale.name} ${sale.sellDate} ${formatShares(sale.shares)}。`)
    await load()
  } catch (err) {
    handleWriteError(err)
  }
}

// 這筆賣出從哪幾筆買進扣
function saleLotText(lot) {
  return `${lot.buyDate ?? '未填日期'} 買進 @ ${price(lot.buyPrice)}：${formatShares(lot.shares)}，成本 ${money(lot.cost)}`
}

const loggingOut = ref(false)
async function doLogout() {
  loggingOut.value = true
  try {
    await logout()
    message.value = ''
  } catch (err) {
    error.value = err.message
  } finally {
    loggingOut.value = false
  }
}

const moneyFormat = new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 0 })
const priceFormat = new Intl.NumberFormat('zh-TW', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const money = (value) => (value == null ? '—' : moneyFormat.format(value))
const signedMoney = (value) => (value == null ? '—' : `${value > 0 ? '+' : ''}${moneyFormat.format(value)}`)
const price = (value) => (value == null ? '—' : priceFormat.format(value))
const rate = (value, digits = 2) => formatRate(value, { digits })
const plainRate = (value, digits = 1) => formatRate(value, { digits, signed: false })
const stockLink = (symbol) => ({ name: 'stock-detail', params: { symbol } })

// 換算與股利（只有發生時才顯示）
function lotDetails(lot) {
  const parts = []
  if (lot.splitRatio !== 1) parts.push(`分割 / 配股換算 ×${Number(lot.splitRatio.toFixed(4))} → 現在 ${formatShares(lot.currentShares)}`)
  if (lot.soldShares > 0) parts.push(lot.remainingShares > 0 ? `已賣出 ${formatShares(lot.soldShares)}，剩 ${formatShares(lot.remainingShares)}` : '已全部賣出')
  if (lot.dividends) parts.push(`已領股利 ${money(lot.dividends)}`)
  return parts.join(' · ')
}

function feeText(lot) {
  const mode = FEE_MODES.find((m) => m.code === lot.feeMode)?.label ?? lot.feeMode
  if (lot.feeMode === 'discount') return `${ratioToDiscount(lot.feeDiscount)} 折`
  return mode
}

// 同期比較：庫存比 0050 多（少）幾個百分點
const benchmarkGap = computed(() => {
  const b = data.value?.benchmark
  if (!b || b.holdingsReturn == null || b.etfReturn == null) return null
  return b.holdingsReturn - b.etfReturn
})

const feeRules = computed(() => data.value?.feeRules)
</script>

<template>
  <section>
    <h1>我的庫存</h1>
    <p class="intro">
      自行記錄的買進紀錄（不與券商同步），以最新<strong>日收盤價</strong>估算市值、損益與出場參考。只有你自己看得到。
      <RouterLink :to="{ name: 'about', hash: '#holdings' }">計算方式說明</RouterLink>
    </p>

    <div v-if="loginError" class="status error" role="alert">
      <p>{{ loginError }}</p>
    </div>

    <p v-if="!auth.loaded" class="status">載入中…</p>
    <div v-else-if="auth.error" class="status error">
      <p>{{ auth.error }}</p>
    </div>
    <p v-else-if="!auth.loginEnabled" class="status">這個網站目前沒有開放登入，無法使用庫存功能。</p>

    <!-- 未登入 -->
    <div v-else-if="!auth.authenticated" class="login-panel">
      <p v-if="pendingSymbol"><strong>登入後即可把 {{ pendingSymbol }} 加入庫存。</strong></p>
      <p>庫存存在你的帳號中，使用 Google 帳號登入；網站只會取得你的 Email 與名稱，不會取得密碼。</p>
      <button type="button" class="primary" @click="startLogin(pendingSymbol)">使用 Google 登入</button>
    </div>

    <!-- 已登入 -->
    <template v-else>
      <div class="account">
        <span>已登入：{{ auth.email }}</span>
        <button type="button" class="link" :disabled="loggingOut" @click="doLogout">登出</button>
      </div>

      <p v-if="loading && !data" class="status">載入中…（資料庫閒置後首次連線可能需要數秒）</p>
      <div v-else-if="error" class="status error">
        <p>{{ error }}</p>
        <button type="button" @click="load">重試</button>
      </div>

      <template v-if="data">
        <div class="toolbar">
          <button type="button" class="primary" :disabled="data.count >= data.maxCount" @click="addLot">＋ 新增一筆</button>
          <span class="meta">共 {{ data.count }} 筆（上限 {{ data.maxCount }}）<template v-if="data.asOf"> · 以 {{ data.asOf }} 收盤價計算</template></span>
        </div>
        <p v-if="message" class="message" :class="{ 'message-error': messageIsError }" :role="messageIsError ? 'alert' : 'status'">{{ message }}</p>

        <div v-if="!data.positions.length && !data.realized.count" class="status empty">
          <p>還沒有庫存。按「新增一筆」，或在今天、選股、個股頁按「＋」加入。</p>
          <p class="meta">只能加入觀察名單內的股票。</p>
        </div>

        <template v-else>
          <div class="cards">
            <div class="card">
              <span class="label">市值</span>
              <span class="value">{{ money(data.summary.marketValue) }}</span>
              <span class="sub">成本 {{ money(data.summary.cost) }}</span>
            </div>
            <div class="card">
              <span class="label">未實現損益</span>
              <span class="value" :class="trendClass(data.summary.unrealizedPnl)">{{ signedMoney(data.summary.unrealizedPnl) }}</span>
              <span class="sub" :class="trendClass(data.summary.unrealizedReturn)">{{ rate(data.summary.unrealizedReturn) }}（已扣賣出成本）</span>
            </div>
            <div class="card">
              <span class="label">已領股利</span>
              <span class="value">{{ money(data.summary.dividends) }}</span>
              <span class="sub">有填買進日期的部分</span>
            </div>
            <div class="card">
              <span class="label">{{ data.realized.count ? '持股含股利損益' : '含股利總損益' }}</span>
              <span class="value" :class="trendClass(data.summary.totalPnl)">{{ signedMoney(data.summary.totalPnl) }}</span>
              <span class="sub" :class="trendClass(data.summary.totalReturn)">{{ rate(data.summary.totalReturn) }}</span>
            </div>
            <template v-if="data.realized.count">
              <div class="card">
                <span class="label">已實現損益</span>
                <span class="value" :class="trendClass(data.realized.realizedPnl)">{{ signedMoney(data.realized.realizedPnl) }}</span>
                <span class="sub" :class="trendClass(data.realized.realizedReturn)">{{ rate(data.realized.realizedReturn) }}（先進先出）</span>
              </div>
              <div class="card">
                <span class="label">總損益</span>
                <span class="value" :class="trendClass(data.realized.overallPnl)">{{ signedMoney(data.realized.overallPnl) }}</span>
                <span class="sub">持股 + 已實現 + 股利</span>
              </div>
            </template>
          </div>

          <div v-if="attention.length" class="attention">
            <h2 class="attention-title">需要留意</h2>
            <ul>
              <li v-for="item in attention" :key="item.key" :class="item.kind">
                <a :href="`#holding-${item.symbol}`" @click.prevent="router.replace({ query: route.query, hash: `#holding-${item.symbol}` })">{{
                  item.name
                }}</a>
                {{ item.text }}
              </li>
            </ul>
          </div>

          <nav class="toc" aria-label="本頁段落">
            <RouterLink v-for="section in sections" :key="section.id" :to="{ query: route.query, hash: `#${section.id}` }">{{
              section.label
            }}</RouterLink>
          </nav>

          <!-- 持股 -->
          <h2 id="holdings-positions">持股 {{ data.positions.length }} 檔</h2>
          <div class="sort-row" role="group" aria-label="排序">
            <span class="meta">排序：</span>
            <button v-for="(sort, key) in SORTS" :key="key" type="button" :class="{ active: sortKey === key }" @click="setSort(key)">
              {{ sort.label }}
            </button>
          </div>

          <p v-if="!data.positions.length" class="status">目前沒有持股（都已賣出，見下方「已實現損益」）。</p>
          <div v-else class="table-wrap">
            <table class="stack">
              <thead>
                <tr>
                  <th>股票</th>
                  <th class="num">股數</th>
                  <th class="num">每股成本</th>
                  <th class="num">收盤價</th>
                  <th class="num">市值</th>
                  <th class="num">未實現損益</th>
                  <th class="num">已領股利</th>
                  <th class="num">移動停利線</th>
                  <th>提醒</th>
                </tr>
              </thead>
              <tbody>
                <template v-for="p in positions" :key="p.symbol">
                  <tr :id="`holding-${p.symbol}`" :class="{ broken: p.belowExitLine, highlighted: route.hash === `#holding-${p.symbol}` }">
                    <th scope="row">
                      <RouterLink :to="stockLink(p.symbol)">{{ p.name }}</RouterLink>
                      <span class="code">{{ p.symbol }}</span>
                      <span v-for="code in p.groups" :key="code" class="tag">{{ groupShortName(code) }}</span>
                      <button
                        type="button"
                        class="toggle"
                        :aria-expanded="expanded.has(p.symbol)"
                        :aria-controls="`lots-${p.symbol}`"
                        @click="toggle(p.symbol)"
                      >
                        {{ expanded.has(p.symbol) ? '收合' : '明細' }} {{ p.lots.length }} 筆
                      </button>
                      <button type="button" class="toggle" @click="openSale(p)">賣出</button>
                    </th>
                    <td class="num" data-label="股數">{{ formatShares(p.shares) }}</td>
                    <td class="num" data-label="每股成本">
                      {{ price(p.costPerShare) }}<span class="sub">均價 {{ price(p.avgPrice) }}</span>
                    </td>
                    <td class="num" data-label="收盤價">
                      {{ price(p.price) }}<span v-if="p.priceDate !== data.asOf" class="sub stale">{{ p.priceDate }}</span>
                    </td>
                    <td class="num" data-label="市值">
                      {{ money(p.marketValue) }}<span class="sub">占 {{ plainRate(p.weight) }}</span>
                    </td>
                    <td class="num" data-label="未實現損益">
                      <span :class="trendClass(p.unrealizedPnl)">{{ signedMoney(p.unrealizedPnl) }}</span>
                      <span class="sub" :class="trendClass(p.unrealizedReturn)">{{ rate(p.unrealizedReturn) }}</span>
                      <span v-if="hasSold(p) && p.overallPnl != null" class="sub"
                        >整檔合計 <span :class="trendClass(p.overallPnl)">{{ signedMoney(p.overallPnl) }}</span></span
                      >
                    </td>
                    <td class="num" data-label="已領股利">
                      {{ p.dividends ? money(p.dividends) : '—' }}
                      <span v-if="!p.dividendsComplete" class="sub">未填日期的不計</span>
                      <span v-else-if="p.totalReturn != null && p.dividends" class="sub" :class="trendClass(p.totalReturn)"
                        >含股利 {{ rate(p.totalReturn) }}</span
                      >
                    </td>
                    <td class="num" data-label="移動停利線">
                      <template v-if="p.exitLine != null">
                        {{ price(p.exitLine) }}
                        <span class="sub" :class="{ near: p.distanceToExit != null && p.distanceToExit < NEAR_EXIT }">{{
                          p.belowExitLine ? '已跌破' : `距 ${plainRate(p.distanceToExit)}`
                        }}</span>
                      </template>
                      <span v-else class="sub">填買進日期後計算</span>
                    </td>
                    <td data-label="提醒" class="notes" :class="{ 'no-notes': !hasNotes(p) }">
                      <span v-if="p.belowExitLine" class="badge warn">跌破停利線</span>
                      <span v-if="p.belowMa60" class="badge">季線下</span>
                      <span v-if="p.nextExDate" class="badge info">{{ p.nextExDate.slice(5) }} {{ exTypeText(p.nextExType) }}</span>
                      <span v-for="s in p.signals" :key="s.code" class="badge signal">{{ s.name }}</span>
                    </td>
                  </tr>
                  <tr v-if="expanded.has(p.symbol)" :id="`lots-${p.symbol}`" class="lots-row">
                    <td colspan="9">
                      <ul class="lots">
                        <li v-for="lot in p.lots" :key="lot.id">
                          <div class="lot-main">
                            <strong>{{ lot.buyDate ?? '未填日期' }}</strong>
                            {{ formatShares(lot.shares) }} @ {{ price(lot.price) }} · 手續費 {{ money(lot.fee) }}（{{ feeText(lot) }}）· 成本
                            {{ money(lot.cost) }}
                          </div>
                          <div v-if="lotDetails(lot)" class="lot-sub">{{ lotDetails(lot) }}</div>
                          <div class="lot-actions">
                            <button type="button" class="link" @click="editLot(p, lot)">修改</button>
                            <button type="button" class="link danger" @click="removeLot(p, lot)">刪除</button>
                          </div>
                        </li>
                      </ul>
                      <p v-if="hasSold(p)" class="sub">
                        這檔已實現損益 <span :class="trendClass(p.realizedPnl)">{{ signedMoney(p.realizedPnl) }}</span>、賣出部分的股利
                        {{ money(p.realizedDividends) }}；整檔合計（未實現 + 已實現 + 股利）<span :class="trendClass(p.overallPnl)">{{
                          signedMoney(p.overallPnl)
                        }}</span
                        >。
                      </p>
                      <p v-if="p.trailingFrom && !p.historyComplete" class="sub">
                        買進日期早於系統的資料起點，最高收盤價從 {{ p.trailingFrom }} 之後有資料的日期算起。
                      </p>
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>
          <p class="note">
            每股成本含買進手續費；損益以收盤價全部賣出試算，已扣賣出手續費與證交稅（不含股利）。移動停利線 = 最早買進日以來的最高收盤價 − 3 ×
            ATR(14)，與交易計畫相同；「距停利線」小於 5% 以橘色標示。股數依買進日之後的分割、配股、減資換算成現在的股數。
          </p>


          <!-- 已實現損益 -->
          <template v-if="data.realized.count">
            <h2 id="holdings-realized">已實現損益</h2>
            <p class="meta">
              賣出依先進先出（與券商相同）從最早買進的扣，沒有買進日期的視為最早；修改過去的買進或賣出後自動重新計算。
              為了移動停利先出場時，單筆可能是負的，看「整檔合計」與「總損益」較能反映整體結果。
            </p>
            <div class="cards">
              <div class="card">
                <span class="label">已實現損益</span>
                <span class="value" :class="trendClass(data.realized.realizedPnl)">{{ signedMoney(data.realized.realizedPnl) }}</span>
                <span class="sub">拿回 {{ money(data.realized.proceeds) }} − 成本 {{ money(data.realized.cost) }}</span>
              </div>
              <div class="card">
                <span class="label">賣出部分的股利</span>
                <span class="value">{{ money(data.realized.dividends) }}</span>
                <span class="sub">{{ data.realized.dividendsComplete ? '持有期間領到的' : '未填買進日期的部分不計' }}</span>
              </div>
              <div class="card">
                <span class="label">總損益</span>
                <span class="value" :class="trendClass(data.realized.overallPnl)">{{ signedMoney(data.realized.overallPnl) }}</span>
                <span class="sub">持股 + 已實現 + 股利</span>
              </div>
            </div>

            <div class="table-wrap">
              <table class="stack">
                <thead>
                  <tr>
                    <th>賣出</th>
                    <th class="num">股數</th>
                    <th class="num">成交價</th>
                    <th class="num">拿回金額</th>
                    <th class="num">買進成本</th>
                    <th class="num">已實現損益</th>
                    <th class="num">持有期間股利</th>
                  </tr>
                </thead>
                <tbody>
                  <template v-for="sale in data.realized.sales" :key="sale.id">
                    <tr>
                      <th scope="row">
                        <strong class="sale-date">{{ sale.sellDate }}</strong>
                        <RouterLink :to="stockLink(sale.symbol)">{{ sale.name }}</RouterLink>
                        <span class="code">{{ sale.symbol }}</span>
                        <button
                          type="button"
                          class="toggle"
                          :aria-expanded="expandedSales.has(sale.id)"
                          :aria-controls="`sale-${sale.id}`"
                          @click="toggleSale(sale.id)"
                        >
                          {{ expandedSales.has(sale.id) ? '收合' : '明細' }}
                        </button>
                      </th>
                      <td class="num" data-label="股數">{{ formatShares(sale.shares) }}</td>
                      <td class="num" data-label="成交價">{{ price(sale.price) }}</td>
                      <td class="num" data-label="拿回金額">
                        {{ money(sale.proceeds) }}<span class="sub">手續費 {{ money(sale.fee) }} · 稅 {{ money(sale.tax) }}</span>
                      </td>
                      <td class="num" data-label="買進成本">{{ money(sale.cost) }}</td>
                      <td class="num" data-label="已實現損益">
                        <span :class="trendClass(sale.realizedPnl)">{{ signedMoney(sale.realizedPnl) }}</span>
                        <span class="sub" :class="trendClass(sale.realizedReturn)">{{ rate(sale.realizedReturn) }}</span>
                      </td>
                      <td class="num" data-label="持有期間股利">
                        {{ money(sale.dividends) }}
                        <span v-if="!sale.dividendsComplete" class="sub">未填日期的部分不計</span>
                      </td>
                    </tr>
                    <tr v-if="expandedSales.has(sale.id)" :id="`sale-${sale.id}`" class="lots-row">
                      <td colspan="7">
                        <ul class="lots">
                          <li v-for="lot in sale.lots" :key="lot.holdingId">
                            <div class="lot-main">{{ saleLotText(lot) }}</div>
                          </li>
                          <li v-if="sale.unmatchedShares > 0" class="unmatched">
                            有 {{ formatShares(sale.unmatchedShares) }} 找不到可扣的買進（之前的紀錄被修改過），成本未計入，請檢查買進或賣出紀錄。
                          </li>
                        </ul>
                        <div class="lot-actions">
                          <button type="button" class="link" @click="editSale(sale)">修改</button>
                          <button type="button" class="link danger" @click="removeSale(sale)">刪除</button>
                        </div>
                      </td>
                    </tr>
                  </template>
                </tbody>
              </table>
            </div>

            <template v-if="data.realized.closedPositions.length">
              <h3 class="closed-title">已全部賣出</h3>
              <ul class="closed">
                <li v-for="c in data.realized.closedPositions" :key="c.symbol">
                  <div>
                    <RouterLink :to="stockLink(c.symbol)">{{ c.name }}</RouterLink>
                    <span class="code">{{ c.symbol }}</span>
                    已實現 <span :class="trendClass(c.realizedPnl)">{{ signedMoney(c.realizedPnl) }}</span> · 股利 {{ money(c.dividends) }} · 合計
                    <span :class="trendClass(c.overallPnl)">{{ signedMoney(c.overallPnl) }}</span>
                    <button type="button" class="toggle" :aria-expanded="expanded.has(c.symbol)" @click="toggle(c.symbol)">
                      買進紀錄 {{ c.lots.length }} 筆
                    </button>
                  </div>
                  <ul v-if="expanded.has(c.symbol)" class="lots">
                    <li v-for="lot in c.lots" :key="lot.id">
                      <div class="lot-main">
                        <strong>{{ lot.buyDate ?? '未填日期' }}</strong>
                        {{ formatShares(lot.shares) }} @ {{ price(lot.price) }} · 成本 {{ money(lot.cost) }}
                      </div>
                      <div v-if="lotDetails(lot)" class="lot-sub">{{ lotDetails(lot) }}</div>
                      <div class="lot-actions">
                        <button type="button" class="link" @click="editLot(c, lot)">修改</button>
                        <button type="button" class="link danger" @click="removeLot(c, lot)">刪除</button>
                      </div>
                    </li>
                  </ul>
                </li>
              </ul>
            </template>
          </template>

          <!-- 同期比較 -->
          <h2 id="holdings-benchmark">同期比較與走勢：同一天買 0050</h2>
          <p class="meta">每一筆有填買進日期的庫存，假設同一天（非交易日為下一個交易日）以同樣的成本在收盤買進 0050，比較到今天的價值（都含已領股利）。<template v-if="data.realized.count">已賣出的部分以拿回的金額（現金）計入，投入成本不變。</template></p>
          <p v-if="!data.benchmark.lots" class="status">填了買進日期的庫存才能比較。</p>
          <template v-else>
            <div class="cards">
              <div class="card">
                <span class="label">我的庫存</span>
                <span class="value" :class="trendClass(data.benchmark.holdingsReturn)">{{ rate(data.benchmark.holdingsReturn) }}</span>
                <span class="sub">{{ money(data.benchmark.invested) }} → {{ money(data.benchmark.holdingsValue) }}</span>
              </div>
              <div class="card">
                <span class="label">同期買 0050</span>
                <span class="value" :class="trendClass(data.benchmark.etfReturn)">{{ rate(data.benchmark.etfReturn) }}</span>
                <span class="sub">{{ money(data.benchmark.invested) }} → {{ money(data.benchmark.etfValue) }}</span>
              </div>
              <div v-if="benchmarkGap != null" class="card">
                <span class="label">差距</span>
                <span class="value" :class="trendClass(benchmarkGap)">{{ rate(benchmarkGap) }}</span>
                <span class="sub">{{ benchmarkGap >= 0 ? '領先' : '落後' }} 0050（百分點）</span>
              </div>
            </div>
            <p class="note">
              納入 {{ data.benchmark.lots }} 筆<template v-if="data.benchmark.excludedLots">，未納入 {{ data.benchmark.excludedLots }} 筆（沒有買進日期）</template>。
              庫存已扣賣出成本、0050 不計交易成本，對 0050 略為有利。持有時間短時差距主要反映運氣。
            </p>
            <HoldingsHistory :holdings="data" />
          </template>

          <!-- 每日通知 -->
          <h2 id="holdings-notify">每日通知（Email）</h2>
          <NotificationSettings :positions="data.positions" />

          <!-- 計算方式 -->
          <h2 id="holdings-notes">計算方式</h2>
          <ul class="note rules">
            <li>
              手續費：公定費率 {{ plainRate(feeRules.feeRate, 4) }}，「折數」再乘上折扣；{{ feeRules.rounding }}。未填最低手續費時為
              {{ money(feeRules.defaultMinFee) }} 元。
            </li>
            <li>
              證交稅（賣出時）：股票 {{ plainRate(feeRules.taxStock) }}、ETF {{ plainRate(feeRules.taxEtf) }}。賣出手續費依該檔最近一筆的方式估算（「實際金額」以公定費率估算）。
            </li>
            <li>已領股利：買進日之後的除息日，每股現金股利 × 當時持有的股數。沒有買進日期的不計。</li>
            <li>
              賣出：依先進先出從最早買進的扣（與券商相同；沒有買進日期的視為最早，只扣賣出日以前買進的）。已實現損益 = 成交金額 − 手續費 − 證交稅 −
              這些股數的買進成本；持股的股數與成本只計還沒賣出的部分，移動停利線從剩下的買進中最早的日期起算。
            </li>
            <li>資料為日收盤價，非即時報價；僅供參考，不構成投資建議。</li>
          </ul>
        </template>
      </template>
    </template>
    <SaleDialog
      :open="saleDialog.open"
      :position="saleDialog.position"
      :sale="saleDialog.sale"
      :fee-rules="feeRules ?? undefined"
      @close="closeSale"
      @saved="saleSaved"
    />
  </section>
</template>

<style scoped>
.intro,
.meta,
.note {
  color: #555;
  font-size: 14px;
}

.meta,
.note {
  font-size: 13px;
}

h2 {
  margin: 28px 0 6px;
  padding-top: 12px;
  border-top: 1px solid #e5e7eb;
  font-size: 18px;
}

.primary {
  border: 1px solid #0b57d0;
  border-radius: 6px;
  background: #0b57d0;
  color: #fff;
}

.link {
  padding: 0;
  border: 0;
  background: none;
  color: #0b57d0;
  font-size: 13px;
}

.link.danger {
  color: #b42318;
}

.login-panel {
  padding: 16px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8f9fa;
}

.login-panel p {
  margin: 0 0 12px;
}

.account {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 10px;
  margin-bottom: 8px;
  color: #555;
  font-size: 13px;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin: 8px 0;
}

.message {
  margin: 8px 0;
  padding: 8px 12px;
  border-left: 4px solid #1a7f37;
  border-radius: 4px;
  background: #eef8f1;
  font-size: 13px;
}

.message.message-error {
  border-left-color: #b42318;
  background: #fdecea;
  color: #8a1c13;
}

.status.empty p {
  margin: 0 0 4px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 8px;
  margin: 12px 0;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  font-variant-numeric: tabular-nums;
}

.card .label {
  color: #666;
  font-size: 12px;
}

.card .value {
  font-size: 20px;
  font-weight: 600;
}

.card .sub {
  color: #888;
  font-size: 12px;
}

.card .sub.up {
  color: #d1242f;
}

.card .sub.down {
  color: #1a7f37;
}

.attention {
  padding: 8px 12px;
  border-left: 4px solid #d97706;
  border-radius: 4px;
  background: #fff8e6;
  font-size: 13px;
}

h2.attention-title {
  margin: 0 0 4px;
  padding: 0;
  border: 0;
  font-size: 14px;
}

.attention ul {
  margin: 0;
  padding-left: 18px;
}

.attention li.exit {
  color: #8a1c13;
}

.toc {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  margin: 12px 0 0;
  font-size: 14px;
}

.sort-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin: 6px 0 8px;
}

.sort-row button {
  padding: 3px 10px;
  font-size: 13px;
}

.sort-row button.active {
  background: #1f2328;
  border-color: #1f2328;
  color: #fff;
}

.table-wrap {
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}

th,
td {
  padding: 8px 10px;
  border-bottom: 1px solid #e5e7eb;
  text-align: left;
  vertical-align: top;
}

thead th {
  background: #f6f8fa;
  white-space: nowrap;
}

tbody th {
  font-weight: 600;
  min-width: 120px;
}

.num {
  text-align: right;
  white-space: nowrap;
}

.code {
  margin-left: 6px;
  color: #888;
  font-size: 12px;
  font-weight: normal;
}

.tag {
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 4px;
  background: #f1f3f5;
  color: #666;
  font-size: 11px;
  font-weight: normal;
}

.toggle {
  display: block;
  margin-top: 4px;
  padding: 1px 8px;
  font-size: 12px;
  font-weight: normal;
}

.sub {
  display: block;
  color: #888;
  font-size: 12px;
  font-weight: normal;
}

.stale {
  color: #b45309;
}

.near {
  color: #b45309;
  font-weight: 600;
}

tr.broken {
  background: #fff5f5;
}

tr.highlighted th,
tr.highlighted td {
  background: #eef4fd;
}

.notes {
  min-width: 120px;
}

.badge {
  display: inline-block;
  margin: 0 4px 2px 0;
  padding: 0 6px;
  border-radius: 4px;
  background: #f1f3f5;
  color: #555;
  font-size: 12px;
  white-space: nowrap;
}

.badge.warn {
  background: #fdecea;
  color: #8a1c13;
}

.badge.info {
  background: #fff4e6;
  color: #b45309;
}

.badge.signal {
  background: #eef4fd;
  color: #0b57d0;
}

.lots-row td {
  background: #fafbfc;
}

.lots {
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 13px;
}

.lots li {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 2px 12px;
  padding: 6px 0;
  border-bottom: 1px dashed #e5e7eb;
}

.lots li:last-child {
  border-bottom: 0;
}

.lot-main {
  flex: 1 1 320px;
}

.lot-sub {
  flex: 1 1 100%;
  order: 3;
  color: #666;
  font-size: 12px;
}

.lot-actions {
  display: flex;
  gap: 12px;
}

.sale-date {
  margin-right: 6px;
}

.closed-title {
  margin: 18px 0 6px;
  font-size: 15px;
}

.closed {
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 14px;
}

.closed > li {
  padding: 6px 0;
  border-bottom: 1px solid #f1f3f5;
}

.unmatched {
  color: #b42318;
}

.rules {
  padding-left: 18px;
}

/* 手機：一列一張卡片，欄名顯示在數字左側（與交易計畫頁相同） */
@media (max-width: 640px) {
  .stack thead {
    display: none;
  }

  .stack tr {
    display: block;
    padding: 8px 0;
    border-bottom: 1px solid #e5e7eb;
  }

  .stack th,
  .stack td {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    align-items: baseline;
    gap: 2px 8px;
    padding: 2px 4px;
    border: 0;
    text-align: right;
  }

  .stack th {
    display: block;
    text-align: left;
  }

  .stack td::before {
    content: attr(data-label);
    margin-right: auto;
    color: #666;
    font-size: 12px;
    text-align: left;
  }

  .stack .sub {
    display: inline;
    margin-left: 4px;
  }

  .stack .notes {
    justify-content: flex-start;
  }

  .stack .no-notes {
    display: none;
  }

  .stack .lots-row td {
    display: block;
    text-align: left;
  }

  .stack .lots-row td::before {
    content: none;
  }

  .toggle {
    display: inline-block;
    margin: 0 0 0 8px;
  }

  .cards {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
