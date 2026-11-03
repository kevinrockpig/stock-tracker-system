<script setup>
import { computed, defineAsyncComponent, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchPaper } from '../api/stocks.js'
import { EXIT_REASONS } from '../utils/backtest.js'
import { formatRate, trendClass } from '../utils/format.js'

// 圖表含 ECharts，資金曲線區塊顯示時才載入
const EquityChart = defineAsyncComponent(() => import('../components/EquityChart.vue'))

// 計畫的執行結果（Python scraper/paper.py 的 status）
const STATUS = {
  pending: '待執行',
  executed: '已執行',
  skipped_no_slot: '略過：沒有空位',
  skipped_limit_up: '略過：開盤接近漲停',
  skipped_held: '略過：已持有',
  skipped_no_data: '略過：沒有行情',
}

// 實測少於這麼多交易日（約一季）時提醒：報酬主要反映運氣
const SHORT_PERIOD_DAYS = 60

const CURVE_COLORS = { account: '#0b57d0', benchmark: '#868e96', etf: '#d97706' }

const route = useRoute()
const router = useRouter()

const accounts = ref([])
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    accounts.value = await fetchPaper()
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}
load()

// 觀察中的策略可能不只一個：網址記錄目前看的是哪一個（例如 /plan?account=high_120/trailing_nocap）
const keyOf = (a) => `${a.strategy.code}/${a.exitRule.code}`
const account = computed(() => accounts.value.find((a) => keyOf(a) === route.query.account) ?? accounts.value[0] ?? null)
function selectAccount(a) {
  router.replace({ query: { ...route.query, account: keyOf(a) } })
}

const sells = computed(() => account.value?.nextPlans.filter((p) => p.action === 'sell') ?? [])
const buys = computed(() => account.value?.nextPlans.filter((p) => p.action === 'buy') ?? [])
// 開盤賣出騰出的空位當天就能買，所以空位 = 總數 − 持股 + 待賣出
const freeSlots = computed(() => {
  const a = account.value
  return a ? Math.max(0, a.slots - a.positions.length + sells.value.length) : 0
})
// 每檔投入前一日總資金的 1/slots，現金不足時以剩餘現金為限
const budget = computed(() => (account.value ? account.value.equity / account.value.slots : 0))
const sellingSymbols = computed(() => new Set(sells.value.map((p) => p.symbol)))

// 距出場線：(收盤 − 出場線) ÷ 收盤；越小越接近出場，排在前面
const distanceOf = (p) => (p.exitLine == null || !p.price ? null : (p.price - p.exitLine) / p.price)
const positions = computed(() =>
  [...(account.value?.positions ?? [])].sort((a, b) => {
    const x = distanceOf(a)
    const y = distanceOf(b)
    if (x == null || y == null) return x == null ? 1 : -1
    return x - y
  }),
)
const invested = computed(() => positions.value.reduce((sum, p) => sum + p.marketValue, 0))

const curve = computed(() => {
  const points = account.value?.equityCurve ?? []
  return {
    dates: points.map((p) => p.date),
    series: [
      { name: '模擬帳戶', color: CURVE_COLORS.account, values: points.map((p) => p.equity) },
      { name: '同一批股票買進持有', color: CURVE_COLORS.benchmark, values: points.map((p) => p.benchmark) },
      { name: '0050 買進持有', color: CURVE_COLORS.etf, values: points.map((p) => p.etf), dashed: true },
    ],
  }
})

