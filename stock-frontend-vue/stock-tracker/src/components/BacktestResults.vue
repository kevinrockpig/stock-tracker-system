<script setup>
import { computed } from 'vue'
import { verdict } from '../utils/backtest.js'
import { formatNumber, formatRate, trendClass } from '../utils/format.js'

const props = defineProps({
  // BacktestResponse.results 中屬於同一個策略的項目
  results: { type: Array, required: true },
  // BacktestResponse.exitRules：出場規則的名稱與說明，也決定卡片順序
  exitRules: { type: Array, required: true },
  // 單一股票的統計時提供代號，卡片附上「在 K 線圖查看」的連結；合併統計時為 null
  symbol: { type: String, default: null },
})

const cards = computed(() =>
  props.exitRules
    .map((rule) => {
      const result = props.results.find((r) => r.exitRule === rule.code)
      return result && { rule, result, verdict: verdict(result) }
    })
    .filter(Boolean),
)

const rate = (value) => formatRate(value)
const plain = (value) => formatRate(value, { digits: 1, signed: false })
</script>

<template>
  <ul class="bt-cards">
    <li v-for="{ rule, result, verdict: v } in cards" :key="rule.code" class="bt-card" :class="v.key">
      <div class="head">
        <strong>{{ rule.name }}</strong>
        <span class="verdict">{{ v.key === 'edge' ? '★ ' : '' }}{{ v.label }}</span>
      </div>
      <p class="rule">{{ rule.description }}</p>

      <div class="excess">
        <span class="label">每筆超額報酬</span>
        <span class="value" :class="trendClass(result.excessReturn)">{{ rate(result.excessReturn) }}</span>
        <span class="ci">95% 區間 {{ rate(result.excessLow) }} ~ {{ rate(result.excessHigh) }}</span>
      </div>
      <p class="reason">{{ v.reason }}</p>

      <dl>
        <dt>交易次數</dt>
        <dd>
          {{ result.trades }} 筆<template v-if="result.openTrades">（另有 {{ result.openTrades }} 筆持有中，不計入）</template>
        </dd>
        <dt>勝率</dt>
        <dd>{{ plain(result.winRate) }}<span class="vs">任意日 {{ plain(result.baselineWinRate) }}</span></dd>
        <dt>平均報酬</dt>
        <dd>
          <span :class="trendClass(result.avgReturn)">{{ rate(result.avgReturn) }}</span>
          <span class="vs">任意日 {{ rate(result.baselineAvgReturn) }}</span>
        </dd>
        <dt>前段 / 後段超額</dt>
        <dd>
          <span :class="trendClass(result.excessFirst)">{{ rate(result.excessFirst) }}</span> /
          <span :class="trendClass(result.excessSecond)">{{ rate(result.excessSecond) }}</span>
          <span class="vs">{{ result.tradesFirst ?? 0 }} / {{ result.tradesSecond ?? 0 }} 筆</span>
        </dd>
      </dl>

      <details>
        <summary>更多統計</summary>
        <dl>
          <dt>勝率 95% 區間</dt>
          <dd>{{ plain(result.winRateLow) }} ~ {{ plain(result.winRateHigh) }}</dd>
          <dt>報酬中位數</dt>
          <dd>{{ rate(result.medianReturn) }}</dd>
          <dt>平均獲利 / 虧損</dt>
          <dd>{{ rate(result.avgWin) }} / {{ rate(result.avgLoss) }}</dd>
          <dt>盈虧比</dt>
          <dd>{{ formatNumber(result.payoffRatio) }}</dd>
          <dt>獲利因子</dt>
          <dd>{{ formatNumber(result.profitFactor) }}</dd>
          <dt>最大單筆虧損</dt>
          <dd>{{ rate(result.maxLoss) }}</dd>
          <dt>最多連續虧損</dt>
          <dd>{{ result.maxConsecutiveLosses }} 筆</dd>
          <dt>平均持有</dt>
          <dd>{{ formatNumber(result.avgHoldingDays, 1) }} 個交易日</dd>
        </dl>
      </details>

      <RouterLink
        v-if="symbol"
        class="more"
        :to="{
          name: 'stock-detail',
          params: { symbol },
          query: { strategy: result.strategy.code, exitRule: rule.code },
        }"
      >
        在 K 線圖查看每筆交易 →
      </RouterLink>
    </li>
  </ul>
</template>

<style scoped>
.bt-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); /* 桌面 2 × 2，手機 1 欄 */
  gap: 12px;
  margin: 0 0 12px;
  padding: 0;
  list-style: none;
}

.bt-card {
  padding: 12px 14px;
  border: 1px solid #e5e7eb;
  border-top: 4px solid #adb5bd;
  border-radius: 8px;
  font-size: 13px;
}

.bt-card.edge {
  border-top-color: #d97706;
}

.head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 4px 8px;
  font-size: 15px;
}

.verdict {
  padding: 1px 6px;
  border-radius: 4px;
  background: #f1f3f5;
  color: #555;
  font-size: 12px;
}

.edge .verdict {
  background: #fff4e6;
  color: #b45309;
  font-weight: 600;
}

.rule,
.reason {
  margin: 4px 0;
  color: #666;
}

.excess {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 2px 8px;
  margin-top: 8px;
}

.excess .label {
  width: 100%;
  color: #666;
}

.excess .value {
  font-size: 22px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.ci {
  color: #555;
}

dl {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 2px 10px;
  margin: 8px 0;
}

dt {
  color: #666;
}

dd {
  margin: 0;
  font-variant-numeric: tabular-nums;
}

.vs {
  margin-left: 6px;
  color: #888;
}

details {
  margin-bottom: 4px;
}

summary {
  color: #0b57d0;
  cursor: pointer;
}

.more {
  display: inline-block;
  margin-top: 4px;
}
</style>
