<script setup>
import { onMounted, ref } from 'vue'
import { fetchStocks } from '../api/stocks.js'
import { formatChange, formatLots, formatPercent, formatPrice, trendClass } from '../utils/format.js'

const stocks = ref([])
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
}

onMounted(load)
</script>

<template>
  <section>
    <div class="toolbar">
      <p class="note">
        價格為<strong>日收盤價</strong>（資料來源 yfinance，非即時報價），請以報價日期為準。點選代號查看 K 線圖。
      </p>
      <button type="button" :disabled="loading" @click="load">重新整理</button>
    </div>

    <p v-if="loading" class="status">載入中…（資料庫閒置後首次連線可能需要數秒）</p>

    <div v-else-if="error" class="status error">
      <p>{{ error }}</p>
      <button type="button" @click="load">重試</button>
    </div>

    <p v-else-if="stocks.length === 0" class="status">
      目前沒有股票資料，請先執行 Python 擷取程式（python -m scraper.main）。
    </p>

    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>代號</th>
            <th>名稱</th>
            <th class="num">收盤價</th>
            <th class="num">漲跌</th>
            <th class="num">漲跌幅</th>
            <th class="num">前日收盤</th>
            <th class="num">成交量（張）</th>
            <th>報價日期</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="stock in stocks" :key="stock.symbol">
            <td>
              <RouterLink :to="{ name: 'stock-detail', params: { symbol: stock.symbol } }">{{ stock.symbol }}</RouterLink>
            </td>
            <td>
              <RouterLink :to="{ name: 'stock-detail', params: { symbol: stock.symbol } }">{{ stock.name }}</RouterLink>
            </td>
            <td class="num">{{ formatPrice(stock.closePrice) }}</td>
            <td class="num" :class="trendClass(stock.change)">{{ formatChange(stock.change) }}</td>
            <td class="num" :class="trendClass(stock.change)">{{ formatPercent(stock.changePercent) }}</td>
            <td class="num">{{ formatPrice(stock.previousClose) }}</td>
            <td class="num">{{ formatLots(stock.volume) }}</td>
            <td>{{ stock.tradeDate ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.note {
  margin: 0;
  color: #555;
  font-size: 14px;
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
  padding: 10px 12px;
  border-bottom: 1px solid #e5e7eb;
  text-align: left;
}

th {
  background: #f8f9fa;
  font-weight: 600;
}

.num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

</style>
