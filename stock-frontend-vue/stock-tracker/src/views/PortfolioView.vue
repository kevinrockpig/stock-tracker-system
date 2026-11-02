<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchGroups, fetchPortfolio } from '../api/stocks.js'
import { falsePassChance } from '../utils/backtest.js'
import { formatNumber, formatRate, trendClass } from '../utils/format.js'

// 排序欄位；網址記錄範圍、排序與要標示的策略（例如 /portfolio?scope=TW50&sort=cagr&strategy=high_120）
const SORTS = {
  calmar: { label: 'Calmar', value: (r) => r.calmar },
  cagr: { label: '年化報酬', value: (r) => r.cagr },
  drawdown: { label: '最大回撤', value: (r) => r.maxDrawdown }, // 負值，越接近 0 越好，所以同樣由大到小
}
const ALL_SCOPE = 'ALL'

const route = useRoute()
const router = useRouter()

const groups = ref([])
const data = ref(null)
const loading = ref(false)
const error = ref('')

const scope = computed(() => (groups.value.some((g) => g.code === route.query.scope) ? route.query.scope : ALL_SCOPE))
const sortKey = computed(() => (route.query.sort in SORTS ? route.query.sort : 'calmar'))
const highlight = computed(() => (typeof route.query.strategy === 'string' ? route.query.strategy : ''))

function select(changes) {
  const query = { ...route.query, scope: scope.value, sort: sortKey.value, ...changes }
  if (query.scope === ALL_SCOPE) delete query.scope
  if (query.sort === 'calmar') delete query.sort
  router.replace({ query })
}

let latest = 0
async function load() {
  const request = ++latest
  loading.value = true
  error.value = ''
  try {
    const result = await fetchPortfolio(scope.value)
    if (request === latest) data.value = result
  } catch (err) {
    if (request === latest) error.value = err.message
  } finally {
    if (request === latest) loading.value = false
  }
}

onMounted(() => {
  fetchGroups()
    .then((list) => (groups.value = list))
    .catch(() => {}) // 只影響範圍按鈕
})
watch(scope, load, { immediate: true })

const ruleOf = (code) => data.value?.exitRules.find((rule) => rule.code === code)
const horizonName = (code) => (ruleOf(code)?.horizon === 'swing' ? '波段' : '短線')

const rows = computed(() => {
  const value = SORTS[sortKey.value].value
  return [...(data.value?.results ?? [])].sort((a, b) => {
    const x = value(a)
    const y = value(b)
    if (x == null || y == null) return x == null ? 1 : -1
    return y - x
  })
})
const counts = computed(() => ({
  effective: rows.value.filter((r) => r.effective).length,
  beats: rows.value.filter((r) => r.beatsBenchmark).length,
  edge: rows.value.filter((r) => r.signalEdge).length,
}))

// 三個範圍各比較 24 組，合計的比較次數
const totalComparisons = computed(() => (data.value?.comparisons ?? 0) * Math.max(1, groups.value.length + 1))
const pass = (ok) => (ok ? '✓' : '—')
</script>

