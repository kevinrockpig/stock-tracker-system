<script setup>
import { computed, ref, watch } from 'vue'
import { fetchHistory } from '../api/stocks.js'
import CandlestickChart from '../components/CandlestickChart.vue'
import { DEFAULT_MA_KEYS, DEFAULT_SUB_CHART_KEYS, MA_LINES, SUB_CHARTS } from '../utils/chartConfig.js'
import { formatChange, formatLots, formatPercent, formatPrice, isoDate, monthsBefore, trendClass } from '../utils/format.js'

const props = defineProps({
  symbol: { type: String, required: true },
})

const RANGES = [
  { months: 1, label: '1 個月' },
  { months: 3, label: '3 個月' },
  { months: 6, label: '6 個月' },
  { months: 12, label: '1 年' },
]

// 一次取最近一年的日 K，切換區間只調整圖表縮放，不重新呼叫 API
const HISTORY_MONTHS = 12

const history = ref(null)
const loading = ref(false)
const error = ref('')
const months = ref(3)
const visibleRange = ref(null)
// 指標顯示設定（切換時圖表維持目前區間）
const maKeys = ref([...DEFAULT_MA_KEYS])
const subChartKeys = ref([...DEFAULT_SUB_CHART_KEYS])

async function load() {
  loading.value = true
  error.value = ''
  history.value = null
  try {
    history.value = await fetchHistory(props.symbol, { from: monthsBefore(isoDate(new Date()), HISTORY_MONTHS) })
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
  }
}

watch(() => props.symbol, load, { immediate: true })

// 最新一筆與前一交易日，用來顯示標題列的收盤價與漲跌
const latest = computed(() => history.value?.prices.at(-1))
const change = computed(() => {
  const prices = history.value?.prices ?? []
  if (prices.length < 2) return null
  return prices.at(-1).closePrice - prices.at(-2).closePrice
})
const changePercent = computed(() => {
  const prices = history.value?.prices ?? []
  if (change.value == null || !prices.at(-2).closePrice) return null
  return (change.value / prices.at(-2).closePrice) * 100
})
</script>

<template>
  <section>
    <RouterLink to="/" class="back">← 返回股票清單</RouterLink>

    <p v-if="loading" class="status">載入中…（資料庫閒置後首次連線可能需要數秒）</p>

    <div v-else-if="error" class="status error">
      <p>{{ error }}</p>
      <button type="button" @click="load">重試</button>
    </div>

    <template v-else-if="history">
      <header class="quote">
        <h1>{{ history.name }} <span class="symbol">{{ history.symbol }}</span></h1>
        <div v-if="latest" class="price-line">
          <span class="price">{{ formatPrice(latest.closePrice) }}</span>
          <span :class="trendClass(change)">{{ formatChange(change) }}（{{ formatPercent(changePercent) }}）</span>
          <span class="meta">成交量 {{ formatLots(latest.volume) }} 張 · {{ latest.tradeDate }} 日收盤價</span>
        </div>
      </header>

      <p v-if="history.prices.length === 0" class="status">
        最近一年沒有交易資料，請先執行 Python 擷取程式（python -m scraper.main）。
      </p>

      <template v-else>
        <div class="toolbar">
          <div class="ranges" role="group" aria-label="顯示區間">
            <button
              v-for="range in RANGES"
              :key="range.months"
              type="button"
              :class="{ active: months === range.months }"
              @click="months = range.months"
            >
              {{ range.label }}
            </button>
          </div>
          <span v-if="visibleRange" class="meta">
            顯示 {{ visibleRange.from }} ~ {{ visibleRange.to }}（{{ visibleRange.count }} 個交易日）
          </span>
        </div>

        <div class="indicator-toggles">
          <fieldset>
            <legend>均線</legend>
            <label v-for="ma in MA_LINES" :key="ma.key" :style="{ '--line-color': ma.color }">
              <input v-model="maKeys" type="checkbox" :value="ma.key" />
              <span class="swatch"></span>{{ ma.label }}
            </label>
          </fieldset>
          <fieldset>
            <legend>副圖</legend>
            <label v-for="sub in SUB_CHARTS" :key="sub.key">
              <input v-model="subChartKeys" type="checkbox" :value="sub.key" />
              {{ sub.label }}
            </label>
          </fieldset>
        </div>

        <CandlestickChart
          :prices="history.prices"
          :months="months"
          :ma-keys="maKeys"
          :sub-chart-keys="subChartKeys"
          @range-change="visibleRange = $event"
        />

        <p class="note">
          價格未還原除息，除息日可能出現跳空缺口（股票分割已調整）。可用滑鼠滾輪縮放、拖曳下方滑桿調整區間。
          技術指標由後端以交易日計算：MACD（12, 26, 9）、RSI（6, 12）、KD（9, 3, 3）；RSI 虛線為 30 / 70，KD 虛線為 20 / 80。
        </p>
      </template>
    </template>
  </section>
</template>

<style scoped>
.back {
  display: inline-block;
  margin-bottom: 12px;
  font-size: 14px;
}

.quote h1 {
  margin-bottom: 4px;
}

.symbol {
  color: #666;
  font-size: 16px;
  font-weight: normal;
}

.price-line {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
}

.price {
  font-size: 28px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.meta {
  color: #666;
  font-size: 13px;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 16px;
  margin-bottom: 8px;
}

.ranges {
  display: flex;
  gap: 6px;
}

.ranges button.active {
  background: #1f2328;
  border-color: #1f2328;
  color: #fff;
}

.indicator-toggles {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  margin-bottom: 4px;
}

.indicator-toggles fieldset {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 12px;
  margin: 0;
  padding: 0;
  border: 0;
  font-size: 14px;
}

.indicator-toggles legend {
  float: left;
  margin-right: 4px;
  padding: 0;
  color: #666;
  font-size: 13px;
}

.indicator-toggles label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}

.swatch {
  display: inline-block;
  width: 14px;
  height: 3px;
  border-radius: 2px;
  background: var(--line-color);
}

.note {
  color: #666;
  font-size: 13px;
}
</style>
