// Java API 呼叫。Vue 只透過 Java API 取得資料，不直接連線資料庫。

// 預設與前端同一個來源（開發時由 Vite 轉發給 Java，見 vite.config.js）；登入需要同一個來源才能帶 session cookie
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || ''

// Neon 閒置休眠後，Java 第一次查詢需要喚醒資料庫，因此給較寬鬆的逾時
const TIMEOUT_MS = 20000

export class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

// method / body 為寫入請求使用；回傳 fetch 的 Response（逾時、連線失敗轉成中文錯誤）
async function request(path, { method = 'GET', body, headers = {} } = {}) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)
  try {
    return await fetch(`${API_BASE_URL}${path}`, {
      method,
      signal: controller.signal,
      // 同一個來源時瀏覽器預設就會帶 cookie；另外設定 VITE_API_BASE_URL（跨網域）時登入功能無法使用
      credentials: 'same-origin',
      headers: body === undefined ? headers : { 'Content-Type': 'application/json', ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (err) {
    if (err.name === 'AbortError') {
      throw new Error(`API 超過 ${TIMEOUT_MS / 1000} 秒沒有回應`)
    }
    // 連線被拒與 CORS 被擋在瀏覽器中都是這個錯誤，詳細原因請看開發者工具 Console
    throw new Error(`無法連線到 API（${API_BASE_URL || '經由前端伺服器轉發'}），請確認 Java 服務已啟動`)
  } finally {
    clearTimeout(timer)
  }
}

async function parse(response) {
  if (!response.ok) {
    // Java 的錯誤回應為 Problem Details 格式，detail 欄位有中文說明
    let detail = ''
    try {
      detail = (await response.json()).detail ?? ''
    } catch {
      // 回應不是 JSON 時改用通用訊息
    }
    if (response.status === 401) detail = '尚未登入，或登入已逾時，請重新登入'
    throw new ApiError(response.status, detail || `API 回應錯誤（HTTP ${response.status}）`)
  }
  return response.status === 204 ? null : response.json()
}

async function getJson(path) {
  return parse(await request(path))
}

// 寫入請求要附上 CSRF token：Java 以 XSRF-TOKEN cookie 提供（GET /api/me 時寫入），前端放進 X-XSRF-TOKEN 標頭
function csrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/)
  return match ? decodeURIComponent(match[1]) : null
}

async function send(method, path, body) {
  if (!csrfToken()) await fetchMe()
  const attempt = () => request(path, { method, body, headers: { 'X-XSRF-TOKEN': csrfToken() ?? '' } })
  let response = await attempt()
  // 403：token 已更換（例如在其他分頁登出再登入）→ 重新取得一次再試
  if (response.status === 403) {
    await fetchMe()
    response = await attempt()
  }
  return parse(response)
}

// 股票清單 + 最新日收盤報價
export function fetchStocks() {
  return getJson('/api/stocks')
}

// 觀察名單的組別：[{ code, name, stocks }]；code 也可作為回測的 scope
export function fetchGroups() {
  return getJson('/api/groups')
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

// 策略回測統計：scope 為 ALL（觀察名單合併）、組別代碼（TW50、CUSTOM）或股票代號
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

// 投資組合回測（共用資金、最多同時持有 10 檔）：scope 為 ALL 或組別代碼
export function fetchPortfolio(scope = 'ALL') {
  return getJson(`/api/portfolio-backtests?${new URLSearchParams({ scope })}`)
}

// 今天：大盤狀態（0050 對 60 日均線）與 R1 規則的候選；登入時候選包含自己的自選。
// frequency：daily（今天的訊號）或 weekly（本週出現過的訊號）
export function fetchToday(frequency = 'daily') {
  return getJson(`/api/today?${new URLSearchParams({ frequency })}`)
}

// ---- 我的計畫（需要登入；未登入時存在瀏覽器，見 utils/plan.js）----

// { plan: { version, frequency, capital, maxLoss, investAmount, updatedAt } | null }
export function fetchMyPlan() {
  return getJson('/api/my-plan')
}

export function saveMyPlan(plan) {
  return send('PUT', '/api/my-plan', plan)
}

export function deleteMyPlan() {
  return send('DELETE', '/api/my-plan')
}

// 交易計畫與往後實測：每個觀察中策略一個模擬帳戶（下一個交易日的計畫、持股、已出場交易、每日資金與基準）
export function fetchPaper() {
  return getJson('/api/paper')
}

// ---- 登入 ----

// 目前的登入狀態：{ loginEnabled, authenticated, email, displayName, loginUrl }；同時取得寫入用的 CSRF token
export function fetchMe() {
  return getJson('/api/me')
}

// 開始 Google 登入（整頁跳轉到 Google，完成後由 Java 導回 /holdings）
export function loginUrl(path) {
  return `${API_BASE_URL}${path}`
}

export function logout() {
  return send('POST', '/api/logout')
}

// ---- 庫存（需要登入，只會讀寫自己的資料）----

// 庫存與分析：{ asOf, summary, benchmark, positions, count, maxCount, feeRules }
export function fetchHoldings() {
  return getJson('/api/holdings')
}

// 資產走勢：{ asOf, etfSymbol, lots, excludedLots, points: [{ date, invested, holdingsValue, etfValue }] }
export function fetchHoldingsHistory() {
  return getJson('/api/holdings/history')
}

// body：{ symbol, buyDate, price, shares, feeMode, feeDiscount, minFee, fee }；回傳存好的一筆（含計算後的手續費）
export function createHolding(body) {
  return send('POST', '/api/holdings', body)
}

export function updateHolding(id, body) {
  return send('PUT', `/api/holdings/${encodeURIComponent(id)}`, body)
}

export function deleteHolding(id) {
  return send('DELETE', `/api/holdings/${encodeURIComponent(id)}`)
}

// 賣出：body：{ symbol, sellDate, price, shares, feeMode, feeDiscount, minFee, fee, taxMode, tax }；
// 回傳存好的一筆（含先進先出的分配與已實現損益）。超過當時可賣的股數時回 400 與說明
export function createSale(body) {
  return send('POST', '/api/holdings/sales', body)
}

export function updateSale(id, body) {
  return send('PUT', `/api/holdings/sales/${encodeURIComponent(id)}`, body)
}

export function deleteSale(id) {
  return send('DELETE', `/api/holdings/sales/${encodeURIComponent(id)}`)
}

// ---- 每日通知（需要登入）----

// { emailEnabled, exDividendDays, maxExDividendDays, entryAlerts, email, riskLevels: { 代號: 代碼 }, riskOptions, weakMarketExtra }
export function fetchNotificationSettings() {
  return getJson('/api/notifications/settings')
}

// body：{ emailEnabled, exDividendDays, entryAlerts }；回傳更新後的設定
export function updateNotificationSettings(body) {
  return send('PUT', '/api/notifications/settings', body)
}

// riskLevel：conservative / standard / aggressive；只能設定自己持有的股票
export function setRiskLevel(symbol, riskLevel) {
  return send('PUT', `/api/notifications/risk-levels/${encodeURIComponent(symbol)}`, { riskLevel })
}

// 依目前設定與最新收盤資料產生的通知內容（與每日寄出的 Email 相同，不會寄出）：{ asOf, subject, body, alertCount }
export function fetchNotificationPreview() {
  return getJson('/api/notifications/preview')
}
