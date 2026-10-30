<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, CandlestickChart, LineChart, ScatterChart } from 'echarts/charts'
import {
  AxisPointerComponent,
  DataZoomComponent,
  GridComponent,
  MarkAreaComponent,
  MarkLineComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { EXIT_REASONS } from '../utils/backtest.js'
import { DOWN_COLOR, MA_LINES, SUB_CHARTS, TRADE_COLORS, UP_COLOR } from '../utils/chartConfig.js'
import { formatChange, formatLots, formatPercent, formatPrice, formatRate, monthsBefore, toLots } from '../utils/format.js'

// 只註冊用到的模組，減少打包大小
echarts.use([
  CandlestickChart, BarChart, LineChart, ScatterChart,
  GridComponent, TooltipComponent, DataZoomComponent, AxisPointerComponent, MarkAreaComponent, MarkLineComponent,
  TitleComponent,
  CanvasRenderer,
])

const props = defineProps({
  // StockHistoryResponse.prices（含 indicators），依日期由舊到新；只含交易日
  prices: { type: Array, required: true },
  // 預設顯示最近幾個月，其餘可用縮放 / 滑桿查看
  months: { type: Number, default: 3 },
  // 要顯示的均線（chartConfig.MA_LINES 的 key）
  maKeys: { type: Array, default: () => [] },
  // 要顯示的副圖（chartConfig.SUB_CHARTS 的 key），依 SUB_CHARTS 的順序排列
  subChartKeys: { type: Array, default: () => [] },
  // BacktestTradesResponse.trades：有值時標出回測的進出場點與持有期間（只畫與 prices 期間重疊的交易）
  trades: { type: Array, default: () => [] },
})

const emit = defineEmits(['range-change'])

// 版面（px）：每個圖區上方保留標題列
const LEFT = 70
const RIGHT = 20
const TITLE_SPACE = 26
const KLINE_HEIGHT = 260
const VOLUME_HEIGHT = 64
const SUB_CHART_HEIGHT = 100
const BOTTOM_SPACE = 64 // X 軸日期 + 下方滑桿

const chartEl = ref(null)
let chart = null
let resizeObserver = null
let zoom = null // 目前顯示的 { startIndex, endIndex }，切換指標時保留

const activeMas = computed(() => MA_LINES.filter((ma) => props.maKeys.includes(ma.key)))
const activeSubCharts = computed(() => SUB_CHARTS.filter((sub) => props.subChartKeys.includes(sub.key)))

const panels = computed(() => {
  const list = [
    { key: 'kline', height: KLINE_HEIGHT },
    { key: 'volume', height: VOLUME_HEIGHT },
    ...activeSubCharts.value.map((sub) => ({ key: sub.key, height: SUB_CHART_HEIGHT, config: sub })),
  ]
  let top = TITLE_SPACE
  return list.map((panel) => {
    const placed = { ...panel, top }
    top += panel.height + TITLE_SPACE
    return placed
  })
})

const chartHeight = computed(() => {
  const last = panels.value.at(-1)
  return last.top + last.height + BOTTOM_SPACE
})

function value(bar, key) {
  return bar.indicators?.[key] ?? null
}

const dateIndex = computed(() => new Map(props.prices.map((bar, i) => [bar.tradeDate, i])))

// 與圖表期間重疊的交易：持有期間的起訖位置（進場早於圖表起點時從第一根 K 線開始；仍持有中則畫到最後一根）
const visibleTrades = computed(() => {
  const prices = props.prices
  if (!prices.length) return []
  const first = prices[0].tradeDate
  const last = prices.at(-1).tradeDate
  return props.trades
    .filter((trade) => trade.entryDate <= last && (trade.exitDate ?? last) >= first)
    .map((trade) => ({
      trade,
      entryIndex: dateIndex.value.get(trade.entryDate), // 進場在圖表期間之前時為 undefined
      exitIndex: trade.exitDate ? dateIndex.value.get(trade.exitDate) : undefined,
      start: trade.entryDate >= first ? trade.entryDate : first,
      end: trade.exitDate ?? last,
    }))
})

// tooltip 用：每個交易日發生的回測事件
const tradeEvents = computed(() => {
  const events = new Map()
  const add = (date, text) => events.set(date, [...(events.get(date) ?? []), text])
  for (const trade of props.trades) {
    add(trade.signalDate, '回測訊號（收盤後出現，下一個交易日開盤進場）')
    add(trade.entryDate, `▲ 回測進場：開盤 ${formatPrice(trade.entryPrice)}`)
    if (trade.exitDate) {
      const dividends = trade.dividends > 0 ? `，含股利 ${formatPrice(trade.dividends)}` : ''
      add(
        trade.exitDate,
        `▼ 回測出場：${formatPrice(trade.exitPrice)}（${EXIT_REASONS[trade.exitReason] ?? trade.exitReason}）` +
          `<br>　報酬 ${formatRate(trade.netReturn)}，持有 ${trade.holdingDays} 個交易日${dividends}`,
      )
    } else {
      add(props.prices.at(-1)?.tradeDate, `回測持有中：已持有 ${trade.holdingDays} 個交易日`)
    }
  }
  return events
})

// 標題列兼圖例：以 ECharts rich text 為每個名稱上色
function panelTitle(panel) {
  const rich = {}
  const colored = (items) =>
    items.map((item) => {
      rich[item.key] = { color: item.color, fontSize: 12 }
      return `{${item.key}|${item.label}}`
    })

  let parts
  if (panel.key === 'kline') {
    parts = ['K 線', ...colored(activeMas.value)]
    if (props.trades.length) {
      rich.entry = { color: TRADE_COLORS.entry, fontSize: 12 }
      rich.exit = { color: TRADE_COLORS.exit, fontSize: 12 }
      parts.push('{entry|▲ 進場}', '{exit|▼ 出場}')
    }
  }
  else if (panel.key === 'volume') parts = ['成交量（張）']
  else parts = [panel.config.title, ...colored(panel.config.lines)]

  return {
    text: parts.join('  '),
    left: LEFT,
    top: panel.top - 20,
    textStyle: { fontSize: 12, fontWeight: 'normal', color: '#666', rich },
  }
}

function tooltipHtml(index) {
  const bar = props.prices[index]
  const previous = props.prices[index - 1]
  const change = previous ? bar.closePrice - previous.closePrice : null
  const percent = previous ? (change / previous.closePrice) * 100 : null
  const changeColor = change > 0 ? UP_COLOR : change < 0 ? DOWN_COLOR : 'inherit'
  const colored = (item, v) => `<span style="color:${item.color}">${item.label} ${formatPrice(v)}</span>`

  const lines = [
    `<strong>${bar.tradeDate}</strong>`,
    `開 ${formatPrice(bar.openPrice)}　高 ${formatPrice(bar.highPrice)}`,
    `低 ${formatPrice(bar.lowPrice)}　收 ${formatPrice(bar.closePrice)}`,
    `<span style="color:${changeColor}">漲跌 ${formatChange(change)}（${formatPercent(percent)}）</span>`,
    `成交量 ${formatLots(bar.volume)} 張`,
  ]
  for (const text of tradeEvents.value.get(bar.tradeDate) ?? []) {
    lines.push(`<span style="color:${TRADE_COLORS.exit}">${text}</span>`)
  }
  if (activeMas.value.length) {
    lines.push(activeMas.value.map((ma) => colored(ma, value(bar, ma.key))).join('　'))
  }
  for (const sub of activeSubCharts.value) {
    const items = sub.lines.map((line) => colored(line, value(bar, line.key)))
    if (sub.bars) items.push(`${sub.bars.label} ${formatPrice(value(bar, sub.bars.key))}`)
    lines.push(items.join('　'))
  }
  return lines.join('<br>')
}

function subChartSeries(panel, axisIndex) {
  const sub = panel.config
  const series = sub.lines.map((line, i) => ({
    name: line.label,
    type: 'line',
    xAxisIndex: axisIndex,
    yAxisIndex: axisIndex,
    data: props.prices.map((bar) => value(bar, line.key)),
    showSymbol: false,
    lineStyle: { width: 1.2, color: line.color },
    itemStyle: { color: line.color },
    // 超買 / 超賣參考線只需要畫一次
    ...(i === 0 && sub.levels
      ? {
          markLine: {
            silent: true,
            symbol: 'none',
            label: { show: false },
            lineStyle: { type: 'dashed', color: '#adb5bd', width: 1 },
            data: sub.levels.map((level) => ({ yAxis: level })),
          },
        }
      : {}),
  }))
  if (sub.bars) {
    series.unshift({
      name: sub.bars.label,
      type: 'bar',
      xAxisIndex: axisIndex,
      yAxisIndex: axisIndex,
      data: props.prices.map((bar) => {
        const v = value(bar, sub.bars.key)
        return { value: v, itemStyle: { color: v >= 0 ? UP_COLOR : DOWN_COLOR } }
      }),
    })
  }
  return series
}

// 回測持有期間的底色：獲利紅、虧損綠（台股慣例）、持有中藍
function holdingAreas() {
  if (!visibleTrades.value.length) return undefined
  return {
    silent: true,
    data: visibleTrades.value.map(({ trade, start, end }) => {
      const tone = trade.netReturn == null ? 'open' : trade.netReturn > 0 ? 'win' : 'loss'
      return [{ xAxis: start, itemStyle: { color: TRADE_COLORS[tone] } }, { xAxis: end }]
    }),
  }
}

function tradeMarkers() {
  if (!visibleTrades.value.length) return []
  const prices = props.prices
  const marker = (name, color, rotate, offset, points) => ({
    name,
    type: 'scatter',
    data: points,
    symbol: 'triangle',
    symbolSize: 10,
    symbolRotate: rotate,
    symbolOffset: [0, offset],
    itemStyle: { color },
    clip: false, // 標記在 K 線上下方，貼近圖區邊緣時也要完整顯示
    z: 5,
  })
  const entries = visibleTrades.value
    .filter((t) => t.entryIndex != null)
    .map((t) => [t.entryIndex, prices[t.entryIndex].lowPrice])
  const exits = visibleTrades.value
    .filter((t) => t.exitIndex != null)
    .map((t) => [t.exitIndex, prices[t.exitIndex].highPrice])
  return [
    marker('回測進場', TRADE_COLORS.entry, 0, 10, entries),
    marker('回測出場', TRADE_COLORS.exit, 180, -10, exits),
  ]
}

function buildOption() {
  const prices = props.prices
  const dates = prices.map((p) => p.tradeDate)
  const lastIndex = panels.value.length - 1

  return {
    animation: false,
    title: panels.value.map(panelTitle),
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      // 以日期找出 K 線位置（回測標記的 dataIndex 不是 K 線的位置）
      formatter: (params) => tooltipHtml(dateIndex.value.get(params[0].axisValue)),
    },
    // 所有圖區共用十字線
    axisPointer: { link: [{ xAxisIndex: 'all' }] },
    grid: panels.value.map((panel) => ({ left: LEFT, right: RIGHT, top: panel.top, height: panel.height })),
    // 類別軸：只排列實際交易日，週末與休市日（國定假日、颱風假）不會留下空白；只有最下方顯示日期
    xAxis: panels.value.map((panel, i) => ({
      type: 'category',
      gridIndex: i,
      data: dates,
      boundaryGap: true,
      axisLabel: { show: i === lastIndex },
      axisTick: { show: i === lastIndex },
    })),
    yAxis: panels.value.map((panel, i) => {
      if (panel.key === 'kline') return { gridIndex: i, scale: true }
      if (panel.key === 'volume') {
        return {
          gridIndex: i,
          splitNumber: 2,
          axisLabel: { formatter: (v) => (v >= 10000 ? `${v / 10000}萬` : v) },
        }
      }
      const [min, max] = panel.config.range ?? [null, null]
      return { gridIndex: i, scale: !panel.config.range, min, max, splitNumber: 2 }
    }),
    dataZoom: [
      { type: 'inside', xAxisIndex: panels.value.map((_, i) => i) },
      { type: 'slider', xAxisIndex: panels.value.map((_, i) => i), bottom: 8, height: 22 },
    ],
    series: [
      {
        name: 'K 線',
        type: 'candlestick',
        // ECharts K 線資料順序為 [開, 收, 低, 高]
        data: prices.map((p) => [p.openPrice, p.closePrice, p.lowPrice, p.highPrice]),
        itemStyle: { color: UP_COLOR, color0: DOWN_COLOR, borderColor: UP_COLOR, borderColor0: DOWN_COLOR },
        markArea: holdingAreas(),
      },
      ...tradeMarkers(),
      ...activeMas.value.map((ma) => ({
        name: ma.label,
        type: 'line',
        data: prices.map((bar) => value(bar, ma.key)),
        showSymbol: false,
        lineStyle: { width: 1.2, color: ma.color },
        itemStyle: { color: ma.color },
      })),
      {
        name: '成交量',
        type: 'bar',
        xAxisIndex: 1,
        yAxisIndex: 1,
        data: prices.map((p) => ({
          value: toLots(p.volume),
          itemStyle: { color: p.closePrice >= p.openPrice ? UP_COLOR : DOWN_COLOR },
        })),
      },
      ...panels.value.slice(2).flatMap((panel, i) => subChartSeries(panel, i + 2)),
    ],
  }
}

