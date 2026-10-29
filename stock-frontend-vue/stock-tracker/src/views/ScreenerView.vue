<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchScreener, fetchStrategies } from '../api/stocks.js'
import { formatChange, formatPercent, formatPrice, trendClass } from '../utils/format.js'

// 區間以「交易日」計算，週末、國定假日與颱風假不計入
const DAY_OPTIONS = [
  { days: 1, label: '最新交易日' },
  { days: 5, label: '近 5 日' },
  { days: 10, label: '近 10 日' },
  { days: 20, label: '近 20 日' },
]
const WIDEST_DAYS = DAY_OPTIONS.at(-1).days

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

function select(changes) {
  router.replace({ query: { strategy: selectedCode.value, days: selectedDays.value, ...changes } })
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

onMounted(loadStrategies)
// 策略清單載入後 selectedCode 才有值，此時會觸發第一次查詢；之後每次切換策略或區間都重新查詢
watch([selectedCode, selectedDays], loadResult)
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
      <p v-if="selectedStrategy" class="description">條件：{{ selectedStrategy.description }}</p>

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
            <RouterLink :to="{ name: 'stock-detail', params: { symbol: match.symbol } }" class="card">
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
              <span class="more">查看 K 線圖 →</span>
            </RouterLink>
          </li>
        </ul>
      </template>
    </template>

    <p class="note">
      訊號由每日更新程式依技術指標計算；「黃金交叉」與「突破 20 日均線」只在發生當天算一次。點選卡片查看 K 線圖與指標。
    </p>
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
.note {
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
</style>
