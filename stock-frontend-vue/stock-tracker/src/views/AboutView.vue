<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { fetchBacktests, fetchStrategies } from '../api/stocks.js'
import { BACKTEST_RULES, falsePassChance } from '../utils/backtest.js'
import { MA_LINES, SUB_CHARTS } from '../utils/chartConfig.js'
import { formatRate } from '../utils/format.js'
import { scrollMotion } from '../utils/scroll.js'

const SECTIONS = [
  { id: 'disclaimer', label: '免責聲明' },
  { id: 'data', label: '資料性質' },
  { id: 'indicators', label: '技術指標' },
  { id: 'strategies', label: '選股策略' },
  { id: 'backtest', label: '回測方法' },
  { id: 'sources', label: '資料來源' },
]

// 指標參數與參考線取自圖表設定，說明頁與 K 線圖保持一致
const sub = Object.fromEntries(SUB_CHARTS.map((chart) => [chart.key, chart]))
const maLabels = MA_LINES.map((ma) => ma.label).join(' / ')

// 策略名稱與條件取自 API（與策略選股頁同一份定義）；API 無法連線時其他內容照常顯示
const strategies = ref([])
const strategyError = ref('')
onMounted(async () => {
  try {
    strategies.value = await fetchStrategies()
  } catch (err) {
    strategyError.value = err.message
  }
})

// 出場規則、回測期間與比較組數取自回測 API；無法連線時只少這些數字，說明照常顯示
const backtest = ref(null)
onMounted(async () => {
  try {
    backtest.value = await fetchBacktests()
  } catch {
    // 出場規則表改顯示「無法取得」
  }
})

const rate = (value, digits = 4) => formatRate(value, { digits, signed: false })
// 價格不變時一買一賣的成本（股票）：買進手續費 + 賣出手續費 + 證交稅
const roundTripCost = rate(2 * BACKTEST_RULES.feeRate + BACKTEST_RULES.stockTaxRate, 3)

// 目錄標示目前讀到的段落
const route = useRoute()
const activeId = ref(SECTIONS[0].id)
const tocEl = ref(null)
let frame = 0

function updateActive() {
  frame = 0
  const headings = SECTIONS.map((section) => document.getElementById(section.id))
  if (headings.some((heading) => !heading)) return
  // 標題已捲到停靠位置（導覽列下方）的最後一段，就是目前段落
  let index = 0
  headings.forEach((heading, i) => {
    const dock = parseFloat(getComputedStyle(heading).scrollMarginTop) || 0
    if (heading.getBoundingClientRect().top <= dock + 1) index = i
  })
  // 已捲到底：最後幾段的標題無法捲到頂端。若剛點了目錄中的某段且標題在畫面內就標示它，否則標示最後一段
  const atBottom = window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 2
  if (atBottom) {
    const hashed = SECTIONS.findIndex((section) => `#${section.id}` === route.hash)
    const top = hashed >= 0 ? headings[hashed].getBoundingClientRect().top : -1
    index = hashed >= index && top >= 0 && top < window.innerHeight ? hashed : SECTIONS.length - 1
  }
  activeId.value = SECTIONS[index].id
}

function onScroll() {
  if (!frame) frame = requestAnimationFrame(updateActive)
}

// 手機版目錄是可左右滑動的橫列：目前段落改變時，把它捲到橫列中央
watch(activeId, (id) => {
  const toc = tocEl.value
  const link = toc?.querySelector(`[data-section="${id}"]`)
  if (!link || toc.scrollWidth <= toc.clientWidth) return
  toc.scrollTo({ left: link.offsetLeft - (toc.clientWidth - link.offsetWidth) / 2, behavior: scrollMotion() })
})
watch(() => route.hash, onScroll)

onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('resize', onScroll, { passive: true })
  updateActive()
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('resize', onScroll)
  cancelAnimationFrame(frame)
})
</script>

