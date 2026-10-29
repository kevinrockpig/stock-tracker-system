<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, CandlestickChart, LineChart } from 'echarts/charts'
import {
  AxisPointerComponent,
  DataZoomComponent,
  GridComponent,
  MarkLineComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { DOWN_COLOR, MA_LINES, SUB_CHARTS, UP_COLOR } from '../utils/chartConfig.js'
import { formatChange, formatLots, formatPercent, formatPrice, monthsBefore, toLots } from '../utils/format.js'

// 只註冊用到的模組，減少打包大小
echarts.use([
  CandlestickChart, BarChart, LineChart,
  GridComponent, TooltipComponent, DataZoomComponent, AxisPointerComponent, MarkLineComponent, TitleComponent,
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

// 標題列兼圖例：以 ECharts rich text 為每個名稱上色
function panelTitle(panel) {
  const rich = {}
  const colored = (items) =>
    items.map((item) => {
      rich[item.key] = { color: item.color, fontSize: 12 }
      return `{${item.key}|${item.label}}`
    })

  let parts
  if (panel.key === 'kline') parts = ['K 線', ...colored(activeMas.value)]
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
      formatter: (params) => tooltipHtml(params[0].dataIndex),
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
      },
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