const moneyFormat = new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 0 })
// 出場線、放棄價等是公式算出的價格（小數很多），一律顯示到小數 2 位
const priceFormat = new Intl.NumberFormat('zh-TW', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const formatPrice = (value) => (value == null ? '—' : priceFormat.format(value))
const money = (value) => (value == null ? '—' : moneyFormat.format(value))
const sharesText = (value) => (value == null ? '—' : `${moneyFormat.format(value)} 股`)
const reasonText = (plan) =>
  plan.action === 'buy' ? (account.value?.strategy.code === plan.reason ? account.value.strategy.name : plan.reason) : EXIT_REASONS[plan.reason] ?? plan.reason
const stockLink = (symbol) => ({ name: 'stock-detail', params: { symbol } })

const SECTIONS = [
  { id: 'plan-next', label: '下一個交易日' },
  { id: 'plan-positions', label: '目前持股' },
  { id: 'plan-curve', label: '資金曲線' },
  { id: 'plan-latest', label: '執行結果' },
  { id: 'plan-closed', label: '已出場交易' },
]

</script>

<template>
  <section>
    <h1>交易計畫（往後實測）</h1>
    <p class="intro">
      用<strong>事先決定的策略</strong>操作一個模擬帳戶：每個交易日收盤後依當天訊號產生計畫，下一個交易日開盤依計畫買賣。每天只處理一次、紀錄不事後修改，用來檢驗回測結果在「沒看過的未來」是否仍然成立。這是模擬，不是投資建議。<RouterLink
        :to="{ name: 'about', hash: '#paper' }"
        >規則說明</RouterLink
      >
    </p>

    <p v-if="loading && !accounts.length" class="status">載入中…（資料庫閒置後首次連線可能需要數秒）</p>
    <div v-else-if="error" class="status error">
      <p>{{ error }}</p>
      <button type="button" @click="load">重試</button>
    </div>
    <p v-else-if="!account && !loading" class="status">還沒有模擬帳戶（每日更新程式執行後建立）。</p>

    <template v-else-if="account">
      <div v-if="accounts.length > 1" class="filter-row" role="group" aria-label="觀察中的策略">
        <button
          v-for="a in accounts"
          :key="keyOf(a)"
          type="button"
          :class="{ active: keyOf(a) === keyOf(account) }"
          @click="selectAccount(a)"
        >
          {{ a.strategy.name }} · {{ a.exitRule.name }}
        </button>
      </div>

      <p class="meta">
        觀察中策略：<strong>{{ account.strategy.name }}</strong>，出場：<strong>{{ account.exitRule.name }}</strong> · 最多同時持有
        {{ account.slots }} 檔 · 自 {{ account.inceptionDate }} 起，已處理到 {{ account.lastProcessed }}（{{ account.tradingDays }}
        個交易日）
      </p>

      <div class="cards">
        <div class="card">
          <span class="label">總資金</span>
          <span class="value">{{ money(account.equity) }}</span>
          <span class="sub">起始 {{ money(account.initialCapital) }}</span>
        </div>
        <div class="card">
          <span class="label">模擬帳戶報酬</span>
          <span class="value" :class="trendClass(account.totalReturn)">{{ formatRate(account.totalReturn) }}</span>
          <span class="sub">最大回撤 {{ formatRate(account.maxDrawdown, { signed: false }) }}</span>
        </div>
        <div class="card">
          <span class="label">同一批股票買進持有</span>
          <span class="value" :class="trendClass(account.benchmarkReturn)">{{ formatRate(account.benchmarkReturn) }}</span>
          <span class="sub">比較的基準</span>
        </div>
        <div class="card">
          <span class="label">0050 買進持有</span>
          <span class="value" :class="trendClass(account.etfReturn)">{{ formatRate(account.etfReturn) }}</span>
          <span class="sub">參考</span>
        </div>
        <div class="card">
          <span class="label">持股 / 現金</span>
          <span class="value">{{ account.positions.length }} / {{ account.slots }} 檔</span>
          <span class="sub">現金 {{ money(account.cash) }}</span>
        </div>
      </div>
      <p v-if="account.tradingDays < SHORT_PERIOD_DAYS" class="caution">
        實測才 {{ account.tradingDays }} 個交易日，報酬高低主要反映這段期間的運氣；至少累積數個月、經歷過漲跌，比較才有意義。
      </p>

      <nav class="toc" aria-label="本頁段落">
        <RouterLink v-for="section in SECTIONS" :key="section.id" :to="{ query: route.query, hash: `#${section.id}` }">{{
          section.label
        }}</RouterLink>
      </nav>

      <!-- 1. 下一個交易日的計畫 -->
      <h2 id="plan-next">下一個交易日的計畫</h2>
      <p class="meta">依 {{ account.lastProcessed }} 收盤產生，於下一個交易日開盤執行（遇假日順延）。</p>

      <p v-if="!sells.length && !buys.length" class="status">沒有計畫：下一個交易日不買也不賣。</p>

      <template v-if="sells.length">
        <h3>賣出 {{ sells.length }} 檔<span class="hint">開盤賣出</span></h3>
        <div class="table-wrap">
          <table class="stack">
            <thead>
              <tr>
                <th>股票</th>
                <th>原因</th>
                <th class="num">收盤價</th>
                <th class="num">出場線</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="plan in sells" :key="`${plan.planDate}/${plan.symbol}`">
                <th scope="row">
                  <RouterLink :to="stockLink(plan.symbol)">{{ plan.name }}</RouterLink>
                  <span class="code">{{ plan.symbol }}</span>
                </th>
                <td data-label="原因">
                  {{ reasonText(plan) }}
                  <span v-if="plan.planDate !== account.lastProcessed" class="sub">{{ plan.planDate }} 的計畫（當天無行情，順延）</span>
                </td>
                <td class="num" data-label="收盤價">{{ formatPrice(plan.closePrice) }}</td>
                <td class="num" data-label="出場線">{{ plan.trailingLine == null ? '季線' : formatPrice(plan.trailingLine) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <template v-if="buys.length">
        <h3>
          買進候選 {{ buys.length }} 檔<span class="hint"
            >空位 {{ freeSlots }} 檔 · 依順序買進，額滿後其餘略過 · 每檔約 {{ money(budget) }} 元</span
          >
        </h3>
        <div class="table-wrap">
          <table class="stack">
            <thead>
              <tr>
                <th class="num">順序</th>
                <th>股票</th>
                <th class="num">收盤價</th>
                <th class="num">開盤高於此價就不買</th>
                <th class="num">ATR 停損參考</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(plan, index) in buys" :key="`${plan.planDate}/${plan.symbol}`" :class="{ overflow: index >= freeSlots }">
                <td class="num order" data-label="順序">{{ index + 1 }}</td>
                <th scope="row">
                  <RouterLink :to="stockLink(plan.symbol)">{{ plan.name }}</RouterLink>
                  <span class="code">{{ plan.symbol }}</span>
                  <span v-if="index >= freeSlots" class="sub">超過空位，預計略過</span>
                </th>
                <td class="num" data-label="收盤價">{{ formatPrice(plan.closePrice) }}</td>
                <td class="num" data-label="開盤高於此價就不買">{{ formatPrice(plan.skipAbove) }}</td>
                <td class="num" data-label="ATR 停損參考">{{ formatPrice(plan.atrStop) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="note">
          買進訊號：{{ account.strategy.name }}。順序在訊號產生時隨機決定（同一天多檔時不挑選）。「開盤高於此價」約為漲 9.5%，接近漲停、實際很難買到，所以放棄。
          ATR 停損只是參考，這個策略實際以移動停利線出場。
        </p>
      </template>

      <!-- 2. 目前持股 -->
      <h2 id="plan-positions">目前持股 {{ positions.length }} 檔</h2>
      <p v-if="!positions.length" class="status">目前沒有持股。</p>
      <template v-else>
        <p class="meta">
          依「距出場線」由近到遠排列：收盤跌破出場線，下一個交易日開盤賣出。持股市值 {{ money(invested) }}，占總資金
          {{ formatRate(invested / account.equity, { digits: 0, signed: false }) }}。
        </p>
        <div class="table-wrap">
          <table class="stack">
            <thead>
              <tr>
                <th>股票</th>
                <th class="num">進場</th>
                <th class="num">收盤價</th>
                <th class="num">報酬</th>
                <th class="num">出場線</th>
                <th class="num">距出場線</th>
                <th class="num">占比</th>
                <th class="num">持有</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in positions" :key="p.symbol" :class="{ selling: sellingSymbols.has(p.symbol) }">
                <th scope="row">
                  <RouterLink :to="stockLink(p.symbol)">{{ p.name }}</RouterLink>
                  <span class="code">{{ p.symbol }}</span>
                  <span v-if="sellingSymbols.has(p.symbol)" class="badge sell">下一個交易日賣出</span>
                </th>
                <td class="num" data-label="進場">
                  {{ formatPrice(p.entryPrice) }}<span class="sub">{{ p.entryDate }}</span>
                </td>
                <td class="num" data-label="收盤價">
                  {{ formatPrice(p.price) }}
                  <span v-if="p.priceDate !== account.lastProcessed" class="sub stale">{{ p.priceDate }}</span>
                </td>
                <td class="num" data-label="報酬">
                  <span :class="trendClass(p.unrealizedReturn)">{{ formatRate(p.unrealizedReturn) }}</span>
                  <span v-if="p.dividends > 0" class="sub">含股利 {{ money(p.dividends) }}</span>
                </td>
                <td class="num" data-label="出場線">{{ formatPrice(p.exitLine) }}</td>
                <td class="num" data-label="距出場線">
                  <span :class="{ near: distanceOf(p) != null && distanceOf(p) < 0.05 }">{{
                    formatRate(distanceOf(p), { digits: 1, signed: false })
                  }}</span>
                </td>
                <td class="num" data-label="占比">{{ formatRate(p.weight, { digits: 1, signed: false }) }}</td>
                <td class="num" data-label="持有">{{ p.heldDays }} 天</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="note">
          報酬以收盤價賣出試算，已扣賣出手續費與證交稅、含已領股利。出場線 = 進場後最高收盤價 − 3 × ATR(14)，隨股價創高而上移、不會下移。
          「距出場線」小於 5% 以橘色標示。持有天數為交易日。
        </p>
      </template>

      <!-- 3. 資金曲線 -->
      <h2 id="plan-curve">資金曲線</h2>
      <p class="meta">
        兩個基準都從啟用日收盤買進，換算成與模擬帳戶相同的起始金額（含股利、不計交易成本）。比較的基準是「同一批股票買進持有」；0050 只作參考。
      </p>
      <p v-if="curve.dates.length < 2" class="status">累積 2 個交易日以上才會畫出曲線（目前 {{ curve.dates.length }} 天）。</p>
      <EquityChart v-else :dates="curve.dates" :series="curve.series" :base="account.initialCapital" />

      <!-- 4. 最近一次的執行結果 -->
      <h2 id="plan-latest">最近一次的執行結果</h2>
      <p class="meta">{{ account.lastProcessed }} 開盤執行的計畫（前一個交易日收盤產生）。</p>
      <p v-if="!account.latestResults.length" class="status">這一天沒有要執行的計畫。</p>
      <div v-else class="table-wrap">
        <table class="stack">
          <thead>
            <tr>
              <th>動作</th>
              <th>股票</th>
              <th>結果</th>
              <th>原因</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="plan in account.latestResults" :key="`${plan.planDate}/${plan.symbol}/${plan.action}`">
              <td data-label="動作">
                <span class="action" :class="plan.action">{{ plan.action === 'buy' ? '買進' : '賣出' }}</span>
              </td>
              <th scope="row">
                <RouterLink :to="stockLink(plan.symbol)">{{ plan.name }}</RouterLink>
                <span class="code">{{ plan.symbol }}</span>
              </th>
              <td data-label="結果" :class="{ skipped: plan.status !== 'executed' }">{{ STATUS[plan.status] ?? plan.status }}</td>
              <td data-label="原因">{{ reasonText(plan) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 5. 已出場交易 -->
      <h2 id="plan-closed">已出場交易 {{ account.closedTrades.length }} 筆</h2>
      <p v-if="!account.closedTrades.length" class="status">還沒有出場的交易。</p>
      <div v-else class="table-wrap">
        <table class="stack">
          <thead>
            <tr>
              <th>股票</th>
              <th class="num">進場</th>
              <th class="num">出場</th>
              <th>出場原因</th>
              <th class="num">持有</th>
              <th class="num">報酬</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in account.closedTrades" :key="`${t.symbol}/${t.entryDate}`">
              <th scope="row">
                <RouterLink :to="stockLink(t.symbol)">{{ t.name }}</RouterLink>
                <span class="code">{{ t.symbol }}</span>
              </th>
              <td class="num" data-label="進場">{{ formatPrice(t.entryPrice) }}<span class="sub">{{ t.entryDate }}</span></td>
              <td class="num" data-label="出場">{{ formatPrice(t.exitPrice) }}<span class="sub">{{ t.exitDate }}</span></td>
              <td data-label="出場原因">{{ EXIT_REASONS[t.exitReason] ?? t.exitReason }}</td>
              <td class="num" data-label="持有">{{ t.heldDays }} 天</td>
              <td class="num" data-label="報酬">
                <span :class="trendClass(t.netReturn)">{{ formatRate(t.netReturn) }}</span>
                <span v-if="t.dividends > 0" class="sub">含股利</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
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

h3 {
  margin: 14px 0 6px;
  font-size: 15px;
}

.hint {
  margin-left: 8px;
  color: #666;
  font-size: 13px;
  font-weight: normal;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 8px 0;
}

.filter-row button.active {
  background: #1f2328;
  border-color: #1f2328;
  color: #fff;
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

.caution {
  padding: 8px 12px;
  border-left: 4px solid #d97706;
  border-radius: 4px;
  background: #fff8e6;
  font-size: 13px;
}

.toc {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  margin: 12px 0 0;
  font-size: 14px;
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

.sub {
  display: block;
  color: #888;
  font-size: 12px;
  font-weight: normal;
}

.stale {
  color: #b45309;
}

.badge.sell {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 4px;
  background: #fff4e6;
  color: #b45309;
  font-size: 12px;
}

tr.selling {
  background: #fffbf2;
}

tr.overflow th,
tr.overflow td {
  color: #999;
}

.near {
  color: #b45309;
  font-weight: 600;
}

.action {
  padding: 0 6px;
  border-radius: 4px;
  font-size: 12px;
}

.action.buy {
  background: #fdecee;
  color: #d1242f;
}

.action.sell {
  background: #e7f5ec;
  color: #1a7f37;
}

.skipped {
  color: #b45309;
}

/* 手機：一列一張卡片，欄名顯示在數字左側 */
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
    margin-right: auto; /* 欄名靠左，數值群組靠右 */
    color: #666;
    font-size: 12px;
    text-align: left;
  }

  .stack .sub {
    display: inline;
    margin-left: 4px;
  }

  .stack th .sub {
    display: block;
    margin-left: 0;
  }

  .cards {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
