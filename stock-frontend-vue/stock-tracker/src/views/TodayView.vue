<script setup>
import { computed, ref, watch } from 'vue'
import { fetchHoldings, fetchToday } from '../api/stocks.js'
import AddHoldingButton from '../components/AddHoldingButton.vue'
import { auth, startLogin } from '../utils/auth.js'
import { formatPercent, formatPrice, trendClass } from '../utils/format.js'
import { groupShortName } from '../utils/groups.js'
import { formatShares, holdingAlerts } from '../utils/holdings.js'
import { CORE_SYMBOL, allocation, frequencyLabel, planState, sharesFor, versionOf } from '../utils/plan.js'

// 今天：收盤後要知道的三件事——大盤能不能開新倉、庫存有沒有要處理的、有哪些候選
const SIGNAL_LABELS = { high_120: '創 120 日新高', golden_cross_trend: '季線上揚黃金交叉' }
const SHOWN = 10 // 候選先顯示 10 檔（R1 最多同時持有 10 檔）

const today = ref(null)
const error = ref('')
const loading = ref(true)
const holdings = ref(null)
const holdingsError = ref('')
const showAll = ref(false)

const plan = computed(() => planState.plan)
const frequency = computed(() => plan.value?.frequency ?? 'daily')

async function load() {
  loading.value = true
  error.value = ''
  try {
    today.value = await fetchToday(frequency.value)
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}

async function loadHoldings() {
  holdingsError.value = ''
  if (!auth.authenticated) {
    holdings.value = null
    return
  }
  try {
    holdings.value = await fetchHoldings()
  } catch (err) {
    holdingsError.value = err.message
  }
}

// 登入狀態與我的計畫確定後才載入（登入時候選包含自己的自選；每週操作時看本週的訊號）
watch(
  () => [auth.loaded, auth.authenticated, planState.loaded],
  ([authLoaded, , planLoaded]) => {
    if (!authLoaded || !planLoaded) return
    load()
    loadHoldings()
  },
  { immediate: true },
)
watch(frequency, (now, before) => now !== before && planState.loaded && load())

const market = computed(() => today.value?.market)
const candidates = computed(() => today.value?.candidates ?? [])
const shown = computed(() => (showAll.value ? candidates.value : candidates.value.slice(0, SHOWN)))
// 有計畫時 0050 是核心（長期持有）：不套用停利線，改在計畫卡顯示目前市值與目標
const alerts = computed(() =>
  holdingAlerts(holdings.value?.positions).filter((a) => !(plan.value && a.kind === 'exit' && a.symbol === CORE_SYMBOL)),
)
const money = computed(() => (plan.value ? allocation(plan.value) : null))
const corePosition = computed(() => holdings.value?.positions.find((p) => p.symbol === CORE_SYMBOL))
const held = computed(() => new Set((holdings.value?.positions ?? []).map((p) => p.symbol)))
const wan = (value) => `${(Math.round(value / 1000) / 10).toLocaleString('zh-TW')} 萬`
const moneyFormat = new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 0 })
const weekStart = computed(() => today.value?.signalsFrom?.slice(5).replace('-', '/'))
const exitAlerts = computed(() => alerts.value.filter((a) => a.kind === 'exit'))
const otherAlerts = computed(() => alerts.value.filter((a) => a.kind !== 'exit'))
</script>

