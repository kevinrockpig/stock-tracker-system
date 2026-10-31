<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchBacktests, fetchScreener, fetchStocks, fetchStrategies } from '../api/stocks.js'
import BacktestResults from '../components/BacktestResults.vue'
import { falsePassChance, groupComparisons } from '../utils/backtest.js'
import { findHashTarget } from '../utils/scroll.js'
import { formatChange, formatPercent, formatPrice, formatRate, trendClass } from '../utils/format.js'

// 區間以「交易日」計算，週末、國定假日與颱風假不計入
const DAY_OPTIONS = [
  { days: 1, label: '最新交易日' },
  { days: 5, label: '近 5 日' },
  { days: 10, label: '近 10 日' },
  { days: 20, label: '近 20 日' },
]
const WIDEST_DAYS = DAY_OPTIONS.at(-1).days
// 回測範圍：觀察名單合併（API 的 scope=ALL）或單一股票
const ALL_SCOPE = 'ALL'

const route = useRoute()
const router = useRouter()

const strategies = ref([])
const result = ref(null)
const loading = ref(false)
const error = ref('')

// 目前選擇由網址決定；網址參數無效時改用預設值
const selectedCode = computed(() => {
  const code = route.query.strategy
  return strategies.value.some((s) => s.code === code) ? code : strategies.value[0]?.code
})
const selectedDays = computed(() => {
  const days = Number(route.query.days)
  return DAY_OPTIONS.some((option) => option.days === days) ? days : DAY_OPTIONS[0].days
})
const selectedStrategy = computed(() => strategies.value.find((s) => s.code === selectedCode.value))

// 回測範圍的選項來自股票清單；清單無法取得時只提供合併結果
const stocks = ref([])
const selectedScope = computed(() => {
  const scope = route.query.scope
  return stocks.value.some((stock) => stock.symbol === scope) ? scope : ALL_SCOPE
})

function select(changes) {
  const query = { strategy: selectedCode.value, days: selectedDays.value, scope: selectedScope.value, ...changes }
  if (query.scope === ALL_SCOPE) delete query.scope // 預設值不寫進網址
  router.replace({ query })
}

