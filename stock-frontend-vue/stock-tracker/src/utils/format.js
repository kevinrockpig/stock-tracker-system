// 清單頁與 K 線圖共用的數字、日期格式

const priceFormat = new Intl.NumberFormat('zh-TW', { minimumFractionDigits: 2, maximumFractionDigits: 4 })
const integerFormat = new Intl.NumberFormat('zh-TW')

export function formatPrice(value) {
  return value == null ? '—' : priceFormat.format(value)
}

export function formatChange(value) {
  return value == null ? '—' : `${value > 0 ? '+' : ''}${priceFormat.format(value)}`
}

export function formatPercent(value) {
  return value == null ? '—' : `${value > 0 ? '+' : ''}${value.toFixed(2)}%`
}

// 台股成交量慣用「張」：1 張 = 1,000 股（ETF 為 1,000 單位）
export function toLots(shares) {
  return shares == null ? null : Math.round(shares / 1000)
}

export function formatLots(shares) {
  return shares == null ? '—' : integerFormat.format(toLots(shares))
}

// 台股慣例：漲紅跌綠
export function trendClass(value) {
  if (value > 0) return 'up'
  if (value < 0) return 'down'
  return ''
}

// 以本地日期組出 YYYY-MM-DD（避免 toISOString 轉成 UTC 而差一天）
export function isoDate(date) {
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

// YYYY-MM-DD 往前推 N 個月
export function monthsBefore(dateString, months) {
  const [year, month, day] = dateString.split('-').map(Number)
  return isoDate(new Date(year, month - 1 - months, day))
}

// 小數比率轉百分比（0.0048 → +0.48%）；回測 API 的報酬、勝率皆為小數
export function formatRate(value, { digits = 2, signed = true } = {}) {
  if (value == null) return '—'
  const percent = value * 100
  // 四捨五入後為 0 時不顯示正負號，避免出現「-0.00%」
  const rounded = Number(percent.toFixed(digits))
  const sign = signed && rounded > 0 ? '+' : ''
  return `${sign}${(rounded === 0 ? 0 : rounded).toFixed(digits)}%`
}

export function formatNumber(value, digits = 2) {
  return value == null ? '—' : value.toFixed(digits)
}
