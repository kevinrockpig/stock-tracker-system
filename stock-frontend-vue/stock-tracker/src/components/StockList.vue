<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchGroups, fetchStocks } from '../api/stocks.js'
import { auth } from '../utils/auth.js'
import AddHoldingButton from './AddHoldingButton.vue'
import { formatChange, formatLots, formatPercent, formatPrice, trendClass } from '../utils/format.js'
import { groupShortName, matchesQuery } from '../utils/groups.js'

// 可排序的欄位；預設依代號。網址記錄搜尋、組別與排序（例如 /?group=CUSTOM&sort=change&q=南亞），上一頁返回時保留
const SORTS = {
  symbol: { label: '代號', value: (s) => s.symbol, ascending: true },
  change: { label: '漲跌幅', value: (s) => s.changePercent, ascending: false },
  volume: { label: '成交量', value: (s) => s.volume, ascending: false },
}

const route = useRoute()
const router = useRouter()

const stocks = ref([])
const groups = ref([])
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    stocks.value = await fetchStocks()
  } catch (err) {
    error.value = err.message
  } finally {
    loading.value = false
  }
  // 組別只影響篩選按鈕，取不到時仍可搜尋
  fetchGroups()
    .then((list) => (groups.value = list))
    .catch(() => {})
}

onMounted(load)

const query = computed(() => (typeof route.query.q === 'string' ? route.query.q : ''))
// 搜尋框用 v-model：注音、拼音輸入法選字完成後才更新，避免選字過程中的暫時文字寫進網址
const search = ref(query.value)
watch(search, (value) => update({ q: value }))
watch(query, (value) => {
  if (value !== search.value) search.value = value // 上一頁 / 清除篩選時同步回搜尋框
})
const selectedGroup = computed(() => (groups.value.some((g) => g.code === route.query.group) ? route.query.group : ''))
const sortKey = computed(() => (route.query.sort in SORTS ? route.query.sort : 'symbol'))

function update(changes) {
  const next = { q: query.value || undefined, group: selectedGroup.value || undefined, sort: sortKey.value, ...changes }
  if (next.sort === 'symbol') next.sort = undefined // 預設值不寫進網址
  router.replace({ query: Object.fromEntries(Object.entries(next).filter(([, v]) => v)) })
}

const visible = computed(() => {
  const sort = SORTS[sortKey.value]
  const direction = sort.ascending ? 1 : -1
  return stocks.value
    .slice()
    .filter((s) => !selectedGroup.value || s.groups.includes(selectedGroup.value))
    .filter((s) => matchesQuery(s, query.value))
    .sort((a, b) => {
      const x = sort.value(a)
      const y = sort.value(b)
      if (x == null || y == null) return x == null ? 1 : -1 // 沒有資料的排最後
      return (x < y ? -1 : x > y ? 1 : 0) * direction
    })
})

// 大部分股票的報價日期相同，只在表頭上方列一次；與最新日期不同的（例如停牌、缺資料）在該列標示
const latestDate = computed(() => stocks.value.reduce((max, s) => (s.tradeDate && s.tradeDate > max ? s.tradeDate : max), ''))

function ariaSort(key) {
  if (sortKey.value !== key) return 'none'
  return SORTS[key].ascending ? 'ascending' : 'descending'
}
</script>