async function loadStrategies() {
  loading.value = true
  error.value = ''
  try {
    strategies.value = await fetchStrategies()
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}

// 快速切換時只採用最後一次請求的結果，避免較慢的舊回應覆蓋新結果
let latestRequest = 0

async function loadResult() {
  if (!selectedCode.value) return
  const request = ++latestRequest
  loading.value = true
  error.value = ''
  try {
    const data = await fetchScreener(selectedCode.value, selectedDays.value)
    if (request === latestRequest) result.value = data
  } catch (err) {
    if (request === latestRequest) {
      error.value = err.message
      result.value = null
    }
  } finally {
    if (request === latestRequest) loading.value = false
  }
}

function retry() {
  if (strategies.value.length) loadResult()
  else loadStrategies()
}

// 回測統計每天收盤後才更新一次，同一範圍在這個頁面只查詢一次；合併結果一定會載入（策略說明下方的摘要使用）
const backtests = ref({})
const backtestLoading = ref(false)
const backtestError = ref('')
let latestBacktest = 0

async function loadBacktest(scope) {
  if (backtests.value[scope]) return
  const request = ++latestBacktest
  backtestLoading.value = true
  backtestError.value = ''
  try {
    const data = await fetchBacktests(scope)
    backtests.value = { ...backtests.value, [scope]: data }
  } catch (err) {
    if (request === latestBacktest) backtestError.value = err.message
  } finally {
    if (request === latestBacktest) backtestLoading.value = false
  }
}

function retryBacktest() {
  loadBacktest(ALL_SCOPE)
  loadBacktest(selectedScope.value)
}

const strategyResults = (data) => data?.results.filter((r) => r.strategy.code === selectedCode.value) ?? []
const allBacktest = computed(() => backtests.value[ALL_SCOPE])
const scopeBacktest = computed(() => backtests.value[selectedScope.value])
const scopeResults = computed(() => strategyResults(scopeBacktest.value))
const exitRuleName = (code) => allBacktest.value?.exitRules.find((rule) => rule.code === code)?.name ?? code

// 策略說明下方的一句話摘要：合併結果中哪些出場方式有優勢
const edgeRules = computed(() => strategyResults(allBacktest.value).filter((r) => r.hasEdge).map((r) => r.exitRule))

// 從選股結果進入個股頁時，K 線圖預先標出這個策略的進出場點：優先用有優勢的出場方式，否則用第一種
const detailExitRule = computed(() => edgeRules.value[0] ?? allBacktest.value?.exitRules[0]?.code)
function detailLink(symbol) {
  const query = detailExitRule.value ? { strategy: selectedCode.value, exitRule: detailExitRule.value } : {}
  return { name: 'stock-detail', params: { symbol }, query }
}

const scopeName = computed(() => {
  if (selectedScope.value === ALL_SCOPE) return stocks.value.length ? `全部 ${stocks.value.length} 檔合併` : '全部合併'
  return stocks.value.find((stock) => stock.symbol === selectedScope.value)?.name ?? selectedScope.value
})

onMounted(() => {
  loadStrategies()
  fetchStocks()
    .then((list) => (stocks.value = list))
    .catch(() => {}) // 只影響回測範圍的選項，錯誤訊息由其他查詢顯示
})
// 策略清單載入後 selectedCode 才有值，此時會觸發第一次查詢；之後每次切換策略或區間都重新查詢
watch([selectedCode, selectedDays], loadResult)
// 從個股頁連到 #backtest 時，區塊要等資料載入後才出現；等上方的選股結果也載入完（版面不再變動）才捲過去
let pendingHash = route.hash === '#backtest'
const pageReady = computed(() => Boolean(scopeBacktest.value) && !loading.value && Boolean(result.value || error.value))
watch(pageReady, async (ready) => {
  if (!ready || !pendingHash) return
  pendingHash = false
  await nextTick()
  findHashTarget(route.hash)?.scrollIntoView()
})
watch(selectedScope, (scope) => {
  loadBacktest(ALL_SCOPE)
  loadBacktest(scope)
}, { immediate: true })
</script>

<template>
  <section>
    <h1>策略選股</h1>

    <div v-if="strategies.length" class="filters">
      <div class="filter-row" role="group" aria-label="選股策略">
        <button
          v-for="strategy in strategies"
          :key="strategy.code"
          type="button"
          :class="{ active: strategy.code === selectedCode }"
          @click="select({ strategy: strategy.code })"
        >
          {{ strategy.name }}
        </button>
      </div>
      <p v-if="selectedStrategy" class="description">
        條件：{{ selectedStrategy.description }}
        <RouterLink :to="{ name: 'about', hash: '#strategies' }">策略說明</RouterLink>
      </p>
      <p v-if="allBacktest && selectedStrategy" class="description">
        歷史回測（觀察名單合併）：<strong v-if="edgeRules.length" class="edge-text">★ {{ edgeRules.map(exitRuleName).join('、') }}有優勢</strong
        ><template v-else>各種出場方式都<strong>無明顯優勢</strong></template>
        <RouterLink :to="{ query: route.query, hash: '#backtest' }" class="jump">看回測結果 ↓</RouterLink>
      </p>

      <div class="filter-row" role="group" aria-label="查詢區間">
        <button
          v-for="option in DAY_OPTIONS"
          :key="option.days"
          type="button"
          class="small"
          :class="{ active: option.days === selectedDays }"
          @click="select({ days: option.days })"
        >
          {{ option.label }}
        </button>
      </div>
    </div>

    <p v-if="loading" class="status">載入中…（資料庫閒置後首次連線可能需要數秒）</p>

    <div v-else-if="error" class="status error">
      <p>{{ error }}</p>
      <button type="button" @click="retry">重試</button>
    </div>

    <template v-else-if="result">
      <p v-if="!result.marketDate" class="status">
        資料庫目前沒有行情資料，請先執行一鍵更新（scripts/update-stock-data.command）。
      </p>

      <template v-else>
        <p class="meta">
          資料區間 {{ result.fromDate }} ~ {{ result.marketDate }}（{{ result.days }} 個交易日），符合
          {{ result.stocks.length }} 檔
        </p>

        <div v-if="result.stocks.length === 0" class="status">
          <p>這段期間沒有股票符合「{{ result.strategy.name }}」。</p>
          <button v-if="result.days < WIDEST_DAYS" type="button" @click="select({ days: WIDEST_DAYS })">
            改看近 {{ WIDEST_DAYS }} 日
          </button>
        </div>

        <ul v-else class="cards">
          <li v-for="match in result.stocks" :key="match.symbol">
            <RouterLink :to="detailLink(match.symbol)" class="card">
              <div class="card-head">
                <strong>{{ match.name }}</strong>
                <span class="symbol">{{ match.symbol }}</span>
                <span v-if="result.days > 1 && match.lastSignalDate === result.marketDate" class="badge">
                  最新交易日符合
                </span>
              </div>
              <div class="price-line">
                <span class="price">{{ formatPrice(match.quote?.closePrice) }}</span>
                <span :class="trendClass(match.quote?.change)">
                  {{ formatChange(match.quote?.change) }}（{{ formatPercent(match.quote?.changePercent) }}）
                </span>
              </div>
              <dl>
                <dt>最近符合</dt>
                <dd>{{ match.lastSignalDate }}</dd>
                <dt>區間內符合</dt>
                <dd>{{ match.signalCount }} 個交易日</dd>
                <dt>報價日期</dt>
                <dd>{{ match.quote?.tradeDate ?? '—' }}（日收盤價）</dd>
              </dl>
              <span class="more">查看 K 線圖與歷史進出場點 →</span>
            </RouterLink>
          </li>
        </ul>
      </template>
    </template>

    <p class="note">
      訊號由每日更新程式依技術指標計算；「黃金交叉」與「突破 20 日均線」只在發生當天算一次。點選卡片查看 K 線圖與指標。
    </p>

    <section v-if="selectedStrategy" id="backtest" class="backtest">
      <h2>歷史回測：{{ selectedStrategy.name }}</h2>
      <p class="meta">
        每次出現訊號，於下一個交易日開盤買進，再依 4 種方式出場；報酬已扣手續費與證交稅、含現金股利，並與「同一檔股票任意一天進場」比較。<RouterLink :to="{ name: 'about', hash: '#backtest' }">回測方法與限制</RouterLink>
      </p>

      <div class="filter-row" role="group" aria-label="回測範圍">
        <button
          type="button"
          class="small"
          :class="{ active: selectedScope === ALL_SCOPE }"
          @click="select({ scope: ALL_SCOPE })"
        >
          全部合併
        </button>
        <button
          v-for="stock in stocks"
          :key="stock.symbol"
          type="button"
          class="small"
          :class="{ active: selectedScope === stock.symbol }"
          @click="select({ scope: stock.symbol })"
        >
          {{ stock.name }}
        </button>
      </div>

      <template v-if="scopeBacktest">
        <p class="meta">
          {{ scopeName }} · 回測期間 {{ scopeBacktest.periodStart }} ~ {{ scopeBacktest.periodEnd }}（前後段以
          {{ scopeBacktest.splitDate }} 切分）<template v-if="scopeResults.length">
            · 同期買進持有 {{ formatRate(scopeResults[0].buyHoldReturn, { digits: 1 }) }}{{ selectedScope === ALL_SCOPE ? '（各檔平均）' : '' }}</template
          >
        </p>
        <BacktestResults
          v-if="scopeResults.length"
          :results="scopeResults"
          :exit-rules="scopeBacktest.exitRules"
          :symbol="selectedScope === ALL_SCOPE ? null : selectedScope"
        />
        <p v-else class="status">這個範圍還沒有「{{ selectedStrategy.name }}」的回測結果。</p>
        <p v-if="selectedScope === ALL_SCOPE && stocks.length" class="meta">選擇個股，可在 K 線圖查看每筆交易的進出場點。</p>
        <p class="caution">
          同時比較了 {{ groupComparisons(scopeBacktest) }} 組（策略 × 出場方式）。即使全都沒有真正的優勢，也可能有組別剛好通過（機率最多約
          {{ falsePassChance(groupComparisons(scopeBacktest)) }}%）；「有優勢」代表值得繼續觀察，不是保證。過去的績效不代表未來。
        </p>
      </template>
      <p v-else-if="backtestLoading" class="status">回測結果載入中…</p>
      <div v-else-if="backtestError" class="status error">
        <p>{{ backtestError }}</p>
        <button type="button" @click="retryBacktest">重試</button>
      </div>
    </section>
  </section>
</template>

<style scoped>
.filters {
  margin-bottom: 12px;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.filter-row button.active {
  background: #1f2328;
  border-color: #1f2328;
  color: #fff;
}

.filter-row button.small {
  font-size: 13px;
  padding: 4px 10px;
}

.description,
.meta,
.note,
.caution {
  margin: 4px 0 12px;
  color: #666;
  font-size: 13px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
  margin: 0 0 16px;
  padding: 0;
  list-style: none;
}

.card {
  display: block;
  height: 100%;
  padding: 14px 16px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  color: inherit;
  box-sizing: border-box;
}

.card:hover {
  border-color: #0b57d0;
  box-shadow: 0 2px 6px rgba(11, 87, 208, 0.12);
  text-decoration: none;
}

.card-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
}

.symbol {
  color: #666;
  font-size: 13px;
}

.badge {
  padding: 1px 6px;
  border-radius: 4px;
  background: #fff4e6;
  color: #d9480f;
  font-size: 12px;
}

.price-line {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin: 8px 0;
}

.price {
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

dl {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 2px 10px;
  margin: 0 0 8px;
  font-size: 13px;
}

dt {
  color: #666;
}

dd {
  margin: 0;
}

.more {
  color: #0b57d0;
  font-size: 13px;
}

.edge-text {
  color: #b45309;
}

.jump {
  margin-left: 8px;
}

.backtest h2 {
  margin: 24px 0 4px;
  padding-top: 16px;
  border-top: 1px solid #e5e7eb;
  font-size: 18px;
}

.caution {
  padding: 8px 12px;
  border-left: 4px solid #d97706;
  border-radius: 4px;
  background: #fff8e6;
  color: #555;
}
</style>
