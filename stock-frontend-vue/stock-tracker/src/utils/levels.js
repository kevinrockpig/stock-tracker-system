// 參考價位（GET /api/stocks/{symbol}/levels）轉成價位表的列與 K 線圖的水平線
import { LEVEL_COLORS } from './chartConfig.js'
import { formatPrice } from './format.js'

const FIB_LABEL = { 0.382: '38.2%', 0.5: '50%', 0.618: '61.8%' }

// 黃金分割與 ATR 停損是計算值（資料庫存到小數 4 位），顯示時取到小數 2 位
const round2 = (value) => Math.round(value * 100) / 100

// 價位表：依價格由高到低排列，收盤價也排在其中，看得出收盤落在哪兩個價位之間
export function levelRows(levels) {
  const swingNote = levels.swing.trend === 'up' ? '自波段高點回檔' : '自波段低點反彈'
  const rows = [
    { key: 'limitUp', kind: 'limit', label: '漲停價', price: levels.nextDay.limitUp, note: '下一個交易日的上限' },
    { key: 'limitDown', kind: 'limit', label: '跌停價', price: levels.nextDay.limitDown, note: '下一個交易日的下限' },
    {
      key: 'swingHigh',
      kind: 'swing',
      label: `${levels.swing.days} 日高點`,
      price: levels.swing.high,
      note: levels.swing.highDate,
    },
    {
      key: 'swingLow',
      kind: 'swing',
      label: `${levels.swing.days} 日低點`,
      price: levels.swing.low,
      note: levels.swing.lowDate,
    },
    ...levels.swing.levels.map((level) => ({
      key: `fib${level.ratio}`,
      kind: 'fib',
      label: `黃金分割 ${FIB_LABEL[level.ratio] ?? level.ratio}`,
      shortLabel: FIB_LABEL[level.ratio] ?? String(level.ratio),
      price: round2(level.price),
      note: swingNote,
    })),
    { key: 'close', kind: 'close', label: '收盤價', price: levels.closePrice, note: levels.basisDate },
  ]
  if (levels.atrStop) {
    rows.push({
      key: 'atrStop',
      kind: 'atrStop',
      label: 'ATR 停損',
      price: round2(levels.atrStop.price),
      note: `收盤 − ${levels.atrStop.multiple} × ATR（${formatPrice(round2(levels.atrStop.atr14))}）`,
    })
  }
  return rows
    .map((row) => ({ ...row, distance: levels.closePrice ? row.price / levels.closePrice - 1 : null }))
    .sort((a, b) => b.price - a.price)
}

// K 線圖的水平線：漲跌停、黃金分割、ATR 停損（波段高低點已在 K 線上，不另畫）
export function levelLines(levels) {
  return levelRows(levels)
    .filter((row) => row.kind !== 'close' && row.kind !== 'swing')
    .map((row) => ({
      key: row.key,
      price: row.price,
      color: LEVEL_COLORS[row.kind],
      dashed: row.kind !== 'atrStop',
      // 三條黃金分割線常常很接近：標籤放左端、只寫比例，避免和右端的漲跌停、ATR 停損及最新 K 線重疊
      text: row.kind === 'fib' ? row.shortLabel : `${row.label.replace('價', '')} ${formatPrice(row.price)}`,
      position: row.kind === 'fib' ? 'insideStartTop' : 'insideEndTop',
    }))
}