<template>
  <section>
    <h1>回測比較：投資組合</h1>
    <p class="intro">
      用<strong>一筆共用資金</strong>，在整個範圍的股票中依策略訊號輪流進出場（最多同時持有
      {{ data?.slots ?? 10 }} 檔），和「同一批股票等額買進持有」比較。和「策略選股」頁的個別回測不同：這裡看的是整套策略實際操作時的資金表現。<RouterLink
        :to="{ name: 'about', hash: '#portfolio' }"
        >規則與判定方式</RouterLink
      >
    </p>

    <div class="filter-row" role="group" aria-label="範圍">
      <button type="button" :class="{ active: scope === ALL_SCOPE }" @click="select({ scope: ALL_SCOPE })">全部合併</button>
      <button
        v-for="group in groups"
        :key="group.code"
        type="button"
        :class="{ active: scope === group.code }"
        @click="select({ scope: group.code })"
      >
        {{ group.name }}
      </button>
    </div>

    <p v-if="loading && !data" class="status">載入中…（資料庫閒置後首次連線可能需要數秒）</p>
    <div v-else-if="error" class="status error">
      <p>{{ error }}</p>
      <button type="button" @click="load">重試</button>
    </div>

    <template v-else-if="data">
      <p v-if="!data.results.length" class="status">這個範圍還沒有投資組合回測結果（每日更新程式執行後產生）。</p>

      <template v-else>
        <p class="meta">
          {{ data.scopeName }}（{{ data.scopeStocks }} 檔）· {{ data.periodStart }} ~ {{ data.periodEnd }} · 前後段以
          {{ data.splitDate }} 切分 · 訊號多於空位時隨機排序，算 {{ data.seeds }} 次取中位數
        </p>

        <p class="summary" :class="{ found: counts.effective }">
          <template v-if="counts.effective">★ 有效策略 {{ counts.effective }} 組。</template>
          <template v-else>目前沒有「有效策略」（標準 1、2 都通過）。</template>
          標準 2（風險調整後勝過買進持有）通過 {{ counts.beats }} 組；標準 1（訊號時機勝過任意日進場）通過 {{ counts.edge }} 組。
        </p>

        <div class="sort-row" role="group" aria-label="排序">
          <span class="meta">排序：</span>
          <button
            v-for="(sort, key) in SORTS"
            :key="key"
            type="button"
            class="small"
            :class="{ active: sortKey === key }"
            @click="select({ sort: key })"
          >
            {{ sort.label }}
          </button>
        </div>

        <div class="table-wrap">
          <table class="stack">
            <thead>
              <tr>
                <th>策略 · 出場方式</th>
                <th class="num">年化報酬</th>
                <th class="num">最大回撤</th>
                <th class="num">Calmar<span class="sub">整段 / 前段 / 後段</span></th>
                <th class="num">持股比例</th>
                <th>判定</th>
              </tr>
            </thead>
            <tbody>
              <tr class="benchmark">
                <th scope="row">同一批股票買進持有<span class="sub">判定的基準</span></th>
                <td class="num" data-label="年化報酬">{{ formatRate(data.benchmark.cagr, { digits: 1 }) }}</td>
                <td class="num" data-label="最大回撤">{{ formatRate(data.benchmark.maxDrawdown, { digits: 1 }) }}</td>
                <td class="num" data-label="Calmar">
                  {{ formatNumber(data.benchmark.calmar) }}
                  <span class="sub">{{ formatNumber(data.benchmark.calmarFirst) }} / {{ formatNumber(data.benchmark.calmarSecond) }}</span>
                </td>
                <td class="num" data-label="持股比例">100%</td>
                <td data-label="判定">—</td>
              </tr>
              <tr class="benchmark etf">
                <th scope="row">0050 ETF 買進持有<span class="sub">參考，不作為判定基準</span></th>
                <td class="num" data-label="年化報酬">{{ formatRate(data.etf.cagr, { digits: 1 }) }}</td>
                <td class="num" data-label="最大回撤">{{ formatRate(data.etf.maxDrawdown, { digits: 1 }) }}</td>
                <td class="num" data-label="Calmar">{{ formatNumber(data.etf.calmar) }}</td>
                <td class="num" data-label="持股比例">100%</td>
                <td data-label="判定">—</td>
              </tr>
              <tr
                v-for="row in rows"
                :key="`${row.strategy.code}/${row.exitRule}`"
                :class="{ effective: row.effective, highlight: row.strategy.code === highlight }"
              >
                <th scope="row">
                  {{ row.strategy.name }}
                  <span class="rule">{{ ruleOf(row.exitRule)?.name ?? row.exitRule }}<span class="tag">{{ horizonName(row.exitRule) }}</span></span>
                </th>
                <td class="num" data-label="年化報酬">
                  <span :class="trendClass(row.cagr)">{{ formatRate(row.cagr, { digits: 1 }) }}</span>
                  <span class="sub">{{ formatRate(row.cagrMin, { digits: 1 }) }} ~ {{ formatRate(row.cagrMax, { digits: 1 }) }}</span>
                </td>
                <td class="num" data-label="最大回撤">{{ formatRate(row.maxDrawdown, { digits: 1 }) }}</td>
                <td class="num" data-label="Calmar">
                  <span :class="{ better: row.calmar > data.benchmark.calmar }">{{ formatNumber(row.calmar) }}</span>
                  <span class="sub">
                    <span :class="{ better: row.calmarFirst > data.benchmark.calmarFirst }">{{ formatNumber(row.calmarFirst) }}</span> /
                    <span :class="{ better: row.calmarSecond > data.benchmark.calmarSecond }">{{ formatNumber(row.calmarSecond) }}</span>
                  </span>
                </td>
                <td class="num" data-label="持股比例">{{ formatRate(row.avgInvested, { digits: 0, signed: false }) }}</td>
                <td data-label="判定" class="verdicts">
                  <span v-if="row.effective" class="badge effective">★ 有效策略</span>
                  <span class="check" title="標準 1：訊號時機勝過任意日進場">標準 1 {{ pass(row.signalEdge) }}</span>
                  <span class="check" title="標準 2：Calmar 整段與前後兩段都勝過買進持有">標準 2 {{ pass(row.beatsBenchmark) }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <ul class="notes">
          <li>Calmar = 年化報酬 ÷ |最大回撤|，代表「每承受 1% 的回撤換到多少報酬」；藍色粗體表示高於買進持有。</li>
          <li>
            <strong>標準 1</strong>：「策略選股」頁的個別回測中，訊號時機勝過任意一天進場（同一範圍）。<strong>標準 2</strong>：整段與前後兩段的 Calmar 都高於同一批股票的買進持有。兩者都通過才標示「有效策略」。
          </li>
          <li>
            三個範圍共比較了約 {{ totalComparisons }} 次（每個範圍 {{ data.comparisons }} 組）。即使全都沒有真正的優勢，每個範圍也可能有組別剛好通過（機率最多約
            {{ falsePassChance(data.comparisons) }}%）；只在一個範圍通過的結果要特別保守看待。
          </li>
          <li>
            觀察名單是「現在」的股票（倖存者偏差），所以策略勝過 0050 ETF 不代表有效；公平的比較是同一批股票的買進持有。
          </li>
          <li>年化報酬下方的範圍是 {{ data.seeds }} 種隨機排序的最小～最大，範圍越大代表結果越依賴「剛好買到哪幾檔」。</li>
        </ul>
      </template>
    </template>
  </section>
</template>

<style scoped>
.intro,
.meta {
  color: #555;
  font-size: 14px;
}

.meta {
  font-size: 13px;
}

.filter-row,
.sort-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin: 8px 0;
}

