<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchBacktests, fetchHistory, fetchTrades } from '../api/stocks.js'
import CandlestickChart from '../components/CandlestickChart.vue'
import { EXIT_REASONS, verdict } from '../utils/backtest.js'
import { CHART_RANGES, MA_LINES, SUB_CHARTS, TRADE_COLORS } from '../utils/chartConfig.js'
import { defaultChartSettings, loadChartSettings, saveChartSettings } from '../utils/chartSettings.js'
import {
  formatChange,
  formatLots,
  formatPercent,
  formatPrice,
  formatRate,
  isoDate,
  monthsBefore,
  trendClass,
} from '../utils/format.js'

const props = defineProps({
  symbol: { type: String, required: true },
})

// 一次取最近一年的日 K，切換區間只調整圖表縮放，不重新呼叫 API
const HISTORY_MONTHS = 12

const history = ref(null)
const loading = ref(false)
const error = ref('')
const visibleRange = ref(null)

// 顯示設定：沿用上次的選擇（存在瀏覽器，所有股票共用）；切換指標時圖表維持目前區間
const saved = loadChartSettings()
const months = ref(saved.months)
const maKeys = ref(saved.maKeys)
const subChartKeys = ref(saved.subChartKeys)

const currentSettings = () => ({ months: months.value, maKeys: maKeys.value, subChartKeys: subChartKeys.value })
watch([months, maKeys, subChartKeys], () => saveChartSettings(currentSettings()), { deep: true })

const isDefaultSettings = computed(() => JSON.stringify(currentSettings()) === JSON.stringify(defaultChartSettings()))
function resetSettings() {
  const defaults = defaultChartSettings()
  months.value = defaults.months
  maKeys.value = defaults.maKeys
  subChartKeys.value = defaults.subChartKeys
}

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

// 回測進出場點：選擇記錄在網址（?strategy=&exitRule=），策略選股頁可直接連過來，也可分享
// 預設不顯示；不是圖表外觀設定，所以不存進瀏覽器
const route = useRoute()
const router = useRouter()

const backtest = ref(null) // 這檔股票的回測統計（BacktestResponse），也提供策略與出場規則的選項
const backtestError = ref('')

async function loadBacktest() {
  backtest.value = null
  backtestError.value = ''
  try {
    backtest.value = await fetchBacktests(props.symbol)
  } catch (err) {
    backtestError.value = err.status === 404 ? '這檔股票還沒有回測結果（每日更新程式執行後產生）。' : err.message
  }
}
watch(() => props.symbol, loadBacktest, { immediate: true })

const strategyOptions = computed(() => {
  const seen = new Map()
  for (const result of backtest.value?.results ?? []) seen.set(result.strategy.code, result.strategy)
  return [...seen.values()]
})
const exitRuleOptions = computed(() => backtest.value?.exitRules ?? [])
const selectedStrategy = computed(() => strategyOptions.value.find((s) => s.code === route.query.strategy))
const selectedExitRule = computed(
  () => exitRuleOptions.value.find((rule) => rule.code === route.query.exitRule) ?? exitRuleOptions.value[0],
)

function selectBacktest(changes) {
  const query = { strategy: selectedStrategy.value?.code, exitRule: selectedExitRule.value?.code, ...changes }
  router.replace({ query: query.strategy ? query : {} })
}

// 目前選擇在這檔股票的回測統計與判定
const selectedStats = computed(() =>
  backtest.value?.results.find(
    (r) => r.strategy.code === selectedStrategy.value?.code && r.exitRule === selectedExitRule.value?.code,
  ),
)
const selectedVerdict = computed(() => selectedStats.value && verdict(selectedStats.value))

const trades = ref([])
const tradesError = ref('')
let latestTrades = 0

async function loadTrades() {
  const request = ++latestTrades
  trades.value = []
  tradesError.value = ''
  if (!selectedStrategy.value || !selectedExitRule.value) return
  try {
    const data = await fetchTrades(props.symbol, selectedStrategy.value.code, selectedExitRule.value.code)
    if (request === latestTrades) trades.value = data.trades
  } catch (err) {
    if (request === latestTrades) tradesError.value = err.message
  }
}
watch([() => props.symbol, selectedStrategy, selectedExitRule], loadTrades, { immediate: true })

