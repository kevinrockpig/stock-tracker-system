// 觀察名單組別的簡稱（股票清單、卡片上的小標籤）；組別的完整名稱與股票數來自 GET /api/groups
const SHORT_NAMES = { TW50: '0050', CUSTOM: '自選' }

export function groupShortName(code) {
  return SHORT_NAMES[code] ?? code
}

// 依代號或名稱搜尋（不分大小寫；代號可省略 .TW / .TWO）
export function matchesQuery(stock, query) {
  const q = query.trim().toLowerCase()
  if (!q) return true
  return stock.symbol.toLowerCase().includes(q) || stock.name.toLowerCase().includes(q)
}
