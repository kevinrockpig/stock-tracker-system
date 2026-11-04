<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

// 只註冊用到的模組，減少打包大小
echarts.use([LineChart, GridComponent, LegendComponent, MarkLineComponent, TooltipComponent, CanvasRenderer])

const props = defineProps({
  // 日期（YYYY-MM-DD），由舊到新
  dates: { type: Array, required: true },
  // 折線：[{ name, color, values, dashed, step, noReturn }]，values 與 dates 一一對應（金額，沒有資料為 null）；
  // step 為 true 時畫成階梯線，noReturn 為 true 時 tooltip 不顯示報酬
  series: { type: Array, required: true },
  // 換算報酬的基準：數字為固定的起始金額（另畫一條水平參考線）；陣列為每天各自的基準（例如當天的投入成本）
  base: { type: [Number, Array], required: true },
  label: { type: String, default: '帳戶資金與基準的每日走勢' },
})

const chartEl = ref(null)
let chart = null
let resizeObserver = null

const moneyFormat = new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 0 })

const baseAt = (index) => (Array.isArray(props.base) ? props.base[index] : props.base)

function returnText(value, base) {
  const percent = (value / base - 1) * 100
  const rounded = Number(percent.toFixed(2))
  return `${rounded > 0 ? '+' : ''}${(rounded === 0 ? 0 : rounded).toFixed(2)}%`
}

function tooltipText(params) {
  const rows = params.map((p) => {
    const line = props.series[p.seriesIndex]
    const base = baseAt(p.dataIndex)
    const value = p.value == null ? '—' : moneyFormat.format(p.value)
    const change = p.value == null || line.noReturn || !base ? '' : `（${returnText(p.value, base)}）`
    return `${p.marker}${p.seriesName}　<b>${value}</b>${change}`
  })
  return [params[0]?.axisValueLabel ?? '', ...rows].join('<br>')
}

function render() {
  chart.setOption(
    {
      animation: false,
      grid: { left: 80, right: 20, top: 36, bottom: 32 },
      legend: { top: 0, left: 0, itemWidth: 18, textStyle: { fontSize: 12 } },
      tooltip: { trigger: 'axis', formatter: tooltipText },
      xAxis: { type: 'category', data: props.dates, boundaryGap: false, axisLabel: { fontSize: 11 } },
      yAxis: {
        type: 'value',
        scale: true, // 不從 0 開始，才看得出差異
        axisLabel: { fontSize: 11, formatter: (value) => moneyFormat.format(value) },
      },
      series: props.series.map((line, index) => ({
        name: line.name,
        type: 'line',
        data: line.values,
        step: line.step ? 'end' : false,
        showSymbol: props.dates.length <= 2, // 只有一兩天時畫點，否則看不到線
        symbolSize: 5,
        lineStyle: { width: index === 0 ? 2.5 : 1.5, color: line.color, type: line.dashed ? 'dashed' : 'solid' },
        itemStyle: { color: line.color },
        z: props.series.length - index, // 第一條（帳戶）畫在最上層
        ...(index === 0 && !Array.isArray(props.base)
          ? {
              markLine: {
                silent: true,
                symbol: 'none',
                label: { show: false },
                lineStyle: { color: '#adb5bd', type: 'dotted' },
                data: [{ yAxis: props.base }],
              },
            }
          : {}),
      })),
    },
    true,
  )
}

onMounted(() => {
  chart = echarts.init(chartEl.value)
  render()
  resizeObserver = new ResizeObserver(() => chart.resize())
  resizeObserver.observe(chartEl.value)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
})

watch(() => [props.dates, props.series, props.base], render)
</script>

<template>
  <div ref="chartEl" class="equity-chart" role="img" :aria-label="label"></div>
</template>

<style scoped>
.equity-chart {
  width: 100%;
  height: 280px;
}
</style>