.filter-row button.active,
.sort-row button.active {
  background: #1f2328;
  border-color: #1f2328;
  color: #fff;
}

.small {
  padding: 3px 10px;
  font-size: 13px;
}

.summary {
  padding: 8px 12px;
  border-left: 4px solid #adb5bd;
  border-radius: 4px;
  background: #f6f8fa;
  font-size: 14px;
}

.summary.found {
  border-left-color: #d97706;
  background: #fff8e6;
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

.sub,
.rule {
  display: block;
  color: #888;
  font-size: 12px;
  font-weight: normal;
}

.rule {
  color: #555;
}

.tag {
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 4px;
  background: #f1f3f5;
  color: #666;
  font-size: 11px;
}

tr.benchmark {
  background: #f8f9fa;
}

tr.etf th,
tr.etf td {
  color: #777;
}

tr.effective {
  background: #fff8e6;
}

tr.highlight th:first-child {
  box-shadow: inset 4px 0 0 #0b57d0;
}

.better {
  color: #0b57d0;
  font-weight: 600;
}

.verdicts {
  white-space: nowrap;
}

.check {
  display: inline-block;
  margin-right: 8px;
  color: #666;
  font-size: 12px;
}

.badge.effective {
  display: inline-block;
  margin-right: 8px;
  padding: 0 6px;
  border-radius: 4px;
  background: #fff4e6;
  color: #b45309;
  font-size: 12px;
  font-weight: 600;
}

.notes {
  padding-left: 20px;
  color: #555;
  font-size: 13px;
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

  .verdicts {
    white-space: normal;
  }
}
</style>
