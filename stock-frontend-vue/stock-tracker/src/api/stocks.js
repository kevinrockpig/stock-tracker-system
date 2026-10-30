// Java API 呼叫。Vue 只透過 Java API 取得資料，不直接連線資料庫。

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8081'

// Neon 閒置休眠後，Java 第一次查詢需要喚醒資料庫，因此給較寬鬆的逾時
const TIMEOUT_MS = 20000

export class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

async function getJson(path) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)

  let response
  try {
    response = await fetch(`${API_BASE_URL}${path}`, { signal: controller.signal })
  } catch (err) {
    if (err.name === 'AbortError') {
      throw new Error(`API 超過 ${TIMEOUT_MS / 1000} 秒沒有回應`)
    }
    // 連線被拒與 CORS 被擋在瀏覽器中都是這個錯誤，詳細原因請看開發者工具 Console
    throw new Error(`無法連線到 API（${API_BASE_URL}），請確認 Java 服務已啟動`)
  } finally {
    clearTimeout(timer)
  }

  if (!response.ok) {
    // Java 的錯誤回應為 Problem Details 格式，detail 欄位有中文說明
    let detail = ''
    try {
      detail = (await response.json()).detail ?? ''
    } catch {
      // 回應不是 JSON 時改用通用訊息
    }
    throw new ApiError(response.status, detail || `API 回應錯誤（HTTP ${response.status}）`)
  }
  return response.json()
}

// 股票清單 + 最新日收盤報價
export function fetchStocks() {
  return getJson('/api/stocks')
}

// 歷史日 K；from / to 為 YYYY-MM-DD，可省略
export function fetchHistory(symbol, { from, to } = {}) {
  const params = new URLSearchParams()
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  const query = params.toString()
  return getJson(`/api/stocks/${encodeURIComponent(symbol)}/history${query ? `?${query}` : ''}`)
}

// 可用的選股策略：[{ code, name, description }]
export function fetchStrategies() {
  return getJson('/api/strategies')
}

// 策略選股：最近 days 個交易日內符合 strategy 的股票
export function fetchScreener(strategy, days) {
  const params = new URLSearchParams({ strategy, days: String(days) })
  return getJson(`/api/screener?${params}`)
}

// 策略回測統計：scope 為 ALL（觀察名單合併）或股票代號
export function fetchBacktests(scope = 'ALL') {
  return getJson(`/api/backtests?${new URLSearchParams({ scope })}`)
}

// 某檔股票在某策略、某出場規則下的歷史回測交易（依進場日由舊到新）
export function fetchTrades(symbol, strategy, exitRule) {
  const params = new URLSearchParams({ strategy, exitRule })
  return getJson(`/api/stocks/${encodeURIComponent(symbol)}/trades?${params}`)
}

// 最新的參考價位（供下一個交易日參考）；尚未計算時回 404
export function fetchLevels(symbol) {
  return getJson(`/api/stocks/${encodeURIComponent(symbol)}/levels`)
}