// 圖表只有最近一年；列表由新到舊列出全部交易
const chartStart = computed(() => history.value?.prices[0]?.tradeDate)
const tradesOnChart = computed(
  () => trades.value.filter((t) => chartStart.value && (t.exitDate ?? '9999') >= chartStart.value).length,
)
const tradesNewestFirst = computed(() => [...trades.value].reverse())

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
              v-for="range in CHART_RANGES"
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
          <button v-if="!isDefaultSettings" type="button" class="reset" @click="resetSettings">恢復預設</button>
        </div>

        <div v-if="strategyOptions.length" class="indicator-toggles">
          <fieldset>
            <legend>回測進出場點</legend>
            <select
              aria-label="回測策略"
              :value="selectedStrategy?.code ?? ''"
              @change="selectBacktest({ strategy: $event.target.value || undefined })"
            >
              <option value="">不顯示</option>
              <option v-for="strategy in strategyOptions" :key="strategy.code" :value="strategy.code">
                {{ strategy.name }}
              </option>
            </select>
            <select
              aria-label="出場方式"
              :value="selectedExitRule?.code"
              :disabled="!selectedStrategy"
              @change="selectBacktest({ exitRule: $event.target.value })"
            >
              <option v-for="rule in exitRuleOptions" :key="rule.code" :value="rule.code">{{ rule.name }}</option>
            </select>
          </fieldset>
        </div>
        <p v-else-if="backtestError" class="meta">回測進出場點：{{ backtestError }}</p>

        <CandlestickChart
          :prices="history.prices"
          :months="months"
          :ma-keys="maKeys"
          :sub-chart-keys="subChartKeys"
          :trades="trades"
          @range-change="visibleRange = $event"
        />

        <section v-if="selectedStrategy && selectedExitRule" id="backtest-trades" class="backtest">
          <h2>回測交易：{{ selectedStrategy.name }} · {{ selectedExitRule.name }}</h2>
          <p v-if="selectedStats" class="verdict-line" :class="selectedVerdict.key">
            <strong>{{ selectedVerdict.key === 'edge' ? '★ ' : '' }}{{ history.name }}：{{ selectedVerdict.label }}</strong>
            — {{ selectedStats.trades }} 筆，勝率 {{ formatRate(selectedStats.winRate, { digits: 1, signed: false }) }}，平均報酬
            <span :class="trendClass(selectedStats.avgReturn)">{{ formatRate(selectedStats.avgReturn) }}</span>，每筆超額報酬
            <span :class="trendClass(selectedStats.excessReturn)">{{ formatRate(selectedStats.excessReturn) }}</span>（95% 區間
            {{ formatRate(selectedStats.excessLow) }} ~ {{ formatRate(selectedStats.excessHigh) }}）。{{ selectedVerdict.reason }}
          </p>
          <p class="meta">
            {{ selectedExitRule.description }}。圖中
            <span :style="{ color: TRADE_COLORS.entry }">▲ 進場</span>（訊號隔一個交易日開盤）、<span
              :style="{ color: TRADE_COLORS.exit }"
              >▼ 出場</span
            >；底色紅為獲利、綠為虧損、藍為持有中，滑鼠移到 K 線上可看細節。圖表只涵蓋最近一年（{{ tradesOnChart }} 筆），全部交易列在下方。
            <RouterLink
              :to="{ name: 'screener', query: { strategy: selectedStrategy.code, scope: symbol }, hash: '#backtest' }"
              >各出場方式比較</RouterLink
            >
          </p>
          <p v-if="tradesError" class="status error">{{ tradesError }}</p>
          <details v-else-if="trades.length">
            <summary>全部 {{ trades.length }} 筆交易（由新到舊）</summary>
            <div class="table-wrap">
              <table>
                <thead>
                  <!-- 報酬放在前面：手機上表格需左右滑動，最重要的欄位不用滑就看得到 -->
                  <tr>
                    <th>進場（開盤）</th>
                    <th>出場</th>
                    <th class="num">報酬</th>
                    <th class="num">持有</th>
                    <th>出場原因</th>
                    <th class="num">股利</th>
                    <th>訊號日</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="trade in tradesNewestFirst" :key="trade.entryDate">
                    <td>{{ trade.entryDate }}<span class="price-cell">{{ formatPrice(trade.entryPrice) }}</span></td>
                    <td>
                      <template v-if="trade.exitDate">
                        {{ trade.exitDate }}<span class="price-cell">{{ formatPrice(trade.exitPrice) }}</span>
                      </template>
                      <template v-else>持有中</template>
                    </td>
                    <td class="num" :class="trendClass(trade.netReturn)">{{ formatRate(trade.netReturn) }}</td>
                    <td class="num">{{ trade.holdingDays }} 日</td>
                    <td>{{ trade.exitReason ? EXIT_REASONS[trade.exitReason] ?? trade.exitReason : '—' }}</td>
                    <td class="num">{{ trade.dividends > 0 ? formatPrice(trade.dividends) : '—' }}</td>
                    <td>{{ trade.signalDate }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </details>
          <p v-else class="meta">這個組合在回測期間沒有交易。</p>
        </section>

        <!-- 中文句子之間不換行：模板換行會變成一個空白 -->
        <p class="note">
          價格未還原除息，除息日可能出現跳空缺口（股票分割已調整）。可用滑鼠滾輪縮放、拖曳下方滑桿調整區間。區間與指標的選擇會記在這個瀏覽器，下次開啟任何個股都沿用。
        </p>
        <p class="note">
          技術指標由後端以交易日計算：MACD（12, 26, 9）、RSI（6, 12）、KD（9, 3, 3）；RSI 虛線為 30 / 70，KD 虛線為 20 / 80。<RouterLink :to="{ name: 'about', hash: '#indicators' }">指標計算方式與解讀</RouterLink>
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

.reset {
  padding: 2px 10px;
  font-size: 13px;
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

select {
  padding: 3px 6px;
  font-size: 14px;
}

.backtest h2 {
  margin: 16px 0 4px;
  font-size: 16px;
}

.verdict-line {
  margin: 4px 0;
  padding: 8px 12px;
  border-left: 4px solid #adb5bd;
  border-radius: 4px;
  background: #f6f8fa;
  font-size: 14px;
}

.verdict-line.edge {
  border-left-color: #d97706;
  background: #fff8e6;
}

summary {
  color: #0b57d0;
  font-size: 14px;
  cursor: pointer;
}

.table-wrap {
  overflow-x: auto;
}

table {
  width: 100%;
  margin-top: 6px;
  border-collapse: collapse;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

th,
td {
  padding: 4px 8px;
  border-bottom: 1px solid #e5e7eb;
  text-align: left;
  white-space: nowrap;
}

thead th {
  background: #f6f8fa;
}

.num {
  text-align: right;
}

.price-cell {
  margin-left: 6px;
  color: #555;
}

/* 手機：價格換到日期下一行，讓進場、出場、報酬、持有不用左右滑動就看得到 */
@media (max-width: 640px) {
  .price-cell {
    display: block;
    margin-left: 0;
  }
}
</style>
