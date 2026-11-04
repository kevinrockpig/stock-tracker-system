<script setup>
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { fetchHoldingsHistory } from '../api/stocks.js'
import { markLoggedOut } from '../utils/auth.js'

// 圖表（ECharts）較大，等走勢資料到了才載入，不拖慢庫存頁
const EquityChart = defineAsyncComponent(() => import('./EquityChart.vue'))

// 我的庫存頁「同期比較」下方的資產走勢：庫存價值、同期 0050、投入成本（階梯線）
const props = defineProps({
  // 庫存資料（每次新增、修改、刪除後會換成新的物件），變了就重新讀取走勢
  holdings: { type: Object, required: true },
})

const COLORS = { holdings: '#0b57d0', etf: '#d97706', invested: '#868e96' }
const RANGES = [
  { key: '3m', label: '3 個月', months: 3 },
  { key: '1y', label: '1 年', months: 12 },
  { key: 'all', label: '全部', months: null },
]

const history = ref(null)
const loading = ref(false)
const error = ref('')
const range = ref('all')

async function load() {
  loading.value = true
  error.value = ''
  try {
    history.value = await fetchHoldingsHistory()
  } catch (err) {
    if (err.status === 401) markLoggedOut()
    else error.value = err.message
  } finally {
    loading.value = false
  }
}
watch(() => props.holdings, load, { immediate: true })

const points = computed(() => history.value?.points ?? [])

// 往前推幾個月的日期（YYYY-MM-DD）
function monthsBefore(date, months) {
  const d = new Date(`${date}T00:00:00Z`)
  d.setUTCMonth(d.getUTCMonth() - months)
  return d.toISOString().slice(0, 10)
}

// 走勢超過 3 個月才需要選期間；只顯示比全部短的選項
const ranges = computed(() => {
  const list = points.value
  if (list.length < 2) return []
  const first = list[0].date
  const last = list[list.length - 1].date
  return RANGES.filter((r) => r.months == null || monthsBefore(last, r.months) > first)
})
watch(ranges, (list) => {
  if (!list.some((r) => r.key === range.value)) range.value = 'all'
})

const shown = computed(() => {
  const months = RANGES.find((r) => r.key === range.value)?.months
  if (months == null) return points.value
  const from = monthsBefore(points.value[points.value.length - 1].date, months)
  return points.value.filter((p) => p.date >= from)
})

const chart = computed(() => ({
  dates: shown.value.map((p) => p.date),
  base: shown.value.map((p) => p.invested),
  series: [
    { name: '我的庫存', color: COLORS.holdings, values: shown.value.map((p) => p.holdingsValue) },
    { name: `同期買 ${history.value?.etfSymbol.replace(/\.TWO?$/, '') ?? ''}`, color: COLORS.etf, values: shown.value.map((p) => p.etfValue), dashed: true },
    { name: '投入成本', color: COLORS.invested, values: shown.value.map((p) => p.invested), step: true, noReturn: true },
  ],
}))
</script>

<template>
  <h3>資產走勢</h3>
  <p v-if="loading && !history" class="status">讀取走勢中…</p>
  <p v-else-if="error" class="error" role="alert">{{ error }}</p>
  <template v-else-if="history">
    <p v-if="!points.length" class="status">填寫買進日期後，即可看到從那天起的走勢。</p>
    <template v-else>
      <div v-if="ranges.length > 1" class="ranges" role="group" aria-label="走勢期間">
        <button
          v-for="r in ranges"
          :key="r.key"
          type="button"
          :class="{ active: range === r.key }"
          :aria-pressed="range === r.key"
          @click="range = r.key"
        >
          {{ r.label }}
        </button>
      </div>
      <EquityChart :dates="chart.dates" :series="chart.series" :base="chart.base" label="我的庫存、同期 0050 與投入成本的每日走勢" />
      <p class="note">
        從第一筆納入的買進日起，每個交易日收盤的價值（庫存已扣賣出成本、都含已領股利）。投入成本為灰色階梯線，買進那天起計入；滑鼠移到（手機點選）某一天，可看到各線相對當天投入成本的報酬。最後一天即上方卡片的數字。
      </p>
    </template>
  </template>
</template>

<style scoped>
h3 {
  margin: 20px 0 6px;
  font-size: 15px;
}

.ranges {
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
}

/* 與個股頁的區間按鈕相同 */
.ranges button.active {
  background: #1f2328;
  border-color: #1f2328;
  color: #fff;
}

.status,
.note {
  color: #555;
  font-size: 13px;
}

.error {
  color: #b42318;
  font-size: 13px;
}
</style>