function applyZoom(startIndex, endIndex) {
  chart.dispatchAction({ type: 'dataZoom', startValue: startIndex, endValue: endIndex })
}

// 縮放到最近 N 個月（以最後一個交易日往前推）
function zoomToMonths() {
  const prices = props.prices
  const start = monthsBefore(prices.at(-1).tradeDate, props.months)
  applyZoom(Math.max(0, prices.findIndex((p) => p.tradeDate >= start)), prices.length - 1)
}

// 記錄並回報目前畫面上的日期區間
function onDataZoom() {
  const dataZoom = chart.getOption().dataZoom[0]
  const last = props.prices.length - 1
  const toIndex = (percent) => Math.round((percent / 100) * last)
  const startIndex = dataZoom.startValue ?? toIndex(dataZoom.start)
  const endIndex = dataZoom.endValue ?? toIndex(dataZoom.end)
  zoom = { startIndex, endIndex }
  emit('range-change', {
    from: props.prices[startIndex]?.tradeDate,
    to: props.prices[endIndex]?.tradeDate,
    count: endIndex - startIndex + 1,
  })
}

// keepZoom：切換指標時維持目前區間；換股票時依 months 重新縮放
function render(keepZoom) {
  chart.setOption(buildOption(), true)
  if (keepZoom && zoom) applyZoom(zoom.startIndex, zoom.endIndex)
  else zoomToMonths()
}

onMounted(() => {
  chart = echarts.init(chartEl.value)
  chart.on('datazoom', onDataZoom)
  render(false)
  resizeObserver = new ResizeObserver(() => chart.resize())
  resizeObserver.observe(chartEl.value)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
})

watch(() => props.prices, () => render(false))
watch(() => props.months, zoomToMonths)
watch(() => props.trades, () => render(true))
// flush: 'post'：等容器高度更新後再重繪，副圖數量改變時版面才會正確
watch(
  () => [props.maKeys, props.subChartKeys],
  () => {
    chart.resize()
    render(true)
  },
  { deep: true, flush: 'post' },
)
</script>

<template>
  <div ref="chartEl" class="chart" :style="{ height: `${chartHeight}px` }"></div>
</template>

<style scoped>
.chart {
  width: 100%;
}
</style>