<template>
  <section>
    <div class="toolbar">
      <!-- 資料每個交易日收盤後更新一次，不提供「重新整理」按鈕 -->
      <p class="note">
        {{ latestDate ? `${latestDate} ` : '' }}收盤價（每個交易日收盤後更新）。點名稱看 K 線圖，按 ＋ 加入庫存。
      </p>
    </div>

    <div v-if="stocks.length" class="filters">
      <input
        type="search"
        class="search"
        placeholder="搜尋代號或名稱"
        aria-label="搜尋代號或名稱"
        v-model="search"
      />
      <div class="group-row" role="group" aria-label="組別">
        <button type="button" :class="{ active: !selectedGroup }" @click="update({ group: undefined })">
          全部 {{ stocks.length }}
        </button>
        <button
          v-for="group in groups"
          :key="group.code"
          type="button"
          :class="{ active: selectedGroup === group.code }"
          @click="update({ group: group.code })"
        >
          {{ group.name }} {{ group.stocks }}
        </button>
      </div>
    </div>

    <p v-if="loading" class="status">載入中…（資料庫閒置後首次連線可能需要數秒）</p>

    <div v-else-if="error" class="status error">
      <p>{{ error }}</p>
      <button type="button" @click="load">重試</button>
    </div>

    <p v-else-if="stocks.length === 0" class="status">
      目前沒有股票資料，請先執行 Python 擷取程式（python -m scraper.main）。
    </p>

    <template v-else>
      <p class="count">
        顯示 {{ visible.length }} / {{ stocks.length }} 檔
        <button v-if="query || selectedGroup" type="button" class="link" @click="update({ q: undefined, group: undefined })">
          清除篩選
        </button>
      </p>
      <p v-if="visible.length === 0" class="status">沒有符合「{{ query }}」的股票。</p>

      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th class="col-symbol" :aria-sort="ariaSort('symbol')">
                <button type="button" class="sort" @click="update({ sort: 'symbol' })">
                  代號<span v-if="sortKey === 'symbol'" aria-hidden="true"> ▲</span>
                </button>
              </th>
              <th>名稱</th>
              <th class="num">收盤價</th>
              <th class="num col-change">漲跌</th>
              <th class="num" :aria-sort="ariaSort('change')">
                <button type="button" class="sort" @click="update({ sort: 'change' })">
                  漲跌幅<span v-if="sortKey === 'change'" aria-hidden="true"> ▼</span>
                </button>
              </th>
              <th class="num" :aria-sort="ariaSort('volume')">
                <button type="button" class="sort" @click="update({ sort: 'volume' })">
                  成交量（張）<span v-if="sortKey === 'volume'" aria-hidden="true"> ▼</span>
                </button>
              </th>
              <th v-if="auth.loginEnabled" class="col-add"><span class="visually-hidden">加入庫存</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="stock in visible" :key="stock.symbol">
              <td class="col-symbol">
                <RouterLink :to="{ name: 'stock-detail', params: { symbol: stock.symbol } }">{{ stock.symbol }}</RouterLink>
              </td>
              <td>
                <RouterLink :to="{ name: 'stock-detail', params: { symbol: stock.symbol } }">{{ stock.name }}</RouterLink>
                <span class="code-mobile"
                  >{{ stock.symbol }} <AddHoldingButton v-if="auth.loginEnabled" :stock="stock" compact class="add-mobile"
                /></span>
                <span v-for="code in stock.groups" :key="code" class="tag">{{ groupShortName(code) }}</span>
                <span v-if="stock.tradeDate && stock.tradeDate !== latestDate" class="stale">{{ stock.tradeDate }}</span>
              </td>
              <td class="num">{{ formatPrice(stock.closePrice) }}</td>
              <td class="num col-change" :class="trendClass(stock.change)">{{ formatChange(stock.change) }}</td>
              <td class="num" :class="trendClass(stock.change)">{{ formatPercent(stock.changePercent) }}</td>
              <td class="num">{{ formatLots(stock.volume) }}</td>
              <td v-if="auth.loginEnabled" class="col-add"><AddHoldingButton :stock="stock" compact /></td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.note {
  margin: 0;
  color: #555;
  font-size: 14px;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-bottom: 8px;
}

.search {
  box-sizing: border-box;
  width: 220px;
  max-width: 100%;
  padding: 6px 10px;
  border: 1px solid #ced4da;
  border-radius: 6px;
  font-size: 14px;
}

.group-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.group-row button {
  padding: 4px 10px;
  font-size: 13px;
}

.group-row button.active {
  background: #1f2328;
  border-color: #1f2328;
  color: #fff;
}

.count {
  margin: 4px 0 8px;
  color: #666;
  font-size: 13px;
}

.link {
  margin-left: 8px;
  padding: 0;
  border: 0;
  background: none;
  color: #0b57d0;
  font-size: 13px;
}

.table-wrap {
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  white-space: nowrap;
}

th,
td {
  padding: 8px 12px;
  border-bottom: 1px solid #e5e7eb;
  text-align: left;
}

th {
  background: #f8f9fa;
  font-weight: 600;
}

.sort {
  padding: 0;
  border: 0;
  background: none;
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}

.num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.tag {
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 4px;
  background: #f1f3f5;
  color: #666;
  font-size: 11px;
}

.stale {
  margin-left: 6px;
  color: #b45309;
  font-size: 12px;
}

.code-mobile {
  display: none;
}

.col-add {
  width: 1%;
  padding-left: 0;
  text-align: right;
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

/* 手機：代號移到名稱下方、隱藏漲跌金額，名稱到漲跌幅不用左右滑動就看得到 */
@media (max-width: 640px) {
  th,
  td {
    padding: 8px 5px; /* 名稱格多了「＋」，每格少 1px 才不用左右滑動 */
    font-size: 14px;
  }

  .col-symbol,
  .col-change,
  .col-add {
    display: none;
  }

  .code-mobile .add-mobile {
    margin-left: 4px;
    padding: 0 4px;
    font-size: 12px;
  }

  .code-mobile {
    display: block;
    color: #888;
    font-size: 12px;
  }
}
</style>
