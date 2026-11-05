// 庫存：手續費試算、股數顯示、手續費設定的記憶，以及全站共用的「新增 / 修改庫存」表單
import { reactive } from 'vue'

// 手續費規則；與 Java TradingCosts 一致（庫存頁載入後改用 API 回傳的 feeRules）。存檔時由伺服器計算，這裡只做預覽
export const DEFAULT_FEE_RULES = { feeRate: 0.001425, defaultMinFee: 20, taxStock: 0.003, taxEtf: 0.001 }

export const FEE_MODES = [
  { code: 'standard', label: '公定費率', hint: '成交金額 × 0.1425%' },
  { code: 'discount', label: '折數', hint: '公定費率打折' },
  { code: 'actual', label: '實際金額', hint: '照對帳單填寫' },
]

export const MAX_HOLDING_SHARES = 100_000_000 // 與 Java 的上限一致

// 手續費試算：無條件捨去到元，低於最低手續費時以最低手續費計
export function estimateFee(amount, { discount = 1, minFee, rules = DEFAULT_FEE_RULES } = {}) {
  if (!(amount > 0)) return null
  // 先四捨五入到 6 位小數再捨去，避免 1000 × 0.001425 這類浮點誤差（1.4249999…）少算 1 元
  const raw = Math.floor(Number((amount * rules.feeRate * discount).toFixed(6)))
  return Math.max(raw, minFee ?? rules.defaultMinFee)
}

// 1,500 股 → 「1 張 500 股」；配股換算後可能有小數股
const shareFormat = new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 2 })
export function formatShares(shares) {
  if (shares == null) return '—'
  const lots = Math.floor(shares / 1000)
  const rest = Math.round((shares - lots * 1000) * 100) / 100
  if (!lots) return `${shareFormat.format(rest)} 股`
  return rest ? `${shareFormat.format(lots)} 張 ${shareFormat.format(rest)} 股` : `${shareFormat.format(lots)} 張`
}

// 折數：畫面以「折」填寫（2.8 折填 2.8），API 為比例（0.28）
export const discountToRatio = (tenths) => Math.round(tenths * 10000) / 100000
export const ratioToDiscount = (ratio) => Math.round(ratio * 100000) / 10000

// 手續費方式、折數、最低手續費存在瀏覽器（大多數人只用一家券商），下次新增時自動帶入
const FEE_SETTINGS_KEY = 'stock-tracker:fee-settings:v1'

export function loadFeeSettings() {
  try {
    const saved = JSON.parse(localStorage.getItem(FEE_SETTINGS_KEY))
    if (!saved || typeof saved !== 'object') return null
    return {
      feeMode: ['standard', 'discount'].includes(saved.feeMode) ? saved.feeMode : 'standard',
      discount: typeof saved.discount === 'number' ? saved.discount : null,
      minFee: typeof saved.minFee === 'number' ? saved.minFee : null,
    }
  } catch {
    return null
  }
}

export function saveFeeSettings({ feeMode, discount, minFee }) {
  // 「實際金額」每筆不同，不記住；只記公定費率 / 折數與最低手續費
  if (feeMode === 'actual') return
  try {
    localStorage.setItem(FEE_SETTINGS_KEY, JSON.stringify({ feeMode, discount, minFee }))
  } catch {
    // 無法儲存時下次回到預設值
  }
}

// 全站只有一個表單（App.vue），股票清單、個股頁、庫存頁都透過 openHoldingForm 開啟
// stock：{ symbol, name, closePrice?, tradeDate? }，省略時在表單中選股票；lot：修改時的原始資料
export const holdingForm = reactive({ open: false, stock: null, lot: null, onSaved: null })

export function openHoldingForm({ stock = null, lot = null, onSaved = null } = {}) {
  Object.assign(holdingForm, { open: true, stock, lot, onSaved })
}

export function closeHoldingForm() {
  Object.assign(holdingForm, { open: false, stock: null, lot: null, onSaved: null })
}

// 除權息類型（Python levels.py：權 / 息 / 權息）
export const exTypeText = (type) => (type ? `除${type}` : '')

// 庫存中需要留意的事：跌破移動停利線、即將除權息（庫存頁與今天頁共用）
const alertPrice = new Intl.NumberFormat('zh-TW', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
export function holdingAlerts(positions = []) {
  const items = []
  for (const p of positions) {
    if (p.belowExitLine) {
      items.push({ key: `${p.symbol}-exit`, symbol: p.symbol, name: p.name, kind: 'exit',
        text: `收盤 ${alertPrice.format(p.price)} 跌破移動停利線 ${alertPrice.format(p.exitLine)}` })
    }
    if (p.nextExDate) {
      const cash = p.nextCashDividend ? `，現金股利 ${p.nextCashDividend} 元` : ''
      items.push({ key: `${p.symbol}-ex`, symbol: p.symbol, name: p.name, kind: 'ex', text: `${p.nextExDate} ${exTypeText(p.nextExType)}${cash}` })
    }
  }
  return items
}