<template>
  <section class="about">
    <h1>使用說明</h1>
    <div class="layout">
      <nav ref="tocEl" class="toc" aria-label="本頁目錄">
        <RouterLink
          v-for="section in SECTIONS"
          :key="section.id"
          :to="{ hash: `#${section.id}` }"
          :data-section="section.id"
          :class="{ active: activeId === section.id }"
          :aria-current="activeId === section.id ? 'location' : undefined"
        >
          {{ section.label }}
        </RouterLink>
      </nav>

      <div class="content">
        <h2 id="disclaimer">免責聲明</h2>
        <div class="disclaimer">
          <p>本網站為個人作品與學習用途，所有資料、指標與選股結果<strong>僅供參考，不構成任何投資建議或買賣邀約</strong>。</p>
          <p>資料可能有延遲、缺漏或錯誤，實際行情請以臺灣證券交易所、證券櫃檯買賣中心或券商公告為準。投資有風險，請自行判斷並承擔損益。</p>
        </div>

        <h2 id="data">資料性質</h2>
        <ul>
          <li><strong>日收盤資料，非即時報價</strong>：每個交易日收盤後更新一次，畫面上標示的日期即為資料日期。</li>
          <li><strong>只計交易日</strong>：週末、國定假日與颱風假休市，不會出現在清單、K 線圖與選股區間中。</li>
          <li>
            <strong>價格未還原除權息</strong>：與券商報價一致，除權息日的 K 線會出現跳空缺口，技術指標也會受影響；股票分割則已調整，分割前後的價格可直接比較（例如 0050 於 2025 年 6 月分割）。
          </li>
          <li><strong>成交量單位為「張」</strong>：1 張 = 1,000 股。</li>
          <li><strong>追蹤範圍</strong>：只包含觀察名單中的股票，見「股票清單」頁。</li>
        </ul>

        <h2 id="indicators">技術指標</h2>
        <p>所有指標都以<strong>交易日的收盤價</strong>計算，由系統每日預先算好，K 線圖與選股使用同一份數字。</p>
        <div class="table-wrap stack">
          <table>
            <thead>
              <tr>
                <th>指標</th>
                <th>計算方式</th>
                <th>常見解讀</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <th scope="row">均線<br /><span class="param">{{ maLabels }}</span></th>
                <td data-label="計算方式">最近 N 個交易日收盤價的平均（MA5 為 5 日平均，約一週；MA20 約一個月；MA60 約一季）。</td>
                <td data-label="常見解讀">短天期均線依序在長天期之上，代表近期價格持續走高（多頭排列）。</td>
              </tr>
              <tr>
                <th scope="row">MACD<br /><span class="param">{{ sub.macd.title }}</span></th>
                <td data-label="計算方式">
                  DIF = 12 日 EMA − 26 日 EMA；MACD = DIF 的 9 日 EMA；OSC = DIF − MACD（柱狀體，正值紅、負值綠）。EMA 為指數移動平均，越近的價格權重越高。
                </td>
                <td data-label="常見解讀">DIF 由下往上穿越 MACD 稱為黃金交叉，由上往下稱為死亡交叉；OSC 由負轉正與黃金交叉同時發生。</td>
              </tr>
              <tr>
                <th scope="row">RSI<br /><span class="param">{{ sub.rsi.title }}</span></th>
                <td data-label="計算方式">N 日內平均漲幅 ÷（平均漲幅 + 平均跌幅）× 100，採 Wilder 平滑；數值介於 0～100。</td>
                <td data-label="常見解讀">高於 {{ sub.rsi.levels[1] }} 常視為超買、低於 {{ sub.rsi.levels[0] }} 常視為超賣（圖中虛線）。</td>
              </tr>
              <tr>
                <th scope="row">KD<br /><span class="param">{{ sub.kd.title }}</span></th>
                <td data-label="計算方式">
                  RSV =（收盤 − 9 日最低）÷（9 日最高 − 9 日最低）× 100；K = ⅔ × 前一日 K + ⅓ × RSV；D = ⅔ × 前一日 D + ⅓ × K（台股常用公式，起始值 50）。
                </td>
                <td data-label="常見解讀">高於 {{ sub.kd.levels[1] }} 常視為超買、低於 {{ sub.kd.levels[0] }} 常視為超賣（圖中虛線）；K 由下往上穿越 D 稱為黃金交叉。</td>
              </tr>
            </tbody>
          </table>
        </div>
        <ul class="small">
          <li>「常見解讀」是一般教科書的說法，不代表準確率，也不構成買賣建議。</li>
          <li>MACD、RSI、KD 需要一段歷史資料才能穩定，資料起始處的一段期間不顯示這些指標。</li>
          <li>不同券商軟體的公式或起始值可能略有差異，數值不一定完全相同。</li>
        </ul>

        <h2 id="strategies">選股策略</h2>
        <p>系統在每個交易日收盤後，依當天的收盤價與指標檢查每檔股票，符合條件的記錄下來。</p>
        <div v-if="strategies.length" class="table-wrap stack">
          <table>
            <thead>
              <tr>
                <th>策略</th>
                <th>條件</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="strategy in strategies" :key="strategy.code">
                <th scope="row">{{ strategy.name }}</th>
                <td data-label="條件">{{ strategy.description }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else-if="strategyError" class="status error">無法取得策略清單：{{ strategyError }}</p>
        <p v-else class="status">載入策略清單中…</p>
        <ul class="small">
          <li>條件中的「前一日」指前一個交易日。</li>
          <li>「策略選股」頁的「近 N 日」為最近 N 個交易日，期間內任何一天符合就會列出，並顯示最後一次符合的日期。</li>
          <li>「黃金交叉」與「突破均線」只在發生的當天成立，隔天不會重複計算；「多頭排列」則是每天都符合就每天成立。</li>
        </ul>

        <h2 id="backtest">回測方法與限制</h2>
        <p>
          回測是把選股策略套用到過去的資料，看「如果當時照規則操作」的結果。系統每天收盤後重新計算一次<template v-if="backtest"
            >，目前期間為 {{ backtest.periodStart }} ~ {{ backtest.periodEnd }}（資料起始的一段期間用來讓指標穩定，不計入）</template
          >。結果顯示在「策略選股」頁與個股頁的 K 線圖。
        </p>

        <h3>進場</h3>
        <ul>
          <li>訊號要等收盤後才知道，因此在<strong>下一個交易日以開盤價買進</strong>。</li>
          <li>
            開盤價比參考價高 {{ rate(BACKTEST_RULES.limitUpSkip, 1) }} 以上（接近漲停）視為買不到，略過這次訊號。參考價為前一日收盤價，除息日再扣除股利。
          </li>
          <li>同一檔股票持有中出現的新訊號不再進場。「多頭排列」連續多天成立時，只在開始成立的第一天進場。</li>
        </ul>

        <h3>出場</h3>
        <div v-if="backtest" class="table-wrap stack">
          <table>
            <thead>
              <tr>
                <th>出場方式</th>
                <th>規則</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="rule in backtest.exitRules" :key="rule.code">
                <th scope="row">{{ rule.name }}</th>
                <td data-label="規則">{{ rule.description }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="status">無法取得出場規則（回測 API 未連線）。</p>
        <p class="small">ATR(14) 是最近 14 個交易日的平均真實波動幅度；股價波動越大，移動停利線離最高價越遠。</p>

        <h3>成本與股利</h3>
        <ul>
          <li>
            手續費 {{ rate(BACKTEST_RULES.feeRate) }}，買進、賣出各收一次（不計券商折扣）；賣出時的證交稅：股票
            {{ rate(BACKTEST_RULES.stockTaxRate, 1) }}、ETF {{ rate(BACKTEST_RULES.etfTaxRate, 1) }}。股票價格不變時，一買一賣約虧
            {{ roundTripCost }}。
          </li>
          <li>持有期間遇到除息（進場日之後、出場日當天以前），現金股利計入報酬。</li>
          <li>報酬是<strong>每一筆交易</strong>的報酬率，不是年化報酬，也不是整體資金的成長。</li>
        </ul>

        <h3>如何判斷有沒有優勢</h3>
        <ul>
          <li>
            <strong>基準</strong>：同一檔股票「任何一個交易日都照同樣規則進出場」的平均報酬。股票長期上漲時，隨便哪天買都可能賺錢，策略要贏過這個基準，訊號才有意義。
          </li>
          <li><strong>超額報酬</strong> = 每筆交易報酬 − 同一檔股票的基準，並附 95% 信賴區間（交易越多、報酬越穩定，區間越窄）。</li>
          <li>
            <strong>穩健性</strong>：回測期間從中間的交易日<template v-if="backtest">（{{ backtest.splitDate }}）</template>切成前後兩段，各段和同一段期間的基準比較，避免只靠某一段行情。
          </li>
          <li>
            標示「<strong>歷史上有優勢</strong>」需同時符合：超額報酬的區間下限大於 0，而且前後兩段的超額報酬都大於 0。其他情況標示「歷史上無明顯優勢」。
          </li>
          <li>勝率的區間以 Wilson 方法計算；單一股票的交易次數較少，區間較寬，判斷以全部合併的結果為主。</li>
        </ul>

        <h3>限制</h3>
        <ul>
          <li>假設都能以開盤價或收盤價成交；實際交易會有滑價、買不到或賣不掉的情況。</li>
          <li>觀察名單是現在挑選的股票（倖存者偏差）：過去表現好的股票，回測結果自然比較好看。</li>
          <li>股價與除權息資料來自 Yahoo Finance，偶有缺漏；減資退還的現金（例如鴻海 2018 年減資）沒有計入。</li>
          <li>
            同時比較了{{ backtest ? ` ${backtest.comparisons} 組` : '多組' }}（策略 × 出場方式）。即使全都沒有真正的優勢，也可能有組別剛好通過<template
              v-if="backtest"
              >（機率最多約 {{ falsePassChance(backtest.comparisons) }}%）</template
            >，所以「有優勢」代表值得繼續觀察，不是保證。
          </li>
          <li>規則與參數事先固定，沒有為了讓結果好看而反覆調整；即便如此，過去的結果也不代表未來。</li>
        </ul>

        <h2 id="sources">資料來源</h2>
        <ul>
          <li>股價：Yahoo Finance（透過 yfinance 取得），僅供個人使用。</li>
          <li>技術指標、選股結果與回測：由本系統依上述股價計算。</li>
        </ul>
      </div>
    </div>
  </section>
</template>

<style scoped>
.about {
  line-height: 1.7;
}

/* 桌面：左側目錄固定在導覽列下方；手機（見下方 @media）：導覽列下方的橫列 */
.layout {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  gap: 32px;
  align-items: start;
}

.toc {
  position: sticky;
  top: calc(var(--nav-height) + 16px);
  display: flex;
  flex-direction: column;
  border-left: 2px solid #e5e7eb;
  font-size: 14px;
}

.toc a {
  margin-left: -2px;
  padding: 4px 12px;
  border-left: 2px solid transparent;
  color: #555;
  white-space: nowrap;
}

.toc a:hover {
  color: #0b57d0;
  text-decoration: none;
}

.toc a.active {
  border-left-color: #0b57d0;
  color: #0b57d0;
  font-weight: 600;
}

h2 {
  margin: 28px 0 8px;
  padding-top: 8px;
  border-top: 1px solid #e5e7eb;
  font-size: 18px;
}

h3 {
  margin: 16px 0 4px;
  font-size: 15px;
}

.content > h2:first-child {
  margin-top: 0;
}

.disclaimer {
  padding: 4px 16px;
  border-left: 4px solid #d97706;
  border-radius: 4px;
  background: #fff8e6;
}

.disclaimer p {
  margin: 8px 0;
}

ul {
  padding-left: 20px;
}

.small {
  color: #555;
  font-size: 14px;
}

.table-wrap {
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
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
  white-space: nowrap;
}

.param {
  color: #666;
  font-size: 12px;
  font-weight: normal;
}

@media (max-width: 899px) {
  /* 目錄橫列固定在導覽列下方；跳到段落時，同時預留導覽列與目錄橫列的高度 */
  .about {
    --toc-height: 44px;
    --anchor-offset: calc(var(--nav-height) + var(--toc-height) + 12px);
  }

  .layout {
    display: block;
  }

  .toc {
    top: var(--nav-height);
    z-index: 10;
    flex-direction: row;
    gap: 4px;
    box-sizing: border-box;
    height: var(--toc-height);
    margin: 0 0 8px;
    overflow-x: auto;
    border-bottom: 1px solid #e5e7eb;
    border-left: 0;
    background: #fff;
    scrollbar-width: thin;
  }

  .toc a {
    margin: 0;
    padding: 10px 8px;
    border-bottom: 2px solid transparent;
    border-left: 0;
  }

  .toc a.active {
    border-bottom-color: #0b57d0;
  }
}

/* 手機寬度：表格改為一列一張卡片，欄名顯示在內容上方 */
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
    display: block;
    padding: 2px 0;
    border: 0;
    white-space: normal;
  }

  .stack td::before {
    content: attr(data-label);
    display: block;
    color: #666;
    font-size: 12px;
  }
}
</style>
