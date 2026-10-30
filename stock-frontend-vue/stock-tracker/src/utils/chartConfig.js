// K 線圖的指標設定：個股頁的切換按鈕與圖表元件共用，確保顏色一致

export const UP_COLOR = '#d1242f'
export const DOWN_COLOR = '#1a7f37'

// 均線（疊加在 K 線上）
export const MA_LINES = [
  { key: 'ma5', label: 'MA5', color: '#f08c00' },
  { key: 'ma10', label: 'MA10', color: '#1971c2' },
  { key: 'ma20', label: 'MA20', color: '#9c36b5' },
  { key: 'ma60', label: 'MA60', color: '#5c940d' },
]

// 副圖（顯示在成交量下方）；lines 為副圖中的折線，levels 為超買 / 超賣參考線
export const SUB_CHARTS = [
  {
    key: 'macd',
    label: 'MACD',
    title: 'MACD (12, 26, 9)',
    lines: [
      { key: 'macdDif', label: 'DIF', color: '#1971c2' },
      { key: 'macdSignal', label: 'MACD', color: '#f08c00' },
    ],
    // OSC 柱狀體：正值紅、負值綠
    bars: { key: 'macdOsc', label: 'OSC' },
  },
  {
    key: 'rsi',
    label: 'RSI',
    title: 'RSI (6, 12)',
    lines: [
      { key: 'rsi6', label: 'RSI6', color: '#f08c00' },
      { key: 'rsi12', label: 'RSI12', color: '#1971c2' },
    ],
    range: [0, 100],
    levels: [30, 70],
  },
  {
    key: 'kd',
    label: 'KD',
    title: 'KD (9, 3, 3)',
    lines: [
      { key: 'kdK', label: 'K', color: '#f08c00' },
      { key: 'kdD', label: 'D', color: '#1971c2' },
    ],
    range: [0, 100],
    levels: [20, 80],
  },
]

// 顯示區間（只調整圖表縮放，不重新呼叫 API）
export const CHART_RANGES = [
  { months: 1, label: '1 個月' },
  { months: 3, label: '3 個月' },
  { months: 6, label: '6 個月' },
  { months: 12, label: '1 年' },
]

export const DEFAULT_MONTHS = 3
export const DEFAULT_MA_KEYS = MA_LINES.map((ma) => ma.key)
export const DEFAULT_SUB_CHART_KEYS = ['macd', 'rsi']