<template>
  <section class="today">
    <h1>今天</h1>
    <p v-if="today" class="meta">{{ today.asOf }} 收盤後的資料，供下一個交易日參考</p>

    <p v-if="loading && !today" class="meta">載入中…</p>
    <p v-else-if="error" class="error">無法取得資料：{{ error }}</p>

    <!-- 我的計畫：還沒設定時請使用者先設定 -->
    <article v-if="planState.loaded && !plan" class="card setup-cta" aria-labelledby="today-setup">
      <h2 id="today-setup">先花 1 分鐘設定你的計畫</h2>
      <p>回答本金、能承受的虧損、多久看一次，就會建議 0050 與選股的比例，候選也會直接算出可以買幾股。</p>
      <RouterLink :to="{ name: 'setup' }" class="button primary">開始設定</RouterLink>
    </article>
    <article v-else-if="plan" class="card plan" aria-labelledby="today-plan">
      <h2 id="today-plan">
        我的計畫：{{ versionOf(plan.version).label }}・{{ frequencyLabel(plan.frequency) }}
        <RouterLink :to="{ name: 'setup' }" class="edit">調整</RouterLink>
      </h2>
      <p>
        投入 {{ wan(money.invest) }}：0050 {{ wan(money.core) }}、選股 {{ wan(money.satellite) }}（每檔約 {{ wan(money.perPosition) }}，最多 10 檔）<template
          v-if="money.cash > 0"
          >，保留現金 {{ wan(money.cash) }}</template
        >。
      </p>
      <p v-if="holdings" class="note">
        0050 目前{{ corePosition ? `市值 ${wan(corePosition.marketValue)}` : '沒有持有' }}，目標 {{ wan(money.core) }}；
        0050 長期持有、不套用停利線，每年第一個交易日調回目標比例。
      </p>
    </article>

    <template v-if="today">
      <!-- 1. 大盤 -->
      <article class="card market" :class="market.canOpen ? 'open' : 'closed'" aria-labelledby="today-market">
        <h2 id="today-market">
          <span class="status-icon" aria-hidden="true">{{ market.canOpen ? '●' : '■' }}</span>
          大盤{{ market.canOpen ? '偏多：可以買新股票' : '轉弱：暫停買新股票' }}
        </h2>
        <p>
          0050 收盤 <strong>{{ formatPrice(market.close) }}</strong>，{{ market.canOpen ? '在' : '跌破' }}季線（60 日均線
          {{ formatPrice(market.ma60) }}）{{ market.canOpen ? '之上' : '' }}，相差 {{ formatPercent(market.gapPercent) }}。
        </p>
        <p class="note">
          規則：0050 在季線之上才買新股票；已經持有的股票不受影響，照停利線處理。
          <RouterLink :to="{ name: 'research' }">為什麼這樣做</RouterLink>
        </p>
      </article>

      <!-- 2. 我的庫存 -->
      <article class="card" aria-labelledby="today-holdings">
        <h2 id="today-holdings">我的庫存</h2>
        <template v-if="!auth.loginEnabled">
          <p class="note">這個網站沒有開放登入，無法記錄庫存。</p>
        </template>
        <template v-else-if="!auth.authenticated">
          <p>登入後，這裡會列出庫存要處理的事，例如跌破停利線、即將除權息。</p>
          <button type="button" class="primary" @click="startLogin()">使用 Google 登入</button>
        </template>
        <p v-else-if="holdingsError" class="error">無法取得庫存：{{ holdingsError }}</p>
        <p v-else-if="!holdings" class="meta">載入中…</p>
        <template v-else-if="!holdings.positions.length">
          <p>還沒有庫存。在下方的候選或「選股」按 ＋ 加入，或到「庫存」新增。</p>
        </template>
        <template v-else>
          <ul v-if="exitAlerts.length" class="todo">
            <li v-for="a in exitAlerts" :key="a.key" class="exit">
              <span class="tag">要處理</span>
              <RouterLink :to="{ name: 'holdings', hash: `#holding-${a.symbol}` }">{{ a.name }}</RouterLink>
              {{ a.text }}，依規則下一個交易日開盤賣出
            </li>
          </ul>
          <ul v-if="otherAlerts.length" class="todo">
            <li v-for="a in otherAlerts" :key="a.key">
              <span class="tag info">留意</span>
              <RouterLink :to="{ name: 'holdings', hash: `#holding-${a.symbol}` }">{{ a.name }}</RouterLink>
              {{ a.text }}
            </li>
          </ul>
          <p v-if="!alerts.length" class="ok"><span aria-hidden="true">✓</span> 持股 {{ holdings.positions.length }} 檔，今天沒有要處理的事。</p>
          <p class="note"><RouterLink :to="{ name: 'holdings' }">查看全部庫存 →</RouterLink></p>
        </template>
      </article>

      <!-- 3. 候選 -->
      <article class="card" aria-labelledby="today-candidates">
        <h2 id="today-candidates">{{ today.frequency === 'weekly' ? '本週' : '今天' }}的候選 {{ candidates.length }} 檔</h2>
        <p class="note">
          {{ today.frequency === 'weekly' ? `本週（${weekStart} 起）` : '今天' }}出現「創 120 日新高」或「季線上揚時 MACD 黃金交叉」的股票，依近 120 日漲幅排序。
          每檔約投入選股資金的 10%、最多同時 10 檔；買進後從最高收盤回落 10% 就賣出。
        </p>
        <p v-if="!today.decisionDay" class="paused">每週操作：週五收盤後才決定，下週第一個交易日開盤買進；今天先看看就好。</p>
        <p v-if="!market.canOpen" class="paused">大盤轉弱，依規則暫停買新股票，以下只供觀察。</p>
        <p v-if="!candidates.length" class="meta">今天沒有符合條件的股票。</p>
        <ul v-else class="candidates">
          <li v-for="c in shown" :key="c.symbol">
            <div class="name">
              <RouterLink :to="{ name: 'stock-detail', params: { symbol: c.symbol } }">{{ c.name }}</RouterLink>
              <span class="symbol">{{ c.symbol }}</span>
              <span v-for="g in c.groups" :key="g" class="badge">{{ groupShortName(g) }}</span>
              <span v-if="c.mine" class="badge mine">我的自選</span>
            </div>
            <div class="price">
              <strong>{{ formatPrice(c.close) }}</strong>
              <span :class="trendClass(c.changePercent)">{{ formatPercent(c.changePercent) }}</span>
            </div>
            <div class="signals">
              <span v-for="s in c.signals" :key="s" class="chip">{{ SIGNAL_LABELS[s] ?? s }}</span>
              <span v-if="c.strength != null" class="strength">120 日 {{ formatPercent(c.strength) }}</span>
            </div>
            <div class="buy">
              <template v-if="held.has(c.symbol)"><span class="badge">已持有</span></template>
              <template v-else-if="plan && sharesFor(plan, c.close) > 0">
                約 <strong>{{ formatShares(sharesFor(plan, c.close)) }}</strong>
                <span class="hint">約 {{ moneyFormat.format(sharesFor(plan, c.close) * c.close) }} 元</span>
              </template>
              <template v-else-if="plan"><span class="hint">每檔金額不足買 1 股</span></template>
              <span class="hint">停損參考 {{ formatPrice(c.stopPrice) }}・開盤高於 {{ formatPrice(c.skipAbove) }} 不買</span>
            </div>
            <div class="action">
              <AddHoldingButton :stock="{ symbol: c.symbol, name: c.name, closePrice: c.close, tradeDate: today.asOf }" compact />
            </div>
          </li>
        </ul>
        <button v-if="candidates.length > SHOWN" type="button" class="more" @click="showAll = !showAll">
          {{ showAll ? '只顯示前 10 檔' : `顯示全部 ${candidates.length} 檔` }}
        </button>
        <p class="note">
          股數與停損參考以今天收盤價計算；實際以買進價為準，停損之後隨最高收盤往上移。<template v-if="!plan">設定計畫後會算出可買幾股。</template>
          候選是依規則篩出的結果，不是投資建議。
        </p>
      </article>
    </template>
  </section>
</template>

<style scoped>
.today {
  display: grid;
  gap: 16px;
}

.today h1 {
  margin-bottom: 0;
}

.meta {
  margin: -8px 0 0;
  color: #666;
  font-size: 14px;
}

.card h2 {
  margin: 0 0 8px;
  font-size: 18px;
}

.card p {
  margin: 0 0 8px;
  line-height: 1.6;
}

.note {
  color: #57606a;
  font-size: 13px;
}

.error {
  color: #d1242f;
}

/* 大盤：狀態以圖示＋文字表示，不只靠顏色 */
.market.open {
  border-left: 4px solid #1a7f37;
}

.market.closed {
  border-left: 4px solid #bf8700;
  background: #fff8e1;
}

.market.open .status-icon {
  color: #1a7f37;
}

.market.closed .status-icon {
  color: #bf8700;
}

.status-icon {
  margin-right: 4px;
  font-size: 14px;
  vertical-align: 2px;
}

.paused {
  padding: 8px 12px;
  border-radius: 6px;
  background: #fff8e1;
  color: #7d5a00;
  font-size: 14px;
}

.primary {
  border: 0;
  border-radius: 6px;
  background: #0b57d0;
  color: #fff;
}

.button {
  display: inline-block;
  padding: 8px 16px;
}

.button:hover {
  text-decoration: none;
}

.setup-cta {
  border: 2px solid #0b57d0;
  background: #f5f9ff;
}

.plan h2 {
  display: flex;
  gap: 12px;
  align-items: baseline;
}

.edit {
  font-size: 14px;
  font-weight: 400;
}

.todo {
  display: grid;
  gap: 8px;
  margin: 0 0 8px;
  padding: 0;
  list-style: none;
}

.todo li {
  line-height: 1.6;
}

.tag {
  display: inline-block;
  margin-right: 6px;
  padding: 0 6px;
  border-radius: 4px;
  background: #d1242f;
  color: #fff;
  font-size: 12px;
}

.tag.info {
  background: #57606a;
}

.ok {
  color: #1a7f37;
}

/* 候選：桌面為一行一檔的五欄，手機改為兩行 */
.candidates {
  margin: 8px 0;
  padding: 0;
  list-style: none;
}

.candidates li {
  display: grid;
  grid-template-columns: minmax(180px, 1.4fr) 120px minmax(160px, 1.3fr) minmax(150px, 1fr) 40px;
  gap: 4px 12px;
  align-items: center;
  padding: 10px 0;
  border-top: 1px solid #eef0f2;
}

.name a {
  font-weight: 600;
}

.symbol {
  margin: 0 6px;
  color: #666;
  font-size: 13px;
}

.badge {
  margin-right: 4px;
  padding: 0 5px;
  border-radius: 4px;
  background: #f1f3f5;
  color: #57606a;
  font-size: 12px;
}

.badge.mine {
  background: #e7f0ff;
  color: #0b57d0;
}

.price {
  display: flex;
  gap: 8px;
  align-items: baseline;
  font-variant-numeric: tabular-nums;
}

.chip {
  display: inline-block;
  margin: 0 4px 2px 0;
  padding: 1px 6px;
  border: 1px solid #d0d7de;
  border-radius: 10px;
  font-size: 12px;
}

.strength {
  color: #57606a;
  font-size: 12px;
  white-space: nowrap;
}

.buy {
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}

.hint {
  display: block;
  color: #666;
  font-size: 12px;
}

.action {
  text-align: right;
}

.more {
  border: 1px solid #ced4da;
  border-radius: 6px;
  background: #fff;
}

@media (max-width: 720px) {
  .candidates li {
    grid-template-columns: 1fr auto;
  }

  .price {
    justify-content: flex-end;
  }

  .signals,
  .buy {
    grid-column: 1;
  }

  .action {
    grid-column: 2;
    grid-row: 2 / span 2;
    align-self: center;
  }
}
</style>
